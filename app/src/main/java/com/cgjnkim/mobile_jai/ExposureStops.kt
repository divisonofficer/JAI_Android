package com.cgjnkim.mobile_jai

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The detents the shutter and gain dials click through.
 *
 * Third-stops, not whole stops, and generated rather than listed -- the same lattice This
 * Polar Camera clicks through (`calculate_exposure_ev_steps`). A whole stop is a coarse
 * thing to be stuck with when the scene is a stop and a half out: it forces a choice
 * between visibly under and visibly over, and on a polarimeter the cost of that choice is
 * asymmetric, since clipping destroys S1 and S2 outright while underexposure only adds
 * noise to them.
 *
 * The lattice is anchored at one second and runs in powers of two thirds of a stop, so
 * every value is `1_000_000 * 2^(n/3)` microseconds for some whole n. That anchoring is
 * what makes the two implementations land on identical numbers rather than merely similar
 * ones, which matters when a capture taken on one is compared against a capture taken on
 * the other.
 */
object ExposureStops {

    /** Third-stops. */
    const val EV_STEPS_PER_STOP = 3

    /**
     * The lattice is anchored here, at one second, and every detent is a whole number of
     * thirds of a stop away from it.
     */
    const val LATTICE_ORIGIN_US = 1_000_000.0

    /**
     * Longest exposure the dial offers, whatever the sensor allows.
     *
     * Four seconds, matching This Polar Camera's `MAX_EXPOSURE_US`. The Blackfly will
     * integrate for thirty, but past a few seconds this stops being a camera setting and
     * becomes an experiment that wants a tripod and a cable release.
     */
    const val MAX_EXPOSURE_US = 4_000_000L

    /**
     * Shutter speeds, fastest first, clamped to what the camera reports it can do.
     *
     * The returned values are exact lattice points, because that is what gets written to
     * the camera and compared against the reference implementation. The *labels* are the
     * conventional photographic names for those points -- see [niceDenominator] -- which
     * are within a percent of them and are what anyone reading a dial expects to see.
     */
    fun shutter(rangeUs: LongRange): List<ValueDial.Stop> {
        if (rangeUs.isEmpty()) return emptyList()
        val lowest = indexAtOrAbove(rangeUs.first)
        val highest = indexAtOrBelow(rangeUs.last)
        if (highest < lowest) return emptyList()
        // Ascending in microseconds, which is fastest shutter first -- the order the
        // dial has always read in, and the order a camera's own dial reads in.
        return (lowest..highest)
            .map { index -> latticeUs(index) }
            .filter { it in rangeUs }
            .map { ValueDial.Stop(label = labelShutter(it), value = it) }
    }

    /**
     * The lattice point at [index] thirds of a stop from [LATTICE_ORIGIN_US].
     *
     * Rounded to whole microseconds, which is the unit the camera's exposure register
     * takes. Near the short end that rounding is a large fraction of a third of a stop --
     * at 20 microseconds a third-stop is five of them -- so the shortest few detents are
     * spaced by what the register can hold rather than by the lattice exactly. Nothing to
     * be done about it, and nothing lost: the value written is still the value recorded.
     */
    fun latticeUs(index: Int): Long =
        (LATTICE_ORIGIN_US * 2.0.pow(index.toDouble() / EV_STEPS_PER_STOP)).roundToLong()

    /** Lowest index whose lattice point is at or above [us]. */
    private fun indexAtOrAbove(us: Long): Int {
        var index = (EV_STEPS_PER_STOP * log2(us.coerceAtLeast(1L) / LATTICE_ORIGIN_US)).toInt() - 1
        while (latticeUs(index) < us) index++
        return index
    }

    /** Highest index whose lattice point is at or below [us]. */
    private fun indexAtOrBelow(us: Long): Int {
        var index = (EV_STEPS_PER_STOP * log2(us.coerceAtLeast(1L) / LATTICE_ORIGIN_US)).toInt() + 1
        while (latticeUs(index) > us) index--
        return index
    }

    /**
     * Gain, in third-stop steps like the shutter, as **milli-decibels**.
     *
     * The unit is the awkward part and it is deliberate: [ValueDial.Stop] carries a
     * `Long`, a third of a stop is 2.007 dB, and rounding that to whole decibels would
     * make three clicks add up to 6 dB on the dial and 6.02 dB on the sensor -- a drift
     * that shows up as a mismatch between what a capture says its gain was and what it
     * actually was. Milli-decibels keep the dial and the register talking about the same
     * number.
     *
     * Gain is not free on this camera: S1 and S2 are differences between analyser
     * readings of similar size, so read noise that gain amplifies lands directly on that
     * difference and eats into DoLP. Trading shutter for gain buys motion resistance with
     * polarization accuracy, which is why both dials sit side by side.
     */
    fun gain(rangeDb: ClosedFloatingPointRange<Double>): List<ValueDial.Stop> {
        if (rangeDb.isEmpty()) return emptyList()
        val step = GAIN_DB_PER_EV / EV_STEPS_PER_STOP
        val first = kotlin.math.ceil(rangeDb.start / step).toInt()
        val last = floor(rangeDb.endInclusive / step).toInt()
        if (last < first) return emptyList()
        return (first..last).map { index ->
            val db = index * step
            ValueDial.Stop(label = formatGain(db), value = (db * 1000.0).roundToLong())
        }
    }

