package kr.easydoc.api.auth

/**
 * 인증이 필요한 경로 패턴.
 *
 * ## 이 목록은 손으로 적지만 손으로 지켜지지는 않는다
 *
 * 열거식 목록은 이 저장소가 이미 두 번 놓친 형태다(사적 응답 헤더 10곳 중 4곳 누락).
 * 그래서 목록을 **계약이 판정한다** — `AuthenticationCoverageContractTest` 가
 * `contracts/easy-doc-v1.yaml` 을 읽어, `security` 가 선언된 오퍼레이션의 경로 집합과
 * 이 목록이 **정확히 같은지** 대조한다. 새 엔드포인트를 붙이면서 여기 추가하지 않으면
 * 그 테스트가 먼저 깨진다.
 *
 * 반대 방향도 본다 — `security: []`(인증 불필요)로 선언된 경로가 이 목록에 들어오면
 * 그것도 실패다. 공개 엔드포인트를 잠그는 것은 계약 위반이면서 화면이 통째로 죽는
 * 변경이라, 조용히 지나가면 안 된다.
 *
 * ## 패턴 문법
 *
 * 계약의 `{document_id}` 같은 경로 변수는 Spring `PathPattern` 의 `{...}` 와 문법이
 * 같으므로 **그대로 적는다.** 대조 테스트가 두 문자열을 정규화 없이 비교할 수 있는
 * 것이 이 선택의 요점이다 — 변환 규칙을 끼우면 그 규칙 자체가 검증되지 않는 코드가 된다.
 */
object AuthenticatedEndpoints {
    /**
     * 계약이 `security: [{ HTTPBearer: [] }]` 로 선언한 경로들.
     *
     * Phase 3 의 `auth` 작업 단위에서는 `/auth/me` 하나다. 작업 공간·문서 단위가
     * 자기 경로를 여기에 더한다 — **엔드포인트를 만드는 그 커밋에서** 더한다.
     */
    val PROTECTED_PATH_PATTERNS: List<String> = listOf("/auth/me")
}
