package com.applemusicktv.media.widevine

import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.RSAPrivateCrtKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * A pure-JVM software Widevine L3 CDM — a hand port of pywidevine's [Cdm] tuned for
 * exactly what we need: turn Apple's `keyUri` + a webPlayback license response into
 * the raw AES-128 CTR content key (hex). No BouncyCastle, no protobuf runtime —
 * minimal protobuf, AES-CMAC, and ASN.1 are hand-rolled below so the whole thing is
 * one armv7-safe file.
 *
 * Mirrors server/get_key.py. The point: get the CLEAR key so we can decrypt segments
 * in-app and feed clean AAC to the bundled ffmpeg decoder, instead of letting the
 * device MediaDrm path mangle frames.
 */
@Suppress("unused")
class WidevineCdm {

    private val clientId: ByteArray
    private val privateKey: PrivateKey

    init {
        val wvd = Base64.decode(WvdBlob.BASE64, Base64.DEFAULT)
        val (cid, pk) = parseWvd(wvd)
        clientId = cid
        privateKey = pk
    }

    // ---- public API -------------------------------------------------------

    /**
     * Full round-trip. [licenseFetcher] is handed the base64 Widevine challenge and
     * must return the raw license bytes from Apple (SignedMessage). Returns the
     * content key as lowercase hex, or null on any failure (logged as AMKEY).
     */
    fun getContentKey(keyUri: String, licenseFetcher: (challengeB64: String) -> ByteArray): String? {
        return try {
            val pssh = reconstructPssh(keyUri)
            val requestId = androidRequestId()
            val licenseRequest = buildLicenseRequest(pssh, requestId)
            val challenge = signedMessage(TYPE_LICENSE_REQUEST, licenseRequest, signChallenge(licenseRequest))
            val response = licenseFetcher(Base64.encodeToString(challenge, Base64.NO_WRAP))
            parseLicense(response, licenseRequest)
        } catch (t: Throwable) {
            Log.e("AMKEY", "CDM failed: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }
    }

    // ---- WVD v2 parse -----------------------------------------------------
    // Layout: "WVD"(3) ver(1) type(1) sec(1) flags(1) privLen(2) priv(privLen)
    //         cidLen(2) cid(cidLen). private_key is PKCS#1 RSAPrivateKey DER.
    private fun parseWvd(b: ByteArray): Pair<ByteArray, PrivateKey> {
        require(b[0].toInt() == 'W'.code && b[1].toInt() == 'V'.code && b[2].toInt() == 'D'.code) { "bad WVD magic" }
        var i = 6 // skip magic(3) + version(1) + type(1) + security_level(1)
        i += 1    // flags(1)
        val privLen = u16(b, i); i += 2
        val priv = b.copyOfRange(i, i + privLen); i += privLen
        val cidLen = u16(b, i); i += 2
        val cid = b.copyOfRange(i, i + cidLen)
        return cid to rsaFromPkcs1(priv)
    }

    // ---- license request --------------------------------------------------

    /** pssh_data(1, repeated bytes), license_type(2), request_id(3) wrapped in
     *  ContentIdentification(1) inside LicenseRequest. client_id(1) is the raw WVD
     *  blob passed through verbatim; type=NEW(3); protocol_version=VERSION_2_1(6). */
    private fun buildLicenseRequest(psshInit: ByteArray, requestId: ByteArray): ByteArray {
        val widevinePsshData = pb {
            bytes(1, psshInit)
            varint(2, LICENSE_TYPE_STREAMING.toLong())
            bytes(3, requestId)
        }
        val contentId = pb { bytes(1, widevinePsshData) }        // widevine_pssh_data = 1
        return pb {
            bytes(1, clientId)                                   // client_id
            bytes(2, contentId)                                  // content_id
            varint(3, REQUEST_TYPE_NEW.toLong())                 // type = NEW
            varint(4, System.currentTimeMillis() / 1000)         // request_time
            varint(6, PROTOCOL_VERSION_2_1.toLong())             // protocol_version
            varint(7, (Random.nextInt(1, Int.MAX_VALUE)).toLong())// key_control_nonce
        }
    }

    /** Android OEMCrypto request_id: 4 random + 4 zero + counter(8, little), then
     *  the 16 bytes re-encoded as an uppercase-hex ASCII string (32 bytes). */
    private fun androidRequestId(): ByteArray {
        val raw = ByteArray(16)
        Random.nextBytes(raw, 0, 4)
        // bytes 4..7 stay zero; counter = 1 in bytes 8..15 little-endian
        raw[8] = 1
        val hex = raw.joinToString("") { "%02X".format(it) }
        return hex.toByteArray(Charsets.US_ASCII)
    }

    private fun signChallenge(licenseRequest: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA1withRSA/PSS")
        sig.setParameter(PSSParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, 20, 1))
        sig.initSign(privateKey)
        sig.update(licenseRequest)
        return sig.sign()
    }

    private fun signedMessage(type: Int, msg: ByteArray, signature: ByteArray): ByteArray = pb {
        varint(1, type.toLong())
        bytes(2, msg)
        bytes(3, signature)
    }

    // ---- license parse ----------------------------------------------------

    private fun parseLicense(response: ByteArray, licenseRequest: ByteArray): String? {
        val sm = ProtoReader(response)
        var msg: ByteArray? = null
        var sessionKey: ByteArray? = null
        var smType = -1L
        sm.forEach { field, wire, r ->
            when (field) {
                1 -> if (wire == 0) smType = r.readVarint()
                2 -> if (wire == 2) msg = r.readBytes()
                4 -> if (wire == 2) sessionKey = r.readBytes()
                else -> r.skip(wire)
            }
        }
        if (smType != TYPE_LICENSE.toLong()) { Log.e("AMKEY", "not a LICENSE message (type=$smType)"); return null }
        val licenseMsg = msg ?: run { Log.e("AMKEY", "no license msg"); return null }
        val wrappedSessionKey = sessionKey ?: run { Log.e("AMKEY", "no session_key"); return null }

        // session key = RSA-OAEP(SHA-1) decrypt with device private key
        val sessKey = rsaOaepDecrypt(wrappedSessionKey)
        val encContext = context("ENCRYPTION", licenseRequest, 128)
        val encKey = cmac(sessKey, byteArrayOf(1) + encContext)   // 16-byte AES key

        // walk License: id(1) -> request_id(1); key(3, repeated) KeyContainer
        val lic = ProtoReader(licenseMsg)
        var keyHex: String? = null
        lic.forEach { field, wire, r ->
            when (field) {
                3 -> if (wire == 2) {
                    val kc = r.readBytes()
                    val parsed = parseKeyContainer(kc, encKey)
                    if (parsed != null) keyHex = parsed
                }
                else -> r.skip(wire)
            }
        }
        if (keyHex == null) Log.e("AMKEY", "no CONTENT key in license")
        return keyHex
    }

    /** KeyContainer: iv(2), key(3), type(4). Returns hex if type==CONTENT(2). */
    private fun parseKeyContainer(kc: ByteArray, encKey: ByteArray): String? {
        var iv: ByteArray? = null
        var wrapped: ByteArray? = null
        var type = -1L
        ProtoReader(kc).forEach { field, wire, r ->
            when (field) {
                2 -> if (wire == 2) iv = r.readBytes()
                3 -> if (wire == 2) wrapped = r.readBytes()
                4 -> if (wire == 0) type = r.readVarint()
                else -> r.skip(wire)
            }
        }
        if (type != KEY_TYPE_CONTENT.toLong()) return null
        val i = iv ?: return null
        val w = wrapped ?: return null
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(i))
        val out = c.doFinal(w)
        val clear = pkcs7Unpad(out)
        return clear.joinToString("") { "%02x".format(it) }
    }

