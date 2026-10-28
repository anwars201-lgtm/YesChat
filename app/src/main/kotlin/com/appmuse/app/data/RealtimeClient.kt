package com.appmuse.app.data

import com.appmuse.app.config.AppConfig
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Small authenticated Server-Sent Events client used by generated collaborative
 * features. The stream is intentionally separate from Retrofit's finite request
 * client: it has no read timeout and can be cancelled with [Closeable.close].
 */
object RealtimeClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "appmuse-realtime-reconnect").apply { isDaemon = true }
    }

    fun subscribe(path: String, onEvent: (String) -> Unit): Closeable {
        val closed = AtomicBoolean(false)
        val activeCall = AtomicReference<Call?>(null)
        val reconnectAttempt = AtomicInteger(0)
        val refreshedSinceAcceptedConnection = AtomicBoolean(false)
        lateinit var connect: () -> Unit

        val reconnect: (Boolean) -> Unit = { immediately ->
            if (!closed.get()) {
                val delays = longArrayOf(1, 2, 4, 8, 16, 30)
                val attempt = reconnectAttempt.getAndIncrement()
                val delay = if (immediately) 0 else delays[attempt.coerceAtMost(delays.lastIndex)]
                reconnectExecutor.schedule({ connect() }, delay, TimeUnit.SECONDS)
            }
        }
        connect = {
            if (!closed.get()) {
                // Rebuild on every connection so a refreshed bearer token is
                // picked up after a long-lived stream disconnects.
                val url = AppConfig.apiUrl.trimEnd('/') + "/" + path.trimStart('/')
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "text/event-stream")
                    .apply {
                        if (AppConfig.apiKey.isNotEmpty()) header("X-API-Key", AppConfig.apiKey)
                        ApiClient.tokens.accessToken?.takeIf { it.isNotEmpty() }?.let {
                            header("Authorization", "Bearer $it")
                        }
                    }
                    .build()
                val call = client.newCall(request)
                activeCall.set(call)
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        activeCall.compareAndSet(call, null)
                        reconnect(false)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        var shouldReconnect = true
                        var reconnectImmediately = false
                        try {
                            response.use {
                                if (!response.isSuccessful) {
                                    if (response.code == 401) {
                                        val rejectedToken = call.request()
                                            .header("Authorization")
                                            ?.removePrefix("Bearer ")
                                        val outcome = runBlocking {
                                            if (refreshedSinceAcceptedConnection.get()) {
                                                ApiClient.rejectFreshRealtimeSession(rejectedToken)
                                            } else {
                                                ApiClient.refreshSessionForRealtime(rejectedToken)
                                            }
                                        }
                                        shouldReconnect = outcome != SessionRefreshOutcome.TERMINAL_FAILURE
                                        reconnectImmediately = outcome == SessionRefreshOutcome.REFRESHED
                                        if (reconnectImmediately) {
                                            refreshedSinceAcceptedConnection.set(true)
                                        }
                                    } else {
                                        shouldReconnect =
                                            response.code !in 400..499 || response.code == 408 || response.code == 429
                                    }
                                    return@use
                                }
                                // A 2xx response proves the current bearer was accepted.
                                // A later 401 may therefore attempt one fresh rotation.
                                refreshedSinceAcceptedConnection.set(false)
                                val source = response.body?.source() ?: return@use
                                while (!closed.get() && !call.isCanceled()) {
                                    val line = source.readUtf8Line() ?: break
                                    if (line.startsWith("data:")) {
                                        reconnectAttempt.set(0)
                                        onEvent(line.removePrefix("data:").trim())
                                    }
                                }
                            }
                        } finally {
                            activeCall.compareAndSet(call, null)
                            if (shouldReconnect) reconnect(reconnectImmediately)
                        }
                    }
                })
            }
        }
        connect()
        return Closeable {
            if (closed.compareAndSet(false, true)) {
                activeCall.getAndSet(null)?.cancel()
            }
        }
    }
}
