package com.example.gymbro.viewmodel

import com.example.gymbro.R
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// MessagesViewModel with fake repositories: the conversations downloaded, the search by username
// and the text of the empty list
class MessagesViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun conversation(id: Long, username: String) =
        Conversation(socialUser(id, username), canShare = true, unread = 0, last = null)

    private val messages = FakeMessageRepository(listOf(conversation(2, "Anna"), conversation(3, "Marco")))
    private val viewModel = MessagesViewModel(FakeUserRepository(), messages)

    private fun shown() = viewModel.state.value.conversations.map { it.user.username }

    @Test
    fun load_showsTheConversations() {
        assertTrue(viewModel.state.value.loading)
        viewModel.load()
        assertEquals(listOf("Anna", "Marco"), shown())
        assertNull(viewModel.state.value.empty)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun search_byUsername_upperLowerCaseAndAtIgnored() {
        viewModel.load()
        viewModel.search(" @AN ")
        assertEquals(listOf("Anna"), shown())
        viewModel.search("")
        assertEquals(listOf("Anna", "Marco"), shown())
    }

    @Test
    fun emptyList_text() {
        viewModel.load()
        viewModel.search("luca")
        assertEquals(EmptyText.Text(R.string.no_users_found), viewModel.state.value.empty)

        messages.conversations = emptyList()
        viewModel.search("")
        viewModel.load()
        assertEquals(EmptyText.Text(R.string.messages_empty), viewModel.state.value.empty)
    }

    @Test
    fun serverNotReachable_errorShownOnlyIfNoConversations() {
        messages.error = ApiException(0, "Not reachable")
        viewModel.load()
        assertEquals(0, (viewModel.state.value.empty as EmptyText.Error).error.code)

        // the conversations downloaded stay, without the error
        messages.error = null
        viewModel.load()
        messages.error = ApiException(0, "Not reachable")
        viewModel.load()
        assertEquals(listOf("Anna", "Marco"), shown())
        assertNull(viewModel.state.value.empty)
    }
}
