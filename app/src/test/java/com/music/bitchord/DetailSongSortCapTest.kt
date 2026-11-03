package com.music.bitchord

import android.content.SharedPreferences
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SongSort
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** The per-album sort map is remembered per browse id, so it has to be bounded. */
class DetailSongSortCapTest {

    private val preferencesMap = mutableMapOf<String, Any?>()

    @Before
    fun setUp() {
        preferencesMap.clear()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    preferencesMap[args[0] as String] = args[1] as String
                    proxy
                }
                "apply", "commit" -> null
                else -> if (method.returnType == SharedPreferences.Editor::class.java) proxy else null
            }
        } as SharedPreferences.Editor

        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when {
                method.name == "getString" -> preferencesMap[args[0] as String] as? String
                    ?: args[1] as? String
                method.name == "edit" -> editor
                else -> null
            }
        } as SharedPreferences

        val field = AppSettings::class.java.getDeclaredField("prefs")
        field.isAccessible = true
        field.set(AppSettings, prefs)
        // The object outlives a single test; start each from an empty map.
        AppSettings.detailSongSorts.value = emptyMap()
    }

    @Test
    fun `the sort map stops growing at the cap`() {
        repeat(250) { AppSettings.setDetailSongSort("album-$it", SongSort.TITLE_ASC) }
        assertEquals(200, AppSettings.detailSongSorts.value.size)
    }

    @Test
    fun `re-sorting an album keeps it, however old`() {
        repeat(250) { AppSettings.setDetailSongSort("album-$it", SongSort.TITLE_ASC) }
        AppSettings.setDetailSongSort("album-0", SongSort.TITLE_DESC)
        assertEquals(SongSort.TITLE_DESC, AppSettings.detailSongSorts.value["album-0"])
        assertEquals(200, AppSettings.detailSongSorts.value.size)
    }
}
