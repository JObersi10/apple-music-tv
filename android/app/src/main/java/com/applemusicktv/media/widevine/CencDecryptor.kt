package com.applemusicktv.media.widevine

import android.util.Log
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * In-app CENC ('cenc' / AES-CTR) decrypter for Apple Music fMP4 audio segments.
 *
 * Turns Widevine-encrypted fragments into CLEAR AAC in the same MP4 structure, so
 * ExoPlayer plays them with no DRM session — the c2.android.aac.decoder (or the
 * bundled ffmpeg decoder) then receives intact frames and the 0x4004 chop is gone.
 *
 * Pairs with [WidevineCdm] (which supplies the raw 16-byte content key). Two steps:
 *  - [processInit]  : rewrite the init segment `enca`→`mp4a`, drop `sinf`/`pssh`,
 *                     and read the default per-sample IV size from `tenc`.
 *  - [decryptSegment]: AES-CTR the encrypted sample bytes in each `moof`/`mdat` pair
 *                     using per-sample IVs from `senc` and sizes from `trun`.
 *
 * Apple audio = scheme 'cenc' (CTR, no pattern), tenc v0, 8-byte IVs, usually
 * full-sample encryption (senc with no subsamples). Subsamples are handled too.
 */
class CencDecryptor(private val keyHex: String) {

    private val key: ByteArray = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    var ivSize: Int = 8; private set

    // ---- init segment -----------------------------------------------------

    /** Rewrite init: `enca`→`mp4a`, remove `sinf`, remove `pssh`. Also learns ivSize
     *  from `tenc`. Returns the clear init bytes. */
    fun processInit(init: ByteArray): ByteArray {
        readTencIvSize(init)
        return rewriteBoxes(init, 0, init.size, top = true)
    }

    private fun readTencIvSize(b: ByteArray) {
        // tenc (v0) payload: reserved(1) default_crypt_skip(1) default_isProtected(1)
        // default_per_sample_iv_size(1) kid(16). Byte-search 'tenc' — it's unique.
        for (i in 0..b.size - 12) {
            if (b[i].toInt() == 't'.code && b[i + 1].toInt() == 'e'.code &&
                b[i + 2].toInt() == 'n'.code && b[i + 3].toInt() == 'c'.code
            ) {
                val p = i + 4 + 4 // 'tenc' + fullbox(version+flags)
                if (p + 4 <= b.size) ivSize = b[p + 3].toInt() and 0xff
                Log.i("AMCENC", "tenc ivSize=$ivSize")
                return
            }
        }
        Log.w("AMCENC", "no tenc; defaulting ivSize=$ivSize")
    }

    /** Depth-first copy of boxes, dropping sinf/pssh and renaming enca→mp4a. Parent
     *  sizes are recomputed from what survives. */
    private fun rewriteBoxes(b: ByteArray, start: Int, end: Int, top: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        var i = start
        while (i + 8 <= end) {
            val size = be32(b, i)
            if (size < 8 || i + size > end) break
            val type = type4(b, i + 4)
            when {
                type == "pssh" || type == "sinf" -> { /* drop */ }
                type == "enca" -> {
                    // enca payload starts after 8-byte header; first 8 bytes reserved +
                    // 2 data_reference_index; AudioSampleEntry layout identical to mp4a.
                    // Recurse to strip the child sinf, rename type to mp4a.
                    val childStart = i + 8 + 28 // AudioSampleEntry fixed fields = 28 bytes
                    val inner = rewriteBoxes(b, childStart, i + size, top = false)
                    val header = b.copyOfRange(i + 8, childStart)
                    val newPayload = header + inner
                    writeBox(out, "mp4a", newPayload)
                }
                type == "stsd" -> {
                    // stsd is a FULLBOX: 4 bytes version/flags + 4 bytes entry_count
                    // precede the child sample entries. Preserve that prefix, recurse after.
                    val prefix = b.copyOfRange(i + 8, i + 16)
                    val inner = rewriteBoxes(b, i + 16, i + size, top = false)
                    writeBox(out, "stsd", prefix + inner)
                }
                type in CONTAINERS -> {
                    val inner = rewriteBoxes(b, i + 8, i + size, top = false)
                    writeBox(out, type, inner)
                }
                else -> out.write(b, i, size)
            }
            i += size
        }
        return out.toByteArray()
    }

