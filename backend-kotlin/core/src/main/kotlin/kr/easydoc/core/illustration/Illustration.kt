package kr.easydoc.core.illustration

import java.time.LocalDate

/**
 * 그림 카탈로그(ER-15) 한 장의 용도. 계약 `IllustrationPurpose` 의 wire 값과 1:1 이다.
 */
enum class IllustrationPurpose(val wire: String) {
    VISIT_OFFICE("visit_office"),
    PHONE_CALL("phone_call"),
    SUBMIT_DOCUMENT("submit_document"),
    APPLY_ONLINE("apply_online"),
    DEADLINE("deadline"),
    PAYMENT("payment"),
    IDENTIFICATION("identification"),
    WAIT("wait"),
    CAUTION("caution"),
    DONE("done"),
    ;

    companion object {
        fun fromWire(wire: String): IllustrationPurpose? = entries.firstOrNull { it.wire == wire }
    }
}

/** 그림 한 장의 검수 상태. [Illustration.selectable] 이 [REVIEWED] 만 고른다(AC-R7-a). */
enum class IllustrationReviewStatus(val wire: String) {
    REVIEWED("reviewed"),
    UNREVIEWED("unreviewed"),
    ;

    companion object {
        fun fromWire(wire: String): IllustrationReviewStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 그림 카탈로그 항목 식별자. 소문자 영숫자와 하이픈만 허용하고, 첫 글자는 하이픈이 될 수
 * 없다(파일명·URL 경로 세그먼트로 그대로 쓰인다). 사용자 콘텐츠가 아니라 [toString] 은
 * 값을 그대로 낸다.
 *
 * **`@JvmInline value class` 가 아니라 일반 class + private 생성자다**(`BusinessNumber`와
 * 같은 형태, 그 KDoc이 이유다) — `SensitiveToStringReachTest`의 자동 표본 생성기
 * (`GeneratedToStringProbes`)는 클래스패스의 **모든** `@JvmInline value class`를
 * `classes.filter { it.isValue }`로 무조건 모아 임의 문자열(`SENSITIVE-PROBE-...`, 대문자
 * 포함)로 주 생성자를 직접 호출해 본다 — 형식 검증을 생성자 `init` 블록에 두는 값 검증
 * value class(예: `PlainBody`) 여야 안전한데, 이 타입처럼 형식이 소문자·숫자·하이픈뿐인
 * 값 클래스라면 그 임의 문자열이 [PATTERN]을 만족하지 못해 예외가 그 자리에서 나고, 그
 * 호출은 어디에서도 잡히지 않아 게이트 자체가 깨진다(`BusinessNumber` 구현 당시 실측).
 * private 생성자로 감추면 `it.isValue`가 거짓이 되어 그 무조건 수집에서 빠진다 —
 * `constructor.isAccessible = true`로 우회하는 다른 수집 경로(`reachedWrappers`,
 * `Illustration.assetId` 필드를 통해 닿는다)는 `GeneratedToStringProbes.INERT_VALUES`에
 * 유효 표본을 등록해 따로 막는다. 형식 검증은 그래서 생성자가 아니라 [parse]/[of] 에서만
 * 한다 — 반사로 직접 생성자를 부르는 이 하네스가 검증을 우회해도 예외가 나지 않는다.
 */
class IllustrationAssetId private constructor(val value: String) {
    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is IllustrationAssetId && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        val PATTERN: Regex = Regex("^[a-z0-9][a-z0-9-]{0,39}$")

        /** 형식이 올바르면 값을, 아니면 `null`을 돌려준다 — 호출부가 404로 존재를 숨길 때 쓴다. */
        fun parse(value: String): IllustrationAssetId? =
            if (PATTERN.matches(value)) IllustrationAssetId(value) else null

        /** 형식이 이미 검증됐다고 보는 자리(리터럴·이미 검증된 리소스)에서 쓴다. */
        fun of(value: String): IllustrationAssetId =
            parse(value) ?: throw IllegalArgumentException("그림 자산 id 형식이 올바르지 않다: $value")
    }
}

/**
 * 그림 카탈로그 항목 한 건 (ER-15). 권리·의미·대체텍스트를 함께 든다.
 *
 * **[toString] 은 [assetId]·[purpose]·[reviewStatus]·[version] 만 낸다.** 이 타입은 사용자
 * 콘텐츠가 아니지만(공공기관이 배포하는 고정 픽토그램 카탈로그), `api`의
 * `SensitiveToStringReachTest`가 필드 이름 토큰(`altText`의 `text` 등)으로 이 data class를
 * 민감 후보로 잡으므로 값을 찍지 않는 게이트 규약을 그대로 따른다. 필드명을 `caption`으로
 * 둔 것도 같은 이유다 — `title`이었다면 같은 토큰에 걸려 `caption`으로 바꿔도 결국 검사
 * 대상이 되니, 애초에 검사에 걸리는 이름을 피하지 않고 값 없는 `toString`을 직접 쓴다.
 */
data class Illustration(
    val assetId: IllustrationAssetId,
    val caption: String,
    val purpose: IllustrationPurpose,
    val altText: String,
    val license: String,
    val source: String,
    val reviewStatus: IllustrationReviewStatus,
    val reviewedBy: String?,
    val reviewedAt: LocalDate?,
    val version: Int,
    val mappingExamples: List<String>,
) {
    init {
        require(caption.isNotBlank()) { "caption 이 비어 있다" }
        require(altText.isNotBlank()) { "altText 가 비어 있다" }
        require(license.isNotBlank()) { "license 가 비어 있다" }
        require(source.isNotBlank()) { "source 가 비어 있다" }
        require(version >= 1) { "version 은 1 이상이어야 한다: $version" }
        require(mappingExamples.size in 1..MAX_MAPPING_EXAMPLES) {
            "mappingExamples 는 1~$MAX_MAPPING_EXAMPLES 개여야 한다: ${mappingExamples.size}"
        }
        require(mappingExamples.all { it.isNotBlank() }) { "mappingExamples 에 빈 값이 있다" }
        when (reviewStatus) {
            IllustrationReviewStatus.REVIEWED -> {
                require(reviewedBy != null && reviewedAt != null) {
                    "검수된 항목은 reviewedBy·reviewedAt 이 있어야 한다"
                }
            }

            IllustrationReviewStatus.UNREVIEWED -> {
                require(reviewedBy == null && reviewedAt == null) {
                    "검수되지 않은 항목은 reviewedBy·reviewedAt 이 없어야 한다"
                }
            }
        }
    }

    /** 검수된 항목만 사용자에게 노출·선택할 수 있다(AC-R7-a). */
    val selectable: Boolean get() = reviewStatus == IllustrationReviewStatus.REVIEWED

    override fun toString(): String = "Illustration(${assetId.value}, ${purpose.wire}, ${reviewStatus.wire}, v$version)"

    private companion object {
        const val MAX_MAPPING_EXAMPLES = 3
    }
}

/**
 * 그림 카탈로그 전체 (ER-15) — 명세상 최대 10종([MAX_ENTRIES])이며 [IllustrationAssetId]가
 * 중복될 수 없다.
 */
class IllustrationCatalog(entries: List<Illustration>) {
    private val entries: List<Illustration> = entries.toList()

    init {
        require(entries.size <= MAX_ENTRIES) { "그림 카탈로그는 최대 $MAX_ENTRIES 종이다: ${entries.size}" }
        val duplicated = entries.groupBy { it.assetId }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "그림 카탈로그에 asset_id 중복이 있다: $duplicated" }
    }

    val size: Int get() = entries.size

    /** 검수된 항목만, 원래 순서 그대로. */
    fun selectable(): List<Illustration> = entries.filter { it.selectable }

    fun find(assetId: IllustrationAssetId): Illustration? = entries.firstOrNull { it.assetId == assetId }

    companion object {
        const val MAX_ENTRIES = 10
    }
}
