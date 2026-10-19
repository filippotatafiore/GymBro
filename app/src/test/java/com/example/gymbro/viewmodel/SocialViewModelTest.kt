package com.example.gymbro.viewmodel

import com.example.gymbro.network.Conversation
import com.example.gymbro.network.FeedPage
import com.example.gymbro.network.FeedPost
import com.example.gymbro.network.Post
import com.example.gymbro.network.PostKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

// SocialViewModel with fake repositories: the feed, a page at a time, and the dot of Messages
class SocialViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun feedPost(id: Long) = FeedPost(
        Post(id, PostKind.MANUAL, "Deadlift", createdAt = Instant.EPOCH), socialUser(2, "Anna"), personalBest = false
    )

    private val social = FakeSocialRepository(
        feedPages = mapOf(
            null to FeedPage(listOf(feedPost(5), feedPost(4)), next = "page2"),
            "page2" to FeedPage(listOf(feedPost(3)), next = null)
        )
    )
    private val messages = FakeMessageRepository()
    private val viewModel = SocialViewModel(FakeUserRepository(), social, messages)

    private fun shown() = viewModel.state.value.feed?.map { it.post.id }

    @Test
    fun feed_aPageAtATime_untilTheLast() {
        viewModel.onPageShown()
        assertEquals(listOf(5L, 4L), shown())

        viewModel.loadMore()
        assertEquals(listOf(5L, 4L, 3L), shown())

        // no other pages
        viewModel.loadMore()
        assertEquals(listOf(5L, 4L, 3L), shown())
        assertEquals(2, social.feedDownloads)
    }

    @Test
    fun feed_notDownloadedAgain_untilTheFollowsChange() {
        viewModel.onPageShown()
        viewModel.onPageShown()
        assertEquals(1, social.feedDownloads)

        social.followsVersion++
        viewModel.onPageShown()
        assertEquals(2, social.feedDownloads)
    }

    @Test
    fun messagesDot_whenSomePlansWereNotSeen() {
        messages.conversations = listOf(Conversation(socialUser(2, "Anna"), canShare = true, unread = 1, last = null))
        viewModel.onPageShown()
        assertEquals(true, viewModel.state.value.unread)
    }
}
