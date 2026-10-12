package com.example.gymbro

import com.example.gymbro.network.Better
import com.example.gymbro.network.Metric
import com.example.gymbro.network.Post
import com.example.gymbro.network.PostKind
import com.example.gymbro.profile.PersonalBests
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

// Run with: right click on this file > Run 'PersonalBestsTest'
class PersonalBestsTest {

    private var nextId = 1L

    // posts published one after the other: every new post is more recent
    private fun run(km: Double, seconds: Long) = Post(
        id = nextId, kind = PostKind.RUN, title = "Run", durationSeconds = seconds, distanceKm = km,
        createdAt = Instant.EPOCH.plusSeconds(nextId++)
    )

    private fun manual(title: String, metric: Metric, better: Better, amount: Double) = Post(
        id = nextId, kind = PostKind.MANUAL, title = title, metric = metric, better = better, amount = amount,
        createdAt = Instant.EPOCH.plusSeconds(nextId++)
    )

    private fun gym(seconds: Long) = Post(
        id = nextId, kind = PostKind.GYM, title = "Gym session", durationSeconds = seconds,
        createdAt = Instant.EPOCH.plusSeconds(nextId++)
    )

    @Test
    fun runs_bestTimeForEveryDistance() {
        // 10 km in 3 hours, 5 km in 1 hour, 10 km in 2 hours -> 5 km in 1 hour and 10 km in 2 hours
        val best = PersonalBests.from(listOf(run(10.0, 3 * 3600), run(5.0, 3600), run(10.0, 2 * 3600)))

        assertEquals(listOf(5.0 to 3600L, 10.0 to 7200L), best.map { it.distanceKm to it.durationSeconds })
    }

    @Test
    fun runs_groupedByMultiplesOfHalfKm() {
        // 10 and 10.2 km count as 10 km; 10.3 and 10.7 km as 10.5 km; 10.8 km as 11 km
        val best = PersonalBests.from(listOf(
            run(10.0, 3000), run(10.2, 2900),
            run(10.3, 3100), run(10.7, 3050),
            run(10.8, 3200),
        ))

        assertEquals(
            listOf(10.2 to 2900L, 10.7 to 3050L, 10.8 to 3200L),
            best.map { it.distanceKm to it.durationSeconds }
        )
        assertEquals(listOf(10.0, 10.5, 11.0), best.map { PersonalBests.distanceGroupKm(it.distanceKm!!) })
    }

    @Test
    fun runs_shorterThanAQuarterKm_areNotPersonalBests() {
        assertEquals(emptyList<Post>(), PersonalBests.from(listOf(run(0.2, 60))))
    }

    @Test
    fun manual_higherIsBetter_highestResult() {
        val best = PersonalBests.from(listOf(
            manual("Deadlift", Metric.WEIGHT, Better.HIGHER, 140.0),
            manual("Deadlift", Metric.WEIGHT, Better.HIGHER, 150.0),
            manual("Deadlift", Metric.WEIGHT, Better.HIGHER, 145.0),
        ))

        assertEquals(listOf(150.0), best.map { it.amount })
    }

    @Test
    fun manual_lowerIsBetter_lowestResult() {
        val best = PersonalBests.from(listOf(
            manual("100 m Sprint", Metric.TIME, Better.LOWER, 14.0),
            manual("100 m Sprint", Metric.TIME, Better.LOWER, 12.5),
            manual("100 m Sprint", Metric.TIME, Better.LOWER, 13.0),
        ))

        assertEquals(listOf(12.5), best.map { it.amount })
    }

    @Test
    fun manual_oneBestForEveryExercise_sameNameIgnoringCase() {
        val best = PersonalBests.from(listOf(
            manual("Squat", Metric.WEIGHT, Better.HIGHER, 100.0),
            manual("squat ", Metric.WEIGHT, Better.HIGHER, 120.0),
            manual("Front Lever", Metric.TIME, Better.HIGHER, 10.0),
        ))

        assertEquals(listOf("Front Lever" to 10.0, "squat " to 120.0), best.map { it.title to it.amount })
    }

    @Test
    fun manual_sameExerciseMeasuredDifferently_separateBests() {
        val best = PersonalBests.from(listOf(
            manual("Pull-up", Metric.WEIGHT, Better.HIGHER, 20.0),
            manual("Pull-up", Metric.TIME, Better.HIGHER, 30.0),
        ))

        assertEquals(2, best.size)
    }

    @Test
    fun manual_theMostRecentChoiceOfBetterDecides() {
        // first evaluated "higher is better", then changed to "lower is better"
        val best = PersonalBests.from(listOf(
            manual("Plank", Metric.TIME, Better.HIGHER, 120.0),
            manual("Plank", Metric.TIME, Better.LOWER, 60.0),
        ))

        assertEquals(listOf(60.0), best.map { it.amount })
    }

    @Test
    fun gymSessions_areNotPersonalBests() {
        assertEquals(emptyList<Post>(), PersonalBests.from(listOf(gym(3600), gym(1800))))
    }
}
