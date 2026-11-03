package com.music.bitchord.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.AfterTest
import kotlin.test.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A failed request and an artist Apple does not have used to be the same null,
 * and both were remembered. One 429 then hid that artist's card for good.
 */
class AppleArtistPhotosTest {

    private val original = AppleArtistPhotos.client

    @AfterTest
    fun restoreClient() {
        AppleArtistPhotos.client = original
    }

    private fun answering(status: Int, body: String): Pair<OkHttpClient, () -> Int> {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("test")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return client to { calls.get() }
    }

    @Test
    fun `a rate limited lookup is retried rather than remembered as absent`() = runBlocking {
        val (client, calls) = answering(status = 429, body = "")
        AppleArtistPhotos.client = client
        val catalogue: suspend () -> Collection<String> = { listOf("Some Long Title") }

        assertNull(AppleArtistPhotos.photoFor("Rate Limited Artist", catalogue))
        assertNull(AppleArtistPhotos.photoFor("Rate Limited Artist", catalogue))

        assertEquals(2, calls(), "a failed request must not be cached as 'no photo'")
    }

    @Test
    fun `a definitive miss is remembered`() = runBlocking {
        val (client, calls) = answering(status = 200, body = """{"results":[]}""")
        AppleArtistPhotos.client = client
        val catalogue: suspend () -> Collection<String> = { listOf("Some Long Title") }

        assertNull(AppleArtistPhotos.photoFor("No Such Artist", catalogue))
        assertNull(AppleArtistPhotos.photoFor("No Such Artist", catalogue))

        assertEquals(1, calls())
    }
}
