package kr.easydoc.api.illustration

import kr.easydoc.application.auth.AuthService
import kr.easydoc.application.illustration.ILLUSTRATION_NOT_FOUND_MESSAGE
import kr.easydoc.core.exceptions.NotFoundException
import org.springframework.stereotype.Component
import java.util.UUID

/** Server-side allowlist for every illustration capability and asset route. */
@Component
class IllustrationAccessGuard(private val auth: AuthService) {
    fun allows(userId: UUID): Boolean =
        auth.readUser(userId).email.equals(ALLOWED_EMAIL, ignoreCase = true)

    fun requireAllowed(userId: UUID) {
        if (!allows(userId)) throw NotFoundException(ILLUSTRATION_NOT_FOUND_MESSAGE)
    }

    private companion object {
        const val ALLOWED_EMAIL = "chihunlee@gmail.com"
    }
}