    // ---- whole file (Apple = single byterange-segmented fMP4) -------------

    /** Decrypt a complete fMP4 file in one pass: rewrite `moov` (enca→mp4a, strip
     *  sinf/pssh) and AES-CTR every `moof`/`mdat`. This is the path Apple actually
     *  uses — the HLS playlist is just byteranges into this one file. */
    fun decryptWhole(data: ByteArray): ByteArray {
        readTencIvSize(data)
        val timescale = readTimescale(data)
        val head = ByteArrayOutputStream()      // ftyp + moov
        val frags = ArrayList<ByteArray>()       // each = rewritten moof + decrypted mdat
        val fragDurations = ArrayList<Long>()    // in `timescale` units, parallel to frags

        var i = 0
        var pendingMoof: ByteArray? = null
        var ivs: List<ByteArray> = emptyList()
        var subs: List<List<Pair<Int, Int>>> = emptyList()
        var sizes = IntArray(0)
        var pendingDur = 0L
        while (i + 8 <= data.size) {
            val size = be32(data, i)
            if (size < 8 || i + size > data.size) break
            when (type4(data, i + 4)) {
                "moov" -> writeBox(head, "moov", rewriteBoxes(data, i + 8, i + size, top = false))
                "ftyp" -> head.write(data, i, size)
                "moof" -> {
                    val info = parseMoof(data, i, i + size)
                    ivs = info.ivs; subs = info.subs; sizes = info.sizes; pendingDur = info.durationTs
                    // Strip senc/saiz/saio (and seig groupings) so ExoPlayer treats the
                    // now-clear samples as unencrypted; without this it NPEs in parseTraf
                    // looking for a track-encryption box we already removed from the init.
                    pendingMoof = rewriteMoof(data, i, i + size)
                }
                "mdat" -> {
                    val copy = data.copyOfRange(i, i + size)
                    decryptMdat(copy, 8, size, ivs, subs, sizes)
                    val moof = pendingMoof
                    if (moof != null) {
                        frags.add(moof + copy)
                        fragDurations.add(pendingDur)
                        pendingMoof = null
                    } else {
                        frags.add(copy) // stray mdat (shouldn't happen)
                        fragDurations.add(0L)
                    }
                }
                // skip any existing sidx/styp — we emit our own index
                "sidx", "styp" -> { /* drop */ }
                else -> head.write(data, i, size)
            }
            i += size
        }

        // Emit ftyp+moov, then a synthesized sidx that lets ExoPlayer seek (the
        // original file has none — that's why fast-forward failed), then fragments.
        val out = ByteArrayOutputStream(data.size + 64)
        out.write(head.toByteArray())
        val refSizes = frags.map { it.size }
        out.write(buildSidx(timescale, fragDurations, refSizes))
        for (f in frags) out.write(f)
        return out.toByteArray()
    }

    /** sidx (v0) indexing each moof+mdat fragment so the stream is seekable. */
    private fun buildSidx(timescale: Int, durations: List<Long>, refSizes: List<Int>): ByteArray {
        val n = durations.size
        val payload = ByteArrayOutputStream()
        fun w32(v: Long) { payload.write((v ushr 24).toInt() and 0xff); payload.write((v ushr 16).toInt() and 0xff); payload.write((v ushr 8).toInt() and 0xff); payload.write(v.toInt() and 0xff) }
        fun w16(v: Int) { payload.write((v ushr 8) and 0xff); payload.write(v and 0xff) }
        w32(0)                       // version 0 + flags 0
        w32(1)                       // reference_ID (track 1)
        w32(timescale.toLong())      // timescale
        w32(0)                       // earliest_presentation_time
        w32(0)                       // first_offset
        w16(0); w16(n)               // reserved, reference_count
        for (k in 0 until n) {
            w32((refSizes[k].toLong() and 0x7fffffff)) // reference_type=0 | referenced_size
            w32(durations[k])                          // subsegment_duration
            w32(0x90000000L)                           // starts_with_SAP=1, SAP_type=1
        }
        return boxBytes("sidx", payload.toByteArray())
    }

