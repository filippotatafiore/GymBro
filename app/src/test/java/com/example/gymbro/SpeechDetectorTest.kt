package com.example.gymbro

import com.example.gymbro.session.SpeechDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Run with: right click on this file > Run 'SpeechDetectorTest'
// One value = 20 ms of sound, in dBFS
class SpeechDetectorTest {

    // true if the detector says that someone stopped speaking during these values
    private fun SpeechDetector.feed(db: Double, frames: Int): Boolean {
        var ended = false
        repeat(frames) { if (add(db)) ended = true }
        return ended
    }

    @Test
    fun aWordAndThenQuiet_detected() {
        val detector = SpeechDetector()
        assertFalse(detector.feed(-60.0, 50))   // quiet room
        assertFalse(detector.feed(-25.0, 30))   // "GymBro" (0.6 s)
        assertTrue(detector.feed(-60.0, 25))    // quiet again: the word ended
    }

    @Test
    fun aShortNoise_ignored() {
        val detector = SpeechDetector()
        detector.feed(-60.0, 50)
        // a click of 60 ms: not a voice
        assertFalse(detector.feed(-20.0, 3))
        assertFalse(detector.feed(-60.0, 50))
    }

    @Test
    fun theNoiseOfThePool_becomesTheBackground() {
        val detector = SpeechDetector()
        detector.feed(-60.0, 50)
        // the pool gets loud and stays loud (water, people): not a word
        assertFalse(detector.feed(-30.0, 400))
        assertEquals(-30.0, detector.noiseDb!!, 2.0)
        // a voice louder than the pool is still heard
        assertFalse(detector.feed(-12.0, 30))
        assertTrue(detector.feed(-30.0, 25))
    }

    @Test
    fun aQuietSound_notAVoice() {
        val detector = SpeechDetector()
        detector.feed(-80.0, 50)
        // louder than the silence, but still very quiet (far from the phone)
        assertFalse(detector.feed(-55.0, 30))
        assertFalse(detector.feed(-80.0, 30))
    }

    @Test
    fun decibels_ofSamples() {
        assertEquals(-100.0, SpeechDetector.decibels(ShortArray(320), 320), 0.0)
        // the loudest square wave: 0 dBFS
        val loud = ShortArray(320) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        assertEquals(0.0, SpeechDetector.decibels(loud, 320), 0.01)
        // half of it: about -6 dBFS
        val half = ShortArray(320) { if (it % 2 == 0) 16384 else -16384 }
        assertEquals(-6.02, SpeechDetector.decibels(half, 320), 0.01)
    }
}
