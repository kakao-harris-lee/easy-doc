package kr.easydoc.api.accesslog

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.servlet.HandlerMapping

/**
 * 접속기록(계획 `docs/plans/2026-09-11-access-log-retention.md` §3.1) `subject_scope` —
 * 「단건이면 그 식별자, 목록이면 조회 조건」을 응답 본문을 보지 않고 요청만으로 구한다.
 * [AdminAccessInterceptor][kr.easydoc.api.admin.AdminAccessInterceptor]는 관리자 확인
 * **전**(핸들러 진입 전)에 돌므로, 컨트롤러가 실제로 찾은 건수 같은 응답 값은 여기서
 * 알 수 없다 — 요청 자체(경로 변수·쿼리 파라미터)가 조회 조건의 전부다.
 *
 * 경로 변수(단건 자원, 예: `workspace_id`)가 있으면 그것을 쓴다. 없으면(목록·집계
 * 오퍼레이션) 쿼리 파라미터를 쓴다. 둘 다 없으면(예: `createAdminAnnouncement`) `null`.
 *
 * **쿼리 파라미터는 허용 목록이다 — 금지 목록이 아니다.** [ALLOWED_QUERY_PARAM_NAMES]에
 * 없는 키는 **값을 지우고 키만 남긴다**(`q=<생략>`). 계약이 `listAdminWorkspaces`의 `q`를
 * 「이름·소유자 이메일 부분 일치」로 정의한다 — 운영자가 `?q=hong@example.com`으로
 * 검색하면 그 이메일(취급자 자신이 아니라 **검색 대상**인 제3자의 것)이 그대로 값에
 * 실린다. 이 표는 삭제 경로가 없고 보관이 1년 이상이라, 값을 가리지 않으면 개인정보를
 * 지키려고 만든 감사 표 자체가 개인정보 저장소가 된다. 「검색으로 조회했다」는 사실은
 * 점검에 필요하지만 검색어 자체는 필요하지 않다 — 그래서 값만 지운다(요청을 깨지는
 * 않는다, [AdminOperationCatalog.operationIdFor]가 매핑 없는 경로에서 **요청 자체**를
 * 끊는 것과는 다른 안전 실패다).
 *
 * 허용한 키(`page`·`size`·`status`·`from`·`to`)는 계약의 관리자 오퍼레이션 파라미터
 * 정의에서 값이 구조적인 것(페이지 번호·상태 enum·날짜)만 골랐다 — `q`(자유 텍스트
 * 검색어) 하나만 값을 지운다. 경로 변수는 이 허용 목록의 대상이 아니다 — 전부 UUID다
 * (`workspace_id`·`id`).
 */
object AdminAccessSubjectScope {
    /** 값을 그대로 남기는 쿼리 파라미터 이름 — 계약에서 값이 구조적인 것만 골랐다(위 KDoc). */
    private val ALLOWED_QUERY_PARAM_NAMES = setOf("page", "size", "status", "from", "to")

    /** 허용 목록 밖 키의 값 자리 — 검색어 원문 대신 남긴다. */
    private const val REDACTED_VALUE = "<생략>"

    @Suppress("UNCHECKED_CAST")
    fun of(request: HttpServletRequest): String? {
        val pathVariables =
            request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>
        if (!pathVariables.isNullOrEmpty()) {
            return pathVariables.entries.joinToString(",") { (key, value) -> "$key=$value" }
        }
        return queryScope(request)
    }

    private fun queryScope(request: HttpServletRequest): String? {
        val params = request.parameterMap
        if (params.isEmpty()) return null
        return params.keys
            .sorted()
            .joinToString("&") { key -> "$key=${valueFor(key, params)}" }
    }

    /** 허용 목록에 있으면 실제 값, 없으면 [REDACTED_VALUE] — 위 클래스 KDoc의 허용 목록 규약. */
    private fun valueFor(
        key: String,
        params: Map<String, Array<String>>,
    ): String =
        if (key in ALLOWED_QUERY_PARAM_NAMES) {
            params[key]?.firstOrNull().orEmpty()
        } else {
            REDACTED_VALUE
        }
}
