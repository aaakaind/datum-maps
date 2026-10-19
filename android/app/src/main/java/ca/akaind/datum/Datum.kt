package ca.akaind.datum

import ca.akaind.datum.routing.RouteService
import ca.akaind.datum.routing.ValhallaRouting
import ca.akaind.datum.search.PhotonSearch
import ca.akaind.datum.search.SearchService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** App-wide services. Tests swap these for fakes. */
object Datum {
    var search: SearchService = PhotonSearch()
    var routing: RouteService = ValhallaRouting()
    var io: CoroutineDispatcher = Dispatchers.IO
    var now: () -> Long = System::currentTimeMillis
}
