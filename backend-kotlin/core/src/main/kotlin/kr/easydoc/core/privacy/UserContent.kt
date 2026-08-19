package kr.easydoc.core.privacy

/**
 * `toString()` 이 값을 가릴 때 그 자리에 대신 찍는 표식.
 *
 * 한 곳에 두는 이유는 **테스트가 「가려졌음」을 이 값으로 확인하기 때문**이다. 타입마다
 * 다른 문자열을 쓰면 확인하는 쪽이 타입마다 다른 상수를 알아야 하고, 그 순간 목록이 생겨
 * 새 타입이 조용히 빠진다.
 */
const val CONTENT_MASK: String = "***"

/**
 * **이 타입은 사용자 콘텐츠를 담는다** — 필드 **이름**만 봐서는 드러나지 않는 자리에 붙인다.
 *
 * ## 왜 필요한가
 *
 * [kr.easydoc.core.privacy] 의 「`toString()` 과 본문」 규율을 상시로 강제하는 것은
 * `SensitiveToStringReachTest` 이고, 그 탐지기의 1차 신호는 **생성자 파라미터 이름**이다
 * (`email`·`name`·`text`·`body`·`sentence` …). 이름 규약은 대부분을 덮지만 전부는 아니다 —
 * `RepairPrompt(system, user)` 가 그 예다. 두 필드 다 프롬프트 **전문**을 담는데, 이름
 * 어디에도 그 사실이 없다.
 *
 * 그런 자리를 이름 규약에 억지로 끼워 넣으면(예: `user` 를 민감 토큰에 추가) 규약이 근거보다
 * 넓어져 관계없는 타입까지 끌려온다(`CLAUDE.md` 규칙 4 — 범위는 근거를 넘지 않는다).
 * 그래서 **선언으로 적는다.**
 *
 * ## 이 애너테이션이 할 수 없는 것 — **범위를 좁히지 못한다**
 *
 * 이것은 탐지 대상을 **넓히기만** 한다. 붙이면 검사를 받고, 떼면 이름 규약이 여전히 판정한다.
 * 검사를 끄는 용도로는 쓸 수 없다 — 면제 조항은 은폐형이고 이 저장소는 그 방향으로 넓히지
 * 않는다(`CLAUDE.md` 규칙 4).
 *
 * `RUNTIME` 유지가 필요한 이유: 탐지기가 소스 텍스트가 아니라 **적재된 클래스**를 읽는다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class UserContent
