package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * A playlist of files on this device, made in the Local Music folder's
 * Playlists tab — the offline player's own playlists, which never touch the
 * account. [LIKED_ID] is always there and always first.
 *
 * Tracks are held by [Song.localUri], the one thing about a file that the
 * folder, the player and a download all agree on. A track's row is kept whole
 * so the page can be drawn without listing the device first; whether the file
 * is still there is asked when the page is read (see
 * [LocalMediaRepository.present]), not by rewriting the record.
 */
data class OfflinePlaylist(
    val id: String,
    val title: String,
    /** In the order they were added; the newest like first for [LIKED_ID]. */
    val songs: List<Song>,
) {
    val browseId: String get() = OfflinePlaylists.PREFIX + id
    val isLiked: Boolean get() = id == OfflinePlaylists.LIKED_ID
}

/**
 * The store behind [OfflinePlaylist]: in memory as a [StateFlow], on disk as
 * one JSON document written off the main thread.
 *
 * Writes are conflated — ten likes in a row are ten state updates and at most
 * a couple of disk writes, each of the newest state — so nothing here ever
 * waits on the disk.
 */
object OfflinePlaylists {
    /**
     * Under `local:` like every other page whose tracks are on the device —
     * which keeps the download button off it — in a segment of its own.
     */
    const val PREFIX = "local:offline:"
    const val LIKED_ID = "liked"

    private const val PREF_NAME = "bitchord_offline_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    private lateinit var prefs: SharedPreferences
    private val lock = Any()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Whether the stored list has been published. Guarded by [lock]. */
    private var loaded = false

    /** Set by [init] to the app's own wording for the built-in playlist. */
    private var likedTitle = "Liked songs"

    private val _playlists = MutableStateFlow(listOf(OfflinePlaylist(LIKED_ID, likedTitle, emptyList())))
    val playlists: StateFlow<List<OfflinePlaylist>> = _playlists.asStateFlow()

    private val _liked = MutableStateFlow<Set<String>>(emptySet())

    /** The [Song.localUri]s in [LIKED_ID], for a heart to be drawn without a scan. */
    val liked: StateFlow<Set<String>> = _liked.asStateFlow()

    fun init(context: Context, likedTitle: String) {
        if (this::prefs.isInitialized) return
        this.likedTitle = likedTitle
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // Parsing every playlist and every liked song is a cold-start cost, and
        // the built-in Liked list is already the initial value, so a page has
        // something to draw until this lands. Published under [lock] against
        // [loaded]: a like that arrives first loads the file itself (see
        // [mutate]), so this can neither overwrite it nor be written back over
        // the file by the collector.
        io.launch {
            val stored = load()
            synchronized(lock) {
                if (!loaded) {
                    loaded = true
                    publish(stored)
                    startPersisting()
                }
            }
        }
    }

    /**
     * Writes every change after the one just published.
     *
     * Started only once the stored list is in hand, and capturing it as the
     * value to skip, so the first write is a real change rather than a
     * re-encode of what was just read. Guarded by [lock] through its callers.
     */
    private fun startPersisting() {
        val initial = _playlists.value
        io.launch {
            playlists.filter { it !== initial }.conflate().collect { persist(it) }
        }
    }

    fun isOfflinePlaylist(browseId: String?): Boolean = browseId?.startsWith(PREFIX) == true

    fun isLikedPage(browseId: String?): Boolean = browseId == PREFIX + LIKED_ID

    fun get(browseId: String?): OfflinePlaylist? {
        val id = browseId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
        return _playlists.value.firstOrNull { it.id == id }
    }

    fun isLiked(song: Song): Boolean = song.localUri?.let { it in _liked.value } == true

    /** Likes or unlikes a device file; returns whether it is liked now. */
    fun toggleLike(song: Song): Boolean {
        val key = song.localUri ?: return false
        var nowLiked = false
        mutate { list ->
            nowLiked = key !in _liked.value
            list.map { playlist ->
                if (!playlist.isLiked) return@map playlist
                val rest = playlist.songs.filterNot { it.localUri == key }
                playlist.copy(songs = if (nowLiked) listOf(song.stored()) + rest else rest)
            }
        }
        return nowLiked
    }

    /** A new, empty playlist — or one holding [songs], when it was made to take them. */
    fun create(title: String, songs: List<Song> = emptyList()): OfflinePlaylist {
        val playlist = OfflinePlaylist(
            id = "p" + System.currentTimeMillis().toString(36),
            title = title.trim(),
            songs = songs.filter { it.localUri != null }.distinctBy { it.localUri }.map { it.stored() },
        )
        mutate { it + playlist }
        return playlist
    }

    class AddResult(val added: Int, val alreadyThere: Int)

    /**
     * Adds [songs] to every playlist in [ids], each at most once per playlist.
     * [AddResult.added] counts the playlists that took at least one of them.
     */
    fun add(ids: Collection<String>, songs: List<Song>): AddResult {
        val incoming = songs.filter { it.localUri != null }.distinctBy { it.localUri }
        var added = 0
        var alreadyThere = 0
        mutate { list ->
            list.map { playlist ->
                if (playlist.id !in ids) return@map playlist
                val known = playlist.songs.mapTo(HashSet()) { it.localUri }
                val fresh = incoming.filter { known.add(it.localUri) }.map { it.stored() }
                if (fresh.isEmpty()) {
                    alreadyThere++
                    return@map playlist
                }
                added++
                // Liked keeps its newest first, as a like from the menu does.
                playlist.copy(songs = if (playlist.isLiked) fresh + playlist.songs else playlist.songs + fresh)
            }
        }
        return AddResult(added, alreadyThere)
    }

