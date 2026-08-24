package com.applemusicktv.data.network

import com.squareup.moshi.JsonClass

/** POST body for adding tracks to a library playlist: { "data": [ { "id": ..., "type": "songs" } ] }. */
@JsonClass(generateAdapter = true)
data class AddTracksBody(val data: List<AddTrackRef>)

@JsonClass(generateAdapter = true)
data class AddTrackRef(val id: String, val type: String = "songs")
