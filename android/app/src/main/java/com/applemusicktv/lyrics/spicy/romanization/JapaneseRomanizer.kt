package com.applemusicktv.lyrics.spicy.romanization

import com.atilika.kuromoji.dict.CharacterDefinitions
import com.atilika.kuromoji.dict.ConnectionCosts
import com.atilika.kuromoji.dict.InsertedDictionary
import com.atilika.kuromoji.dict.TokenInfoDictionary
import com.atilika.kuromoji.dict.UnknownDictionary
import com.atilika.kuromoji.ipadic.Tokenizer
import com.atilika.kuromoji.trie.DoubleArrayTrie
import com.atilika.kuromoji.util.ResourceResolver
import java.io.File
import java.io.FileInputStream

/**
 * Japanese romanizer. Uses Kuromoji (IPADIC) for kanji readings — but the ~33MB dictionary is NOT
 * bundled; it's downloaded on demand by [JapaneseDictionaryPack]. Until the pack is present (or if it
 * fails to load) this degrades to kana-only romanization (kanji left as-is) via [KanaRomanizer].
 *
 * Logic ported from the Spicy Player mobile app (kuroshiro-style reading resolution): each token is
 * read by its pronunciation (は → "wa", 東京 → "tōkyō"), readings shared back to the syllables they span.
 *
 * [dictDir] is set once at app startup; the tokenizer builds lazily (and re-tries once the pack lands).
 */
object JapaneseRomanizer : Romanizer {
    override val script = Script.JAPANESE

    /** Where [JapaneseDictionaryPack] extracts the dictionary. Set at app startup. */
    @Volatile var dictDir: File? = null

    @Volatile private var tk: Tokenizer? = null
    @Volatile private var failed = false

    /** The tokenizer if the pack is present and loads; null (→ kana fallback) otherwise. Re-tries once
     *  the pack is downloaded, but never re-tries after a hard load failure. */
    private fun tokenizer(): Tokenizer? {
        tk?.let { return it }
        if (failed) return null
        val dir = dictDir ?: return null
        if (!dirReady(dir)) return null
        synchronized(this) {
            tk?.let { return it }
            return try {
                FileDictionaryBuilder(dir).build().also {
                    tk = it
                    android.util.Log.i("AMtr", "kuromoji tokenizer loaded from $dir")
                }
            } catch (t: Throwable) {
                failed = true
                android.util.Log.e("AMtr", "kuromoji tokenizer load FAILED: ${t.message}", t)
                null
            }
        }
    }

    private fun dirReady(dir: File): Boolean =
        DICT_FILES.all { File(dir, it).let { f -> f.exists() && f.length() > 0 } }

    override fun isAvailable(): Boolean = tokenizer() != null

    override fun romanize(text: String): String {
        val t = tokenizer() ?: return KanaRomanizer.romanize(text)
        return try {
            tokens(t, text).joinToString(" ") { token -> KanaRomanizer.romanize(token.spoken()) }
        } catch (th: Throwable) {
            KanaRomanizer.romanize(text)
        }
    }

