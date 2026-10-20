package com.applemusicktv.lyrics.spicy.romanization

/**
 * The Japanese romanizer. Always [JapaneseRomanizer]: it uses the downloaded Kuromoji dictionary when
 * present and self-degrades to kana-only conversion (kanji unchanged) when it isn't — so once the
 * pronunciation pack downloads, kanji readings light up with no restart. The old KanaOnly fallback
 * selection is gone; JapaneseRomanizer owns that fallback internally.
 */
object JapaneseRomanizerProvider {
    fun get(): Romanizer = JapaneseRomanizer
}
