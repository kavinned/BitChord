package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * An artist's photo as Apple Music shows it — the square portrait on their
 * artist page — found from nothing but their name. Top Picks draws its
 * artists with these rather than YouTube's channel art.
 *
 * Two public, unauthenticated requests, both small:
 *  - the iTunes search API turns the name into the artist's Apple Music
 *    page, taken only when the name it gives back is the name asked for *and*
 *    that Apple artist shares a song or release title with the YouTube
 *    artist's own page ([AppleCatalogue]) — a name alone has put another
 *    "Krsna"'s face on KR$NA;
 *  - that page's `og:image`, read off the first few kilobytes of the page —
 *    the tag sits near the top of a document that runs to most of a megabyte,
 *    so the rest is never downloaded.
 *
 * The photo comes back as an `is1-ssl.mzstatic.com` template whose last path
 * segment is the size; callers ask for the size they draw at (see the UI's
 * `croppedAt`). Answers, misses included, are kept for the process and handed
 * to [seed] / [snapshot] to outlive it, so a row rebuilt on every Home open
 * looks each artist up once.
 */
object AppleArtistPhotos {

    private const val TAG = "AppleArtistPhotos"
    private const val HEAD_LIMIT = 64 * 1024
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36"

    /**
     * [KEY_PREFIX] + normalised name → photo, or "" for an artist Apple has no
     * (proven) photo of.
     */
    private val known = ConcurrentHashMap<String, String>()

    /**
     * Marks answers made with the catalogue check. Answers stored before it
     * were matched on name alone, so [seed] drops anything without it.
     */
    private const val KEY_PREFIX = "v2|"

