package kr.easydoc.application.dictionary

import kr.easydoc.core.privacy.CONTENT_MASK

/**
 * 사전 단위 표기 (P0-5 계획 §3.2) — 이름·라이선스 요약·적재된 색인의 `schema_version`.
 *
 * **엔트리별 출처가 아니다.** `easy_dict.index.json` 은 엔트리마다 원천·라이선스를 담지
 * 않으므로(계획 §3.2), 조회 응답이 보여줄 수 있는 것은 사전 전체에 대한 표기뿐이다.
 * 엔트리별 출처는 색인 스키마 1.1.0이 필요한 별 작업이라 이 계획 밖이다.
 */
data class DictionaryAttribution(
    val name: String,
    val license: String,
    val schemaVersion: String,
) {
    /**
     * `name` 이 `SensitiveToStringReachTest` 의 민감 판정 토큰 `name` 에 걸린다 — 실제로는
     * 비밀이 아닌 공개 사전 이름이지만, `MailProperties.fromAddress` 와 같은 규약으로
     * 길이만 남긴다(그 자체는 비밀이 아니어도 규율을 지키는 선례).
     */
    override fun toString(): String =
        "DictionaryAttribution(name=$CONTENT_MASK ${name.length}자, license=$license, schemaVersion=$schemaVersion)"
}

/** 현재 배선된 사전의 표기를 돌려준다. 조회 기능이 꺼져 있어도(색인 미적재) 항상 값이 있다. */
fun interface DictionaryAttributionProvider {
    fun current(): DictionaryAttribution
}
