package kr.easydoc.api.auth

/** 인증이 필요한 경로 패턴. */
object AuthenticatedEndpoints {
    /** 계약이 `security: [{ HTTPBearer: [] }]` 로 선언한 경로들. */
    val PROTECTED_PATH_PATTERNS: List<String> =
        listOf(
            "/auth/me",
            // `GET /conversions/{conversion_id}` 를 만든 커밋(C6)이 더했다(위 규약).
            // `PUT` 과 `/export` 는 아직 없다 — 그 오퍼레이션을 만드는 커밋이 아니라 **경로**를
            // 만드는 커밋이 더하는 것이므로, 이 한 줄이 그 경로의 두 메서드를 함께 덮는다.
            "/conversions/{conversion_id}",
            "/documents",
            // `DELETE /documents/{document_id}` 를 만든 커밋이 더했다(위 규약).
            "/documents/{document_id}",
            "/workspaces",
            "/workspaces/{workspace_id}",
        )
}
