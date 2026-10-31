package kr.easydoc.core.illustration.suggestion

/**
 * 비어 있지 않은 각 원문 줄이 Unicode Number(Nd·Nl·No), 공백, 좁게 정한 숫자 구분 기호만으로
 * 이루어지는지 검사한다. 일반적인 의미 분석은 하지 않는다.
 */
fun isNumericOnlySource(sourceUnits: List<String>): Boolean {
    val nonBlankUnits = sourceUnits.filter { it.isNotBlank() }
    return nonBlankUnits.isNotEmpty() && nonBlankUnits.all { it.hasOnlyNumericCharacters() }
}

private fun String.hasOnlyNumericCharacters(): Boolean =
    codePoints().allMatch { codePoint ->
        Character.getType(codePoint) in UNICODE_NUMBER_TYPES ||
            Character.isWhitespace(codePoint) ||
            Character.isSpaceChar(codePoint) ||
            codePoint in NUMERIC_SEPARATORS
    }

private val UNICODE_NUMBER_TYPES =
    setOf(
        Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt(),
    )

private val NUMERIC_SEPARATORS =
    setOf(
        ','.code,
        '.'.code,
        '-'.code,
        '/'.code,
        ':'.code,
        '‐'.code,
        '‑'.code,
        '‒'.code,
        '–'.code,
        '—'.code,
        '−'.code,
    )
