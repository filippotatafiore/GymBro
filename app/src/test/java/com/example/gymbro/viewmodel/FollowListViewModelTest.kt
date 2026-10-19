package com.example.gymbro.viewmodel

import com.example.gymbro.R
import com.example.gymbro.network.ApiException
import com.example.gymbro.viewmodel.FollowListViewModel.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

// FollowListViewModel with fake repositories: the two lists, the search, and the lists downloaded
// again only when the follows of the logged user change
class FollowListViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val social = FakeSocialRepository(
        followers = listOf(socialUser(2, "Anna"), socialUser(3, "Marco"), socialUser(4, "Annalisa")),
        following = listOf(socialUser(3, "Marco"))
    )
    private val viewModel = FollowListViewModel(userId = 7, Tab.FOLLOWERS, FakeUserRepository(), social)

    private fun shown() = viewModel.state.value.shown?.users?.map { it.username }

    @Test
    fun theListChosen_withTheCountsOfBoth() {
        assertNull(viewModel.state.value.shown)
        viewModel.onPageShown()
        assertEquals(listOf("Anna", "Marco", "Annalisa"), shown())
        assertEquals(3, viewModel.state.value.shown?.followersCount)
        assertEquals(1, viewModel.state.value.shown?.followingCount)

        viewModel.showTab(Tab.FOLLOWING)
        assertEquals(listOf("Marco"), shown())
    }

    @Test
    fun search_onTheListShown() {
        viewModel.onPageShown()
        viewModel.search("anna")
        assertEquals(listOf("Anna", "Annalisa"), shown())
        viewModel.showTab(Tab.FOLLOWING)
        assertEquals(emptyList<String>(), shown())
        assertEquals(EmptyText.Text(R.string.no_users_found), viewModel.state.value.empty)
    }

    @Test
    fun emptyList_text() {
        social.following = emptyList()
        viewModel.onPageShown()
        viewModel.showTab(Tab.FOLLOWING)
        assertEquals(EmptyText.Text(R.string.no_following), viewModel.state.value.empty)
    }

    @Test
    fun downloadedAgain_onlyWhenTheFollowsChange() {
        viewModel.onPageShown()
        viewModel.onPageShown()
        assertEquals(1, social.listDownloads)

        // e.g. a user followed in their profile
        social.followsVersion++
        viewModel.onPageShown()
        assertEquals(2, social.listDownloads)
    }

    @Test
    fun serverNotReachable_error() {
        social.error = ApiException(0, "Not reachable")
        viewModel.onPageShown()
        assertEquals(0, (viewModel.state.value.empty as EmptyText.Error).error.code)
        assertNull(viewModel.state.value.shown)
    }
}
