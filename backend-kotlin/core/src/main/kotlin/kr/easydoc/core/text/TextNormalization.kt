package kr.easydoc.core.text

/**
 * XML 1.0 이 허용하지 않는 제어문자. 탭(`\x09`)·개행(`\x0A`)·복귀(`\x0D`)는 문서 구조를
 * 이루므로 남긴다.
 */
private val CONTROL_CHARS = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]")

/** XML 에 담을 수 없는 제어문자를 제거한다 (탭·개행·복귀는 유지). */
fun stripControlChars(text: String): String = CONTROL_CHARS.replace(text, "")
