package kr.easydoc.api.auth

/** 인증이 필요한 경로 패턴. */
object AuthenticatedEndpoints {
    /** 계약이 `security: [{ HTTPBearer: [] }]` 로 선언한 경로들. */
    val PROTECTED_PATH_PATTERNS: List<String> =
        listOf(
            "/auth/me",
            "/documents",
            // `DELETE /documents/{document_id}` 를 만든 커밋이 더했다(위 규약).
            "/documents/{document_id}",
            "/workspaces",
            "/workspaces/{workspace_id}",
        )
}
