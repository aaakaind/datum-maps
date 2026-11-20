@file:Suppress("DEPRECATION")

package ca.akaind.datum.car

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.PanModeListener
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.versioning.CarAppApiLevels
import ca.akaind.datum.R

/**
 * Map screens built from the newest templates the car's host supports: the map-
 * with-content template on car API 7 and up (current Android Auto), and the older
 * place-list and route-preview templates on hosts that predate it.
 */
object Templates {
    private fun api(ctx: CarContext) = ctx.carAppApiLevel

    fun mapStrip(ctx: CarContext, onRecenter: () -> Unit, onZoom: (Double) -> Unit): ActionStrip =
        ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(CarUi.action(ctx, R.drawable.ic_car_recenter) { onRecenter() })
            .addAction(CarUi.action(ctx, R.drawable.ic_car_zoom_in) { onZoom(1.0) })
            .addAction(CarUi.action(ctx, R.drawable.ic_car_zoom_out) { onZoom(-1.0) })
            .build()

    private fun header(title: String) = Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build()

    fun placeList(ctx: CarContext, title: String, items: ItemList?, mapStrip: ActionStrip, pan: PanModeListener): Template {
        if (api(ctx) >= CarAppApiLevels.LEVEL_7) {
            val list = ListTemplate.Builder().setHeader(header(title))
                .apply { if (items == null) setLoading(true) else setSingleList(items) }.build()
            return MapWithContentTemplate.Builder()
                .setContentTemplate(list)
                .setMapController(MapController.Builder().setMapActionStrip(mapStrip).setPanModeListener(pan).build())
                .build()
        }
        val b = PlaceListNavigationTemplate.Builder()
        if (api(ctx) >= CarAppApiLevels.LEVEL_5) b.setHeader(header(title)) else b.setTitle(title).setHeaderAction(Action.BACK)
        if (items == null) b.setLoading(true) else b.setItemList(items)
        if (api(ctx) >= CarAppApiLevels.LEVEL_4) b.setMapActionStrip(mapStrip).setPanModeListener(pan)
        return b.build()
    }

    fun routePreview(ctx: CarContext, title: String, routes: ItemList?, onGo: () -> Unit, mapStrip: ActionStrip, pan: PanModeListener): Template {
        if (api(ctx) >= CarAppApiLevels.LEVEL_7) {
            // The list's action is a floating button: an icon, no text.
            val fab = Action.Builder().setIcon(CarUi.icon(ctx, R.drawable.ic_car_go, tint = CarColor.createCustom(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())))
                .setBackgroundColor(CarUi.ACCENT).setOnClickListener { onGo() }.build()
            val list = ListTemplate.Builder().setHeader(header(title))
                .apply { if (routes == null) setLoading(true) else setSingleList(routes).addAction(fab) }.build()
            return MapWithContentTemplate.Builder()
                .setContentTemplate(list)
                .setMapController(MapController.Builder().setMapActionStrip(mapStrip).setPanModeListener(pan).build())
                .build()
        }
        val b = RoutePreviewNavigationTemplate.Builder()
        if (api(ctx) >= CarAppApiLevels.LEVEL_5) b.setHeader(header(title)) else b.setTitle(title).setHeaderAction(Action.BACK)
        val go = Action.Builder().setTitle(ctx.getString(R.string.car_go)).setBackgroundColor(CarUi.ACCENT).setOnClickListener { onGo() }.build()
        if (routes == null) b.setLoading(true) else b.setItemList(routes).setNavigateAction(go)
        if (api(ctx) >= CarAppApiLevels.LEVEL_4) b.setMapActionStrip(mapStrip).setPanModeListener(pan)
        return b.build()
    }

    fun message(ctx: CarContext, title: String, text: String, vararg actions: Action, back: Boolean = true): Template {
        val b = MessageTemplate.Builder(text)
        val start = if (back) Action.BACK else Action.APP_ICON
        if (api(ctx) >= CarAppApiLevels.LEVEL_7) b.setHeader(Header.Builder().setTitle(title).setStartHeaderAction(start).build())
        else b.setTitle(title).setHeaderAction(start)
        actions.forEach { b.addAction(it) }
        return b.build()
    }
}
