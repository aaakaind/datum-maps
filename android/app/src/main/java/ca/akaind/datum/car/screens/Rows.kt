package ca.akaind.datum.car.screens

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place as CarPlace
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import ca.akaind.datum.car.CarUi
import ca.akaind.datum.geo.Geo
import ca.akaind.datum.geo.LatLon
import ca.akaind.datum.search.Place

/** A result row: name, then distance and address, with a numbered map marker. */
internal fun placeRow(ctx: CarContext, p: Place, index: Int, from: LatLon?, onClick: () -> Unit): Row {
    val b = Row.Builder().setTitle(p.name).setOnClickListener(onClick).setBrowsable(false)
    val detail = p.address.ifBlank { null }
    if (from != null) {
        val text = SpannableString("  " + (detail?.let { "· $it" } ?: ""))
        text.setSpan(DistanceSpan.create(CarUi.distance(Geo.distance(from, p.latLon))), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        b.addText(text)
    } else if (detail != null) {
        b.addText(detail)
    }
    val marker = PlaceMarker.Builder().setLabel("${index + 1}").setColor(CarColor.createCustom(0xFFC8432B.toInt(), 0xFFE2694E.toInt())).build()
    b.setMetadata(Metadata.Builder().setPlace(CarPlace.Builder(CarLocation.create(p.lat, p.lon)).setMarker(marker).build()).build())
    return b.build()
}
