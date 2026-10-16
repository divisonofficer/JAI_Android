package com.cgjnkim.mobile_jai

import org.junit.Assert.*
import org.junit.Test

class PhoneExposurePlanTest {
    @Test fun fourShotModeHasOneReferencePerLightAndCorrectPartners() {
        val steps = PhoneExposurePlan.steps(5000.0, 100000.0, includeTwoSeconds = false, includeOneSecond = false)
        assertEquals(listOf(false, false, true, true), steps.map { it.phoneOn })
        assertEquals(listOf(5000.0, 500000.0, 5000.0, 500000.0), steps.map { it.rgbUs })
        assertEquals(listOf(100000.0, 500000.0, 100000.0, 500000.0), steps.map { it.nirUs })
        assertEquals(listOf(0, 1, 0, 1), steps.map { it.slot })
        assertEquals(listOf(2, 3, 0, 1), steps.map { it.partnerIndex })
    }

    @Test fun sixShotModePairsEachExposureWithoutTwoSeconds() {
        val steps = PhoneExposurePlan.steps(625.64, 100000.0, includeTwoSeconds = false)
        assertEquals(6, steps.size)
        assertEquals(listOf(false, false, false, true, true, true), steps.map { it.phoneOn })
        assertEquals(listOf(625.64, 500000.0, 1000000.0, 625.64, 500000.0, 1000000.0), steps.map { it.rgbUs })
        assertEquals(listOf(3, 4, 5, 0, 1, 2), steps.map { it.partnerIndex })
        for (step in steps) {
            val partner = steps[step.partnerIndex]
            assertEquals(step.rgbUs, partner.rgbUs, 0.0)
            assertEquals(step.nirUs, partner.nirUs, 0.0)
            assertEquals(step.index, partner.partnerIndex)
        }
    }
    @Test fun preservesSeparateDialExposuresAndPairsEverySetting() {
        val steps = PhoneExposurePlan.steps(6201.0, 100000.0)
        assertEquals(8, steps.size)
        assertEquals(listOf(false, false, false, false, true, true, true, true), steps.map { it.phoneOn })
        assertEquals(listOf(6201.0, 500000.0, 1000000.0, 2000000.0), steps.take(4).map { it.rgbUs })
        assertEquals(listOf(100000.0, 500000.0, 1000000.0, 2000000.0), steps.take(4).map { it.nirUs })
        for (step in steps) {
            val partner = steps[step.partnerIndex]
            assertEquals(step.rgbUs, partner.rgbUs, 0.0)
            assertEquals(step.nirUs, partner.nirUs, 0.0)
            assertEquals(step.slot, partner.slot)
            assertNotEquals(step.phoneOn, partner.phoneOn)
            assertEquals(step.index, partner.partnerIndex)
        }
    }

    @Test fun keepsDialShotEvenWhenItMatchesAFixedExposure() {
        val steps = PhoneExposurePlan.steps(500000.0, 500000.0)
        assertEquals(8, steps.size)
        assertEquals(listOf(0, 1, 2, 3), steps.take(4).map { it.slot })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidDialExposure() { PhoneExposurePlan.steps(Double.NaN, 100000.0) }
}
