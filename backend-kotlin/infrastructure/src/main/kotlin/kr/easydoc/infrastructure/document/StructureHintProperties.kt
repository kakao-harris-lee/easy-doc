package kr.easydoc.infrastructure.document

import kr.easydoc.core.easyread.StructureHintOptions
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `[구조]` 절(표·목록 구조 힌트, P0-4 S8-2) 설정. 바인딩 접두사는 `easydoc.prompt`
 * (계획 §1.3·§4, `docs/plans/2026-09-06-p0-4-structure-hints.md`).
 *
 * [maxRuns] 는 run 수 상한이다 — 넘으면 per-run 나열 대신 "표·목록이 많습니다" 한 문장으로
 * 접는다(계획 §4 리스크 「프롬프트 길이」). 장문 공고는 run 이 수십 개일 수 있어 코드 상수가
 * 아니라 운영 중 조정 가능한 구성값이다(CLAUDE.md 「상수와 구성 관리」). 기본값은
 * [StructureHintOptions.DEFAULT_MAX_RUNS](40, 계획 §4 예시)와 같다.
 */
@ConfigurationProperties(prefix = "easydoc.prompt")
data class StructureHintProperties(val structureMaxRuns: Int = StructureHintOptions.DEFAULT_MAX_RUNS) {
    /** core 로 넘길 순수 옵션 값으로 바꾼다 — core 는 Spring `@ConfigurationProperties` 를 모른다. */
    fun toStructureHintOptions(): StructureHintOptions = StructureHintOptions(maxRuns = structureMaxRuns)
}
