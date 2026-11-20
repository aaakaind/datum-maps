package ca.akaind.datum.car.map

import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

/**
 * Connects the car host's drawing surface and touch input to [SurfaceMap].
 * One per car session.
 */
class CarMapRenderer(private val carContext: CarContext, private val map: SurfaceMap = SurfaceMap(carContext)) :
    SurfaceCallback, CarMap by map {

    fun start() {
        map.setDarkMode(carContext.isDarkMode)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        map.attach(surface, container.width, container.height, container.dpi)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) = map.setVisibleArea(visibleArea)

    override fun onStableAreaChanged(stableArea: Rect) = Unit

    override fun onSurfaceDestroyed(container: SurfaceContainer) = map.detach()

    override fun onScroll(distanceX: Float, distanceY: Float) = map.scroll(distanceX, distanceY)

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) = map.scale(focusX, focusY, scaleFactor)

    fun stop() = map.detach()
}