    private fun rsaOaepDecrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("RSA/ECB/OAEPPadding")
        val spec = OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
        c.init(Cipher.DECRYPT_MODE, privateKey, spec)
        return c.doFinal(data)
    }

    // context = LABEL + 0x00 + license_request + keySizeBits(4, big)
    private fun context(label: String, msg: ByteArray, keySizeBits: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(label.toByteArray(Charsets.US_ASCII))
        out.write(0)
        out.write(msg)
        out.write((keySizeBits ushr 24) and 0xff)
        out.write((keySizeBits ushr 16) and 0xff)
        out.write((keySizeBits ushr 8) and 0xff)
        out.write(keySizeBits and 0xff)
        return out.toByteArray()
    }

    // ---- pssh reconstruction ---------------------------------------------
    // keyUri = "...,<base64>". >30 raw bytes = a full pssh init; else it's a KID and
    // we wrap it as WidevinePsshData{ algorithm=1(AESCTR), key_id=[kid] }.
    private fun reconstructPssh(keyUri: String): ByteArray {
        val b64 = keyUri.substringAfterLast(',')
        val raw = Base64.decode(b64, Base64.DEFAULT)
        if (raw.size > 30) return raw
        return pb {
            varint(1, 1)          // algorithm = AESCTR
            bytes(2, raw)         // key_id (repeated bytes, single)
        }
    }

    // ---- AES-CMAC (RFC 4493) ---------------------------------------------
    private fun cmac(key: ByteArray, message: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val l = cipher.doFinal(ByteArray(16))
        val k1 = dbl(l)
        val k2 = dbl(k1)

        val n = message.size
        val complete = n != 0 && n % 16 == 0
        val blocks = if (complete) n / 16 else n / 16 + 1

        var x = ByteArray(16)
        for (idx in 0 until blocks - 1) {
            val block = message.copyOfRange(idx * 16, idx * 16 + 16)
            x = cipher.doFinal(xor(x, block))
        }
        val lastStart = (blocks - 1) * 16
        val last = if (complete) {
            xor(message.copyOfRange(lastStart, lastStart + 16), k1)
        } else {
            val padded = ByteArray(16)
            val rem = n - lastStart
            System.arraycopy(message, lastStart, padded, 0, rem)
            padded[rem] = 0x80.toByte()
            xor(padded, k2)
        }
        return cipher.doFinal(xor(x, last))
    }

    private fun dbl(input: ByteArray): ByteArray {
        val out = ByteArray(16)
        var carry = 0
        for (i in 15 downTo 0) {
            val v = (input[i].toInt() and 0xff) shl 1 or carry
            out[i] = (v and 0xff).toByte()
            carry = (v ushr 8) and 1
        }
        if ((input[0].toInt() and 0x80) != 0) out[15] = (out[15].toInt() xor 0x87).toByte()
        return out
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray =
        ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }

    // ---- ASN.1 PKCS#1 RSAPrivateKey --------------------------------------
    // SEQUENCE { version, n, e, d, p, q, dP, dQ, qInv } — all INTEGERs.
    private fun rsaFromPkcs1(der: ByteArray): PrivateKey {
        val a = Asn1(der)
        a.expect(0x30); a.readLen()          // SEQUENCE
        a.readInt()                          // version
        val n = a.readInt(); val e = a.readInt(); val d = a.readInt()
        val p = a.readInt(); val q = a.readInt()
        val dp = a.readInt(); val dq = a.readInt(); val qinv = a.readInt()
        val spec = RSAPrivateCrtKeySpec(n, e, d, p, q, dp, dq, qinv)
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    private class Asn1(val b: ByteArray) {
        var i = 0
        fun expect(tag: Int) { require((b[i++].toInt() and 0xff) == tag) { "asn1 tag" } }
        fun readLen(): Int {
            var len = b[i++].toInt() and 0xff
            if (len and 0x80 != 0) {
                val n = len and 0x7f
                len = 0
                repeat(n) { len = (len shl 8) or (b[i++].toInt() and 0xff) }
            }
            return len
        }
        fun readInt(): BigInteger {
            expect(0x02)
            val len = readLen()
            val v = b.copyOfRange(i, i + len); i += len
            return BigInteger(1, v)
        }
    }

    // ---- minimal protobuf -------------------------------------------------

    private fun pb(build: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(build).toByteArray()

    private class ProtoWriter {
        private val out = ByteArrayOutputStream()
        fun varint(field: Int, value: Long) {
            tag(field, 0); writeVarint(value)
        }
        fun bytes(field: Int, value: ByteArray) {
            tag(field, 2); writeVarint(value.size.toLong()); out.write(value)
        }
        private fun tag(field: Int, wire: Int) { writeVarint(((field shl 3) or wire).toLong()) }
        private fun writeVarint(v: Long) {
            var x = v
            while (true) {
                val b = (x and 0x7f).toInt()
                x = x ushr 7
                if (x != 0L) out.write(b or 0x80) else { out.write(b); break }
            }
        }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    class ProtoReader(private val b: ByteArray) {
        private var i = 0
        fun forEach(cb: (field: Int, wire: Int, r: ProtoReader) -> Unit) {
            while (i < b.size) {
                val tag = readVarint().toInt()
                val field = tag ushr 3
                val wire = tag and 0x7
                cb(field, wire, this)
            }
        }
        fun readVarint(): Long {
            var shift = 0; var result = 0L
            while (true) {
                val byte = b[i++].toInt() and 0xff
                result = result or ((byte.toLong() and 0x7f) shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
            }
            return result
        }
        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            val out = b.copyOfRange(i, i + len); i += len
            return out
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                2 -> { val len = readVarint().toInt(); i += len }
                5 -> i += 4
                1 -> i += 8
                else -> error("bad wire $wire")
            }
        }
    }

    private fun pkcs7Unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val pad = data[data.size - 1].toInt() and 0xff
        if (pad in 1..16 && pad <= data.size) {
            var ok = true
            for (k in data.size - pad until data.size) if ((data[k].toInt() and 0xff) != pad) { ok = false; break }
            if (ok) return data.copyOfRange(0, data.size - pad)
        }
        return data // not padded (e.g. bare 16-byte key)
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    companion object {
        private const val TYPE_LICENSE_REQUEST = 1
        private const val TYPE_LICENSE = 2
        private const val REQUEST_TYPE_NEW = 1
        private const val LICENSE_TYPE_STREAMING = 1
        private const val PROTOCOL_VERSION_2_1 = 21   // VERSION_2_1
        private const val KEY_TYPE_CONTENT = 2
    }
}
