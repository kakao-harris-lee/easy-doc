package kr.easydoc.api.support

/**
 * 내보내기 계약 절. [ContractSpec] 에 두지 않는 이유는 그 객체가 이미 계약 전수 파서라
 * 형식 전용 오라클까지 넣으면 LargeClass 분모가 조용히 커지기 때문이다.
 */
object ContractExportSpec {
    /**
     * 내보내기 `filename*` 금지 문자. YAML 단일 인용 문자열의 `\uXXXX` 를 실제 문자로 펼친 뒤
     * 정규식으로 쓴다 — 금지 집합을 테스트에 손열거하지 않기 위해서다(CE-5·CE-6).
     */
    fun filenameForbidden(): Regex {
        val header =
            ContractSpec.map(
                "paths",
                EXPORT_PATH,
                "get",
                "responses",
                "200",
                "headers",
                "Content-Disposition",
            )
        val policy =
            header["x-filename-charset"] as? Map<*, *>
                ?: error("내보내기 Content-Disposition 에 x-filename-charset 이 없다")
        val raw =
            policy["forbidden"]?.toString()
                ?: error("x-filename-charset.forbidden 이 없다")
        val expanded = UNICODE_ESCAPE.replace(raw) { match -> Character.toString(match.groupValues[1].toInt(16)) }
        return Regex(expanded)
    }

    /** 내보내기 200 의 미디어 타입 집합. 형식 enum 과 짝을 맞출 때 쓴다. */
    fun successContentTypes(): Set<String> {
        val types =
            ContractSpec
                .map("paths", EXPORT_PATH, "get", "responses", "200", "content")
                .keys
                .map { it.toString() }
                .toSet()
        require(types.isNotEmpty()) { "내보내기 200 content 가 비었다 — 이 대조는 아무것도 재지 않는다" }
        return types
    }

    private const val EXPORT_PATH = "/conversions/{conversion_id}/export"

    /**
     * YAML 단일 인용 문자열이 그대로 남긴 `\uXXXX`. SnakeYAML 이 이미 펼쳤으면 일치가 없어
     * 원문이 정규식이 된다.
     */
    private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")
}
