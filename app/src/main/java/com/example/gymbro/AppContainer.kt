package com.example.gymbro

import com.example.gymbro.repository.AccountRepository
import com.example.gymbro.repository.AccountRepositoryImpl
import com.example.gymbro.repository.ChatRepository
import com.example.gymbro.repository.ChatRepositoryImpl
import com.example.gymbro.repository.MessageRepository
import com.example.gymbro.repository.MessageRepositoryImpl
import com.example.gymbro.repository.Notices
import com.example.gymbro.repository.PlanRepository
import com.example.gymbro.repository.PlanRepositoryImpl
import com.example.gymbro.repository.ProfileRepository
import com.example.gymbro.repository.ProfileRepositoryImpl
import com.example.gymbro.repository.RouteRepository
import com.example.gymbro.repository.RouteRepositoryImpl
import com.example.gymbro.repository.SessionRepository
import com.example.gymbro.repository.SessionRepositoryImpl
import com.example.gymbro.repository.SocialRepository
import com.example.gymbro.repository.SocialRepositoryImpl
import com.example.gymbro.repository.SyncManager
import com.example.gymbro.repository.SyncManagerImpl
import com.example.gymbro.repository.UserRepository
import com.example.gymbro.repository.UserRepositoryImpl

// Dependency injection by hand: the one instance of every repository of the app, each one created
// with the objects it needs (database, scope of the app, other repositories). The ViewModels
// receive them in their constructor (see their Factory), never creating them: in the unit tests
// they receive fake repositories instead.
class AppContainer(app: GymBroApp) {

    // Coroutines that must complete even if the page that started them is closed (e.g. saving a
    // session started by a ViewModel)
    val appScope = app.applicationScope

    // Messages of the work done in background, shown by GymBroApp
    val notices = Notices()

    val users: UserRepository = UserRepositoryImpl(appScope, notices)
    val sessions: SessionRepository = SessionRepositoryImpl(app.database, notices)
    val plans: PlanRepository = PlanRepositoryImpl(app.database, appScope, notices)
    val social: SocialRepository = SocialRepositoryImpl(appScope)
    val profiles: ProfileRepository = ProfileRepositoryImpl(app, app.database, users, sessions, social)
    val chats: ChatRepository = ChatRepositoryImpl(app.database, appScope, plans)
    val accounts: AccountRepository = AccountRepositoryImpl(app, appScope, sessions, plans, profiles, chats)
    val messages: MessageRepository = MessageRepositoryImpl()
    val routes: RouteRepository = RouteRepositoryImpl(app)

    private val syncManager = SyncManagerImpl(app, appScope, sessions, plans, users)
    val sync: SyncManager = syncManager

    // Synchronizes with the server every time the phone is online (last: it may start at once)
    fun start() {
        syncManager.start()
    }
}
