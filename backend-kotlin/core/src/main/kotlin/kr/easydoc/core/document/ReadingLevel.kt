package kr.easydoc.core.document

import kr.easydoc.core.exceptions.InvalidInputException

/** 쉬운 글의 목표 독해 수준. API와 DB에는 [wireName]만 저장한다. */
enum class ReadingLevel(val wireName: String) {
    GRADE_5_6("grade_5_6"),
    GRADE_3_4("grade_3_4"),
    ;

    companion object {
        fun ofWireName(value: String): ReadingLevel =
            entries.firstOrNull { it.wireName == value }
                ?: throw InvalidInputException("쉬운 글 수준은 grade_5_6 또는 grade_3_4여야 합니다")
    }
}