    /** Audio track timescale from moov > trak > mdia > mdhd (fallback 44100). */
    private fun readTimescale(b: ByteArray): Int {
        for (i in 0..b.size - 12) {
            if (b[i].toInt() == 'm'.code && b[i + 1].toInt() == 'd'.code &&
                b[i + 2].toInt() == 'h'.code && b[i + 3].toInt() == 'd'.code
            ) {
                val ver = b[i + 4].toInt()
                val tsOff = i + 4 + 4 + (if (ver == 1) 16 else 8)
                if (tsOff + 4 <= b.size) return be32(b, tsOff)
            }
        }
        return 44100
    }

    // ---- media segment ----------------------------------------------------

    /** Decrypt one media segment (may contain several moof/mdat pairs). Returns clear
     *  bytes with the same box layout (senc/saiz/saio left in place but now inert). */
    fun decryptSegment(seg: ByteArray): ByteArray {
        val out = seg.copyOf()
        var i = 0
        var pendingIvs: List<ByteArray> = emptyList()
        var pendingSubs: List<List<Pair<Int, Int>>> = emptyList()
        var pendingSizes: IntArray = IntArray(0)
        while (i + 8 <= out.size) {
            val size = be32(out, i)
            if (size < 8 || i + size > out.size) break
            when (type4(out, i + 4)) {
                "moof" -> {
                    val info = parseMoof(out, i, i + size)
                    pendingIvs = info.ivs; pendingSubs = info.subs; pendingSizes = info.sizes
                }
                "mdat" -> decryptMdat(out, i + 8, i + size, pendingIvs, pendingSubs, pendingSizes)
            }
            i += size
        }
        return out
    }

    private class MoofInfo(val ivs: List<ByteArray>, val subs: List<List<Pair<Int, Int>>>, val sizes: IntArray, val durationTs: Long)

    /** Rebuild a moof with the sample-encryption boxes removed and trun.data_offset
     *  corrected for the bytes that vanished (mdat now sits earlier). */
    private fun rewriteMoof(b: ByteArray, start: Int, end: Int): ByteArray {
        val children = ByteArrayOutputStream()
        var i = start + 8
        while (i + 8 <= end) {
            val sz = be32(b, i); if (sz < 8 || i + sz > end) break
            if (type4(b, i + 4) == "traf") children.write(rewriteTraf(b, i, i + sz))
            else children.write(b, i, sz)
            i += sz
        }
        val payload = children.toByteArray()
        return boxBytes("moof", payload)
    }

    private fun rewriteTraf(b: ByteArray, start: Int, end: Int): ByteArray {
        // Pass 1: how many bytes will we drop?
        var removed = 0
        run {
            var i = start + 8
            while (i + 8 <= end) {
                val sz = be32(b, i); if (sz < 8 || i + sz > end) break
                if (isDropBox(b, i)) removed += sz
                i += sz
            }
        }
        // Pass 2: copy kept boxes, patching trun.data_offset -= removed.
        val kept = ByteArrayOutputStream()
        var i = start + 8
        while (i + 8 <= end) {
            val sz = be32(b, i); if (sz < 8 || i + sz > end) break
            when {
                isDropBox(b, i) -> { /* drop */ }
                type4(b, i + 4) == "trun" -> {
                    val box = b.copyOfRange(i, i + sz)
                    val flags = be32(box, 8) and 0xffffff
                    if (flags and 0x1 != 0) {              // data_offset present at +16
                        val off = be32(box, 16) - removed
                        box[16] = (off ushr 24).toByte(); box[17] = (off ushr 16).toByte()
                        box[18] = (off ushr 8).toByte();  box[19] = off.toByte()
                    }
                    kept.write(box)
                }
                else -> kept.write(b, i, sz)
            }
            i += sz
        }
        return boxBytes("traf", kept.toByteArray())
    }

