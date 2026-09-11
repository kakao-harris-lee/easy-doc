package kr.easydoc.api.admin

/**
 * (HTTP 메서드, 경로 패턴) → 계약 `operationId` — 접속기록(계획
 * `docs/plans/2026-09-11-access-log-retention.md` §3.1)의 `operation` 열이 계약의
 * `operationId`와 같아야 하므로([AdminAccessInterceptor]가 이 표로 채운다), [AdminEndpoints]가
 * 든 경로마다 이 표에 항목이 하나씩 있어야 한다 — 없으면 [operationIdFor]가 던진다.
 *
 * 경로 패턴은 스프링이 [org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE]
 * 로 채우는 값 그대로다(`AdminEndpoints.ADMIN_ONLY_PATH_PATTERNS`와 같은 문자열).
 */
object AdminOperationCatalog {
    private data class Route(
        val method: String,
        val pattern: String,
    )

    private val OPERATIONS: Map<Route, String> =
        mapOf(
            Route("GET", "/admin/workspaces") to "listAdminWorkspaces",
            Route("GET", "/admin/workspaces/{workspace_id}") to "readAdminWorkspace",
            Route("POST", "/admin/workspaces/{workspace_id}/credits") to "adjustAdminWorkspaceCredits",
            Route("GET", "/admin/invoice-requests") to "listAdminInvoiceRequests",
            Route("POST", "/admin/invoice-requests/{id}/handle") to "handleAdminInvoiceRequest",
            Route("GET", "/admin/errors") to "readAdminErrors",
            Route("GET", "/admin/usage") to "readAdminUsage",
            Route("GET", "/admin/announcements") to "listAdminAnnouncements",
            Route("POST", "/admin/announcements") to "createAdminAnnouncement",
            Route("PATCH", "/admin/announcements/{id}") to "updateAdminAnnouncement",
        )

    fun operationIdFor(
        method: String,
        pattern: String,
    ): String =
        OPERATIONS[Route(method.uppercase(), pattern)]
            ?: error("관리자 전용 경로에 operationId 매핑이 없다: $method $pattern")
}
