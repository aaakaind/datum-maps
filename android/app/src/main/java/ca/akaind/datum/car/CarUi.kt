package ca.akaind.datum.car

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.OnClickListener
import androidx.car.app.navigation.model.Maneuver as CarManeuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.core.graphics.drawable.IconCompat
import ca.akaind.datum.R
import ca.akaind.datum.geo.Geo
import ca.akaind.datum.geo.Units
import ca.akaind.datum.routing.Maneuver
import java.util.TimeZone
import kotlin.math.abs

/** Small builders shared by the car screens. */
object CarUi {
    val ACCENT: CarColor = CarColor.createCustom(0xFFC8432B.toInt(), 0xFFE2694E.toInt())

    fun icon(ctx: CarContext, @DrawableRes res: Int, tint: CarColor? = CarColor.DEFAULT): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(ctx, res)).apply { if (tint != null) setTint(tint) }.build()

    fun action(ctx: CarContext, @DrawableRes res: Int, title: String? = null, listener: OnClickListener): Action =
        Action.Builder().setIcon(icon(ctx, res)).apply { if (title != null) setTitle(title) }.setOnClickListener(listener).build()

    /** A distance in the unit and precision a driver should see. */
    fun distance(meters: Double): Distance {
        val r = Units.round(meters)
        val unit = when (r.unit) {
            Units.Unit.M -> Distance.UNIT_METERS
            Units.Unit.KM -> if (r.decimals > 0) Distance.UNIT_KILOMETERS_P1 else Distance.UNIT_KILOMETERS
            Units.Unit.FT -> Distance.UNIT_FEET
            Units.Unit.MI -> if (r.decimals > 0) Distance.UNIT_MILES_P1 else Distance.UNIT_MILES
        }
        return Distance.create(r.value, unit)
    }

    fun estimate(remainingM: Double, remainingS: Double, nowMs: Long): TravelEstimate {
        val arrive = DateTimeWithZone.create(nowMs + (remainingS * 1000).toLong(), TimeZone.getDefault())
        return TravelEstimate.Builder(distance(remainingM), arrive).setRemainingTimeSeconds(remainingS.toLong().coerceAtLeast(0)).build()
    }

    fun step(ctx: CarContext, m: Maneuver): Step {
        val b = Step.Builder(m.instruction).setManeuver(maneuver(ctx, m))
        m.streetNames.firstOrNull()?.let { b.setRoad(it) }
        return b.build()
    }

    fun maneuver(ctx: CarContext, m: Maneuver): CarManeuver {
        val (type, res) = Maneuvers.map(m)
        val b = CarManeuver.Builder(type).setIcon(icon(ctx, res, tint = null))
        if (type == CarManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW || type == CarManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW) {
            b.setRoundaboutExitNumber(m.roundaboutExit.coerceIn(1, 20))
        }
        return b.build()
    }
}

