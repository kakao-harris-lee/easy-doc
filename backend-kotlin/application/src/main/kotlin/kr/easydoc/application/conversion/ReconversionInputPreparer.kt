package kr.easydoc.application.conversion

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.SegmentMapDerivation
import kr.easydoc.application.document.StoredConversion
import kr.easydoc.application.document.StoredSourceText
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.segment.SegmentConfidence
import kr.easydoc.core.segment.SourceStructure
import kr.easydoc.core.segment.splitUnits

/** The validated input and derived context passed to one unit reconversion call. */
class ReconversionInput(
    val sourceUnitIndex: Int,
    val unit: String,
    val unitStructure: SourceStructure,
    val priorBodyContext: String?,
    val requiredCredits: Credits,
) {
    override fun toString(): String =
        "ReconversionInput(sourceUnitIndex=$sourceUnitIndex, unit=${unit.length}자, " +
            "priorContext=${if (priorBodyContext == null) "없음" else "있음"}, credits=$requiredCredits)"
}

/**
 * Builds the immutable input for a reconversion provider call.
 *
 * The service keeps validation, reservation, semaphore acquisition, provider invocation, and
 * settlement in their existing order. This collaborator only derives the selected source unit,
 * its structure hint, and the conservative prefix context from already loaded rows.
 */
internal class ReconversionInputPreparer(
    private val cipher: ContentCipher,
    private val segmentMapDerivation: SegmentMapDerivation,
    private val contextEnabled: Boolean,
) {
    fun prepare(
        stored: StoredConversion,
        source: StoredSourceText,
        sourceUnits: List<String>,
        sourceUnitIndex: Int,
        requestedEasyUnitIndexes: List<Int>,
    ): ReconversionInput {
        val unit = sourceUnits[sourceUnitIndex]
        val targetKind = source.structureOrBody(sourceUnits.size).kinds[sourceUnitIndex]
        return ReconversionInput(
            sourceUnitIndex = sourceUnitIndex,
            unit = unit,
            unitStructure = SourceStructure(listOf(targetKind)),
            priorBodyContext =
                if (contextEnabled) {
                    priorBodyContext(
                        stored = stored,
                        source = source,
                        sourceUnits = sourceUnits,
                        sourceUnitIndex = sourceUnitIndex,
                        requestedEasyUnitIndexes = requestedEasyUnitIndexes,
                    )
                } else {
                    null
                },
            requiredCredits = Credits.requiredFor(unit.length, stored.readingLevel),
        )
    }

    /**
     * Returns only a complete, high-confidence prefix before the selected source unit. Unknown or
     * ambiguous mappings return null so the provider never receives a guessed context.
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
    private fun priorBodyContext(
        stored: StoredConversion,
        source: StoredSourceText,
        sourceUnits: List<String>,
        sourceUnitIndex: Int,
        requestedEasyUnitIndexes: List<Int>,
    ): String? {
        val edited = stored.ciphertexts.editedText
        val easy = stored.ciphertexts.easyText
        val encryptedBody = edited ?: easy ?: return null
        val bodyField =
            if (edited != null) {
                EncryptedField.CONVERSION_EDITED_TEXT
            } else {
                EncryptedField.CONVERSION_EASY_TEXT
            }
        val body = cipher.decrypt(encryptedBody, stored.id, bodyField)
        val easyUnits = splitUnits(body.value)
        val map = segmentMapDerivation.deriveOrNull(source, body) ?: return null
        if (
            map.sourceUnitCount != sourceUnits.size ||
            map.easyUnitCount != easyUnits.size ||
            map.units.size != easyUnits.size ||
            map.units.any { it.easyUnitIndex !in easyUnits.indices }
        ) {
            return null
        }

        val mappedToTarget = map.units.filter { sourceUnitIndex in it.sourceUnitIndexes }
        if (mappedToTarget.isEmpty() || mappedToTarget.any { !isExactHighMapping(it, sourceUnitIndex) }) {
            return null
        }
        val mappedIndexes = mappedToTarget.map { it.easyUnitIndex }.sorted()
        // An empty/stale request must not make the server guess which part of a merged mapping was
        // selected. The request may reorder indexes, but it must name the whole current mapping.
        if (requestedEasyUnitIndexes.distinct().sorted() != mappedIndexes) return null
        val targetEasyIndex = mappedIndexes.first()
        if (targetEasyIndex == 0) return ""

        val preceding = map.units.filter { it.easyUnitIndex < targetEasyIndex }
        if (
            preceding.any { it.confidence != SegmentConfidence.HIGH } ||
            preceding.any {
                it.sourceUnitIndexes.isEmpty() ||
                    it.sourceUnitIndexes.any { index -> index >= sourceUnitIndex }
            }
        ) {
            return null
        }
        val prefix = easyUnits.take(targetEasyIndex).joinToString("\n")
        return prefix.takeIf { it.length <= MAX_PRIOR_BODY_CONTEXT_CHARS }
    }

    private fun isExactHighMapping(
        unit: kr.easydoc.core.segment.SegmentUnit,
        sourceUnitIndex: Int,
    ): Boolean = unit.confidence == SegmentConfidence.HIGH && unit.sourceUnitIndexes == listOf(sourceUnitIndex)

    private companion object {
        /** Do not guess a first occurrence from an unbounded preceding body. */
        const val MAX_PRIOR_BODY_CONTEXT_CHARS = 12_000
    }
}
