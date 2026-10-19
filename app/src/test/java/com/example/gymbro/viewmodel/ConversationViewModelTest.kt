package com.example.gymbro.viewmodel

import com.example.gymbro.network.ConversationDetails
import com.example.gymbro.network.SharedPlan
import com.example.gymbro.plan.Plan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

// ConversationViewModel with fake repositories: the plans shared with a user, seen when the page
// shows them, and the page closed when the user does not exist anymore
class ConversationViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val plan = Plan(
        1, "Push Pull Legs", null, null, null, null, preferences = "", notes = "", days = emptyList(),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH
    )

    private fun shared(id: Long, fromMe: Boolean, read: Boolean) =
        SharedPlan(id, fromMe, plan, read, savedPlanId = null, createdAt = Instant.EPOCH)

    private fun viewModel(messages: FakeMessageRepository) =
        ConversationViewModel(userId = 2, username = "Anna", FakeUserRepository(), messages, FakePlanRepository(listOf(plan)))

    @Test
    fun plansReceived_seenWhenShown() {
        val details = ConversationDetails(
            socialUser(2, "Anna", isFollowing = true, followsYou = true), canShare = true,
            shares = listOf(shared(10, fromMe = true, read = true), shared(11, fromMe = false, read = false))
        )
        val messages = FakeMessageRepository(details = mapOf(2L to details))
        val viewModel = viewModel(messages)

        viewModel.load()
        assertEquals(details, viewModel.state.value.details)
        assertEquals(true, viewModel.state.value.shareEnabled)
        assertEquals(listOf(2L), messages.markedRead)
    }

    @Test
    fun nothingNew_notMarkedAsSeen() {
        val details = ConversationDetails(socialUser(2, "Anna"), canShare = false, shares = listOf(shared(10, fromMe = true, read = true)))
        val messages = FakeMessageRepository(details = mapOf(2L to details))
        val viewModel = viewModel(messages)

        viewModel.load()
        assertEquals(false, viewModel.state.value.shareEnabled)
        assertTrue(messages.markedRead.isEmpty())
    }

    @Test
    fun userNotFound_thePageCloses() = runTest {
        val viewModel = viewModel(FakeMessageRepository())
        viewModel.load()
        assertEquals(ConversationViewModel.Event.UserNotFound, viewModel.events.first())
    }

    @Test
    fun share_chooseAmongThePlansOfTheUser() = runTest {
        val viewModel = viewModel(FakeMessageRepository())
        viewModel.choosePlanToShare()
        assertEquals(ConversationViewModel.Event.ChoosePlan(listOf(plan)), viewModel.events.first())
    }
}