    private fun isDropBox(b: ByteArray, i: Int): Boolean = when (type4(b, i + 4)) {
        "senc", "saiz", "saio" -> true
        "sbgp", "sgpd" -> type4(b, i + 12) == "seig" // grouping_type after fullbox(4)
        else -> false
    }

    private fun boxBytes(type: String, payload: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(payload.size + 8)
        writeBox(o, type, payload)
        return o.toByteArray()
    }

    private fun parseMoof(b: ByteArray, start: Int, end: Int): MoofInfo {
        var ivs: List<ByteArray> = emptyList()
        var subs: List<List<Pair<Int, Int>>> = emptyList()
        var sizes = IntArray(0)
        var defaultDur = 1024L   // tfhd default_sample_duration (AAC frame = 1024)
        var trunDurSum = -1L     // sum of per-sample durations, if trun carries them
        // walk descendants for tfhd + senc + trun (all inside traf)
        fun walk(s: Int, e: Int) {
            var i = s
            while (i + 8 <= e) {
                val sz = be32(b, i); if (sz < 8 || i + sz > e) break
                val t = type4(b, i + 4)
                when {
                    t == "tfhd" -> defaultDur = parseTfhdDefaultDuration(b, i + 8, i + sz) ?: defaultDur
                    t == "senc" -> { val r = parseSenc(b, i + 8, i + sz); ivs = r.first; subs = r.second }
                    t == "trun" -> { val r = parseTrun(b, i + 8, i + sz); sizes = r.first; trunDurSum = r.second }
                    t in CONTAINERS -> walk(i + 8, i + sz)
                }
                i += sz
            }
        }
        walk(start + 8, end)
        val durationTs = if (trunDurSum >= 0) trunDurSum else sizes.size * defaultDur
        return MoofInfo(ivs, subs, sizes, durationTs)
    }

    // tfhd: fullbox; track_ID(4); [base_data_offset(8) if 0x1]; [sample_desc_idx(4) if 0x2];
    // [default_sample_duration(4) if 0x8]; ...  Returns the default duration if present.
    private fun parseTfhdDefaultDuration(b: ByteArray, start: Int, end: Int): Long? {
        var p = start
        val flags = be32(b, p) and 0xffffff; p += 4
        p += 4 // track_ID
        if (flags and 0x1 != 0) p += 8
        if (flags and 0x2 != 0) p += 4
        return if (flags and 0x8 != 0) be32(b, p).toLong() and 0xffffffffL else null
    }

    // senc: fullbox(version+flags); flags&2 => subsamples. sample_count(u32);
    // per sample: iv(ivSize) [+ subsample_count(u16), (clear u16, enc u32)*]
    private fun parseSenc(b: ByteArray, start: Int, end: Int): Pair<List<ByteArray>, List<List<Pair<Int, Int>>>> {
        var p = start
        val flags = be32(b, p) and 0xffffff; p += 4
        val hasSub = flags and 0x2 != 0
        val count = be32(b, p); p += 4
        val ivs = ArrayList<ByteArray>(count)
        val subs = ArrayList<List<Pair<Int, Int>>>(count)
        for (s in 0 until count) {
            val iv = b.copyOfRange(p, p + ivSize); p += ivSize
            ivs.add(iv)
            if (hasSub) {
                val n = be16(b, p); p += 2
                val list = ArrayList<Pair<Int, Int>>(n)
                for (j in 0 until n) {
                    val clear = be16(b, p); p += 2
                    val enc = be32(b, p); p += 4
                    list.add(clear to enc)
                }
                subs.add(list)
            } else subs.add(emptyList())
        }
        return ivs to subs
    }

