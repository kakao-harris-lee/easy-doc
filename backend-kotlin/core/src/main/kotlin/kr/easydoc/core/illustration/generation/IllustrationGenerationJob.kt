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
) {
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
            append("Preserve these facts and conditions exactly: ")
            append(configuration.preservedFacts.joinToString(" | ")).append(". ")
        }
        append("Verified source context: ")
        append(suggestion.sourceAnchors.joinToString(" | ") { it.quote }).append(". ")
        append("Do not put important names, numbers, conditions, or instructions in image text. ")
        append("Do not add unsupported actions or facts. Return one 1024x1024 PNG. ")
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
    )

const val MAX_GENERATION_SCENES: Int = 6
const val MAX_GENERATION_FACTS: Int = 10
const val MAX_GENERATION_ALT_TEXT_CHARS: Int = 300
