package kr.easydoc.core.actionguide

import kr.easydoc.core.exceptions.InvalidInputException

/** JSON 후보 v1에서 허용하는 여섯 가지 섹션. 순서는 화면에서 쓰는 기본 순서다. */
enum class ActionGuideSectionKind(val wireName: String) {
    ELIGIBILITY("eligibility"),
    BENEFITS("benefits"),
    DOCUMENTS("documents"),
    STEPS("steps"),
    EXCEPTIONS("exceptions"),
    CONTACT("contact"),
    ;

    companion object {
        fun ofWireName(value: String): ActionGuideSectionKind =
            entries.firstOrNull { it.wireName == value }
                ?: throw InvalidInputException("알 수 없는 행동 안내문 섹션입니다.")
    }
}

enum class ActionGuideSectionStatus(val wireName: String) {
    AVAILABLE("available"),
    NOT_IN_SOURCE("not_in_source"),
    NEEDS_REVIEW("needs_review"),
    ;

    companion object {
        fun ofWireName(value: String): ActionGuideSectionStatus =
            entries.firstOrNull { it.wireName == value }
                ?: throw InvalidInputException("알 수 없는 행동 안내문 섹션 상태입니다.")
    }
}

/** 원문 인용도 본문으로 취급한다. 로그에 원문 조각을 내지 않도록 toString을 제한한다. */
data class ActionGuideSourceAnchor(
    val sourceUnitIndexes: List<Int>,
    val quote: String,
) {
    override fun toString(): String = "ActionGuideSourceAnchor(indexCount=${sourceUnitIndexes.size})"
}

data class ActionGuideItem(
    val text: String,
    val cautions: List<String>,
    val sourceAnchors: List<ActionGuideSourceAnchor>,
) {
    override fun toString(): String =
        "ActionGuideItem(cautionCount=${cautions.size}, anchorCount=${sourceAnchors.size})"
}

data class ActionGuideSection(
    val kind: ActionGuideSectionKind,
    val status: ActionGuideSectionStatus,
    val items: List<ActionGuideItem>,
) {
    override fun toString(): String = "ActionGuideSection(kind=$kind, status=$status, itemCount=${items.size})"
}

data class ActionGuideCandidate(
    val schemaVersion: Int,
    val sections: List<ActionGuideSection>,
) {
    override fun toString(): String =
        "ActionGuideCandidate(schemaVersion=$schemaVersion, sectionCount=${sections.size})"
}
