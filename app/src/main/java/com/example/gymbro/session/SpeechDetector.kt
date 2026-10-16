package com.example.gymbro.session

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

// Finds when someone says something near the phone (e.g. "GymBro"), from how loud the microphone
// is: one value every 20 ms, in dBFS (0 = the loudest sound, -60 = very quiet). The background
// noise (e.g. a swimming pool) is followed automatically: a sound counts as voice when it is louder
// than the noise by marginDb. Nothing is recorded or recognized here: only the loudness is used.
// Plain Kotlin: tested in SpeechDetectorTest.kt
class SpeechDetector(
    private val marginDb: Double = 12.0,  // voice: louder than the background noise by at least this
    private val minDb: Double = -50.0,    // ...and at least this loud (a very quiet room)
    private val startFrames: Int = 6,     // 120 ms of voice: someone started speaking
    private val endFrames: Int = 20,      // 400 ms of quiet after the voice: they stopped
    private val maxFrames: Int = 150      // 3 s of sound without pauses: noise, not a word
) {

    // Level of the background noise (null until the first value)
    var noiseDb: Double? = null
        private set

    private var loudFrames = 0
    private var speaking = false
    private var speechFrames = 0
    private var quietFrames = 0
    private var speechLevelSum = 0.0

    // A new value of the loudness. Returns true when someone has just stopped speaking (the moment
    // to start listening to the number of laps).
    fun add(db: Double): Boolean {
        val noise = noiseDb ?: db.also { noiseDb = it }
        val threshold = max(noise + marginDb, minDb)

        if (!speaking) {
            if (db >= threshold) {
                loudFrames++
                if (loudFrames >= startFrames) {
                    speaking = true
                    speechFrames = loudFrames
                    speechLevelSum = db * loudFrames
                    quietFrames = 0
                }
            } else {
                loudFrames = 0
                // the background noise: followed quickly when it gets quieter, slowly when louder
                noiseDb = noise + (db - noise) * (if (db < noise) 0.2 else 0.02)
            }
            return false
        }

        speechFrames++
        speechLevelSum += db
        // a little quieter than the start: the voice goes up and down while speaking
        quietFrames = if (db < threshold - 3) quietFrames + 1 else 0
        if (speechFrames >= maxFrames) {
            // a long sound without pauses (e.g. water, people shouting): it becomes the background
            noiseDb = speechLevelSum / speechFrames
            reset()
            return false
        }
        if (quietFrames >= endFrames) {
            reset()
            return true
        }
        return false
    }

    // How loud the sound is compared to the background noise, from 0 to 1 (the circle of the
    // microphone on the page grows with it)
    fun level(db: Double): Float {
        val noise = noiseDb ?: return 0f
        return ((db - noise) / (2 * marginDb)).coerceIn(0.0, 1.0).toFloat()
    }

    private fun reset() {
        speaking = false
        loudFrames = 0
        speechFrames = 0
        quietFrames = 0
        speechLevelSum = 0.0
    }

    companion object {
        // Loudness of [count] samples of 16 bit audio, in dBFS (silence: -100)
        fun decibels(samples: ShortArray, count: Int): Double {
            if (count <= 0) return -100.0
            var sum = 0.0
            for (i in 0 until count) {
                val value = samples[i] / 32768.0
                sum += value * value
            }
            val rms = sqrt(sum / count)
            return if (rms <= 1e-5) -100.0 else 20 * log10(rms)
        }
    }
}
