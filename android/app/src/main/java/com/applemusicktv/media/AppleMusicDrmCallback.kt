package com.applemusicktv.media

import android.util.Base64
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.toRequestBody as bytesToRequestBody
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

    /**
     * Device provisioning. This is NOT an Apple call — it goes to Google's Widevine
     * provisioning server, whose URL the CDM supplies in the request. Returning an
     * empty array here (the previous stub) makes provideProvisionResponse throw and
     * playback dies before a single key is ever fetched.
     */
    override fun executeProvisionRequest(
        uuid: UUID,
        request: ExoMediaDrm.ProvisionRequest,
    ): ByteArray {
        val url = request.defaultUrl + "&signedRequest=" + String(request.data, Charsets.UTF_8)
        val resp = http.newCall(
            Request.Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody(null, 0, 0))
                .build()
        ).execute()
        if (!resp.isSuccessful) error("provisioning failed http=${resp.code}")
        return resp.body!!.bytes()
    }

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
        val respBody = resp.body!!.string()
        val json = JSONObject(respBody)
        android.util.Log.i("AMMV", "license http=${resp.code} status=${json.opt("status")} hasLicense=${json.has("license")} keys=${json.keys().asSequence().toList()}")
        val licenseB64 = json.getString("license")
        return Base64.decode(licenseB64, Base64.DEFAULT)
    }
}
