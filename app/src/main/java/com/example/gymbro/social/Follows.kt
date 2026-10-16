package com.example.gymbro.social

// The follows of the logged user changed (a user followed or not followed anymore): the pages that
// depend on them (feed, lists of users) load their data again when they are shown
object Follows {

    // Increased at every change
    var version = 0
        private set

    fun changed() {
        version++
    }
}