/** Valhalla maneuver types to Android Auto maneuver types and DATUM's arrow icons. */
object Maneuvers {
    fun map(m: Maneuver): Pair<Int, Int> {
        val angle = Geo.turnAngle(m.bearingBefore.toDouble(), m.bearingAfter.toDouble())
        val right = angle >= 0
        return when (m.type) {
            1, 2, 3 -> CarManeuver.TYPE_DEPART to R.drawable.ic_mnv_depart
            4 -> CarManeuver.TYPE_DESTINATION to R.drawable.ic_mnv_destination
            5 -> CarManeuver.TYPE_DESTINATION_RIGHT to R.drawable.ic_mnv_destination
            6 -> CarManeuver.TYPE_DESTINATION_LEFT to R.drawable.ic_mnv_destination
            7 -> CarManeuver.TYPE_NAME_CHANGE to R.drawable.ic_mnv_straight
            8, 17, 22 -> CarManeuver.TYPE_STRAIGHT to R.drawable.ic_mnv_straight
            9 -> CarManeuver.TYPE_TURN_SLIGHT_RIGHT to R.drawable.ic_mnv_slight_right
            10 -> CarManeuver.TYPE_TURN_NORMAL_RIGHT to R.drawable.ic_mnv_turn_right
            11 -> CarManeuver.TYPE_TURN_SHARP_RIGHT to R.drawable.ic_mnv_sharp_right
            12 -> CarManeuver.TYPE_U_TURN_RIGHT to R.drawable.ic_mnv_uturn_right
            13 -> CarManeuver.TYPE_U_TURN_LEFT to R.drawable.ic_mnv_uturn_left
            14 -> CarManeuver.TYPE_TURN_SHARP_LEFT to R.drawable.ic_mnv_sharp_left
            15 -> CarManeuver.TYPE_TURN_NORMAL_LEFT to R.drawable.ic_mnv_turn_left
            16 -> CarManeuver.TYPE_TURN_SLIGHT_LEFT to R.drawable.ic_mnv_slight_left
            // Ramps: Valhalla says which side; the bearings say how sharp.
            18 -> ramp(true, abs(angle), on = true)
            19 -> ramp(false, abs(angle), on = true)
            20 -> ramp(true, abs(angle), on = false)
            21 -> ramp(false, abs(angle), on = false)
            23 -> CarManeuver.TYPE_KEEP_RIGHT to R.drawable.ic_mnv_keep_right
            24 -> CarManeuver.TYPE_KEEP_LEFT to R.drawable.ic_mnv_keep_left
            25 -> CarManeuver.TYPE_MERGE_SIDE_UNSPECIFIED to R.drawable.ic_mnv_merge
            37 -> CarManeuver.TYPE_MERGE_RIGHT to R.drawable.ic_mnv_merge
            38 -> CarManeuver.TYPE_MERGE_LEFT to R.drawable.ic_mnv_merge
            26 -> CarManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW to R.drawable.ic_mnv_roundabout
            27 -> CarManeuver.TYPE_ROUNDABOUT_EXIT_CCW to R.drawable.ic_mnv_roundabout
            28 -> CarManeuver.TYPE_FERRY_BOAT to R.drawable.ic_mnv_ferry
            29 -> CarManeuver.TYPE_STRAIGHT to R.drawable.ic_mnv_straight
            else -> (if (abs(angle) < 20) CarManeuver.TYPE_STRAIGHT else if (right) CarManeuver.TYPE_TURN_NORMAL_RIGHT else CarManeuver.TYPE_TURN_NORMAL_LEFT) to
                (if (abs(angle) < 20) R.drawable.ic_mnv_straight else if (right) R.drawable.ic_mnv_turn_right else R.drawable.ic_mnv_turn_left)
        }
    }

    private fun ramp(right: Boolean, angle: Double, on: Boolean): Pair<Int, Int> {
        val sharpness = when {
            angle < 60 -> 0
            angle < 120 -> 1
            else -> 2
        }
        val type = when {
            on && right -> intArrayOf(CarManeuver.TYPE_ON_RAMP_SLIGHT_RIGHT, CarManeuver.TYPE_ON_RAMP_NORMAL_RIGHT, CarManeuver.TYPE_ON_RAMP_SHARP_RIGHT)
            on -> intArrayOf(CarManeuver.TYPE_ON_RAMP_SLIGHT_LEFT, CarManeuver.TYPE_ON_RAMP_NORMAL_LEFT, CarManeuver.TYPE_ON_RAMP_SHARP_LEFT)
            right -> intArrayOf(CarManeuver.TYPE_OFF_RAMP_SLIGHT_RIGHT, CarManeuver.TYPE_OFF_RAMP_NORMAL_RIGHT, CarManeuver.TYPE_OFF_RAMP_NORMAL_RIGHT)
            else -> intArrayOf(CarManeuver.TYPE_OFF_RAMP_SLIGHT_LEFT, CarManeuver.TYPE_OFF_RAMP_NORMAL_LEFT, CarManeuver.TYPE_OFF_RAMP_NORMAL_LEFT)
        }[sharpness]
        val icon = if (right) intArrayOf(R.drawable.ic_mnv_slight_right, R.drawable.ic_mnv_turn_right, R.drawable.ic_mnv_sharp_right)[sharpness]
        else intArrayOf(R.drawable.ic_mnv_slight_left, R.drawable.ic_mnv_turn_left, R.drawable.ic_mnv_sharp_left)[sharpness]
        return type to icon
    }
}
