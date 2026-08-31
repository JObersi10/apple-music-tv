package com.applemusicktv.media

import android.util.Base64
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Widevine license callback for Apple Music LIVE radio (linear.tv key server).
 *
 * The stock HttpMediaDrmCallback POSTs the raw challenge with no Content-Type and Apple's
 * `linear.tv.apple.com/v1/radio/streaming-key-delivery` answers HTTP 415 (unsupported media
 * type). We can't sniff the web player's exact call here (the in-app browser has no Widevine
 * CDM), so this callback logs the server's real response for each attempted body shape — the
 * endpoint's own reply is the spec. `MODE` selects the body format; flip it from logcat findings.
 *
 * Provisioning is a Google call (same as [AppleMusicDrmCallback]) — never Apple.
 */
@UnstableApi
class LiveRadioDrmCallback(
    private val keyUri: String,
    private val bearer: String,
    private val mut: String,
    private val adamId: String,
    /** Widevine EXT-X-KEY URI from the media playlist — the web player's license body `uri`. */
    private val wvKeyUri: String,
) : MediaDrmCallback {

    private val http = OkHttpClient()

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray {
        val url = request.defaultUrl + "&signedRequest=" + String(request.data, Charsets.UTF_8)
        val resp = http.newCall(
            Request.Builder().url(url).post(ByteArray(0).toRequestBody(null, 0, 0)).build()
        ).execute()
        if (!resp.isSuccessful) error("provisioning failed http=${resp.code}")
        return resp.body!!.bytes()
    }

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
        // Exact shape MusicKit's WebPlaybackLicenseManager uses for /v1/radio/streaming-key-delivery.
        val challenge = Base64.encodeToString(request.data, Base64.NO_WRAP)
        val body = org.json.JSONObject().apply {
            put("adamId",         adamId)
            put("isLibrary",      false)
            put("user-initiated", true)
            put("challenge",      challenge)
            put("uri",            wvKeyUri)
            put("key-system",     "com.widevine.alpha")
        }.toString()

        val req = Request.Builder()
            .url(keyUri)
            .post(body.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $bearer")
            .addHeader("Accept", "application/json")
            .addHeader("X-Apple-Music-User-Token", mut)
            .addHeader("X-Apple-Renewal", "true")
            .addHeader("Origin", "https://music.apple.com")
            .build()

        val resp = http.newCall(req).execute()
        val text = resp.body?.string() ?: ""
        Log.i("AMRadio", "license http=${resp.code} len=${text.length}")
        if (!resp.isSuccessful) error("live radio license http=${resp.code}: ${text.take(120)}")

        // Response is a flat { "license": "<base64 widevine license>" } (verified on-device).
        // The older HLS manager wraps in license-responses[0]; handle both to be safe.
        val json = org.json.JSONObject(text)
        val licB64 = when {
            json.has("license") -> json.getString("license")
            json.has("license-responses") -> {
                val r0 = json.getJSONArray("license-responses").getJSONObject(0)
                if (r0.optInt("status", 0) != 0) error("license status=${r0.optInt("status")}: ${text.take(160)}")
                r0.optString("license", r0.optString("key", ""))
            }
            else -> ""
        }
        if (licB64.isEmpty()) error("no license field: ${text.take(160)}")
        return Base64.decode(licB64, Base64.DEFAULT)
    }
}
