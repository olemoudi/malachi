package dev.malachi.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One process-wide OkHttp client. Every client owns a dispatcher executor and a connection pool
 * that are never shut down, so building a fresh one per download would churn threads for the
 * lifetime of a process that is meant to stay alive. Derive variants with [OkHttpClient.newBuilder],
 * which shares those pools.
 */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
