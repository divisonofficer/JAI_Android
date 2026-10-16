package com.cgjnkim.mobile_jai

/** Sensor-specific dial exposure followed by fixed exposures, OFF before ON. */
internal object PhoneExposurePlan {
    data class Step(val index: Int, val slot: Int, val phoneOn: Boolean, val rgbUs: Double, val nirUs: Double, val exposuresPerLight: Int = 4) {
        val partnerIndex: Int get() = if (phoneOn) index - exposuresPerLight else index + exposuresPerLight
    }

    fun steps(rgbUs: Double, nirUs: Double, includeTwoSeconds: Boolean = true, includeOneSecond: Boolean = true): List<Step> {
        require(rgbUs.isFinite() && rgbUs > 0 && nirUs.isFinite() && nirUs > 0)
        val exposures = buildList {
            add(rgbUs to nirUs)
            add(500_000.0 to 500_000.0)
            if (includeOneSecond) add(1_000_000.0 to 1_000_000.0)
            if (includeTwoSeconds) add(2_000_000.0 to 2_000_000.0)
        }
        return listOf(false, true).flatMap { on ->
            exposures.mapIndexed { slot, (rgb, nir) -> Step((if (on) exposures.size else 0) + slot, slot, on, rgb, nir, exposures.size) }
        }
    }
}
