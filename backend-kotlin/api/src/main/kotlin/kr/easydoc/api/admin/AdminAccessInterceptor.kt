package kr.easydoc.api.admin

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kr.easydoc.api.auth.AUTHENTICATED_USER_ATTRIBUTE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.admin.AdminGuard
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * `/admin/…` 경로의 두 번째 축 — 인증 인터셉터([kr.easydoc.api.auth.AuthenticationInterceptor])
 * **뒤**에 등재된다(`WebMvcConfig.addInterceptors` 순서). 이미 채워진 [AuthenticatedUser]를
 * 요청 속성에서 읽어 [AdminGuard]에 넘긴다 — 관리자가 아니거나 이메일이 미검증이면
 * `AdminRequiredException`(→ 403 「관리자 권한이 필요합니다」)이 여기서 던져진다.
 */
@Component
class AdminAccessInterceptor(private val adminGuard: AdminGuard) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val user =
            request.getAttribute(AUTHENTICATED_USER_ATTRIBUTE) as? AuthenticatedUser
                ?: error("인증 인터셉터가 돌지 않은 경로에서 관리자 확인을 요구했다")
        adminGuard.requireAdmin(user.id)
        return true
    }
}
