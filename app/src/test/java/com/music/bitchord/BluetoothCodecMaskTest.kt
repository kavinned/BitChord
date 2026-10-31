package com.music.bitchord

import com.music.bitchord.playback.audio.bluetooth.BluetoothAudioTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothCodecMaskTest {

    @Test
    fun `a named sample rate passes through`() {
        assertEquals(44100, BluetoothAudioTracker.mapSampleRate(44100))
        assertEquals(192000, BluetoothAudioTracker.mapSampleRate(192000))
    }

    @Test
    fun `a sample-rate mask resolves to its bit`() {
        assertEquals(192000, BluetoothAudioTracker.mapSampleRate(1 shl 5))
        assertEquals(48000, BluetoothAudioTracker.mapSampleRate(1 shl 1))
    }

    @Test
    fun `a sample rate off the mask is rejected`() {
        assertNull(BluetoothAudioTracker.mapSampleRate(0))
        assertNull(BluetoothAudioTracker.mapSampleRate(64))
    }

    @Test
    fun `a named bit depth passes through`() {
        assertEquals(16, BluetoothAudioTracker.mapBitDepth(16))
        assertEquals(32, BluetoothAudioTracker.mapBitDepth(32))
    }

    @Test
    fun `a bit-depth mask resolves to its bit`() {
        assertEquals(16, BluetoothAudioTracker.mapBitDepth(1 shl 0))
        assertEquals(24, BluetoothAudioTracker.mapBitDepth(1 shl 1))
        assertEquals(32, BluetoothAudioTracker.mapBitDepth(1 shl 2))
    }

    @Test
    fun `a bit depth off the mask is rejected`() {
        assertNull(BluetoothAudioTracker.mapBitDepth(0))
        assertNull(BluetoothAudioTracker.mapBitDepth(8))
    }
}
