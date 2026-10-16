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

    // a swim of [meters] at an average pace of [secondsPer100m]
    private fun swim(meters: Int, secondsPer100m: Long) = Post(
        id = nextId, kind = PostKind.SWIM, title = "Swim", durationSeconds = secondsPer100m * meters / 100,
        distanceKm = meters / 1000.0, poolLengthM = 25.0, laps = meters / 25,
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
    fun swims_bestPaceForEveryDistance() {
        // 1.3 km at 02:00 /100 m and 1.7 km at 04:00 /100 m both count as 1.5 km: the faster pace wins
        val best = PersonalBests.from(listOf(swim(1300, 120), swim(1700, 240)))

        assertEquals(listOf(1.3), best.map { it.distanceKm })
    }

    @Test
    fun swims_paceDecides_notTheTime() {
        // 1.5 km in 30:00 (02:00 /100 m) and 1.3 km in 28:10 (02:10 /100 m): the first one
        val best = PersonalBests.from(listOf(swim(1500, 120), swim(1300, 130)))

        assertEquals(listOf(1.5), best.map { it.distanceKm })
    }

    @Test
    fun swims_groupedByMultiplesOfHalfKm_atMost200mAway() {
        val best = PersonalBests.from(listOf(
            swim(800, 150), swim(1000, 160),   // 1 km
            swim(1200, 140),                    // 1 km (200 m away)
            swim(1250, 100),                    // 250 m from 1 and from 1.5 km: no distance
            swim(1550, 130),                    // 1.5 km
            swim(150, 90),                      // less than 0.3 km: no distance
        ))

        assertEquals(listOf(1.2, 1.55), best.map { it.distanceKm })
        assertEquals(listOf(1.0, 1.5), best.map { PersonalBests.swimGroupKm(it.distanceKm!!) })
    }

    @Test
    fun swimGroups() {
        assertEquals(0.5, PersonalBests.swimGroupKm(0.3)!!, 0.0)
        assertEquals(1.5, PersonalBests.swimGroupKm(1.3)!!, 0.0)
        assertEquals(1.5, PersonalBests.swimGroupKm(1.7)!!, 0.0)
        assertEquals(2.0, PersonalBests.swimGroupKm(1.8)!!, 0.0)
        assertEquals(null, PersonalBests.swimGroupKm(1.25))
        assertEquals(null, PersonalBests.swimGroupKm(0.25))
        // 52 laps of 25 m written with the decimals of a computer: still 1.3 km
        assertEquals(1.5, PersonalBests.swimGroupKm(52 * 25 / 1000.0 + 1e-12)!!, 0.0)
    }

    @Test
    fun gymSessions_areNotPersonalBests() {
        assertEquals(emptyList<Post>(), PersonalBests.from(listOf(gym(3600), gym(1800))))
    }
}
