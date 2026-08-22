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

const val MV_VIDEO_FILE = "mv_video.m3u8"
const val MV_AUDIO_FILE = "mv_audio.m3u8"
const val MV_MASTER_FILE = "mv_master.m3u8"
const val MV_SUBS_FILE = "mv_subs.m3u8"

data class MusicVideoResult(
    val adamId:     String,
    /** Minimal master m3u8 referencing the two local media files by name. */
    val masterText: String,
    val videoText:  String,
    val audioText:  String,
    /** Rewritten WebVTT subtitle playlist, or null when the video has no captions. */
    val subsText:   String? = null,
    /** Fallback Widevine key uri for the license request body. */
    val keyUri:     String = "",
    /** placeholder-KID (hex) → license uri, so the callback routes per track. */
    val keyMap:     Map<String, String> = emptyMap(),
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

    /** Real catalogue metadata for the Info panel (composer, genre, release, label…). */
    data class MvDetails(val info: String, val artistId: String?)

    suspend fun getMusicVideoDetails(mvId: String, bearer: String, mut: String): MvDetails? =
        withContext(Dispatchers.IO) {
            try {
                val numeric = mvId.filter { it.isDigit() }.ifEmpty { mvId }
                val url = "https://amp-api-edge.music.apple.com/v1/catalog/us/music-videos/$numeric?l=en-US&include=artists"
                val resp = http.newCall(Request.Builder().url(url)
                    .addHeader("Authorization", "Bearer $bearer")
                    .addHeader("Music-User-Token", mut)
                    .addHeader("Origin", "https://music.apple.com").build()).execute()
                val data = JSONObject(resp.body!!.string()).optJSONArray("data")?.optJSONObject(0) ?: return@withContext null
                val a = data.optJSONObject("attributes") ?: JSONObject()
                val artistId = data.optJSONObject("relationships")?.optJSONObject("artists")
                    ?.optJSONArray("data")?.optJSONObject(0)?.optString("id")?.ifBlank { null }
                val parts = mutableListOf<String>()
                a.optJSONArray("genreNames")?.let { g ->
                    if (g.length() > 0) parts.add((0 until g.length()).joinToString(", ") { g.getString(it) })
                }
                a.optString("releaseDate").takeIf { it.isNotBlank() }?.let { parts.add("Released $it") }
                a.optString("albumName").takeIf { it.isNotBlank() }?.let { parts.add("From \"$it\"") }
                a.optString("composerName").takeIf { it.isNotBlank() }?.let { parts.add("Composed by $it") }
                a.optString("copyright").takeIf { it.isNotBlank() }?.let { parts.add(it) }
                MvDetails(parts.joinToString("\n"), artistId)
            } catch (e: Exception) {
                Log.w("AMMV", "MV details fetch failed: ${e.message}"); null
            }
        }

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
     * Resolve a music-video id to a set of playable, DRM-corrected HLS playlists.
     *
     * The catch that took three failed attempts: Apple's MV playlist ships a Widevine
     * `#EXT-X-KEY` whose pssh carries a **placeholder KID ("Mic1…"), not the real
     * content KID**. Trust it and the device CDM requests the wrong key → the video
     * sample's real KID (in the fMP4 init segment's `tenc` box) is never satisfied and
     * MediaCodec dies "Crypto key not available". So we do exactly what the audio path
     * does: download each track's init segment, read the real KID off `tenc`, synthesize
     * a correct Widevine pssh, and rewrite the key line with it. Apple's license endpoint
     * returns the real content key regardless of the challenge KID (it maps by adamId),
     * so once the session/sample/license KIDs all agree, playback works.
     *
     * We emit a minimal master with one video variant (best ≤1080p) + one audio rendition
     * pointing at two locally-written media playlists; segments still stream from mvod on
     * demand (nothing but the tiny m3u8 files touches disk). Returns the playlist texts —
     * the caller writes the three files and plays the master via file://.
     */
    suspend fun getMusicVideoPlayback(mvId: String, bearer: String, mut: String): MusicVideoResult =
        withContext(Dispatchers.IO) {
            val numeric = mvId.replace(Regex("^[a-z]+\\."), "")
            val forms = listOf("salableAdamId", "universalLibraryId")
            var entry: JSONObject? = null
            var lastBody = ""
            for (field in forms) {
                val idVal = if (field == "universalLibraryId") mvId else numeric
                val bodyStr = """{"$field":"$idVal","language":"en-US"}"""
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
                val json = JSONObject(lastBody)
                if (json.has("songList") && json.getJSONArray("songList").length() > 0) {
                    val e = json.getJSONArray("songList").getJSONObject(0)
                    if (e.has("hls-playlist-url")) { entry = e; break }
                }
            }
            if (entry == null) error("MV webPlayback rejected: ${lastBody.take(200)}")
            val adamId = entry.optString("songId", numeric)
            val masterUrl = entry.getString("hls-playlist-url")
            val masterText = fetchText(masterUrl, emptyMap())
            val mBase = masterUrl.substringBeforeLast("/") + "/"
            fun abs(u: String) = if (u.startsWith("http")) u else mBase + u
            val lines = masterText.lines()

            // Best video variant ≤1080p (its STREAM-INF attrs are reused in our master).
            data class V(val height: Int, val attrs: String, val uri: String)
            val variants = mutableListOf<V>()
            for (i in lines.indices) {
                if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                    val h = Regex("""RESOLUTION=\d+x(\d+)""").find(lines[i])?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val uri = lines.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: continue
                    variants.add(V(h, lines[i].removePrefix("#EXT-X-STREAM-INF:"), uri))
                }
            }
            val vPick = variants.filter { it.height in 1..1080 }.maxByOrNull { it.height }
                ?: variants.maxByOrNull { it.height } ?: error("No video variant in MV master")

            // The audio group this variant references, else the first/highest audio rendition.
            val wantGroup = Regex("""AUDIO="([^"]+)"""").find(vPick.attrs)?.groupValues?.get(1)
            val audioLines = lines.filter { it.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") }
            val aLine = audioLines.firstOrNull { wantGroup != null && it.contains("GROUP-ID=\"$wantGroup\"") }
                ?: audioLines.lastOrNull() ?: error("No audio rendition in MV master")
            val aUri = Regex("""URI="([^"]+)"""").find(aLine)?.groupValues?.get(1) ?: error("No audio URI")

            val video = rewriteMvMedia(abs(vPick.uri))
            val audio = rewriteMvMedia(abs(aUri))
            val videoText = video.text
            val audioText = audio.text

            // Subtitles (WebVTT, unencrypted) — pick the DEFAULT/first CC or SUBTITLES
            // rendition if present, absolutise its segment URIs, no DRM rewrite needed.
            val subLine = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES") }
                ?: lines.firstOrNull { it.startsWith("#EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS") && it.contains("URI=") }
            val subGroup = subLine?.let { Regex("""GROUP-ID="([^"]+)"""").find(it)?.groupValues?.get(1) }
            val subsText: String? = subLine?.let { sl ->
                val su = Regex("""URI="([^"]+)"""").find(sl)?.groupValues?.get(1) ?: return@let null
                runCatching {
                    val raw = fetchText(abs(su), emptyMap())
                    val sBase = abs(su).substringBeforeLast("/") + "/"
                    raw.lines().joinToString("\n") { ln ->
                        if (ln.isNotBlank() && !ln.startsWith("#") && !ln.startsWith("http")) sBase + ln else ln
                    }
                }.getOrNull()
            }
            // CEA-608 closed captions are muxed INTO the video (no URI, just INSTREAM-ID) —
            // e.g. Die With a Smile. ExoPlayer extracts them from the decoded video, but only
            // if the master declares the CC group. Pass the line through verbatim.
            val ccLine = lines.firstOrNull {
                it.startsWith("#EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS") && it.contains("INSTREAM-ID") && !it.contains("URI=")
            }
            val ccGroup = ccLine?.let { Regex("""GROUP-ID="([^"]+)"""").find(it)?.groupValues?.get(1) }
            // Per-track routing: the DRM callback matches each session's challenge (which
            // embeds the track's placeholder KID) to that track's license uri. Apple's
            // license then binds the real content key under a KID the samples reference.
            val keyMap = listOfNotNull(
                video.kidHex?.let { it to (video.keyUri ?: "") },
                audio.kidHex?.let { it to (audio.keyUri ?: "") },
            ).toMap()
            val keyUri = video.keyUri ?: ""
            Log.i("AMMV", "keyMap=${keyMap.keys}")

            // Minimal master: our single video variant + single audio rendition, both
            // pointing at the local rewritten media playlists.
            val master = buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:6")
                appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
                appendLine("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"English\",DEFAULT=YES,AUTOSELECT=YES,URI=\"$MV_AUDIO_FILE\"")
                if (subsText != null) {
                    appendLine("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"English\",DEFAULT=NO,AUTOSELECT=YES,FORCED=NO,LANGUAGE=\"en\",URI=\"$MV_SUBS_FILE\"")
                }
                if (ccLine != null) appendLine(ccLine)   // CEA-608 CC group, verbatim
                // Force AUDIO="aud" (and SUBTITLES="subs") to match our rendition groups, and
                // drop any CLOSED-CAPTIONS ref unless we carried the CC group through.
                var attrs = vPick.attrs.replace(Regex("""AUDIO="[^"]+""""), "AUDIO=\"aud\"")
                    .let { if (it.contains("AUDIO=")) it else "$it,AUDIO=\"aud\"" }
                    .replace(Regex(""",?SUBTITLES="[^"]+""""), "")
                    .replace(Regex(""",?CLOSED-CAPTIONS=(NONE|"[^"]+")"""), "")
                if (subsText != null) attrs = "$attrs,SUBTITLES=\"subs\""
                attrs = if (ccGroup != null) "$attrs,CLOSED-CAPTIONS=\"$ccGroup\"" else "$attrs,CLOSED-CAPTIONS=NONE"
                appendLine("#EXT-X-STREAM-INF:$attrs")
                appendLine(MV_VIDEO_FILE)
            }
            Log.i("AMMV", "mv=$adamId picked ${vPick.height}p variant; rewrote v+a playlists keyUri=${keyUri.take(36)}")
            MusicVideoResult(adamId = adamId, masterText = master, videoText = videoText, audioText = audioText, subsText = subsText, keyUri = keyUri, keyMap = keyMap)
        }

    /**
     * Fetch one MV media playlist, KEEP Apple's original Widevine key line (its pssh
     * carries the per-track placeholder KID Apple maps to the real content key — the
     * fMP4 `tenc` default is unprotected, so there is no real KID to synthesize), and
     * drop the FairPlay/PlayReady key lines so ExoPlayer commits to Widevine. Segment +
     * map URIs are absolutised so the local file resolves them against mvod. Returns the
     * rewritten text plus this track's placeholder KID (16 bytes) and license uri so the
     * DRM callback can route each session's request to the matching track.
     */
    private fun rewriteMvMedia(mediaUrl: String): MvMedia {
        val text = fetchText(mediaUrl, emptyMap())
        val base = mediaUrl.substringBeforeLast("/") + "/"
        fun abs(u: String) = if (u.startsWith("http")) u else base + u

        val wvLine = text.lines()
            .firstOrNull { it.startsWith("#EXT-X-KEY") && it.contains("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed") }
        val wvUri = wvLine?.let { Regex("""URI="([^"]+)"""").find(it)?.groupValues?.get(1) }
        // Apple's placeholder KID (Mic1/Mic6) lives in the pssh: protobuf `12 10 <16 bytes>`.
        val placeholderKid = wvUri?.substringAfter("base64,", "")?.let { b64 ->
            runCatching {
                val raw = Base64.decode(b64, Base64.DEFAULT)
                var j = 0; var found: ByteArray? = null
                while (j + 2 + 16 <= raw.size) {
                    if (raw[j] == 0x12.toByte() && raw[j + 1] == 0x10.toByte()) { found = raw.copyOfRange(j + 2, j + 18); break }
                    j++
                }
                found
            }.getOrNull()
        }
        val kidHex = placeholderKid?.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        // The tenc default_KID is what MediaCodec queries per sample; it's typically all
        // zeros here (the content key is identified by the pssh, not tenc). Build a pssh
        // carrying BOTH: the tenc KID (so the decoder's key lookup hits) AND the Apple
        // placeholder KID (so the two tracks get distinct DRM sessions and the license
        // callback can route each to its own uri).
        val mapUri = Regex("""#EXT-X-MAP:URI="([^"]+)"""").find(text)?.groupValues?.get(1)
        val tencKid = mapUri?.let { runCatching { extractTencKid(fetchBytes(abs(it))) }.getOrNull() }
        val kids = listOfNotNull(tencKid, placeholderKid).ifEmpty { null }
        val rewrittenKeyLine = kids?.let {
            val pssh = Base64.encodeToString(widevinePssh(it), Base64.NO_WRAP)
            "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"data:text/plain;base64,$pssh\"," +
                "KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\",KEYFORMATVERSIONS=\"1\""
        }

        var keyEmitted = false
        val out = buildString {
            for (raw in text.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-KEY") -> {
                        // Emit our combined-KID Widevine line once (drop FairPlay/PlayReady).
                        if (!keyEmitted) { appendLine(rewrittenKeyLine ?: wvLine ?: line); keyEmitted = true }
                    }
                    line.startsWith("#EXT-X-MAP:") -> {
                        val u = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)
                        appendLine(if (u == null) line else line.replace("\"$u\"", "\"${abs(u)}\""))
                    }
                    line.isEmpty() || line.startsWith("#") -> appendLine(line)
                    else -> appendLine(abs(line))
                }
            }
        }
        Log.i("AMMV", "track tencKid=${tencKid?.joinToString(""){"%02x".format(it.toInt() and 0xFF)}} placeholder=$kidHex")
        return MvMedia(out, wvUri, kidHex)
    }

    private data class MvMedia(val text: String, val keyUri: String?, val kidHex: String?)

    /** Scan an fMP4 init segment for the `tenc` box and return its 16-byte default KID. */
    private fun extractTencKid(bytes: ByteArray): ByteArray? {
        var i = 0
        while (i + 4 <= bytes.size - 4) {
            if (bytes[i] == 't'.code.toByte() && bytes[i + 1] == 'e'.code.toByte() &&
                bytes[i + 2] == 'n'.code.toByte() && bytes[i + 3] == 'c'.code.toByte()) {
                // tenc body: version(1) flags(3) reserved(1) pattern(1) isProtected(1)
                // ivSize(1) then default_KID(16). KID starts 4(type)+9 from here.
                // tenc body after 'tenc': version(1) flags(3) reserved(1) pattern(1)
                // isProtected(1) ivSize(1) then default_KID(16) → KID at 4+8.
                val kidStart = i + 4 + 8
                if (kidStart + 16 <= bytes.size) return bytes.copyOfRange(kidStart, kidStart + 16)
            }
            i++
        }
        return null
    }

    private fun fetchBytes(url: String): ByteArray =
        http.newCall(Request.Builder().url(url).build()).execute().body!!.bytes()

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

    /** Minimal Widevine pssh box wrapping one or more key ids. */
    private fun widevinePssh(kids: List<ByteArray>): ByteArray {
        // Widevine protobuf: field 1 (algorithm) = AESCTR, then field 2 (key_id) repeated.
        var payload = byteArrayOf(0x08, 0x01)
        for (kid in kids) payload = payload + byteArrayOf(0x12, 0x10) + kid
        return psshBox(payload)
    }

    /** Minimal Widevine pssh box wrapping a single key id. */
    private fun widevinePssh(kid: ByteArray): ByteArray {
        // Widevine protobuf: field 1 (algorithm) = AESCTR, field 2 (key_id) = kid.
        val payload = byteArrayOf(0x08, 0x01, 0x12, 0x10) + kid
        return psshBox(payload)
    }

    private fun psshBox(payload: ByteArray): ByteArray {
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
