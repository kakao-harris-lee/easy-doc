package kr.easydoc.core.easyread

/** URL 내부의 괄호 쌍은 유지하고, 주소를 감싼 닫는 괄호와 뒤의 본문은 제외한다. */
internal fun trimReferenceSuffix(text: String): String {
    var depth = 0
    for ((index, character) in text.withIndex()) {
        when (character) {
            '(' -> {
                depth++
            }

            ')' -> {
                if (depth == 0) return text.substring(0, index)
                depth--
            }
        }
    }
    return text
}
