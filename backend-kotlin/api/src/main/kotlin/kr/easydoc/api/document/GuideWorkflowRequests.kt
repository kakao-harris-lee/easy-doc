package kr.easydoc.api.document

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import kr.easydoc.application.actionguide.GuideReviewRevision
import kr.easydoc.core.actionguide.ActionGuideSourceAnchor
import kr.easydoc.core.actionguide.ExtractedGuideAction
import kr.easydoc.core.actionguide.GuideActionPresence
import kr.easydoc.core.actionguide.GuideAnalysisResult
import kr.easydoc.core.actionguide.GuideCoverageStatus
import kr.easydoc.core.actionguide.GuideInformation
import kr.easydoc.core.actionguide.GuideInformationStatus
import kr.easydoc.core.actionguide.GuideSuitability
import kr.easydoc.core.actionguide.GuideUnitAssessment
import kr.easydoc.core.exceptions.InvalidInputException
import java.util.UUID

data class GuideRevisionRequest
    @JsonCreator
    constructor(
        @param:JsonProperty(
            "expected_content_revision",
            required = true,
        ) @field:Min(1) @field:Max(9_007_199_254_740_991) val expectedContentRevision: Long,
        @param:JsonProperty(
            "expected_analysis_revision",
            required = true,
        ) @field:Min(1) @field:Max(9_007_199_254_740_991) val expectedAnalysisRevision: Long,
        @param:JsonProperty(
            "expected_review_revision",
            required = true,
        ) @field:Min(0) @field:Max(9_007_199_254_740_991) val expectedReviewRevision: Long,
    ) {
        fun revision(): GuideReviewRevision =
            GuideReviewRevision(expectedContentRevision, expectedAnalysisRevision, expectedReviewRevision)

        override fun toString(): String = "GuideRevisionRequest()"
    }

data class GuideSignalRequest
    @JsonCreator
    constructor(
        @param:JsonProperty(
            "expected_content_revision",
            required = true,
        ) @field:Min(1) @field:Max(9_007_199_254_740_991) val expectedContentRevision: Long,
        @param:JsonProperty(
            "expected_analysis_revision",
            required = true,
        ) @field:Min(1) @field:Max(9_007_199_254_740_991) val expectedAnalysisRevision: Long,
        @param:JsonProperty(
            "expected_review_revision",
            required = true,
        ) @field:Min(0) @field:Max(9_007_199_254_740_991) val expectedReviewRevision: Long,
        @param:JsonProperty("note", required = true) val note: String,
        @param:JsonProperty("body_unit_indexes", required = true) val bodyUnitIndexes: List<Int>,
        @param:JsonProperty("body_quote", required = true) @param:JsonSetter(nulls = Nulls.SET) val bodyQuote: String?,
    ) {
        fun revision(): GuideReviewRevision =
            GuideReviewRevision(expectedContentRevision, expectedAnalysisRevision, expectedReviewRevision)

        override fun toString(): String = "GuideSignalRequest()"
    }

data class GuideDraftRequest
    @JsonCreator
    constructor(
        @param:JsonProperty(
            "expected_content_revision",
            required = true,
        ) @field:Min(1) @field:Max(9_007_199_254_740_991) val expectedContentRevision: Long,
        @param:JsonProperty(
            "expected_analysis_revision",
            required = true,
        ) @field:Min(1) @field:Max(9_007_199_254_740_991) val expectedAnalysisRevision: Long,
        @param:JsonProperty(
            "expected_review_revision",
            required = true,
        ) @field:Min(0) @field:Max(9_007_199_254_740_991) val expectedReviewRevision: Long,
        @param:JsonProperty("request_id", required = true) val requestId: UUID,
        @param:JsonProperty("analysis_id", required = true) val analysisId: UUID,
        @param:JsonProperty("mode", required = true) val mode: String,
    ) {
        fun revision(): GuideReviewRevision =
            GuideReviewRevision(expectedContentRevision, expectedAnalysisRevision, expectedReviewRevision)

        override fun toString(): String = "GuideDraftRequest()"
    }

