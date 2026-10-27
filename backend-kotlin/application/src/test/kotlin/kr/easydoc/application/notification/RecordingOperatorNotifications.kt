package kr.easydoc.application.notification

import kr.easydoc.application.auth.UserRepository
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import java.util.UUID

class RecordingOperatorNotifications : OperatorNotifications {
    val events = mutableListOf<OperatorNotificationEvent>()
    var failNext = false
    var onNotify: () -> Unit = {}

    override fun notify(event: OperatorNotificationEvent) {
        onNotify()
        if (failNext) {
            failNext = false
            error("recording notification failure")
        }
        events += event
    }
}

class NotificationUserRepository(var user: User? = null) : UserRepository {
    var findByIdCount = 0
        private set

    override fun findById(id: UUID): User? {
        findByIdCount++
        return user?.takeIf { it.id == id }
    }

    override fun findByEmail(email: String): StoredUser? = error("not used")

    override fun exists(id: UUID): Boolean = error("not used")

    override fun lockForUpdate(id: UUID): User? = error("not used")

    override fun create(
        email: String,
        passwordHash: PasswordHash,
    ): User = error("not used")

    override fun createWithoutPassword(
        email: String,
        emailVerified: Boolean,
    ): User = error("not used")

    override fun updatePasswordHash(
        userId: UUID,
        passwordHash: PasswordHash,
    ) = error("not used")

    override fun markEmailVerified(userId: UUID): Boolean = error("not used")
}
