package kr.easydoc.core.llm

/**
 * LLM 이 낸 구조화 JSON 하나를 **파싱하기 전에** 자르는 길이 상한(문자 수).
 *
 * 파서가 먼저 자르지 않으면 형식이 깨진 거대한 응답 하나가 파싱·검증 비용을 그대로 떠안는다.
 * 코드 포인트가 아니라 `String.length` 로 재는 것은 「파서에 넘기기 전에 값싸게 자른다」는
 * 목적 때문이다 — 내용 길이 규칙은 각 검증기가 코드 포인트로 따로 본다.
 *
 * ER-06 행동 안내 후보(`ActionGuideCandidateParser`)와 R7 ER-17 그림 제안
 * (`IllustrationSuggestionParser`)이 같은 값을 쓴다. 구조화 출력 하나의 상한이라 운영 중
 * 바뀌는 구성값이 아니라 코드 상수다.
 */
const val MAX_LLM_JSON_CHARS: Int = 262_144
