package com.applemusicktv.media

import javax.inject.Inject
import javax.inject.Singleton

/** One video in a playback queue. */
data class VideoItem(val id: String, val title: String, val artist: String)

/**
 * A process-wide handoff for the video queue. PlaylistDetail (or any list) fills this
 * before navigating to the video player, and [MusicVideoViewModel] reads it so prev/next
 * move through the list in place — no re-navigation, seamless like the audio queue.
 */
@Singleton
class VideoQueue @Inject constructor() {
    var items: List<VideoItem> = emptyList()
        private set
    var index: Int = 0
        private set

    fun set(list: List<VideoItem>, startIndex: Int) {
        items = list
        index = startIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }

    fun current(): VideoItem? = items.getOrNull(index)
    fun hasNext(): Boolean = index < items.lastIndex
    fun hasPrev(): Boolean = index > 0

    fun next(): VideoItem? { if (hasNext()) index++; return current() }
    fun prev(): VideoItem? { if (hasPrev()) index--; return current() }
}
