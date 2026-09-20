package kr.easydoc.core.actionguide

import kr.easydoc.core.easyread.findMissingFacts
import kr.easydoc.core.exceptions.InvalidInputException

/** 구조·원문 인용·제한된 사실 규칙만 검증한다. 의미 정확성이나 자격을 확정하지 않는다. */
object ActionGuideCandidateValidator {
    private const val SCHEMA_VERSION = 1
    private const val MAX_ITEMS_PER_SECTION = 10
    private const val MAX_ITEM_CODE_POINTS = 500
    private const val MAX_USER_TEXT_CODE_POINTS = 4_000
    private const val MAX_ANCHORS_PER_ITEM = 10
    private const val MAX_ANCHOR_QUOTE_CODE_POINTS = 1_000

    fun validate(
        candidate: ActionGuideCandidate,
        sourceUnits: List<String>,
    ) {
        validateStructure(candidate)
        candidate.sections.forEach { validateSectionAgainstSource(it, sourceUnits) }
        validateExceptionCautions(candidate)
    }

    /** 저장된 암호문을 다시 읽을 때도 적용하는, 원문 없이 결정 가능한 제한. */
    fun validateStructure(candidate: ActionGuideCandidate) {
        if (candidate.schemaVersion != SCHEMA_VERSION) invalid()
        if (candidate.sections.map { it.kind }.toSet() != ActionGuideSectionKind.entries.toSet()) invalid()
        if (candidate.sections.size != ActionGuideSectionKind.entries.size) invalid()
        val userTextCodePoints = candidate.sections.sumOf(::validateSectionStructure)
        if (userTextCodePoints > MAX_USER_TEXT_CODE_POINTS) invalid()
    }

    private fun validateSectionStructure(section: ActionGuideSection): Int {
        if (section.items.size > MAX_ITEMS_PER_SECTION) invalid()
        if (section.status == ActionGuideSectionStatus.NOT_IN_SOURCE && section.items.isNotEmpty()) invalid()
        if (section.status == ActionGuideSectionStatus.AVAILABLE && section.items.isEmpty()) invalid()
        return section.items.sumOf { validateItemStructure(it, section.status) }
    }

    private fun validateItemStructure(
        item: ActionGuideItem,
        status: ActionGuideSectionStatus,
    ): Int {
        if (item.text.isBlank() || item.text.countCodePoints() > MAX_ITEM_CODE_POINTS) invalid()
        if (item.cautions.size > MAX_ITEMS_PER_SECTION || item.sourceAnchors.size > MAX_ANCHORS_PER_ITEM) {
            invalid()
        }
        if (status == ActionGuideSectionStatus.AVAILABLE && item.sourceAnchors.isEmpty()) invalid()
        item.cautions.forEach { if (it.isBlank() || it.countCodePoints() > MAX_ITEM_CODE_POINTS) invalid() }
        item.sourceAnchors.forEach(::validateAnchorStructure)
        return item.text.countCodePoints() + item.cautions.sumOf { it.countCodePoints() }
    }

    private fun validateAnchorStructure(anchor: ActionGuideSourceAnchor) {
        if (anchor.quote.isBlank() || anchor.quote.countCodePoints() > MAX_ANCHOR_QUOTE_CODE_POINTS) invalid()
        if (anchor.sourceUnitIndexes.isEmpty()) invalid()
        if (anchor.sourceUnitIndexes != anchor.sourceUnitIndexes.distinct().sorted()) invalid()
    }

    private fun validateSectionAgainstSource(
        section: ActionGuideSection,
        sourceUnits: List<String>,
    ) {
        section.items.forEach { item ->
            item.sourceAnchors.forEach { validateAnchor(it, sourceUnits) }
            if (section.status == ActionGuideSectionStatus.AVAILABLE) validateAvailableFacts(item)
        }
    }

    private fun validateAvailableFacts(item: ActionGuideItem) {
        val evidence = item.sourceAnchors.joinToString("\n") { it.quote }
        val userText = (listOf(item.text) + item.cautions).joinToString("\n")
        if (findMissingFacts(userText, evidence).isNotEmpty()) invalid()
    }

    private fun validateAnchor(
        anchor: ActionGuideSourceAnchor,
        sourceUnits: List<String>,
    ) {
        if (anchor.sourceUnitIndexes.any { it !in sourceUnits.indices }) invalid()
        val selectedUnits = anchor.sourceUnitIndexes.map(sourceUnits::get)
        val repeatedQuote = selectedUnits.all { anchor.quote in it }
        val spanningQuote =
            anchor.sourceUnitIndexes.zipWithNext().all { (left, right) -> right == left + 1 } &&
                anchor.quote in selectedUnits.joinToString("\n")
        if (!repeatedQuote && !spanningQuote) invalid()
    }

    /** 기계적으로 연결 여부만 검사한다. 어떤 예외가 어떤 행동에 해당하는지는 사람의 검수가 필요하다. */
    private fun validateExceptionCautions(candidate: ActionGuideCandidate) {
        val exceptions = candidate.sections.single { it.kind == ActionGuideSectionKind.EXCEPTIONS }
        val steps = candidate.sections.single { it.kind == ActionGuideSectionKind.STEPS }
        if (exceptions.status == ActionGuideSectionStatus.AVAILABLE &&
            steps.status == ActionGuideSectionStatus.AVAILABLE &&
            steps.items.none { it.cautions.isNotEmpty() }
        ) {
            invalid()
        }
    }
}

private fun String.countCodePoints(): Int = codePointCount(0, length)

private fun invalid(): Nothing = throw InvalidInputException("행동 안내문 후보 검증에 실패했습니다.")
