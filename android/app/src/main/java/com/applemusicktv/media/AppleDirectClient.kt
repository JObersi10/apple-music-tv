package com.applemusicktv.media

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class WebPlaybackResult(
    val adamId:   String,
    val hlsUrl:   String,
    val keyUri:   String,
    /** Raw media playlist — needed so the EXT-X-KEY line can be rewritten. */
    val hlsText:  String = "",
)

@Singleton
class AppleDirectClient @Inject constructor() {

    private val http = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .addHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                .build())
        }
        .build()

    private var cachedBearer: String = ""

    suspend fun getBearer(): String = withContext(Dispatchers.IO) {
        if (cachedBearer.isNotEmpty()) return@withContext cachedBearer
        try {
            val html = http.newCall(Request.Builder().url("https://music.apple.com/").build())
                .execute().body!!.string()
            val scriptPath = Regex("""crossorigin src="(/assets/index[^"]+\.js)"""")
                .find(html)?.groupValues?.get(1) ?: return@withContext ""
            val js = http.newCall(Request.Builder().url("https://music.apple.com$scriptPath").build())
                .execute().body!!.string()
            val token = Regex("""(eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]*)""")
                .find(js)?.value ?: return@withContext ""
            cachedBearer = token
            token
        } catch (e: Exception) {
            Log.e("AppleDirectClient", "Bearer scrape failed: ${e.message}")
            ""
        }
    }

    fun clearBearerCache() { cachedBearer = "" }

    suspend fun getWebPlayback(songId: String, bearer: String, mut: String): WebPlaybackResult =
        withContext(Dispatchers.IO) {
            // Apple's webPlayback wants a library song under "universalLibraryId"
            // and a catalog song under "salableAdamId". Sending the wrong form
            // returns failureType 1010 (NoSalableAdamId). We can't always tell
            // which a given id is, so try the natural form first, then the other.
            val isLibrary = songId.startsWith("i.")
            val forms = if (isLibrary)
                listOf("universalLibraryId", "salableAdamId")
            else
                listOf("salableAdamId", "universalLibraryId")

            var entry: JSONObject? = null
            var lastBody = ""
            for (field in forms) {
                val bodyStr = """{"$field":"$songId","language":"en-US"}"""
                val resp = http.newCall(
                    Request.Builder()
                        .url("https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback")
                        .post(bodyStr.toRequestBody("application/json".toMediaType()))
                        .addHeader("Authorization", "Bearer $bearer")
                        .addHeader("Cookie",        "media-user-token=$mut")
                        .addHeader("Origin",        "https://music.apple.com")
                        .build()
                ).execute()
                lastBody = resp.body!!.string()
                Log.d("AppleDirectClient", "webPlayback[$field] http=${resp.code} bearerLen=${bearer.length} body=${lastBody.take(200)}")
                val json = JSONObject(lastBody)
                if (json.has("songList") && json.getJSONArray("songList").length() > 0) {
                    entry = json.getJSONArray("songList").getJSONObject(0)
                    break
                }
            }
            if (entry == null) error("webPlayback rejected both forms: ${lastBody.take(200)}")
            val adamId = entry.getString("songId")
            val assets = entry.getJSONArray("assets")

            // --- DIAGNOSTIC (AMWP) ---------------------------------------------
            // Dump every flavor so we can see if a cleaner (44.1 kHz) encode is on
            // offer, and hunt for a per-asset Sound Check / loudness gain that would
            // explain quiet tracks like FEEL. Tag AMWP; pull with logcat.
            run {
                val flavors = (0 until assets.length()).joinToString(", ") { idx ->
                    val a = assets.getJSONObject(idx)
                    "${a.optString("flavor")}(${a.optString("sampleRate", "?")}Hz)"
                }
                Log.i("AMWP", "song=$adamId flavors=[$flavors]")
                // Top-level entry keys + any that smell like loudness/gain.
                Log.i("AMWP", "entryKeys=${entry.keys().asSequence().toList()}")
                for (k in entry.keys()) {
                    if (Regex("gain|loud|volume|sound|normal", RegexOption.IGNORE_CASE).containsMatchIn(k))
                        Log.i("AMWP", "entry.$k=${entry.opt(k)}")
                }
                // Sound Check gain often rides on each asset, not the entry.
                for (idx in 0 until assets.length()) {
                    val a = assets.getJSONObject(idx)
                    for (k in a.keys()) {
                        if (Regex("gain|loud|volume|sound|normal", RegexOption.IGNORE_CASE).containsMatchIn(k))
                            Log.i("AMWP", "asset[${a.optString("flavor")}].$k=${a.opt(k)}")
                    }
                }
            }
            // -------------------------------------------------------------------

            // Flavor identities (per gamdl's MEDIA_CODEC_FLAVOR_MAP):
            //   28:ctrp256 = aac-web    = AAC-LC 256k, Widevine (CENC)  ← no SBR
            //   32:ctrp64  = aac-he-web = HE-AAC  64k, Widevine (CENC)  ← SBR
            // On-device, Android's FDK decoder throws 0x4004 ("substituting silence")
            // on the HE-AAC/SBR frames of some tracks — that's the standalone chop.
            // AAC-LC has no SBR, so prefer 28:ctrp256; only these two ctrp flavors are
            // Widevine-decryptable on-device. Fall back ctrp256 → ctrp64 → any ctrp.
            var ctrp256 = ""; var ctrp64 = ""; var anyCtrp = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val flavor = a.optString("flavor")
                when {
                    flavor == "28:ctrp256" -> ctrp256 = a.getString("URL")
                    flavor == "32:ctrp64"  -> ctrp64  = a.getString("URL")
                    flavor.contains("ctrp") && anyCtrp.isEmpty() -> anyCtrp = a.getString("URL")
                }
            }
            val assetUrl = ctrp256.ifEmpty { ctrp64.ifEmpty { anyCtrp } }
            Log.i("AMWP", "chose asset flavor=${if (ctrp256.isNotEmpty()) "28:ctrp256" else if (ctrp64.isNotEmpty()) "32:ctrp64" else "other-ctrp"}")
            if (assetUrl.isEmpty()) error("No CENC stream asset for $songId")

            // Resolve to media playlist and extract keyUri
            val (mediaUrl, hlsText) = resolveMediaPlaylist(assetUrl, bearer, mut)
            val keyUri = Regex("""URI="(data:[^"]+)"""").find(hlsText)?.groupValues?.get(1)
                ?: error("No key URI in HLS manifest")

            WebPlaybackResult(adamId = adamId, hlsUrl = mediaUrl, keyUri = keyUri, hlsText = hlsText)
        }

    /**
     * ExoPlayer's HLS parser hard-rejects Apple's `#EXT-X-KEY:METHOD=ISO-23001-7`
     * ("Couldn't match METHOD=..."), so the manifest never even loads. Drop the key
     * line — the CENC pssh lives in the fMP4 init segment, and the DrmSessionManager
     * we attach handles the Widevine license from there. Segment URIs are absolutised
     * because the rewritten playlist is served from a local file, so relative paths
     * would otherwise resolve against file://.
     */
    fun rewritePlaylistForExo(text: String, playlistUrl: String): String {
        val base = playlistUrl.substring(0, playlistUrl.lastIndexOf('/') + 1)
        fun abs(u: String) = if (u.startsWith("http")) u else base + u
        return buildString {
            for (raw in text.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-KEY") -> rewriteKeyLine(line)?.let { appendLine(it) }
                    line.startsWith("#EXT-X-MAP:") -> {
                        val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)
                        appendLine(if (uri == null) line else line.replace(uri, abs(uri)))
                    }
                    line.isEmpty() || line.startsWith("#") -> appendLine(line)
                    else -> appendLine(abs(line))
                }
            }
        }
    }

    /**
     * Apple signals CENC as `METHOD=ISO-23001-7`, which ExoPlayer's HLS parser rejects
     * outright, and the init segment carries **no pssh box** — so simply dropping the
     * line left the CDM with no init data and playback died in
     * queueSecureInputBuffer. Re-emit it as SAMPLE-AES-CTR with a Widevine KEYFORMAT,
     * carrying a pssh we build from Apple's KID.
     */
    private fun rewriteKeyLine(line: String): String? {
        if (!line.contains("ISO-23001-7")) return line          // already something Exo knows
        val data = Regex("""URI="data:[^,]*,([^"]+)"""").find(line)?.groupValues?.get(1) ?: return null
        val kid = try { Base64.decode(data, Base64.DEFAULT) } catch (_: Exception) { null } ?: return null
        if (kid.size != 16) { Log.w("AppleDirectClient", "unexpected KID size ${kid.size}"); return null }
        val pssh = Base64.encodeToString(widevinePssh(kid), Base64.NO_WRAP)
        return "#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI=\"data:text/plain;base64,$pssh\"," +
            "KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\",KEYFORMATVERSIONS=\"1\""
    }

    /** Minimal Widevine pssh box wrapping a single key id. */
    private fun widevinePssh(kid: ByteArray): ByteArray {
        // Widevine protobuf: field 1 (algorithm) = AESCTR, field 2 (key_id) = kid.
        val payload = byteArrayOf(0x08, 0x01, 0x12, 0x10) + kid
        val systemId = byteArrayOf(
            0xED.toByte(), 0xEF.toByte(), 0x8B.toByte(), 0xA9.toByte(), 0x79, 0xD6.toByte(),
            0x4A, 0xCE.toByte(), 0xA3.toByte(), 0xC8.toByte(), 0x27, 0xDC.toByte(),
            0xD5.toByte(), 0x1D, 0x21, 0xED.toByte(),
        )
        val size = 4 + 4 + 4 + 16 + 4 + payload.size
        val out = java.nio.ByteBuffer.allocate(size)
        out.putInt(size)
        out.put("pssh".toByteArray(Charsets.US_ASCII))
        out.putInt(0)                 // version 0, flags 0
        out.put(systemId)
        out.putInt(payload.size)
        out.put(payload)
        return out.array()
    }

    /**
     * Download the #EXT-X-MAP init segment and log its encryption boxes. ExoPlayer
     * fails at queueSecureInputBuffer with a bare IllegalArgumentException, which tells
     * us nothing about *why* — this prints the actual scheme (cenc vs cbcs), the
     * pattern block counts, IV size and which DRM systems have a pssh, so the fix
     * stops being guesswork.
     */
    fun probeInitSegment(hlsText: String, playlistUrl: String, bearer: String, mut: String) {
        try {
            val base = playlistUrl.substring(0, playlistUrl.lastIndexOf('/') + 1)
            val mapUri = Regex("""#EXT-X-MAP:URI="([^"]+)"""").find(hlsText)?.groupValues?.get(1)
            if (mapUri == null) { Log.w("AMProbe", "no EXT-X-MAP in playlist"); return }
            val url = if (mapUri.startsWith("http")) mapUri else base + mapUri
            val req = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $bearer")
                .addHeader("Cookie", "media-user-token=$mut")
                .build()
            val bytes = http.newCall(req).execute().body!!.bytes()
            Log.i("AMProbe", "init segment ${bytes.size} bytes")
            walkBoxes(bytes, 0, bytes.size, 0)
        } catch (e: Exception) {
            Log.w("AMProbe", "probe failed: ${e.message}")
        }
    }

    private fun be32(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
        ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    private fun walkBoxes(b: ByteArray, start: Int, end: Int, depth: Int) {
        var i = start
        while (i + 8 <= end) {
            var size = be32(b, i).toLong()
            val type = String(b, i + 4, 4, Charsets.US_ASCII)
            var header = 8
            if (size == 1L) { header = 16; size = 0; for (k in 0 until 8) size = (size shl 8) or (b[i + 8 + k].toLong() and 0xFF) }
            if (size == 0L) size = (end - i).toLong()
            if (size < header || i + size > end) return
            val bodyStart = i + header
            val bodyEnd = (i + size).toInt()

            when (type) {
                // Containers worth descending into.
                "moov", "trak", "mdia", "minf", "stbl", "sinf", "schi" ->
                    walkBoxes(b, bodyStart, bodyEnd, depth + 1)
                "stsd" -> walkBoxes(b, bodyStart + 8, bodyEnd, depth + 1)
                // Protected audio sample entry: 8 bytes of entry header + 20 bytes of
                // AudioSampleEntry before the child boxes (sinf lives in there).
                "enca" -> walkBoxes(b, bodyStart + 28, bodyEnd, depth + 1)
                "schm" -> {
                    val scheme = String(b, bodyStart + 4, 4, Charsets.US_ASCII)
                    val ver = be32(b, bodyStart + 8)
                    Log.i("AMProbe", "schm scheme=$scheme version=0x${ver.toString(16)}")
                }
                "tenc" -> {
                    val version = b[bodyStart].toInt() and 0xFF
                    val patternByte = b[bodyStart + 6].toInt() and 0xFF
                    val isProtected = b[bodyStart + 6 + 1].toInt() and 0xFF
                    val ivSize = b[bodyStart + 6 + 2].toInt() and 0xFF
                    val kid = (0 until 16).joinToString("") { k ->
                        "%02x".format(b[bodyStart + 6 + 3 + k].toInt() and 0xFF)
                    }
                    Log.i("AMProbe", "tenc v$version cryptBlk=${patternByte shr 4} skipBlk=${patternByte and 0x0F} " +
                        "isProtected=$isProtected ivSize=$ivSize kid=$kid")
                }
                "pssh" -> {
                    val sys = (0 until 16).joinToString("") { k ->
                        "%02x".format(b[bodyStart + 4 + k].toInt() and 0xFF)
                    }
                    val name = when (sys) {
                        "edef8ba979d64acea3c827dcd51d21ed" -> "Widevine"
                        "9a04f07998404286ab92e65be0885f95" -> "PlayReady"
                        "94ce86fb07ff4f43adb893d2fa968ca2" -> "FairPlay"
                        else -> "unknown"
                    }
                    Log.i("AMProbe", "pssh system=$name ($sys) size=$size")
                }
            }
            i += size.toInt()
        }
    }

    private fun resolveMediaPlaylist(url: String, bearer: String, mut: String): Pair<String, String> {
        val headers = mapOf(
            "Authorization" to "Bearer $bearer",
            "Cookie"        to "media-user-token=$mut",
        )
        val text = fetchText(url, headers)
        if (!text.contains("#EXT-X-STREAM-INF")) return url to text

        // Master playlist → pick highest bandwidth
        val lines = text.lines()
        var bestBw = -1; var bestUrl = ""
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val next = lines.getOrNull(i + 1)?.trim() ?: continue
                if (next.isNotEmpty() && !next.startsWith("#") && bw >= bestBw) {
                    bestBw = bw
                    bestUrl = if (next.startsWith("http")) next
                              else url.substring(0, url.lastIndexOf('/') + 1) + next
                }
            }
        }
        if (bestUrl.isEmpty()) error("No variant in master playlist")
        return bestUrl to fetchText(bestUrl, headers)
    }

    private fun fetchText(url: String, headers: Map<String, String>): String {
        val req = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        return http.newCall(req).execute().body!!.string()
    }
}
