package kr.easydoc.core.text

/**
 * XML 1.0 이 허용하지 않는 제어문자. 탭(`\x09`)·개행(`\x0A`)·복귀(`\x0D`)는 문서 구조를
 * 이루므로 남긴다.
 */
private val CONTROL_CHARS = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]")

/** XML 에 담을 수 없는 제어문자를 제거한다 (탭·개행·복귀는 유지). */
fun stripControlChars(text: String): String = CONTROL_CHARS.replace(text, "")

/**
 * 저장 경계에서 개행을 `\n` 하나로 통일한다.
 *
 * `core.segment.splitUnits` 는 `\n` 만으로 줄을 가른다 — 그 왕복 불변식
 * (`joinUnits(splitUnits(x)) == x`)을 지키려고 `splitUnits` 자체는 `\r` 을 다루지 않는다.
 * CRLF 문서(`\r\n`)나 옛 Mac 방식(단독 `\r`)이 그대로 저장되면 각 줄 끝에 `\r` 이 남아
 * `checkStyle` 문장 길이, `alignSegments` 앵커, `compliantSourceUnits`, 화면에 보이는
 * `segment_map` 으로 새 나간다 — 이 함수는 그 문제를 `splitUnits` 가 보기 **전**, 저장이 되는
 * 입구에서 끊는다.
 *
 * `\r\n` 을 먼저 접어야 한다 — 순서를 바꾸면(단독 `\r` 을 먼저 바꾸면) `\r\n` 이 `\n\n` 으로
 * 늘어나 원문에 없던 빈 줄이 생긴다.
 */
fun normalizeLineEndings(text: String): String = text.replace("\r\n", "\n").replace("\r", "\n")
