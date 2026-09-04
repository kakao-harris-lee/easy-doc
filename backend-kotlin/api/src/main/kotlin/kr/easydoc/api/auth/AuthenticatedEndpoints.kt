package kr.easydoc.api.auth

/** 인증이 필요한 경로 패턴. */
object AuthenticatedEndpoints {
    /** 계약이 `security: [{ HTTPBearer: [] }]` 로 선언한 경로들. */
    val PROTECTED_PATH_PATTERNS: List<String> =
        listOf(
            "/auth/me",
            // 이메일 인증 2종(backlog §1.4 P0-1/P0-3) — 대상 이메일은 토큰의 사용자로 고정된다.
            "/auth/email-verification/request",
            "/auth/email-verification/confirm",
            // `GET`·`PUT /conversions/{conversion_id}` 와 `GET .../export` 는 경로가 다르다.
            // 인터셉터 패턴은 하위 경로를 덮지 않으므로 export 를 **따로** 넣는다.
            "/conversions/{conversion_id}",
            "/conversions/{conversion_id}/export",
            // 피드백도 같은 사유로 **따로** 넣는다 — 위 항목이 하위 경로를 덮지 않는다.
            "/conversions/{conversion_id}/feedback",
            "/documents",
            // `DELETE /documents/{document_id}` 를 만든 커밋이 더했다(위 규약).
            "/documents/{document_id}",
            // 원문 조회도 같은 사유로 **따로** 넣는다 — 위 항목이 하위 경로를 덮지 않는다.
            "/documents/{document_id}/source",
            "/workspaces",
            "/workspaces/{workspace_id}",
        )
}
