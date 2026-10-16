package fr.hybridcoach

import fr.hybridcoach.domain.*
import org.junit.Assert.*
import org.junit.Test

class SportMathTest {
    @Test
    fun speedAndPaceRoundTrip() {
        assertEquals(200.0, SportMath.paceFromSpeed(18.0), .0001)
        assertEquals(18.0, SportMath.speedFromPace(200.0), .0001)
        assertEquals("3:20/km", SportMath.paceLabel(200.0))
    }

    @Test
    fun raceTarget() {
        assertEquals(208.8, 2088.0 / 10, .0001)
        assertEquals("3:29/km", SportMath.paceLabel(208.8))
    }

    @Test
    fun vmaInterval() {
        val p = AthleteProfile(vma = "20")
        val s =
            SportMath.snapshot(
                RunningStep(
                    durationKind = DurationKind.DISTANCE,
                    amount = 400.0,
                    targetKind = TargetKind.VMA,
                    target = 105.0,
                ),
                p,
            )
        assertEquals(171.42857, s.resolvedPace!!, .001)
        assertEquals(68.5714, SportMath.targetSeconds(400.0, s.resolvedPace!!), .001)
    }

    @Test
    fun nestedRepeatsCountTimeAndDistance() {
        val work =
            RunningStep(
                durationKind = DurationKind.DISTANCE,
                amount = 400.0,
                targetKind = TargetKind.PACE,
                target = 180.0,
                resolvedPace = 180.0,
            )
        val rest = RunningStep(kind = StepKind.RECOVERY, amount = 60.0)
        val block =
            RunningStep(
                kind = StepKind.REPEAT,
                repetitions = 3,
                children =
                    listOf(
                        RunningStep(
                            kind = StepKind.REPEAT,
                            repetitions = 4,
                            children = listOf(work, rest),
                        ),
                        rest.copy(amount = 180.0),
                    ),
            )
        validatePlan(WorkoutPlan(running = listOf(block)))
        assertEquals(4800.0, SportMath.plannedMeters(listOf(block)), .0001)
        assertEquals(2124.0, SportMath.plannedSeconds(listOf(block)), .0001)
        assertEquals(27, SportMath.flatten(listOf(block)).size)
    }

    @Test
    fun snapshotsDoNotDependOnUpdatedProfile() {
        val step =
            SportMath.snapshot(
                RunningStep(targetKind = TargetKind.REFERENCE, reference = "10 km"),
                AthleteProfile(pace10 = "210"),
            )
        val stored = codec.encodeToString(RunningStep.serializer(), step)
        assertEquals(210.0, codec.decodeFromString<RunningStep>(stored).resolvedPace!!, 0.0)
    }

    @Test
    fun epleyOnlyValidForRelevantRepRanges() {
        assertEquals(100.0, SportMath.estimated1RM(100.0, 1)!!, 0.0)
        assertEquals(120.0, SportMath.estimated1RM(90.0, 10)!!, .0001)
        assertNull(SportMath.estimated1RM(50.0, 20))
        assertNull(SportMath.estimated1RM(0.0, 5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroSpeedRejected() {
        SportMath.paceFromSpeed(0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteSpeedRejected() {
        SportMath.paceFromSpeed(Double.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingVmaRejected() {
        validatePlan(
            WorkoutPlan(running = listOf(RunningStep(targetKind = TargetKind.VMA, target = 105.0)))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun explosiveRepeatRejected() {
        var s = RunningStep()
        repeat(4) {
            s = RunningStep(kind = StepKind.REPEAT, repetitions = 100, children = listOf(s))
        }
        validatePlan(WorkoutPlan(running = listOf(s)))
    }

    @Test
    fun decimalCommaAccepted() {
        assertEquals(12.5, "12,5".decimal()!!, 0.0)
        assertNull("NaN".decimal())
        assertNull("Infinity".decimal())
    }

    @Test
    fun durationFormatting() {
        assertEquals("1:30:05", SportMath.durationLabel(5405))
        assertEquals("2:05", SportMath.durationLabel(125))
        assertEquals("4:00/km", SportMath.paceLabel(239.6))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidResultRejected() {
        validateResult(WorkoutResult(rpe = "12"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun checkedSetNeedsReps() {
        validateResult(WorkoutResult(sets = listOf(SetLog("x", 0, checked = true))))
    }

    @Test
    fun userZonesAreResolvedAndFrozen() {
        val profile = AthleteProfile(hrZones = "1:120-140\n2:140-160", paceZones = "1:300-360")
        val hr =
            SportMath.snapshot(RunningStep(targetKind = TargetKind.HR_ZONE, target = 2.0), profile)
        val pace =
            SportMath.snapshot(
                RunningStep(targetKind = TargetKind.PACE_ZONE, target = 1.0),
                profile,
            )
        validatePlan(WorkoutPlan(running = listOf(hr, pace)))
        assertEquals(140.0, hr.resolvedLower!!, 0.0)
        assertEquals(160.0, hr.resolvedUpper!!, 0.0)
        assertEquals(330.0, pace.resolvedPace!!, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidZonesRejected() {
        parseZones("1:160-120")
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingZoneRejected() {
        validatePlan(
            WorkoutPlan(
                running =
                    listOf(
                        SportMath.snapshot(
                            RunningStep(targetKind = TargetKind.HR_ZONE, target = 2.0),
                            AthleteProfile(),
                        )
                    )
            )
        )
    }

    @Test
    fun rangeUsesMidpointForPlannedEstimates() {
        val step =
            SportMath.snapshot(
                RunningStep(targetKind = TargetKind.PACE_RANGE, target = 200.0, targetMax = 220.0),
                AthleteProfile(),
            )
        assertEquals(210.0, step.resolvedPace!!, 0.0)
    }
}