    /**
     * Romanizes one line's [syllables] with the whole line as tokenizer context, one entry per
     * syllable. A syllable starting a new word gets a leading space so the romaji reads/wraps by word.
     */
    fun romanizeLine(syllables: List<String>): List<String> {
        val t = tokenizer() ?: return syllables.map(KanaRomanizer::romanize)
        val text = syllables.joinToString("")
        val owner = syllables.flatMapIndexed { index, s -> List(s.length) { index } }
        val kana = List(syllables.size) { mutableListOf<String>() }
        val startsAt = syllables.runningFold(0) { at, s -> at + s.length }
        val startsWord = BooleanArray(syllables.size)
        return try {
            val tokens = tokens(t, text)
            val ruby = rubyReadings(text, tokens)
            var lastReading: String? = null
            for (token in tokens) {
                if (token.surface.isNotBlank()) {
                    val syllable = startsAt.indexOf(token.position)
                    if (syllable > 0 && syllable < syllables.size) startsWord[syllable] = true
                }
                val readings = when {
                    token.surface.all { it == '々' } && token.pronunciation.orEmpty().none(::isKanaChar) && lastReading != null ->
                        token.surface.map { lastReading!! }
                    hasJapanese(token.surface) -> charReadings(token.surface, token.pronunciation ?: token.reading)
                    else -> token.surface.map(Char::toString)
                }.mapIndexed { i, r -> ruby[token.position + i] ?: r }
                readings.lastOrNull { it.isNotEmpty() }?.let { lastReading = it }
                val bySyllable = readings.indices.groupBy { owner[token.position + it] }.toSortedMap()
                for ((syllable, chars) in bySyllable) {
                    val piece = chars.joinToString("") { readings[it] }
                    if (piece.isNotBlank()) kana[syllable] += piece
                }
            }
            val pieces = kana.flatMapIndexed { i, list -> list.map { i to it } }
            val romaji = List(syllables.size) { mutableListOf<String>() }
            pieces.forEachIndexed { k, (syllable, piece) ->
                romaji[syllable] += if (piece.endsWith('ッ')) {
                    val next = pieces.getOrNull(k + 1)?.second?.let { KanaRomanizer.romanize(it).trim() }.orEmpty()
                    val doubled = when {
                        next.startsWith("ch") -> "t"
                        next.firstOrNull()?.let { it.isLetter() && it.lowercaseChar() !in "aeiouāēīōūn" } == true -> next.first().toString()
                        else -> ""
                    }
                    KanaRomanizer.romanize(piece.trimEnd('ッ')).trim() + doubled
                } else {
                    KanaRomanizer.romanize(piece).trim()
                }
            }
            romaji.mapIndexed { i, list ->
                val joined = list.filter(String::isNotEmpty).joinToString(" ").trim()
                if (startsWord[i] && joined.isNotEmpty()) " $joined" else joined
            }
        } catch (th: Throwable) {
            syllables.map(KanaRomanizer::romanize)
        }
    }

    private val RUBY = Regex("[(（]([ぁ-ゖァ-ーー]+)[)）]")

    private fun rubyReadings(text: String, tokens: List<KToken>): Map<Int, String> = buildMap {
        for (m in RUBY.findAll(text)) {
            val word = tokens.lastOrNull { it.position + it.surface.length == m.range.first } ?: continue
            val kanji = word.surface.takeLastWhile(::isKanji).length
            if (kanji == 0) continue
            for (i in m.range.first - kanji until m.range.first) put(i, "")
            for (i in m.range) put(i, if (i in m.groups[1]!!.range) toKatakana(text[i]).toString() else "")
        }
    }

    private class KToken(
        var surface: String,
        var reading: String,
        var pronunciation: String?,
        val pos: String,
        val position: Int,
    ) {
        fun spoken(): String = if (hasJapanese(surface)) pronunciation ?: reading else surface
    }