    fun remove(browseId: String, song: Song) {
        val id = browseId.removePrefix(PREFIX)
        val key = song.localUri ?: return
        mutate { list ->
            list.map { if (it.id == id) it.copy(songs = it.songs.filterNot { s -> s.localUri == key }) else it }
        }
    }

    /** Liked is the app's, not the user's: it can't be renamed or deleted. */
    fun rename(browseId: String, title: String) {
        val id = browseId.removePrefix(PREFIX)
        if (id == LIKED_ID || title.isBlank()) return
        mutate { list -> list.map { if (it.id == id) it.copy(title = title.trim()) else it } }
    }

    fun delete(browseId: String) {
        val id = browseId.removePrefix(PREFIX)
        if (id == LIKED_ID) return
        mutate { list -> list.filterNot { it.id == id } }
    }

    private inline fun mutate(change: (List<OfflinePlaylist>) -> List<OfflinePlaylist>) {
        synchronized(lock) {
            // A change that beats the background parse loads the file first, so
            // it is applied to what is stored rather than to the placeholder —
            // and so the first thing ever written back is the file plus this
            // change, never the placeholder alone.
            if (!loaded) {
                loaded = true
                publish(load())
                startPersisting()
            }
            publish(change(_playlists.value))
        }
    }

    /** Sets the list and the liked set together, Liked pinned first. */
    private fun publish(list: List<OfflinePlaylist>) {
        val liked = list.firstOrNull { it.isLiked } ?: OfflinePlaylist(LIKED_ID, likedTitle, emptyList())
        val ordered = listOf(liked.copy(title = likedTitle)) + list.filterNot { it.isLiked }
        _playlists.value = ordered
        _liked.value = liked.songs.mapNotNullTo(HashSet()) { it.localUri }
    }

    /**
     * Only what playing and drawing a row needs. The queue fields a song picks
     * up on its way through the player are left behind: they describe where it
     * was played from last, not the file.
     */
    private fun Song.stored(): Song = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        albumName = albumName,
        localUri = localUri,
        localPath = localPath,
        localDateAddedSeconds = localDateAddedSeconds,
        localDateModifiedSeconds = localDateModifiedSeconds,
        trackNumber = trackNumber,
    )

    private fun load(): List<OfflinePlaylist> {
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return runCatching { parse(raw) }.getOrElse {
            DebugLog.w("BitChord", "Offline playlists unreadable: ${it.message}")
            emptyList()
        }
    }

    /**
     * Parsed record by record. One unreadable playlist — a field of the wrong
     * type, say — used to fail the whole array, and the empty result was then
     * written back over the stored file on the next edit, taking every playlist
     * and every like with it.
     */
    internal fun parse(raw: String): List<OfflinePlaylist> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val obj = array.getJSONObject(i)
                val songs = obj.getJSONArray("songs")
                OfflinePlaylist(
                    id = obj.getString("id"),
                    title = obj.optString("title"),
                    songs = (0 until songs.length()).mapNotNull { s ->
                        runCatching { songFrom(songs.getJSONObject(s)) }.getOrNull()
                    },
                )
            }.onFailure {
                DebugLog.w("BitChord", "Offline playlist $i unreadable: ${it.message}")
            }.getOrNull()
        }
    }

    private fun persist(list: List<OfflinePlaylist>) {
        runCatching {
            val array = JSONArray()
            list.forEach { playlist ->
                val songs = JSONArray()
                playlist.songs.forEach { songs.put(it.toJson()) }
                array.put(
                    JSONObject()
                        .put("id", playlist.id)
                        .put("title", playlist.title)
                        .put("songs", songs),
                )
            }
            prefs.edit().putString(KEY_PLAYLISTS, array.toString()).commit()
        }.onFailure { DebugLog.w("BitChord", "Offline playlists not saved: ${it.message}") }
    }

    private fun Song.toJson(): JSONObject = JSONObject().apply {
        put("videoId", videoId)
        put("title", title)
        put("artist", artist)
        putOpt("thumbnailUrl", thumbnailUrl)
        putOpt("durationText", durationText)
        putOpt("artistId", artistId)
        putOpt("albumId", albumId)
        putOpt("albumName", albumName)
        putOpt("localUri", localUri)
        putOpt("localPath", localPath)
        localDateAddedSeconds?.let { put("added", it) }
        localDateModifiedSeconds?.let { put("modified", it) }
        trackNumber?.let { put("track", it) }
    }

    private fun songFrom(obj: JSONObject): Song? {
        fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
        val uri = obj.text("localUri") ?: return null
        return Song(
            videoId = obj.text("videoId") ?: uri,
            title = obj.optString("title"),
            artist = obj.optString("artist"),
            thumbnailUrl = obj.text("thumbnailUrl"),
            durationText = obj.text("durationText"),
            artistId = obj.text("artistId"),
            albumId = obj.text("albumId"),
            albumName = obj.text("albumName"),
            localUri = uri,
            localPath = obj.text("localPath"),
            localDateAddedSeconds = if (obj.has("added")) obj.getLong("added") else null,
            localDateModifiedSeconds = if (obj.has("modified")) obj.getLong("modified") else null,
            trackNumber = if (obj.has("track")) obj.getInt("track") else null,
        )
    }
}

/**
 * As the Playlists tab lists it and its page opens — the same [LocalGroup]
 * an album out of the folder is. Liked keeps its heart rather than borrowing
 * the sleeve of whatever was liked last.
 */
fun OfflinePlaylist.toGroup(): LocalGroup = LocalGroup(
    browseId = browseId,
    title = title,
    subtitle = "",
    thumbnailUrl = if (isLiked) null else songs.firstNotNullOfOrNull { it.thumbnailUrl },
    type = com.music.bitchord.data.model.BrowseType.PLAYLIST,
    songs = songs,
)
