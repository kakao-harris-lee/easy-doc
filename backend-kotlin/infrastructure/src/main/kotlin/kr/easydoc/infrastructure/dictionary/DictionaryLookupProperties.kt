package kr.easydoc.infrastructure.dictionary

import kr.easydoc.core.privacy.CONTENT_MASK
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 검수 화면 조회(P0-5) 설정. 바인딩 접두사는 `easydoc.dictionary.lookup`.
 *
 * worker 의 프롬프트 주입 스위치([DictionaryProperties.enabled])와 **의도적으로 분리한
 * 이름**이다 - 프롬프트 주입은 기본이 켜짐(계획 §3.2 정책)이지만, 조회는 API 프로세스가
 * 색인을 새로 읽는 경로라 기본값을 켜짐으로 두면 이번 변경만으로 API 기동이 1.5MB JSON을
 * 읽기 시작하는 부작용이 생긴다. 그래서 [enabled] 기본값은 **꺼짐**이다 - 실제 조회
 * 엔드포인트(조각 4)가 배선되고 운영자가 명시적으로 켤 때만 API 가 색인을 적재한다.
 *
 * `max-query-chars` 는 두지 않는다 — 계약 2.11.0 이 wire 상한 100자를 정한 뒤로는
 * `core/dictionary/TermLookup.kt` 의 `TermQuery.MAX_LENGTH` 가 그 불변식의 정본이다
 * (계약과 함께 바뀌어야 하는 값이라 CLAUDE.md 「상수와 구성 관리」에 따라 코드 상수다).
 *
 * [rateLimitPerMinute] 는 운영 중 조정될 수 있는 진짜 손잡이라(계획 §3.4 "사용자별
 * 분당 60을 구성값으로 받는다") 여기 둔다. [dictionaryName]·[dictionaryLicense] 는
 * 사전 단위 표기(계획 §3.2) — 엔트리별 출처는 색인 스키마 1.1.0이 필요한 별 작업이라
 * 범위 밖이고, 이 두 값은 원천이 바뀌면(코드 변경 없이) 함께 바뀔 수 있는 사실이라
 * 구성값으로 시작한다.
 */
@ConfigurationProperties(prefix = "easydoc.dictionary.lookup")
data class DictionaryLookupProperties(
    val enabled: Boolean = false,
    val rateLimitPerMinute: Int = DEFAULT_RATE_LIMIT_PER_MINUTE,
    val dictionaryName: String = DEFAULT_DICTIONARY_NAME,
    val dictionaryLicense: String = DEFAULT_DICTIONARY_LICENSE,
) {
    /**
     * `dictionaryName` 이 민감 판정 토큰 `name` 에 걸린다 — 실제로는 공개 사전 이름이라
     * 비밀은 아니지만, `MailProperties.fromAddress` 와 같은 규약으로 길이만 남긴다.
     */
    override fun toString(): String =
        "DictionaryLookupProperties(enabled=$enabled, rateLimitPerMinute=$rateLimitPerMinute, " +
            "dictionaryName=$CONTENT_MASK ${dictionaryName.length}자, dictionaryLicense=$dictionaryLicense)"

    companion object {
        const val DEFAULT_RATE_LIMIT_PER_MINUTE: Int = 60
        const val DEFAULT_DICTIONARY_NAME: String = "easy-dictionary (쉬운 말 사전)"
        const val DEFAULT_DICTIONARY_LICENSE: String =
            "국립국어원 공공데이터(공공누리 제1유형) 및 자체 검수 복지 용어 시드"
    }
}