    /**
     * Exposure compensation, in third-stop steps, as **milli-EV**.
     *
     * What it means: the auto exposure aims at a mid-grey, and this moves where that is.
     * Plus one stop tells the meter to settle a stop brighter than it otherwise would --
     * which is how you photograph a white wall without the meter turning it grey, or a
     * specular scene without it blowing the highlight it is averaging into.
     *
     * The same third-stop spacing as the shutter, and the same unit trick as [gain]: a
     * third of a stop is 0.3333 EV and [ValueDial.Stop] carries a `Long`.
     *
     * Bounded at [MAX_COMPENSATION_EV] where the reference implementation is unbounded --
     * it only snaps to the third-EV lattice and lets any value through. A dial has to end
     * somewhere, and five stops either way is wider than any camera's own dial for a
     * reason: this is the only exposure control AUTO offers, so the range it does not
     * cover is a scene the shutter cannot be talked into.
     */
    fun compensation(): List<ValueDial.Stop> {
        val steps = (MAX_COMPENSATION_EV * EV_STEPS_PER_STOP).toInt()
        return (-steps..steps).map { index ->
            val ev = index.toDouble() / EV_STEPS_PER_STOP
            ValueDial.Stop(label = formatCompensation(ev), value = (ev * 1000.0).roundToLong())
        }
    }

    /** Five stops either way. */
    const val MAX_COMPENSATION_EV = 5

    /** "+1.3" / "0" / "-2", the way a camera prints exposure compensation. */
    fun formatCompensationMilliEv(milliEv: Long): String = formatCompensation(milliEv / 1000.0)

    private fun formatCompensation(ev: Double): String = when {
        abs(ev) < 0.05 -> "0"
        abs(ev - ev.roundToLong()) < 0.05 -> "%+d".format(ev.roundToLong())
        else -> "%+.1f".format(ev)
    }

    /** "50.0 ms" / "1.0 s" style reading for the value shown above the dial. */
    fun formatShutter(us: Long): String = when {
        us >= 1_000_000 -> "%.1f s".format(us / 1_000_000.0)
        us >= 1_000 -> "%.1f ms".format(us / 1000.0)
        else -> "$us µs"
    }

    /** "12.0" / "2.0", in decibels, from milli-decibels. */
    fun formatGainMilliDb(milliDb: Long): String = formatGain(milliDb / 1000.0)

    private fun formatGain(db: Double): String =
        if (abs(db - db.roundToLong()) < 0.05) "${db.roundToLong()}" else "%.1f".format(db)

    /**
     * The name a camera would print for an exposure of [us] microseconds.
     *
     * A third-stop lattice point is not a round number -- a third of a stop below a
     * thousandth is 1/1260 -- so the conventional series rounds the mantissa to one of
     * ten familiar values. This reproduces that, which is why the dial reads 1/8000 for a
     * value that is exactly 122 microseconds rather than 125.
     */
    private fun labelShutter(us: Long): String {
        if (us >= 1_000_000) {
            val seconds = us / 1_000_000.0
            return if (abs(seconds - seconds.roundToLong()) < 0.05) {
                "${seconds.roundToLong()}\""
            } else {
                "%.1f\"".format(seconds)
            }
        }
        val denominator = niceDenominator(1_000_000.0 / us)
        return if (abs(denominator - denominator.roundToLong()) < 0.05) {
            "1/${denominator.roundToLong()}"
        } else {
            "1/%.1f".format(denominator)
        }
    }

    /**
     * Rounds to the conventional photographic series: 1, 1.25, 1.6, 2, 2.5, 3.2, 4, 5,
     * 6.4, 8 and their decades. These are the numbers printed on every shutter dial --
     * note 1.25 and 6.4 rather than the Renard 1.2 and 6.3, because that is what cameras
     * actually engrave (1/1250, 1/6400).
     */
    private fun niceDenominator(value: Double): Double {
        if (value <= 0.0 || !value.isFinite()) return value
        val decade = 10.0.pow(floor(log10(value)))
        val mantissa = value / decade
        // Nearest in ratio rather than in difference: the series is geometric, so 1.2 is
        // as far from 1.0 as 8.0 is from 6.4.
        val nearest = NICE_MANTISSAS.minByOrNull { abs(ln(it / mantissa)) } ?: mantissa
        return nearest * decade
    }

    private val NICE_MANTISSAS = doubleArrayOf(1.0, 1.25, 1.6, 2.0, 2.5, 3.2, 4.0, 5.0, 6.4, 8.0, 10.0)

    /** 20*log10(2): the decibels that double the signal. */
    const val GAIN_DB_PER_EV = 6.020599913279624
}
