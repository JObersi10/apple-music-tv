package com.applemusicktv.lyrics.spicy.romanization

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * On-demand "Japanese pronunciation pack": Kuromoji's IPADIC dictionary. The ~33MB of `.bin` files are
 * NOT shipped in the APK (excluded in build.gradle `packaging{}`). Instead they're downloaded once, on
 * the user's request, by streaming the public `kuromoji-ipadic` JAR from Maven Central and extracting
 * just the dictionary binaries into the app's files dir. [JapaneseRomanizer] loads from [dir] when
 * [isReady]; otherwise it falls back to kana-only romanization (kanji left as-is).
 *
 * No custom hosting: Maven Central already serves the artifact, stably and publicly.
 */
object JapaneseDictionaryPack {

    /** The 7 dictionary resources the IPADIC tokenizer needs (names as they sit in the JAR). */
    private val FILES = listOf(
        "characterDefinitions.bin",
        "connectionCosts.bin",
        "doubleArrayTrie.bin",
        "tokenInfoDictionary.bin",
        "tokenInfoFeaturesMap.bin",
        "tokenInfoPartOfSpeechMap.bin",
        "tokenInfoTargetMap.bin",
    )
    private const val JAR_URL =
        "https://repo1.maven.org/maven2/com/atilika/kuromoji/kuromoji-ipadic/0.9.0/kuromoji-ipadic-0.9.0.jar"
    private const val PREFIX = "com/atilika/kuromoji/ipadic/"
    /** ~33MB uncompressed; a short sanity floor so a truncated download isn't treated as ready. */
    private const val MIN_TOTAL_BYTES = 30_000_000L

    fun dir(context: Context): File = File(context.filesDir, "kuromoji_ipadic").apply { mkdirs() }

    /** All dictionary files present and plausibly complete. */
    fun isReady(context: Context): Boolean {
        val d = dir(context)
        val files = FILES.map { File(d, it) }
        if (files.any { !it.exists() || it.length() == 0L }) return false
        return files.sumOf { it.length() } >= MIN_TOTAL_BYTES
    }

    @Volatile private var downloading = false
    val inProgress: Boolean get() = downloading

    /**
     * Download + extract the dictionary. Streams the JAR and writes only the 7 `.bin` entries, so the
     * full JAR is never stored. [onProgress] gets a 0..1 fraction (best-effort, by extracted bytes vs
     * the ~33MB target). Returns true on success. Safe to call when already [isReady] (returns true).
     * Must run off the main thread.
     */
    fun download(context: Context, onProgress: (Float) -> Unit = {}): Boolean {
        if (isReady(context)) return true
        if (downloading) return false
        downloading = true
        val d = dir(context)
        val tmp = File(d, ".tmp").apply { deleteRecursively(); mkdirs() }
        try {
            val conn = (URL(JAR_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 30_000
                setRequestProperty("User-Agent", "AppleMusicTV")
            }
            if (conn.responseCode != 200) return false
            var extracted = 0L
            var got = 0
            ZipInputStream(conn.inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.startsWith(PREFIX)) {
                        val base = name.substring(PREFIX.length)
                        if (base in FILES) {
                            File(tmp, base).outputStream().buffered().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zip.read(buf); if (n < 0) break
                                    out.write(buf, 0, n); extracted += n
                                    onProgress((extracted.toFloat() / MIN_TOTAL_BYTES).coerceIn(0f, 0.99f))
                                }
                            }
                            got++
                            if (got == FILES.size) { zip.closeEntry(); break }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            if (got != FILES.size) return false
            // Atomically move extracted files into place.
            FILES.forEach { File(tmp, it).renameTo(File(d, it)) }
            onProgress(1f)
            return isReady(context)
        } catch (t: Throwable) {
            return false
        } finally {
            tmp.deleteRecursively()
            downloading = false
        }
    }
}