    private fun tokens(tk: Tokenizer, text: String): List<KToken> {
        val tokens = tk.tokenize(text).mapTo(mutableListOf()) { t ->
            val reading = t.reading?.takeIf { it != "*" }
            val pronunciation = t.pronunciation?.takeIf { it != "*" }
            KToken(
                surface = t.surface,
                reading = when {
                    !hasJapanese(t.surface) -> t.surface
                    reading == null -> if (t.surface.all(::isKanaChar)) toKatakana(t.surface) else t.surface
                    else -> toKatakana(reading)
                },
                pronunciation = pronunciation,
                pos = t.partOfSpeechLevel1,
                position = t.position,
            )
        }
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.pos == "助動詞" && (t.surface == "う" || t.surface == "ウ") && i > 0 && tokens[i - 1].pos == "動詞") {
                val prev = tokens[i - 1]
                prev.surface += "う"
                prev.pronunciation = (prev.pronunciation ?: prev.reading) + "ー"
                prev.reading += "ウ"
                tokens.removeAt(i)
                continue
            }
            i++
        }
        var j = 0
        while (j < tokens.size) {
            val t = tokens[j]
            if ((t.pos == "動詞" || t.pos == "形容詞") && t.surface.length > 1 &&
                (t.surface.endsWith('っ') || t.surface.endsWith('ッ')) && j + 1 < tokens.size
            ) {
                val next = tokens[j + 1]
                t.surface += next.surface
                t.pronunciation = (t.pronunciation ?: t.reading) + (next.pronunciation ?: next.reading)
                t.reading += next.reading
                tokens.removeAt(j + 1)
                continue
            }
            j++
        }
        return tokens
    }

    internal fun charReadings(surface: String, reading: String?): List<String> {
        if (reading == null) return surface.map(Char::toString)
        val kata = surface.map(::toKatakana)
        var prefix = 0
        while (prefix < surface.length && prefix < reading.length && isKana(kata[prefix]) && kata[prefix] == reading[prefix]) prefix++
        var suffix = 0
        while (suffix < surface.length - prefix && suffix < reading.length - prefix &&
            isKana(kata[surface.length - 1 - suffix]) && kata[surface.length - 1 - suffix] == reading[reading.length - 1 - suffix]
        ) suffix++
        val middleChars = surface.length - prefix - suffix
        if (middleChars == 0) return kata.map(Char::toString)
        val morae = morae(reading.substring(prefix, reading.length - suffix))
        val middle = List(middleChars) { i ->
            morae.subList(i * morae.size / middleChars, (i + 1) * morae.size / middleChars).joinToString("")
        }
            .mapIndexed { i, r -> if (r.startsWith('ー')) kata[prefix + i] + r.drop(1) else r }
            .toMutableList()
        for (i in 1 until middle.size) {
            while (middle[i].startsWith('ッ')) {
                middle[i - 1] = middle[i - 1] + "ッ"
                middle[i] = middle[i].drop(1)
            }
        }
        return kata.take(prefix).map(Char::toString) + middle + kata.takeLast(suffix).map(Char::toString)
    }

    private fun morae(reading: String): List<String> = buildList {
        for (c in reading) {
            if (isEmpty() || c !in "ャュョァィゥェォヮー") add(c.toString()) else this[lastIndex] += c
        }
    }

    private fun hasJapanese(s: String) = s.any { isKanaChar(it) || isKanji(it) }
    private fun isKanaChar(c: Char) = c in '぀'..'ゟ' || c in '゠'..'ヿ'
    private fun isKanji(c: Char) = c in '一'..'鿏' || c in '豈'..'﫿' || c in '㐀'..'䶿'
    private fun isKana(c: Char) = c.code in 0x30A1..0x30FC
    private fun toKatakana(c: Char) = if (c.code in 0x3041..0x3096) (c.code + 0x60).toChar() else c
    private fun toKatakana(s: String) = String(CharArray(s.length) { toKatakana(s[it]) })

    private val DICT_FILES = listOf(
        "characterDefinitions.bin", "connectionCosts.bin", "doubleArrayTrie.bin",
        "tokenInfoDictionary.bin", "tokenInfoFeaturesMap.bin",
        "tokenInfoPartOfSpeechMap.bin", "tokenInfoTargetMap.bin",
    )

    /**
     * An IPADIC tokenizer builder that loads the dictionary from a downloaded directory ([dir]) instead
     * of the classpath (the .bin are excluded from the APK). Mirrors the stock
     * `Tokenizer.Builder.loadDictionaries()` but swaps in a file-backed [ResourceResolver]; the IPADIC
     * penalty/feature defaults (2/3000, 7/1700, 9 features) are inlined since the originals are private.
     */
    private class FileDictionaryBuilder(private val dir: File) : Tokenizer.Builder() {
        override fun loadDictionaries() {
            penalties = arrayListOf(2, 3000, 7, 1700)
            val res = ResourceResolver { name -> FileInputStream(File(dir, File(name).name)) }
            try {
                doubleArrayTrie = DoubleArrayTrie.newInstance(res)
                connectionCosts = ConnectionCosts.newInstance(res)
                tokenInfoDictionary = TokenInfoDictionary.newInstance(res)
                characterDefinitions = CharacterDefinitions.newInstance(res)
                unknownDictionary = UnknownDictionary.newInstance(res, characterDefinitions, 9)
                insertedDictionary = InsertedDictionary(9)
            } catch (e: Exception) {
                throw RuntimeException("Could not load downloaded Kuromoji dictionaries.", e)
            }
        }
    }
}
