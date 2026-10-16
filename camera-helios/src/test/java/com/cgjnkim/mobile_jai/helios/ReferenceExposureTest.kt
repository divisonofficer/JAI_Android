package com.cgjnkim.mobile_jai.helios

import org.junit.Test
import org.junit.Assert.assertTrue

class ReferenceExposureTest {
    @Test fun acceptsObservedRawTimingStep() {
        TritonCamera.requireExposure(613.456, 625.640)
    }
    @Test fun allowsTransferAndFreshAcquisitionAtShortAndLongExposures() {
        assertTrue(TritonCamera.frameTimeoutMs(625.640, 5.0) >= 8_000)
        assertTrue(TritonCamera.frameTimeoutMs(4_000_000.0, 0.249) >= 17_000)
    }
    @Test fun acceptsSensorQuantization() {
        TritonCamera.requireExposure(500_000.0, 499_999.64)
        TritonCamera.requireExposure(1_000_000.0, 1_000_000.0)
        TritonCamera.requireExposure(4_000_000.0, 4_000_000.0)
    }
    @Test(expected = IllegalStateException::class) fun rejectsClampedLongExposure() {
        TritonCamera.requireExposure(4_000_000.0, 1_000_000.0)
    }
    @Test(expected = IllegalStateException::class) fun rejectsInvalidReadback() {
        TritonCamera.requireExposure(500_000.0, Double.NaN)
    }
}
