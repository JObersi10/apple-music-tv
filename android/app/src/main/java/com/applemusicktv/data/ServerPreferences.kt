package com.applemusicktv.data

import android.content.Context
import androidx.core.content.edit
import com.applemusicktv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("apple_music_tv", Context.MODE_PRIVATE)

    // Runtime reachability of the configured server (health-checked at startup
    // and on playback errors). When false, playback falls back to the on-device
    // standalone path. In-memory only — not persisted.
    @Volatile
    var serverReachable: Boolean = true

    fun getPcServerIp(): String = prefs.getString("pc_server_ip", "") ?: ""
    fun setPcServerIp(ip: String) = prefs.edit { putString("pc_server_ip", ip.trim()) }
    fun hasPcServer(): Boolean = getPcServerIp().isNotEmpty()

    fun effectiveBaseUrl(): String {
        val ip = getPcServerIp()
        return if (ip.isEmpty()) BuildConfig.PROXY_BASE_URL
        else if (ip.startsWith("http")) "${ip.trimEnd('/')}/"
        else "http://$ip:3000/"
    }
}
