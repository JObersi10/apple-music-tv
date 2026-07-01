package com.applemusicktv.media

import android.util.Base64
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

@UnstableApi
class AppleMusicDrmCallback(
    private val adamId: String,
    private val keyUri: String,
    private val bearer: String,
    private val mut:    String,
) : MediaDrmCallback {

    private val http = OkHttpClient()

    override fun executeProvisionRequest(
        uuid: UUID,
        request: ExoMediaDrm.ProvisionRequest,
    ): ByteArray = ByteArray(0)

    override fun executeKeyRequest(
        uuid: UUID,
        request: ExoMediaDrm.KeyRequest,
    ): ByteArray {
        val challenge = Base64.encodeToString(request.data, Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("challenge",      challenge)
            put("key-system",     "com.widevine.alpha")
            put("uri",            keyUri)
            put("adamId",         adamId)
            put("isLibrary",      false)
            put("user-initiated", true)
        }.toString()

        val httpReq = Request.Builder()
            .url("https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense")
            .post(body.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $bearer")
            .addHeader("Cookie",        "media-user-token=$mut")
            .addHeader("Origin",        "https://music.apple.com")
            .build()

        val resp = http.newCall(httpReq).execute()
        val licenseB64 = JSONObject(resp.body!!.string()).getString("license")
        return Base64.decode(licenseB64, Base64.DEFAULT)
    }
}
