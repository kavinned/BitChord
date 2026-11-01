package com.music.bitchord

import com.music.bitchord.data.sources.module.isSameOrigin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleOriginTest {

    @Test
    fun `an absolute URL on the module's own origin is allowed`() {
        assertTrue(isSameOrigin("https://api.example.com/search?q=x", "https://api.example.com"))
    }

    @Test
    fun `an omitted port matches the scheme's default`() {
        assertTrue(isSameOrigin("https://api.example.com:443/search", "https://api.example.com"))
        assertTrue(isSameOrigin("http://api.example.com:80/search", "http://api.example.com"))
    }

    @Test
    fun `another host is refused`() {
        assertFalse(isSameOrigin("https://collector.example.net/?t=x", "https://api.example.com"))
    }

    @Test
    fun `a host that merely ends with the module's host is refused`() {
        assertFalse(isSameOrigin("https://api.example.com.collector.net/", "https://api.example.com"))
    }

    @Test
    fun `another scheme is refused`() {
        assertFalse(isSameOrigin("http://api.example.com/search", "https://api.example.com"))
    }

    @Test
    fun `another port is refused`() {
        assertFalse(isSameOrigin("https://api.example.com:8443/search", "https://api.example.com"))
    }

    @Test
    fun `a base with no origin refuses an absolute URL`() {
        assertFalse(isSameOrigin("https://api.example.com/search", ""))
    }
}
