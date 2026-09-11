package kr.easydoc.api.admin

import kr.easydoc.api.auth.AUTHENTICATED_USER_ATTRIBUTE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.accesslog.PersonalDataAccessLogEntry
import kr.easydoc.application.accesslog.PersonalDataAccessLogWriter
import kr.easydoc.application.accesslog.RecordPersonalDataAccess
import kr.easydoc.application.admin.AdminAccessRepository
import kr.easydoc.application.admin.AdminGuard
import kr.easydoc.core.exceptions.AdminRequiredException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerMapping
import java.time.Clock
import java.util.UUID

/**
 * 리뷰 지적 1 고정판 — Spring 도 DB 도 없이, [RecordPersonalDataAccess]가 예외를 던지는
 * 대역으로 기록 실패가 인터셉터의 fail-closed 결정을 지키는지 잰다(`AdminGrantArgsTest`와
 * 같은 형태 — 순수 단위 테스트, `MockHttpServletRequest`만 빌린다).
 */
class AdminAccessInterceptorTest {
    @Test
    @DisplayName("성공 경로에서 기록이 실패하면 컨트롤러에 이르지 못하고 요청이 실패한다")
    fun `기록 실패는 성공 경로도 실패시킨다`() {
        val interceptor = interceptorWith(isAdmin = true, writer = ThrowingWriter())

        assertThatThrownBy { interceptor.preHandle(adminRequest(), MockHttpServletResponse(), Any()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("기록 실패")
    }

    @Test
    @DisplayName("거절 경로에서 기록이 실패해도 접근이 조용히 허용되지 않는다")
    fun `기록 실패는 거절 경로에서도 접근을 허용하지 않는다`() {
        val interceptor = interceptorWith(isAdmin = false, writer = ThrowingWriter())

        // 원래의 AdminRequiredException(403)이 기록 실패 예외로 대체되더라도, preHandle이
        // true를 반환해 컨트롤러로 넘어가는 일은 없다 — 「접근이 거부됐다」는 사실은 그대로다.
        assertThatThrownBy { interceptor.preHandle(adminRequest(), MockHttpServletResponse(), Any()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("기록 실패")
    }

    @Test
    @DisplayName("기록이 되면 관리자 확인 성공은 그대로 통과다")
    fun `기록 성공시 정상 통과`() {
        val writer = RecordingWriter()
        val interceptor = interceptorWith(isAdmin = true, writer = writer)

        val allowed = interceptor.preHandle(adminRequest(), MockHttpServletResponse(), Any())

        assertThat(allowed).isTrue()
        assertThat(writer.seen).hasSize(1)
    }

    @Test
    @DisplayName("기록이 되면 관리자 확인 실패는 그대로 403이다")
    fun `기록 성공시 거절은 그대로 403`() {
        val writer = RecordingWriter()
        val interceptor = interceptorWith(isAdmin = false, writer = writer)

        assertThatThrownBy { interceptor.preHandle(adminRequest(), MockHttpServletResponse(), Any()) }
            .isInstanceOf(AdminRequiredException::class.java)
        assertThat(writer.seen).hasSize(1)
    }

    private fun interceptorWith(
        isAdmin: Boolean,
        writer: PersonalDataAccessLogWriter,
    ): AdminAccessInterceptor {
        val repository =
            object : AdminAccessRepository {
                override fun isVerifiedAdmin(userId: UUID): Boolean = isAdmin

                override fun setIsAdmin(
                    userId: UUID,
                    isAdmin: Boolean,
                ): Boolean = true
            }
        return AdminAccessInterceptor(
            adminGuard = AdminGuard(repository),
            accessLog = RecordPersonalDataAccess(writer, Clock.systemUTC()),
        )
    }

    private fun adminRequest(): MockHttpServletRequest {
        val request = MockHttpServletRequest("GET", "/admin/workspaces")
        request.setAttribute(AUTHENTICATED_USER_ATTRIBUTE, AuthenticatedUser(UUID.randomUUID()))
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/admin/workspaces")
        return request
    }

    private class ThrowingWriter : PersonalDataAccessLogWriter {
        override fun insert(entry: PersonalDataAccessLogEntry): Nothing = error("기록 실패")
    }

    private class RecordingWriter : PersonalDataAccessLogWriter {
        val seen = mutableListOf<PersonalDataAccessLogEntry>()

        override fun insert(entry: PersonalDataAccessLogEntry) {
            seen += entry
        }
    }
}
