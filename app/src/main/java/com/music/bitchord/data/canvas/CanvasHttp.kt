package com.music.bitchord.data.canvas

import com.music.bitchord.data.Http
import com.music.bitchord.data.boundedString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The app's client with a hard ceiling on a whole canvas call.
 *
 * The shared client bounds how long it waits for a connect and for each read,
 * but a host that accepts and then drips bytes resets that read window
 * indefinitely. Canvas is decoration, so a lookup gets 8 seconds in total and
 * no more. Built from [Http.client] so the connection pool stays shared.
 */
internal val canvasClient: OkHttpClient by lazy {
    Http.client.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
}

/**
 * A plain GET returning the body, or null for anything that isn't a 2xx or
 * that throws. Canvas is decoration: no provider failure is allowed to reach
 * the caller, and none of these hosts are ours to depend on.
 */
internal fun canvasGet(url: String, headers: Map<String, String> = emptyMap()): String? {
    val request = Request.Builder().url(url).apply {
        headers.forEach { (name, value) -> header(name, value) }
    }.build()
    return runCatching {
        canvasClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.boundedString(CANVAS_MAX_BODY_BYTES) else null
        }
    }.getOrNull()
}

/**
 * Same as [canvasGet], but keeps the status code even on failure — for the
 * few callers where "it wasn't a 2xx" needs to say *which* code, rather than
 * collapsing every kind of failure into the same null.
 */
internal fun canvasGetWithStatus(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String?> {
    val request = Request.Builder().url(url).apply {
        headers.forEach { (name, value) -> header(name, value) }
    }.build()
    return runCatching {
        canvasClient.newCall(request).execute().use { response ->
            response.code to if (response.isSuccessful) response.body?.boundedString(CANVAS_MAX_BODY_BYTES) else null
        }
    }.getOrDefault(-1 to null)
}

/** A canvas lookup is a small metadata document; a few hundred KB is already huge. */
/**
 * How much of a response is kept, for the lookup bodies that are JSON.
 *
 * Not the default for everything here: these requests also fetch Apple's web
 * player page and its script bundle, which are megabytes — 2.5 MB and 3.3 MB
 * when this was written. A 512 KB cap made [boundedString] return null rather
 * than a short read, so the page came back empty, no token was ever found and
 * every Apple-served canvas silently vanished. The Spotify canvaz JSON, which
 * is what this was meant for, stays under the cap where it is read.
 */
private const val CANVAS_MAX_BODY_BYTES = 4L * 1024 * 1024

/** The browser UA these catalog endpoints expect; they 403 an unknown one. */
internal const val CANVAS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/122.0.0.0 Safari/537.36"
