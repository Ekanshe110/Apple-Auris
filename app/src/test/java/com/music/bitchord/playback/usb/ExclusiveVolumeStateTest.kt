package com.music.bitchord.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusiveVolumeStateTest {

    @Test
    fun `system listening level is curved once and app gain stays linear`() {
        val listening = 0.5f
        val app = 0.5f
        val expected = UacFeatureVolume.perceptualLinearGain(listening) * app

        assertEquals(expected, exclusiveSoftwareGain(listening, app), 1e-6f)
    }

    @Test
    fun `one system slider is not applied twice`() {
        val listening = 0.5f
        val once = UacFeatureVolume.perceptualLinearGain(listening)
        val squared = UacFeatureVolume.perceptualLinearGain(listening * listening)

        assertEquals(once, exclusiveSoftwareGain(listening, 1f), 1e-6f)
        assertTrue("double application makes normal steps feel much larger", once > squared)
    }

    @Test
    fun `remembered DAC level wins over an attach-time maximum`() {
        assertEquals(0.6f, attachMaxPinFallbackGain(1f, 0.6f)!!, 1e-6f)
        assertNull("a genuine maximum without prior evidence stays trustworthy", attachMaxPinFallbackGain(1f, Float.NaN))
    }

    @Test
    fun `Feature Unit needs a changed readback before it owns volume`() {
        assertTrue(featureVolumeReadbackProvesWritable(100, 101))
        assertFalse(featureVolumeReadbackProvesWritable(100, 100))
        assertFalse(featureVolumeReadbackProvesWritable(100, null))
        assertFalse(featureVolumeReadbackProvesWritable(null, 100))
    }

    @Test
    fun `partial Feature Unit channel writes do not report success`() {
        assertTrue(featureVolumeWritesSucceeded(listOf(true, true)))
        assertFalse(featureVolumeWritesSucceeded(listOf(true, false)))
        assertFalse(featureVolumeWritesSucceeded(listOf(false)))
        assertFalse(featureVolumeWritesSucceeded(emptyList()))
    }

    @Test
    fun `software gain stays muted until hardware unity is established`() {
        assertEquals(
            0f,
            exclusiveSoftwareGainAfterHardwareUnity(
                unityEstablished = false,
                listeningGain = 0.5f,
                appGain = 0.5f,
            ),
            1e-6f,
        )
        assertEquals(
            exclusiveSoftwareGain(0.5f, 0.5f),
            exclusiveSoftwareGainAfterHardwareUnity(
                unityEstablished = true,
                listeningGain = 0.5f,
                appGain = 0.5f,
            ),
            1e-6f,
        )
    }

    @Test
    fun `only explicit volume changes during an active session are remembered`() {
        assertTrue(
            shouldRememberDacGain(explicitVolumeBroadcast = true, exclusiveSessionActive = true)
        )
        assertFalse(
            shouldRememberDacGain(explicitVolumeBroadcast = false, exclusiveSessionActive = true)
        )
        assertFalse(
            shouldRememberDacGain(explicitVolumeBroadcast = true, exclusiveSessionActive = false)
        )
    }
}
