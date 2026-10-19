package com.example.gymbro.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.gymbro.session.ActiveSession
import com.example.gymbro.session.SwimMath
import com.example.gymbro.session.SwimTracker
import com.example.gymbro.session.VoiceLapCounter
import com.example.gymbro.session.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow

// Swim session in progress (SwimSessionFragment): the laps swum (kept in SwimTracker), the
// statistics, and the laps counted by voice (VoiceLapCounter: microphone and speech recognizer,
// used only while the page is on the screen). voiceCounter: creates the voice counter of this
// ViewModel (it needs the context of the app).
class SwimSessionViewModel(
    voiceCounter: (CoroutineScope, VoiceLapCounter.Listener) -> VoiceLapCounter
) : ViewModel(), VoiceLapCounter.Listener {

    // lastAdded: the last laps added (null = nothing to undo); showGoalReached: the goal was just
    // reached, the window was not shown yet
    data class Laps(
        val laps: Int,
        val goalLaps: Int?,
        val poolLengthM: Double,
        val meters: Double,
        val lastAdded: Int?,
        val showGoalReached: Boolean
    )

    // Average time of 100 m and of a lap (seconds, null: nothing swum yet), and the pool
    data class Stats(val secondsPer100m: Long?, val secondsPerLap: Long?, val poolLengthM: Double)

    // What the voice counting heard
    sealed interface VoiceEvent {
        data class Number(val laps: Int, val heard: String) : VoiceEvent    // added to the laps
        data class Nothing(val heard: String?) : VoiceEvent                 // no number
        data object Problem : VoiceEvent                                    // the recognizer does not work now
    }

    // Updated at every change of the laps
    val laps: Flow<Laps> = SwimTracker.changes.map {
        Laps(
            laps = SwimTracker.laps,
            goalLaps = SwimTracker.goalLaps,
            poolLengthM = SwimTracker.poolLengthM,
            meters = SwimTracker.meters,
            lastAdded = SwimTracker.lastAdded,
            showGoalReached = SwimTracker.goalReached && !SwimTracker.goalSeen
        )
    }

    // Updated at every change of the laps, and every second (the pace changes with the time)
    val stats: Flow<Stats> = combine(SwimTracker.changes, everySecond()) { _, _ ->
        val seconds = ActiveSession.elapsedMillis() / 1000
        Stats(
            SwimMath.secondsPer100m(seconds, SwimTracker.meters),
            SwimMath.secondsPerLap(seconds, SwimTracker.laps),
            SwimTracker.poolLengthM
        )
    }

    private val _voiceState = MutableStateFlow(VoiceState.OFF)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val voice = voiceCounter(viewModelScope, this)

    // How loud the sound near the phone is (0..1): the circle of the microphone
    val voiceLevel: StateFlow<Float> = voice.level

    private val _voiceEvents = Channel<VoiceEvent>(Channel.BUFFERED)
    val voiceEvents: Flow<VoiceEvent> = _voiceEvents.receiveAsFlow()

    // The laps reached and the distance swum (for the "goal reached" window)
    val goalLaps: Int?
        get() = SwimTracker.goalLaps
    val meters: Double
        get() = SwimTracker.meters

    // Buttons +2, +4, +8, +10
    fun add(laps: Int) {
        SwimTracker.add(laps)
    }

    // Removes the last laps added (e.g. a button pressed by mistake)
    fun undo() {
        SwimTracker.undo()
    }

    // The "goal reached" window was closed (then the user chose to keep swimming)
    fun goalReachedSeen() {
        SwimTracker.goalSeen = true
    }


    // --------------------------------------------------------- VOICE COUNTING

    // The microphone is used only while the page is on the screen
    fun onPageVisible() {
        if (SwimTracker.voiceCounting) voice.start()
    }

    fun onPageHidden() {
        voice.stop()
    }

    // Turned on (the microphone permission was given) or off by the user: remembered for the next swims
    fun turnVoiceOn() {
        SwimTracker.voiceCounting = true
        voice.start()
    }

    fun turnVoiceOff() {
        SwimTracker.voiceCounting = false
        voice.stop()
    }

    // The recognizer was not available: try again
    fun retryVoice() {
        voice.start()
    }

    // Listens at once, without waiting for a word (tap on the microphone, e.g. in a loud pool)
    fun listenNow() {
        voice.listenNow()
    }

    override fun onStateChanged(state: VoiceState) {
        _voiceState.value = state
    }

    // A number said aloud: added to the laps
    override fun onNumber(laps: Int, heard: String) {
        SwimTracker.add(laps)
        _voiceEvents.trySend(VoiceEvent.Number(laps, heard))
    }

    override fun onNothing(heard: String?) {
        _voiceEvents.trySend(VoiceEvent.Nothing(heard))
    }

    override fun onProblem() {
        _voiceEvents.trySend(VoiceEvent.Problem)
    }

    override fun onCleared() {
        voice.stop()
    }

    private fun everySecond(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(1000)
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                SwimSessionViewModel { scope, listener -> VoiceLapCounter(app, scope, listener) }
            }
        }
    }
}
