package ca.akaind.datum.geo

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Distances and durations for people: metric, or miles and feet where those are the norm. */
object Units {
    var imperial: Boolean = Locale.getDefault().country in setOf("US", "LR", "MM")

    /** A distance rounded the way a driver reads it, as a value and a unit. */
    data class Rounded(val value: Double, val unit: Unit, val decimals: Int)
    enum class Unit(val label: String) { M("m"), KM("km"), FT("ft"), MI("mi") }

    fun round(meters: Double): Rounded {
        if (imperial) {
            val feet = meters * 3.28084
            val miles = meters / 1609.344
            return when {
                feet < 1000 -> Rounded(max(50.0, (feet / 50).roundToLong() * 50.0), Unit.FT, 0)
                miles < 10 -> Rounded((miles * 10).roundToLong() / 10.0, Unit.MI, 1)
                else -> Rounded(miles.roundToLong().toDouble(), Unit.MI, 0)
            }
        }
        return when {
            meters < 100 -> Rounded(max(10.0, (meters / 10).roundToLong() * 10.0), Unit.M, 0)
            meters < 950 -> Rounded((meters / 50).roundToLong() * 50.0, Unit.M, 0)
            meters < 9950 -> Rounded((meters / 100).roundToLong() / 10.0, Unit.KM, 1)
            else -> Rounded((meters / 1000).roundToLong().toDouble(), Unit.KM, 0)
        }
    }

    fun distance(meters: Double): String {
        val r = round(meters)
        val v = if (r.decimals == 0) "%,d".format(r.value.roundToLong()) else "%.1f".format(r.value)
        return "$v ${r.unit.label}"
    }

    fun duration(seconds: Double): String {
        val m = max(1, (seconds / 60).roundToInt())
        if (m < 60) return "$m min"
        val h = m / 60
        return if (h < 48) "$h h %02d min".format(m % 60) else "${(h / 24.0).roundToInt()} days"
    }
}