data class GuideDraftReviewRequest
    @JsonCreator
    constructor(
        @param:JsonProperty(
            "expected_content_revision",
            required = true,
        ) @field:Min(1) @field:Max(9_007_199_254_740_991) val expectedContentRevision: Long,
        @param:JsonProperty(
            "expected_analysis_revision",
            required = true,
        ) @field:Min(1) @field:Max(9_007_199_254_740_991) val expectedAnalysisRevision: Long,
        @param:JsonProperty(
            "expected_review_revision",
            required = true,
        ) @field:Min(0) @field:Max(9_007_199_254_740_991) val expectedReviewRevision: Long,
        @param:JsonProperty(
            "expected_draft_revision",
            required = true,
        ) @field:Min(1) @field:Max(9_007_199_254_740_991) val expectedDraftRevision: Long,
        @param:JsonProperty("confirmed_block_ids", required = true) val confirmedBlockIds: List<String>,
    ) {
        fun revision(): GuideReviewRevision =
            GuideReviewRevision(expectedContentRevision, expectedAnalysisRevision, expectedReviewRevision)

        override fun toString(): String = "GuideDraftReviewRequest()"
    }

data class GuideDraftApplyRequest
    @JsonCreator
    constructor(
        @param:JsonProperty(
            "expected_content_revision",
            required = true,
        ) @field:Min(1) @field:Max(9_007_199_254_740_991) val expectedContentRevision: Long,
        @param:JsonProperty(
            "expected_analysis_revision",
            required = true,
        ) @field:Min(1) @field:Max(9_007_199_254_740_991) val expectedAnalysisRevision: Long,
        @param:JsonProperty(
            "expected_review_revision",
            required = true,
        ) @field:Min(0) @field:Max(9_007_199_254_740_991) val expectedReviewRevision: Long,
        @param:JsonProperty("request_id", required = true) val requestId: UUID,
        @param:JsonProperty(
            "expected_draft_revision",
            required = true,
        ) @field:Min(1) @field:Max(9_007_199_254_740_991) val expectedDraftRevision: Long,
    ) {
        fun revision(): GuideReviewRevision =
            GuideReviewRevision(expectedContentRevision, expectedAnalysisRevision, expectedReviewRevision)

        override fun toString(): String = "GuideDraftApplyRequest()"
    }

data class GuideCorrectionRequest
    @JsonCreator
    constructor(
        @param:JsonProperty(
            "expected_content_revision",
            required = true,
        ) @field:Min(1) @field:Max(9_007_199_254_740_991) val expectedContentRevision: Long,
        @param:JsonProperty(
            "expected_analysis_revision",
            required = true,
        ) @field:Min(1) @field:Max(9_007_199_254_740_991) val expectedAnalysisRevision: Long,
        @param:JsonProperty(
            "expected_review_revision",
            required = true,
        ) @field:Min(0) @field:Max(9_007_199_254_740_991) val expectedReviewRevision: Long,
        @param:JsonProperty("suitability", required = true) val suitability: String,
        @param:JsonProperty("action_presence", required = true) val actionPresence: String,
        @param:JsonProperty("reason", required = true) val reason: String,
        @param:JsonProperty("evidence", required = true) val evidence: List<ActionGuideAnchorPayload>,
        @param:JsonProperty("actions", required = true) val actions: List<ExtractedGuideActionPayload>,
        @param:JsonProperty("coverage", required = true) val coverage: List<GuideUnitAssessmentPayload>,
    ) {
        fun revision(): GuideReviewRevision =
            GuideReviewRevision(expectedContentRevision, expectedAnalysisRevision, expectedReviewRevision)

        override fun toString(): String = "GuideCorrectionRequest()"

        fun result(): GuideAnalysisResult =
            try {
                GuideAnalysisResult(
                    GuideSuitability.valueOf(suitability.uppercase()),
                    GuideActionPresence.valueOf(actionPresence.uppercase()),
                    reason,
                    evidence.map { ActionGuideSourceAnchor(it.sourceUnitIndexes, it.quote) },
                    actions.map { it.domain() },
                    coverage.map {
                        GuideUnitAssessment(
                            it.sourceUnitId,
                            GuideCoverageStatus.valueOf(it.status.uppercase()),
                            it.actionIds,
                        )
                    },
                    emptyList(),
                    false,
                )
            } catch (_: IllegalArgumentException) {
                throw InvalidInputException("분석 교정 입력이 올바르지 않습니다")
            }
    }

private fun GuideInformationPayload.domain(): GuideInformation =
    GuideInformation(
        GuideInformationStatus.valueOf(status.uppercase()),
        text,
        evidence.map { ActionGuideSourceAnchor(it.sourceUnitIndexes, it.quote) },
    )

private fun ExtractedGuideActionPayload.domain(): ExtractedGuideAction =
    ExtractedGuideAction(
        id,
        instruction.domain(),
        actor.domain(),
        beneficiaries.domain(),
        conditions.map {
            it.domain()
        },
        deadline.domain(),
        preparation.domain(),
        contact.domain(),
        afterActionIds,
        orderEvidence.map { ActionGuideSourceAnchor(it.sourceUnitIndexes, it.quote) },
    )
