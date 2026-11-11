package kr.easydoc.core.illustration.generation

import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestion
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionBodyRange
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionPurpose
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionSourceAnchor
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

enum class IllustrationGenerationJobStatus(val wireName: String) {
    QUEUED("queued"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    SUPERSEDED("superseded"),
    ;

    val active: Boolean get() = this == QUEUED || this == RUNNING

    companion object {
        fun ofWireName(value: String): IllustrationGenerationJobStatus =
            entries.firstOrNull { it.wireName == value }
                ?: throw InvalidInputException("알 수 없는 그림 생성 작업 상태입니다: $value")
    }
}

enum class IllustrationGenerationJobFailureCode(val wireName: String) {
    GENERATION_FAILED("generation_failed"),
    RESULT_INVALID("result_invalid"),
    OUTCOME_UNKNOWN("outcome_unknown"),
    ;

    companion object {
        fun ofWireName(value: String): IllustrationGenerationJobFailureCode =
            entries.firstOrNull { it.wireName == value }
                ?: throw InvalidInputException("알 수 없는 그림 생성 작업 실패 코드입니다: $value")
    }
}

data class IllustrationGenerationConfiguration(
    val scenes: List<String>,
    val preservedFacts: List<String>,
    val altText: String,
) {
    override fun toString(): String =
        "IllustrationGenerationConfiguration(sceneCount=${scenes.size}, factCount=${preservedFacts.size}, " +
            "altText=${altText.length}자)"
}

data class IllustrationGenerationMetadata(
    val suggestionId: UUID,
    val analysisVersion: String,
    val configurationRevision: String,
    val purpose: IllustrationSuggestionPurpose,
    val reason: String,
    val bodyRange: IllustrationSuggestionBodyRange,
    val sourceAnchors: List<IllustrationSuggestionSourceAnchor>,
    val scenes: List<String>,
    val preservedFacts: List<String>,
    val altText: String,
    val discarded: Boolean = false,
    /** 이 입력을 만든 생성 프롬프트의 개정([ILLUSTRATION_GENERATION_PROMPT_VERSION]). 옛 레코드는 `null` 이다. */
    val promptVersion: String? = null,
) {
    // `promptVersion` 은 toString 에 내지 않는다. 값 자체는 서버가 찍는 상수라 안전하지만,
    // `SensitiveToStringReachTest` 의 민감 판정은 **이름**으로 돌고 「prompt」가 그 토큰이다 —
    // 이름만으로 거르는 게이트에 예외를 뚫는 것보다 로그 한 칸을 비우는 편이 싸다. 이 값을 읽는
    // 쪽은 저장된 입력(`prompt_version`)과 평가 레인 산출물이지 로그가 아니다.
    override fun toString(): String =
        "IllustrationGenerationMetadata(suggestionId=$suggestionId, analysisVersion=$analysisVersion, " +
            "configurationRevision=$configurationRevision, purpose=$purpose, " +
            "anchorCount=${sourceAnchors.size}, sceneCount=${scenes.size}, factCount=${preservedFacts.size}, " +
            "altText=${altText.length}자)"
}

class IllustrationGenerationInput(
    val finalPrompt: String,
    val metadata: IllustrationGenerationMetadata,
    val executionMode: String = "provider",
) {
    override fun toString(): String =
        "IllustrationGenerationInput(finalPrompt=${finalPrompt.length}자, metadata=$metadata)"
}

fun validateIllustrationGenerationAltText(altText: String) {
    require(altText.isNotBlank() && altText.codePointCount(0, altText.length) <= MAX_GENERATION_ALT_TEXT_CHARS) {
        "대체텍스트 구성이 올바르지 않습니다"
    }
}

fun validateIllustrationGenerationConfiguration(
    suggestion: IllustrationSuggestion,
    configuration: IllustrationGenerationConfiguration,
) {
    require(configuration.scenes.isNotEmpty() && configuration.scenes.size <= MAX_GENERATION_SCENES) {
        "그림 장면 구성이 올바르지 않습니다"
    }
    require(configuration.preservedFacts.size <= MAX_GENERATION_FACTS) { "보존할 사실 구성이 올바르지 않습니다" }
    validateIllustrationGenerationAltText(configuration.altText)
    require(configuration.scenes.all { it in suggestion.scenes }) { "최신 제안에 없는 장면은 사용할 수 없습니다" }
    require(configuration.preservedFacts.all { it in suggestion.preservedFacts }) {
        "최신 제안에 없는 원문 사실은 사용할 수 없습니다"
    }
    require(configuration.scenes.distinct().size == configuration.scenes.size) { "장면이 중복됐습니다" }
    require(configuration.preservedFacts.distinct().size == configuration.preservedFacts.size) {
        "보존할 사실이 중복됐습니다"
    }
}

fun illustrationConfigurationRevision(configuration: IllustrationGenerationConfiguration): String {
    val canonical =
        buildString {
            configuration.scenes.forEach { append(it.length).append(':').append(it).append(';') }
            append('|')
            configuration.preservedFacts.forEach { append(it.length).append(':').append(it).append(';') }
            append('|').append(configuration.altText)
        }
    return MessageDigest
        .getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * 생성 프롬프트의 개정 식별자. [buildIllustrationGenerationPrompt] 의 문구를 바꿀 때마다 올린다 —
 * 「어느 프롬프트가 그린 그림인가」를 나중에 잇는 값이다. 사람 기억에 맡기지 않는다:
 * `IllustrationGenerationTest` 가 프롬프트 전문의 SHA-256 을 이 값과 나란히 고정하므로,
 * 문구를 고치면 digest 가 먼저 깨지고 그 자리에서 이 상수를 올리게 된다.
 *
 * 감사·품질 추적 전용이다. 멱등 키([illustrationConfigurationRevision] 와 작업 fingerprint),
 * DB 열, 공개 API, `contracts/easy-doc-v1.yaml` 에는 넣지 않는다 — 프롬프트 문구가 바뀌었다고
 * 이미 만든 그림을 다시 그리게 하거나 계약을 흔들 이유가 없다.
 */
const val ILLUSTRATION_GENERATION_PROMPT_VERSION: String = "r7-illustration-generation-2"

fun buildIllustrationGenerationPrompt(
    suggestion: IllustrationSuggestion,
    configuration: IllustrationGenerationConfiguration,
): String =
    buildString {
        append("Create exactly one educational illustration for adult readers. ")
        append("Use a clear, respectful visual style and do not depict adults as children. ")
        append("Purpose: ").append(suggestion.purpose.wireName).append(". ")
        append("Show these scenes: ").append(configuration.scenes.joinToString(" | ")).append(". ")
        if (configuration.preservedFacts.isNotEmpty()) {
            // 「그대로 그려라」가 아니라 「어기지 마라」다. 2차 평가 표본 002-2 의 보존 사실이
            // 「20분 이상」이었고, 옛 문구는 아래 수치 금지와 정면으로 부딪쳤다 — 보존 사실은
            // 그림이 어겨서는 안 될 제약이지 그려야 할 대상이 아니다(이슈 #207 리뷰 ①).
            append("The image must not contradict these facts and conditions: ")
            append(configuration.preservedFacts.joinToString(" | ")).append(". ")
            append("Numbers in these facts and in the scenes are shown in the accompanying text; ")
            append("do not draw them. ")
        }
        append("Verified source context: ")
        append(suggestion.sourceAnchors.joinToString(" | ") { it.quote }).append(". ")
        append("Do not put important names, conditions, or instructions in image text. ")
        append("Do not add unsupported actions or facts. ")
        // 1차 평가(2026-11-10)에서 모델이 원문이 정한 대상을 더 구체적인 다른 것으로 바꾸고
        // (분변 검사 → 대장내시경), 없는 예시 금액을 그려 넣고, 절차 단계를 하나 더 붙였다.
        // 계획 §12.4 ①이 그 세 가지를 프롬프트에서 막으라고 정한다.
        append("Keep the exact kind of every object the source names — tests, procedures, documents, ")
        append("benefits, and items provided — and never replace one with a different or more specific kind. ")
        append("If the source does not say how something looks, ")
        append("keep it generic instead of inventing equipment, places, or methods. ")
        // 수치 지시는 하나뿐이다. 2차 평가(2026-11-10)에서 표본 002-2 가 「20분 이상」을 10분을
        // 가리키는 시계로 그렸다 — 「없는 숫자를 만들지 마라」가 「원문에 있는 숫자는 그려도 된다」로
        // 읽혔고, 글자가 아닌 그림(시곗바늘)은 「그림 속 글자」 금지에도 걸리지 않았다. 이슈 #204 ①이
        // 두 문장을 하나로 합치고 「그려진 수량」까지 막으라고 정한다.
        append("Do not show any number, amount, date, time, duration, or quantity in the image — ")
        append("not as written text and not as a drawn quantity such as clock hands, calendar marks, ")
        append("or bar lengths. The accompanying text carries every number. ")
        // 「세어 그린 사물」까지 막으면 「서류 두 장을 준비합니다」 같은 장면과 여러 단계 절차를
        // 그릴 수 없다 — 막을 것은 수량 자체가 아니라 숫자 표기다(이슈 #207 리뷰 ②).
        append("Draw only as many objects or steps as the scenes name, without numerals or numeric marks. ")
        append("Do not add, repeat, or reorder procedure steps beyond the scenes listed above. ")
        append("Do not draw a service or benefit as a physical package or box ")
        append("unless the source says an item is handed over. ")
        append("Return one 1024x1024 PNG. ")
        append("Accessibility description for the reviewer, not text to render: ")
        append(configuration.altText)
    }

fun illustrationGenerationMetadata(
    suggestion: IllustrationSuggestion,
    analysisVersion: String,
    configuration: IllustrationGenerationConfiguration,
    configurationRevision: String,
): IllustrationGenerationMetadata =
    IllustrationGenerationMetadata(
        suggestionId = suggestion.suggestionId,
        analysisVersion = analysisVersion,
        configurationRevision = configurationRevision,
        purpose = suggestion.purpose,
        reason = suggestion.reason,
        bodyRange = suggestion.bodyRange,
        sourceAnchors = suggestion.sourceAnchors,
        scenes = configuration.scenes,
        preservedFacts = configuration.preservedFacts,
        altText = configuration.altText,
        promptVersion = ILLUSTRATION_GENERATION_PROMPT_VERSION,
    )

const val MAX_GENERATION_SCENES: Int = 6
const val MAX_GENERATION_FACTS: Int = 10
const val MAX_GENERATION_ALT_TEXT_CHARS: Int = 300
