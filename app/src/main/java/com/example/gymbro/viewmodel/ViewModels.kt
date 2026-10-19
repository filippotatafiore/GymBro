package com.example.gymbro.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.gymbro.AppContainer
import com.example.gymbro.GymBroApp
import com.example.gymbro.network.ApiException

// MVVM: every page (View: activity or fragment) has a ViewModel, which
// - keeps the state of the page (StateFlow, read-only for the page): the page shows it every time
//   it changes, and it survives a recreation of the page (e.g. rotation, new theme)
// - receives the actions of the user (its functions) and runs them with the repositories (Model),
//   in its coroutines (viewModelScope)
// - sends the page one-shot events (e.g. a message, close the page), received once
// The pages never use the repositories, the server or the database directly.

// The repositories of the app (see AppContainer), for the factories of the ViewModels
val CreationExtras.container: AppContainer
    get() = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as GymBroApp).container

// A text shown when a list is empty: a text of the app, or the error of the download
sealed interface EmptyText {
    data class Text(@StringRes val text: Int) : EmptyText
    data class Error(val error: ApiException) : EmptyText
}
