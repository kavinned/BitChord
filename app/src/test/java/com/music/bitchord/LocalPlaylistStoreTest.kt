package com.music.bitchord

import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylistStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalPlaylistStoreTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var queued: MutableList<Runnable>

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        queued = mutableListOf()
        LocalPlaylistStore.initForTesting(prefs) { queued += it }
    }

    /**
     * Every edit used to walk and re-serialise the whole collection on the
     * calling thread. The in-memory list still updates at once, but a burst of
     * edits now leaves one pending background write rather than one per edit.
     */
    @Test
    fun `a burst of edits queues one whole-collection write`() {
        val playlist = LocalPlaylistStore.savePlaylist("Mix", listOf(song("v1")))
        LocalPlaylistStore.renamePlaylist(playlist.id, "Mix 2")
        LocalPlaylistStore.renamePlaylist(playlist.id, "Mix 3")

        assertEquals("Mix 3", LocalPlaylistStore.playlists.value.single().title)
        assertEquals(1, queued.size)
        assertTrue(prefs.getAll().isEmpty())
    }

    private fun song(id: String) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist",
        thumbnailUrl = null,
    )

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            data[key] as? Set<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(this)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private class Editor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
                if (key != null) pending[key] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) pending.remove(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                pending.clear()
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                pending.forEach { (key, value) ->
                    if (value == null) prefs.data.remove(key) else prefs.data[key] = value
                }
            }
        }
    }
}