    /** Test seam: the unit tests replace this to answer the two fixed hosts. */
    internal var client: OkHttpClient = Http.client.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val OG_IMAGE = Regex("""property="og:image"\s+content="([^"]+)"""")

    /**
     * What a lookup proved.
     *
     * A failed request and an artist Apple does not have both used to arrive
     * as null, and the caller remembered both as "this artist has no photo".
     * One 429 then hid that card for good, across restarts. Only [Absent] may
     * be remembered; [Failed] is a question still open.
     */
    private sealed interface Outcome {
        data class Found(val url: String) : Outcome
        data object Absent : Outcome
        data object Failed : Outcome
    }

    /** Answers from an earlier process. */
    fun seed(photos: Map<String, String>) {
        photos.forEach { (key, url) ->
            if (key.startsWith(KEY_PREFIX) && (url.isEmpty() || isArtistPhoto(url))) known.putIfAbsent(key, url)
        }
    }

    /** Everything known now, misses included, for [seed] next time. */
    fun snapshot(): Map<String, String> = HashMap(known)

    /**
     * [artistName]'s Apple Music photo, or null when Apple has none that
     * provably is them. [catalogue] is the YouTube artist's own song and
     * release titles ([AppleCatalogue.titlesOf]); it is only asked for on a
     * cache miss, since it may cost a page fetch. With no titles nothing can be
     * proved, and nothing is asked or remembered. Never throws.
     */
    suspend fun photoFor(artistName: String, catalogue: suspend () -> Collection<String>): String? {
        val name = artistName.matchKey()
        if (name.isEmpty()) return null
        val key = KEY_PREFIX + name
        known[key]?.let { return it.ifEmpty { null } }
        val titles = AppleCatalogue.keys(runCatching { catalogue() }.getOrDefault(emptyList()))
        if (titles.isEmpty()) return null
        val found = withContext(Dispatchers.IO) {
            when (val outcome = runCatching { lookup(artistName, name, titles) }
                .onFailure { Log.d(TAG, "lookup failed for $artistName: $it") }
                .getOrDefault(Outcome.Failed)) {
                is Outcome.Found -> outcome.url
                Outcome.Absent -> ""
                Outcome.Failed -> return@withContext null // a request miss: not remembered
            }
        } ?: return null
        known[key] = found
        return found.ifEmpty { null }
    }

    /**
     * The same search the artist page's header art makes
     * (`AppleArtistArtRepository`), so a Top Picks card shows the face that
     * page does: the US store, names compared on their letters and digits
     * alone — "Vishal-Shekhar" and "Vishal & Shekhar" are one name — and the
     * best-known match first.
     *
     * Apple can list the same artist twice, and the lesser entry often has no
     * photo, only one of their record covers. So a match whose page has no
     * real photo passes the turn to the next one with the same name. Every
     * candidate must first share a title with [titles]; one that doesn't is
     * somebody else and is never counted against [MATCHES_TRIED].
     */
    private fun lookup(artistName: String, key: String, titles: Set<String>): Outcome {
        val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(artistName, "UTF-8")}" +
            "&entity=musicArtist&limit=5"
        val body = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
            .execute().use { response ->
                if (!response.isSuccessful) return Outcome.Failed
                response.body?.string() ?: return Outcome.Failed
            }
        val results = runCatching { json.parseToJsonElement(body).jsonObject["results"]?.jsonArray }
            .getOrNull() ?: return Outcome.Failed
        val candidates = results.asSequence()
            .map { it.jsonObject }
            .filter { it.string("artistName").orEmpty().matchKey() == key }
            .filter { it.string("artistLinkUrl").orEmpty().startsWith("https://music.apple.com/") }
            .toList()
        // The search answered and none of its results is this artist.
        if (candidates.isEmpty()) return Outcome.Absent

        val pages = mutableListOf<String>()
        for (result in candidates) {
            val id = result.string("artistId")?.toLongOrNull() ?: continue
            if (!AppleCatalogue.sharesTitles(id, titles)) {
                Log.d(TAG, "$artistName: Apple ${result.string("artistName")} ($id) shares no titles, skipped")
                continue
            }
            result.string("artistLinkUrl")?.substringBefore('?')?.let(pages::add)
            if (pages.size == MATCHES_TRIED) break
        }
        // Candidates by name existed but none could be proved this artist:
        // [AppleCatalogue.sharesTitles] answers a failed request with the same
        // false a genuine "somebody else" gets, so this is unproven, not absent.
        if (pages.isEmpty()) return Outcome.Failed
        for (page in pages) ogImage(page)?.let { return Outcome.Found(it) }
        return Outcome.Absent
    }

    private const val MATCHES_TRIED = 2

    private fun String.matchKey(): String = AppleCatalogue.matchKey(this)

    /** The page's `og:image`, read off its head and the download dropped as soon as it's found. */
    private fun ogImage(pageUrl: String): String? =
        client.newCall(Request.Builder().url(pageUrl).header("User-Agent", USER_AGENT).build()).execute().use { response ->
            // A failed page is an open question, not proof the artist has no
            // photo — [photoFor] must not remember it either way.
            if (!response.isSuccessful) error("Apple artist page failed (HTTP ${response.code})")
            val source = response.body?.source() ?: return null
            val buffer = Buffer()
            val head = StringBuilder()
            while (head.length < HEAD_LIMIT && source.read(buffer, 8_192) != -1L) {
                head.append(buffer.readUtf8())
                OG_IMAGE.find(head)?.let { return it.groupValues[1].takeIf(::isArtistPhoto) }
                if ("</head>" in head) return null
            }
            null
        }

    /**
     * A portrait rather than a stand-in. Apple's artist photos live under
     * `Features…` or `AMCArtistImages…`; an artist it has no photo of gets the
     * cover of one of their records instead (`Music…`), which on a card billed
     * "Featuring" reads as a film poster, not a person — so that counts as no
     * photo.
     */
    private fun isArtistPhoto(url: String): Boolean {
        val bucket = url.substringAfter("mzstatic.com/image/thumb/", "").substringBefore('/')
        return bucket.startsWith("Features") || bucket.startsWith("AMCArtistImages")
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.content
}
