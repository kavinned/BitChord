package com.music.bitchord

import com.music.bitchord.data.trimToLimit
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedMapsTest {

    @Test
    fun `a map stops growing at the limit`() {
        val map = ConcurrentHashMap<String, String>()
        repeat(600) { map["k$it"] = "v$it" }

        map.trimToLimit(512)

        assertEquals(512, map.size)
    }

    @Test
    fun `a map inside the limit is left as it is`() {
        val map = ConcurrentHashMap<String, String>()
        repeat(10) { map["k$it"] = "v$it" }

        map.trimToLimit(512)

        assertEquals(10, map.size)
    }

    @Test
    fun `a set stops growing at the limit`() {
        val set = ConcurrentHashMap.newKeySet<String>()
        repeat(600) { set.add("k$it") }

        set.trimToLimit(512)

        assertEquals(512, set.size)
    }
}
