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
            // 명시적 계정 연결 2종(2.10.0, backlog §1.4) — 대상 계정은 토큰의 사용자로 고정된다.
            // `/auth/oauth/{provider}/start`·`/callback`(공개, security: [])과 경로가
            // 갈리므로 인터셉터 패턴이 로그인 흐름을 잠그지 않는다.
            "/auth/oauth/{provider}/link/start",
            "/auth/oauth/{provider}/link/callback",
            // `GET`·`PUT /conversions/{conversion_id}` 와 `GET .../export` 는 경로가 다르다.
            // 인터셉터 패턴은 하위 경로를 덮지 않으므로 export 를 **따로** 넣는다.
            "/conversions/{conversion_id}",
            "/conversions/{conversion_id}/export",
            // 피드백도 같은 사유로 **따로** 넣는다 — 위 항목이 하위 경로를 덮지 않는다.
            "/conversions/{conversion_id}/feedback",
            // 재변환(P0-4 S4, 2.14.0)도 같은 사유로 **따로** 넣는다.
            "/conversions/{conversion_id}/units/{source_unit_index}/reconvert",
            "/documents",
            // `DELETE /documents/{document_id}` 를 만든 커밋이 더했다(위 규약).
            "/documents/{document_id}",
            // 원문 조회도 같은 사유로 **따로** 넣는다 — 위 항목이 하위 경로를 덮지 않는다.
            "/documents/{document_id}/source",
            "/workspaces",
            "/workspaces/{workspace_id}",
            // 사전 조회(2.11.0, P0-5) — 소유 자원이 없지만 여전히 인증은 필요하다(계약 security).
            "/dictionary/lookup",
        )
}
