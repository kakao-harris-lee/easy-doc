package kr.easydoc.api.admin

/**
 * `x-admin-only: true` 오퍼레이션의 경로 패턴. 전부 `/admin/…` 아래다
 * (`AuthenticationCoverageContractTest`의 관리자 전용 축이 계약과 대조한다). 이 목록은
 * [kr.easydoc.api.auth.AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS]에도 들어간다 —
 * 관리자 전용 경로는 인증도 필요하기 때문이다(인증 인터셉터가 먼저, 이 가드가 나중에).
 */
object AdminEndpoints {
    val ADMIN_ONLY_PATH_PATTERNS: List<String> =
        listOf(
            "/admin/workspaces/{workspace_id}/subscription",
            "/admin/workspaces/{workspace_id}/payments/{id}/refund",
            "/admin/workspaces/{workspace_id}/billing",
            "/admin/workspaces/{workspace_id}/billing/orders/{id}/sync",
            "/admin/workspaces/{workspace_id}/billing/stop-renewal",
            "/admin/workspaces/{workspace_id}/billing/retry-card-deletion",
            "/admin/workspaces/{workspace_id}/monthly-summary",
            "/admin/workspaces/{workspace_id}/monthly-history",
            "/admin/workspaces/{workspace_id}/credit-transactions",
            "/admin/workspaces/{workspace_id}/payments",
            "/admin/workspaces",
            "/admin/workspaces/{workspace_id}",
            "/admin/workspaces/{workspace_id}/credits",
            "/admin/feedback",
            "/admin/invoice-requests",
            "/admin/invoice-requests/{id}/handle",
            "/admin/errors",
            "/admin/usage",
            "/admin/announcements",
            "/admin/announcements/{id}",
        )
}
