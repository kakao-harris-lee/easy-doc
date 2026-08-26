package kr.easydoc.api.auth

/** 인증이 필요한 경로 패턴. */
object AuthenticatedEndpoints {
    /** 계약이 `security: [{ HTTPBearer: [] }]` 로 선언한 경로들. */
    val PROTECTED_PATH_PATTERNS: List<String> =
        listOf(
            "/auth/me",
            // `GET`·`PUT /conversions/{conversion_id}` 와 `GET .../export` 는 경로가 다르다.
            // 인터셉터 패턴은 하위 경로를 덮지 않으므로 export 를 **따로** 넣는다.
            "/conversions/{conversion_id}",
            "/conversions/{conversion_id}/export",
            // 피드백도 같은 사유로 **따로** 넣는다 — 위 항목이 하위 경로를 덮지 않는다.
            "/conversions/{conversion_id}/feedback",
            "/documents",
            // `DELETE /documents/{document_id}` 를 만든 커밋이 더했다(위 규약).
            "/documents/{document_id}",
            "/workspaces",
            "/workspaces/{workspace_id}",
        )
}
