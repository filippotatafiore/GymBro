package com.example.gymbro.viewmodel

import android.graphics.Bitmap
import com.example.gymbro.Accent
import com.example.gymbro.AppTheme
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.Conversation
import com.example.gymbro.network.ConversationDetails
import com.example.gymbro.network.FeedPage
import com.example.gymbro.network.PlanRequest
import com.example.gymbro.network.Post
import com.example.gymbro.network.SharedPlan
import com.example.gymbro.network.Sex
import com.example.gymbro.network.SocialUser
import com.example.gymbro.network.User
import com.example.gymbro.network.UserProfile
import com.example.gymbro.plan.Plan
import com.example.gymbro.repository.MessageRepository
import com.example.gymbro.repository.PlanRepository
import com.example.gymbro.repository.SocialRepository
import com.example.gymbro.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

// Fake repositories for the tests of the ViewModels: they implement the same interfaces of the
// real ones (see the repository package), with data in memory instead of the server and of the
// database. The ViewModels receive them in their constructor (dependency injection).

// The coroutines of the ViewModels (viewModelScope, on the main thread in the app) run at once
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(UnconfinedTestDispatcher())
    override fun finished(description: Description) = Dispatchers.resetMain()
}

fun socialUser(id: Long, username: String, isFollowing: Boolean = false, followsYou: Boolean = false) =
    SocialUser(id, username, hasPicture = false, isFollowing = isFollowing, followsYou = followsYou)

// The logged user
class FakeUserRepository(override var user: User? = User(1, "me", "me@example.com", hasPicture = false)) : UserRepository {
    override val isLoggedIn: Boolean
        get() = user != null
    override var askPersonalInfo = false

    override fun saveBodyData(heightCm: Double?, weightKg: Double?, birthYear: Int?, sex: Sex?) {
        user = user?.copy(heightCm = heightCm, weightKg = weightKg, birthYear = birthYear, sex = sex)
    }

    override fun setAppearance(accent: Accent, theme: AppTheme) {
        user = user?.copy(accent = accent, theme = theme)
    }

    override fun setActivePlan(planId: Long?) {
        user = user?.copy(activePlanId = planId)
    }

    override fun forgetActivePlan(planId: Long) {
        if (user?.activePlanId == planId) setActivePlan(null)
    }

    override suspend fun push() {}

    override suspend fun refresh(): User = user ?: throw ApiException(401, "Login required")
}

// The workout plans of the logged user, in memory (always "synced")
class FakePlanRepository(plans: List<Plan> = emptyList()) : PlanRepository {

    private val saved = MutableStateFlow(plans)

    override fun plans(): Flow<List<Plan>> = saved
    override suspend fun all(): List<Plan> = saved.value
    override suspend fun plan(id: Long): Plan? = saved.value.firstOrNull { it.id == id }
    override suspend fun sync() {}
    override suspend fun trySync(): ApiException? = null

    override suspend fun update(plan: Plan): PlanRepository.Saved {
        store(plan)
        return PlanRepository.Saved(plan, synced = true)
    }

    override suspend fun delete(id: Long): Boolean {
        saved.value = saved.value.filter { it.id != id }
        return true
    }

    override suspend fun setPublic(id: Long, public: Boolean): Plan {
        val plan = plan(id)?.copy(isPublic = public) ?: throw ApiException(404, "Plan not found")
        return plan.also { store(it) }
    }

    override suspend fun create(request: PlanRequest): Plan = throw ApiException(0, "Not reachable")
    override suspend fun copyPublic(ownerId: Long, planId: Long): Plan = throw ApiException(0, "Not reachable")
    override suspend fun copyShared(shareId: Long): Plan = throw ApiException(0, "Not reachable")

    override suspend fun store(plan: Plan) {
        saved.value = listOf(plan) + saved.value.filter { it.id != plan.id }
    }

    override suspend fun deleteAll(userId: Long) {
        saved.value = emptyList()
    }
}

// Messages: the conversations and the plans shared. error: thrown by every request (e.g. offline)
class FakeMessageRepository(
    var conversations: List<Conversation> = emptyList(),
    var details: Map<Long, ConversationDetails> = emptyMap()
) : MessageRepository {

    var error: ApiException? = null
    val markedRead = mutableListOf<Long>()

    override suspend fun conversations(): List<Conversation> {
        error?.let { throw it }
        return conversations
    }

    override suspend fun conversation(userId: Long): ConversationDetails {
        error?.let { throw it }
        return details[userId] ?: throw ApiException(404, "User not found")
    }

    override suspend fun share(userId: Long, planId: Long): SharedPlan = throw ApiException(500, "Not used")

    override suspend fun markRead(userId: Long) {
        markedRead += userId
    }

    override suspend fun sharedPlan(shareId: Long): SharedPlan = throw ApiException(404, "Not used")
}

// The other users: followers and followed users, and the pages of the feed (by the "before" of
// the page). error: thrown by every request (e.g. offline)
class FakeSocialRepository(
    var followers: List<SocialUser> = emptyList(),
    var following: List<SocialUser> = emptyList(),
    var feedPages: Map<String?, FeedPage> = emptyMap()
) : SocialRepository {

    override var followsVersion = 0
    var error: ApiException? = null
    var listDownloads = 0
    var feedDownloads = 0

    private fun check() {
        error?.let { throw it }
    }

    override suspend fun search(text: String): List<SocialUser> {
        check()
        return (followers + following).distinctBy { it.id }.filter { it.username.contains(text, ignoreCase = true) }
    }

    override suspend fun profile(userId: Long): UserProfile = throw ApiException(404, "Not used")
    override suspend fun follow(userId: Long): UserProfile = throw ApiException(404, "Not used")
    override suspend fun unfollow(userId: Long): UserProfile = throw ApiException(404, "Not used")

    override suspend fun followers(userId: Long): List<SocialUser> {
        check()
        listDownloads++
        return followers
    }

    override suspend fun following(userId: Long): List<SocialUser> {
        check()
        return following
    }

    override suspend fun feed(before: String?): FeedPage {
        check()
        feedDownloads++
        return feedPages[before] ?: FeedPage(emptyList(), null)
    }

    override suspend fun posts(userId: Long): List<Post> = emptyList()
    override suspend fun publicPlans(userId: Long): List<Plan> = emptyList()
    override suspend fun publicPlan(userId: Long, planId: Long): Plan = throw ApiException(404, "Not used")
    override suspend fun picture(userId: Long): Bitmap? = null
    override fun cachedSmallPicture(userId: Long): Bitmap? = null
    override suspend fun smallPicture(userId: Long): Bitmap? = null
    override fun forgetPicture(userId: Long) {}
}