    // trun: fullbox; sample_count(u32); [data_offset if flag1]; [first_flags if flag4];
    // per sample fields gated by flags: duration(0x100) size(0x200) flags(0x400) cto(0x800)
    private fun parseTrun(b: ByteArray, start: Int, end: Int): Pair<IntArray, Long> {
        var p = start
        val flags = be32(b, p) and 0xffffff; p += 4
        val count = be32(b, p); p += 4
        if (flags and 0x1 != 0) p += 4 // data_offset
        if (flags and 0x4 != 0) p += 4 // first_sample_flags
        val hasDur = flags and 0x100 != 0
        val hasSize = flags and 0x200 != 0
        val hasFlags = flags and 0x400 != 0
        val hasCto = flags and 0x800 != 0
        val sizes = IntArray(count)
        var durSum = if (hasDur) 0L else -1L
        for (s in 0 until count) {
            if (hasDur) { durSum += be32(b, p).toLong() and 0xffffffffL; p += 4 }
            if (hasSize) { sizes[s] = be32(b, p); p += 4 } else sizes[s] = -1
            if (hasFlags) p += 4
            if (hasCto) p += 4
        }
        return sizes to durSum
    }

    // Walk mdat sample-by-sample; CTR-decrypt encrypted bytes with per-sample IV.
    private fun decryptMdat(
        b: ByteArray, start: Int, end: Int,
        ivs: List<ByteArray>, subs: List<List<Pair<Int, Int>>>, sizes: IntArray,
    ) {
        if (ivs.isEmpty()) return
        var pos = start
        val n = ivs.size
        for (s in 0 until n) {
            val sampleSize = if (s < sizes.size && sizes[s] >= 0) sizes[s] else (end - pos)
            val sampleEnd = pos + sampleSize
            if (sampleEnd > end) { Log.w("AMCENC", "sample overrun s=$s"); return }
            val cipher = ctr(ivs[s])
            val subList = if (s < subs.size) subs[s] else emptyList()
            if (subList.isEmpty()) {
                // full-sample encryption
                ctrInPlace(cipher, b, pos, sampleSize)
            } else {
                var q = pos
                for ((clear, enc) in subList) {
                    q += clear // clear bytes untouched, do NOT advance keystream
                    if (enc > 0) ctrInPlace(cipher, b, q, enc)
                    q += enc
                }
            }
            pos = sampleEnd
        }
    }

    private fun ctr(iv8: ByteArray): Cipher {
        val iv = ByteArray(16)
        System.arraycopy(iv8, 0, iv, 0, minOf(iv8.size, 16))
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c
    }

    // Decrypt in place; one cipher per sample so the CTR keystream continues across
    // subsamples exactly as CENC requires.
    private fun ctrInPlace(c: Cipher, b: ByteArray, off: Int, len: Int) {
        val dec = c.update(b, off, len)
        System.arraycopy(dec, 0, b, off, dec.size)
    }

    // ---- box helpers ------------------------------------------------------

    private fun writeBox(out: ByteArrayOutputStream, type: String, payload: ByteArray) {
        val size = 8 + payload.size
        out.write((size ushr 24) and 0xff); out.write((size ushr 16) and 0xff)
        out.write((size ushr 8) and 0xff); out.write(size and 0xff)
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(payload)
    }

    private fun be16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
    private fun be32(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or
        ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
    private fun type4(b: ByteArray, i: Int) = String(b, i, 4, Charsets.US_ASCII)

    companion object {
        // Boxes we descend into when searching/rewriting.
        private val CONTAINERS = setOf(
            "moov", "trak", "mdia", "minf", "stbl", "stsd", "mvex", "moof", "traf",
            "sinf", "schi", "edts", "udta", "mp4a", "enca",
        )
    }
}
