package ca.akaind.datum.car

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.HandshakeInfo
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.OnDoneCallback
import androidx.car.app.model.CarText
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.car.app.testing.TestScreenManager
import androidx.car.app.testing.navigation.TestNavigationManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import ca.akaind.datum.Datum
import ca.akaind.datum.FakeRouting
import ca.akaind.datum.FakeSearch
import ca.akaind.datum.TestSession
import ca.akaind.datum.car.screens.CategoryScreen
import ca.akaind.datum.car.screens.CATEGORIES
import ca.akaind.datum.car.screens.HomeScreen
import ca.akaind.datum.car.screens.NavigationScreen
import ca.akaind.datum.car.screens.PermissionScreen
import ca.akaind.datum.car.screens.RoutePreviewScreen
import ca.akaind.datum.car.screens.SearchScreen
import ca.akaind.datum.kanataRoutes
import ca.akaind.datum.location
import ca.akaind.datum.ottawaRoutes
import ca.akaind.datum.search.Place
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarScreensTest {
    private lateinit var car: TestCarContext
    private lateinit var session: TestSession
    private val search = FakeSearch()
    private val routing = FakeRouting(kanataRoutes())
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val lansdowne = Place("Lansdowne Park", "Ottawa, Ontario", 45.39917, -75.68431, "rec")

    @Before fun setUp() {
        Datum.io = Dispatchers.Unconfined
        Datum.search = search
        Datum.routing = routing
        car = TestCarContext.createCarContext(app)
        host(8)
        session = TestSession()
        session.setCarContextInternal(car)
    }

    private fun host(api: Int) = car.updateHandshakeInfo(HandshakeInfo("com.google.android.projection.gearhead", api))

    private fun grantLocation() = shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private val controllers = HashMap<Screen, ScreenController>()

    private fun template(screen: Screen): Template {
        controllers.getOrPut(screen) { ScreenController(screen) }.moveToState(Lifecycle.State.RESUMED)
        shadowOf(Looper.getMainLooper()).idle()
        return screen.onGetTemplate()
    }

    private fun CarText?.s() = this?.toCharSequence().toString()

    private val done = object : OnDoneCallback {}

    private val screens get() = car.getCarService(TestScreenManager::class.java)

    private fun start(intent: Intent = Intent()): Screen {
        val s = session.onCreateScreen(intent)
        session.fakeMap.loc = location(45.42363, -75.70090)
        return s
    }

    private fun rows(t: Template): List<Row> = when (t) {
        is SearchTemplate -> t.itemList!!.items
        is ListTemplate -> t.singleList!!.items
        is PlaceListNavigationTemplate -> t.itemList!!.items
        is RoutePreviewNavigationTemplate -> t.itemList!!.items
        is MapWithContentTemplate -> rows(t.contentTemplate)
        else -> error("no rows in $t")
    }.map { it as Row }

    @Test fun asksForLocationFirst() {
        val first = session.onCreateScreen(Intent())
        assertTrue(first is PermissionScreen)
        assertTrue(screens.screensPushed.single() is HomeScreen)
        assertTrue(template(first) is MessageTemplate)
    }

    @Test fun homeIsTheMapWithSearch() {
        grantLocation()
        val home = start()
        assertTrue(home is HomeScreen)
        val t = template(home) as NavigationTemplate
        assertEquals("Search", t.actionStrip!!.actions.single().title.s())
        assertEquals(4, t.mapActionStrip!!.actions.size)
    }

    @Test fun searchShowsCategoriesThenResults() {
        grantLocation()
        start()
        val s = SearchScreen(car, session)
        val empty = template(s) as SearchTemplate
        assertEquals(CATEGORIES.map { it.label }, rows(empty).take(CATEGORIES.size).map { it.title.s() })

        search.results = listOf(lansdowne, Place("TD Place", "Ottawa", 45.3984, -75.6835, "rec"))
        (s.onGetTemplate() as SearchTemplate).searchCallbackDelegate.sendSearchSubmitted("lansdowne", done)
        shadowOf(Looper.getMainLooper()).idle()
        val found = s.onGetTemplate() as SearchTemplate
        assertEquals(listOf("Lansdowne Park", "TD Place"), rows(found).map { it.title.s() })
        assertEquals(listOf("lansdowne"), search.queries)
    }

    @Test fun categoryReadsTheMapTiles() {
        grantLocation()
        start()
        session.fakeMap.pois = listOf(Place("Esso", "Fuel", 45.41, -75.70, "transit"), Place("Petro-Canada", "Fuel", 45.40, -75.69, "transit"))
        val t = template(CategoryScreen(car, session, CATEGORIES[0]))
        assertTrue(t is MapWithContentTemplate)
        assertEquals(listOf("Esso", "Petro-Canada"), rows(t).map { it.title.s() })
        assertEquals(2, session.fakeMap.places.size)
        assertTrue(search.queries.isEmpty()) // nothing sent: the tiles had the answer

        host(3)
        val legacy = template(CategoryScreen(car, session, CATEGORIES[0]))
        assertTrue(legacy is PlaceListNavigationTemplate)
    }

    @Test fun routePreviewOffersAlternativesAndStartsGuidance() {
        grantLocation()
        start()
        val preview = RoutePreviewScreen(car, session, lansdowne)
        val t = template(preview) as MapWithContentTemplate
        val list = t.contentTemplate as ListTemplate
        assertEquals(2, rows(t).size)
        val go = list.actions.single()
        assertNotNull(go.icon)
        assertEquals(null, go.title)
        assertEquals(2, routing.calls.single().alternates)
        assertEquals(lansdowne, session.fakeMap.dest)
        assertNotNull(session.fakeMap.routes.first)
        assertEquals(1, session.fakeMap.routes.second.size)

        list.actions.single().onClickDelegate!!.sendClick(done)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(screens.screensPushed.last() is NavigationScreen)
        assertEquals("Lansdowne Park", session.recents.all().first().name)

        host(3)
        val legacy = template(RoutePreviewScreen(car, session, lansdowne)) as RoutePreviewNavigationTemplate
        assertEquals("Go", legacy.navigateAction!!.title.s())
    }

    @Test fun guidesTurnByTurnToArrival() {
        grantLocation()
        start()
        val route = ottawaRoutes()[0]
        val nav = NavigationScreen(car, session, lansdowne, route)
        val first = template(nav) as NavigationTemplate
        val navManager = car.getCarService(NavigationManager::class.java) as TestNavigationManager
        assertEquals(1, navManager.navigationStartedCount)
        assertEquals("Drive south.", session.fakeVoice.said.first())
        assertTrue(session.fakeMap.navOn)
        assertNotNull(first.navigationInfo)

        // Fixes along Parliament Road: the card shows the next turn and how far.
        val listeners = DatumSession::class.java.getDeclaredField("listeners").apply { isAccessible = true }.get(session) as Collection<*>
        @Suppress("UNCHECKED_CAST")
        fun fix(i: Int, speed: Float = 12f) = listeners.forEach { (it as (android.location.Location) -> Unit)(location(route.shape[i].lat, route.shape[i].lon, speed)) }
        fix(40)
        val driving = nav.onGetTemplate() as NavigationTemplate
        val info = driving.navigationInfo as RoutingInfo
        assertEquals("Turn left onto Exhibition Way/voie Exhibition.", info.currentStep!!.cue.s())
        assertNotNull(info.currentDistance)
        assertNotNull(driving.destinationTravelEstimate)
        assertTrue(navManager.tripsSent.isNotEmpty())

        fix(route.shape.lastIndex, 0f)
        val arrived = nav.onGetTemplate() as NavigationTemplate
        assertTrue(arrived.navigationInfo is MessageInfo)
        assertEquals("Done", arrived.actionStrip!!.actions.first().title.s())

        controllers.getValue(nav).moveToState(Lifecycle.State.DESTROYED)
        assertEquals(1, navManager.navigationEndedCount)
    }

    @Test fun navigateIntentsFromAssistant() {
        grantLocation()
        val byQuery = start(Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:0,0?q=Lansdowne+Park")))
        assertTrue(byQuery is SearchScreen)
        assertEquals(listOf("Lansdowne Park"), search.queries)

        val byPoint = DatumSession.parseGeo(Uri.parse("geo:45.39917,-75.68431"))
        assertTrue(byPoint is DatumSession.GeoTarget.Point)
        val labelled = DatumSession.parseGeo(Uri.parse("geo:0,0?q=45.39917,-75.68431(TD%20Place)")) as DatumSession.GeoTarget.Point
        assertEquals("TD Place", labelled.place.name)
        assertEquals(45.39917, labelled.place.lat, 1e-9)
        assertEquals(null, DatumSession.parseGeo(Uri.parse("geo:0,0")))
    }
}
