package com.example.gymbro.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.gymbro.repository.Place
import com.example.gymbro.repository.PlannedRoute
import com.example.gymbro.repository.RouteRepository
import com.example.gymbro.session.GeoPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Route planner of a run (RoutePlannerActivity): the current position of the user (origin), the
// destination and the optional stop chosen on the map or by name, and the walking route between
// them, whose distance is the goal of the run
class RoutePlannerViewModel(private val routes: RouteRepository) : ViewModel() {

    // route: the walking route (null: no destination yet, or being calculated); calculating: the
    // route is being calculated; places: the places found by the search (null: the list is hidden)
    data class State(
        val origin: GeoPoint? = null,
        val destination: GeoPoint? = null,
        val stop: GeoPoint? = null,
        val route: PlannedRoute? = null,
        val calculating: Boolean = false,
        val places: List<Place>? = null
    ) {
        // The run can start: a route of at least 100 m
        val canStart: Boolean
            get() = route != null && route.distanceMeters >= 100
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var routeJob: Job? = null
    private var searchJob: Job? = null

    // The first precise position of the user is the start of the route
    fun setOrigin(origin: GeoPoint) {
        if (_state.value.origin != null) return
        _state.update { it.copy(origin = origin) }
        updateRoute()
    }

    // A tap on the map or a place found: sets the destination or (asStop) the stop
    fun setPoint(point: GeoPoint, asStop: Boolean) {
        _state.update { if (asStop) it.copy(stop = point, places = null) else it.copy(destination = point, places = null) }
        updateRoute()
    }

    fun removeStop() {
        _state.update { it.copy(stop = null) }
        updateRoute()
    }

    // Goal distance: walking route from the current position to the destination, through the stop
    private fun updateRoute() {
        routeJob?.cancel()
        val state = _state.value
        val origin = state.origin
        val destination = state.destination
        if (destination == null || origin == null) {
            _state.update { it.copy(route = null, calculating = false) }
            return
        }
        _state.update { it.copy(route = null, calculating = true) }
        routeJob = viewModelScope.launch {
            val route = routes.walkingRoute(origin, state.stop, destination)
            _state.update { it.copy(route = route, calculating = false) }
        }
    }

    // Places matching the text of the search bar, shown in a list over the map
    fun search(query: String) {
        if (query.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val places = routes.searchPlaces(query, _state.value.origin)
            _state.update { it.copy(places = places) }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { RoutePlannerViewModel(container.routes) }
        }
    }
}
