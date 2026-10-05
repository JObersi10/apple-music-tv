package com.applemusicktv.lyrics.spicy.romanization

/**
 * Japanese romanizer. The upstream Spicy Player uses Kuromoji (IPADIC) for kanji readings, but that
 * ships a ~15 MB dictionary — too heavy for this TV app — so it's omitted here. [isAvailable] returns
 * false, which makes [JapaneseRomanizerProvider] fall back to [KanaOnlyJapaneseRomanizer]: kana is
 * romanized (Hepburn), kanji is left as-is. Drop Kuromoji back in and restore the tokenizer path to
 * get full kanji readings.
 */
object JapaneseRomanizer : Romanizer {
    override val script = Script.JAPANESE
    override fun isAvailable(): Boolean = false
    override fun romanize(text: String): String = KanaRomanizer.romanize(text)

    /** Per-syllable kana romanization (the Kuromoji token-aware path is omitted; see class note). */
    fun romanizeLine(line: List<String>): List<String> = line.map { romanize(it) }
}
