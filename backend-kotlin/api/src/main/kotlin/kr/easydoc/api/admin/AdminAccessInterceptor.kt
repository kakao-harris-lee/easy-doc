package kr.easydoc.api.admin

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kr.easydoc.api.accesslog.AdminAccessSubjectScope
import kr.easydoc.api.accesslog.ClientIpResolver
import kr.easydoc.api.auth.AUTHENTICATED_USER_ATTRIBUTE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.accesslog.RecordPersonalDataAccess
import kr.easydoc.application.admin.AdminGuard
import kr.easydoc.core.exceptions.AdminRequiredException
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping

/**
 * `/admin/…` 경로의 두 번째 축 — 인증 인터셉터([kr.easydoc.api.auth.AuthenticationInterceptor])
 * **뒤**에 등재된다(`WebMvcConfig.addInterceptors` 순서). 이미 채워진 [AuthenticatedUser]를
 * 요청 속성에서 읽어 [AdminGuard]에 넘긴다 — 관리자가 아니거나 이메일이 미검증이면
 * `AdminRequiredException`(→ 403 「관리자 권한이 필요합니다」)이 여기서 던져진다.
 *
 * **접속기록(계획 `docs/plans/2026-09-11-access-log-retention.md` §3.2)을 잡는 자리이기도
 * 하다.** `x-admin-only` 10개 오퍼레이션이 전부 이 경로 패턴(`AdminEndpoints`)을 지나므로,
 * 엔드포인트마다 기록 호출을 흩지 않고 여기 한 자리에서 성공·거절을 모두 남긴다 — 새
 * 관리자 엔드포인트가 생겨도 이 인터셉터 대상에만 넣으면 기록이 빠지지 않는다. 거절
 * (403)도 남겨야 하므로 [AdminGuard.requireAdmin]을 감싸 [AdminRequiredException]을 잡고
 * 기록한 뒤 그대로 다시 던진다 — 응답 자체는 바뀌지 않는다.
 */
@Component
class AdminAccessInterceptor(
    private val adminGuard: AdminGuard,
    private val accessLog: RecordPersonalDataAccess,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val user =
            request.getAttribute(AUTHENTICATED_USER_ATTRIBUTE) as? AuthenticatedUser
                ?: error("인증 인터셉터가 돌지 않은 경로에서 관리자 확인을 요구했다")
        val pattern =
            request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
                ?: error("경로 매핑 패턴이 없는 요청에서 관리자 확인을 요구했다")
        val operation = AdminOperationCatalog.operationIdFor(request.method, pattern)
        val clientIp = ClientIpResolver.resolve(request)
        val subjectScope = AdminAccessSubjectScope.of(request)

        try {
            adminGuard.requireAdmin(user.id)
        } catch (failure: AdminRequiredException) {
            accessLog.recordRejection(user.id, clientIp, operation, subjectScope)
            throw failure
        }
        accessLog.recordSuccess(user.id, clientIp, operation, subjectScope)
        return true
    }
}
