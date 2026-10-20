package com.applemusicktv.data

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Retries a request a few times on transient network failures — chiefly `UnknownHostException` and
 * socket timeouts, which this Fire TV throws in bursts when its DNS flakes (artwork from mzstatic,
 * translate.googleapis, …). Without this a single blip leaves the cover/gloss blank until the next
 * reload. Bounded with a short backoff so it never hangs a request indefinitely.
 */
class RetryInterceptor(private val maxRetries: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var lastError: IOException? = null
        for (attempt in 0..maxRetries) {
            try {
                return chain.proceed(chain.request())
            } catch (e: IOException) {
                lastError = e
                if (attempt == maxRetries) break
                try {
                    Thread.sleep(400L * (attempt + 1))   // 0.4s, 0.8s, 1.2s …
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastError ?: IOException("request failed")
    }
}
