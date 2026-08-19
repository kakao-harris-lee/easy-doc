package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException

/**
 * 블록(문단 · 표 셀 · 페이지 · 구역) 텍스트를 정규화해 개행 하나로 잇는다.
 *
 * 원본: `app/ingest/extractors.py::_join_blocks` + `_ensure_extracted_length`.
 *
 * 결과의 모양은 형식과 무관하게 하나다 — **공백뿐인 줄 없이, 블록이 개행 하나로 이어진
 * 텍스트**. 후속 단계(마스킹·프롬프트)가 형식별 분기 없이 같은 입력을 받게 하기 위해서다.
 *
 * ## 상한을 **누적 중에** 건다 (원본과 다른 지점 — 계획 §5 D-4)
 *
 * 원본은 전부 이어 붙인 **뒤에** 길이를 쟀다(`_ensure_extracted_length`). 그 시점에는 이미
 * 수백만 자가 힙에 올라와 있어, 상한은 "거절"만 하고 "소모"는 막지 못한다. 여기서는 줄을
 * 붙일 때마다 누적 길이를 보고 넘는 즉시 끊는다 — 재는 대상(이어 붙인 결과의 길이)은 같고
 * 발화 시점만 앞당긴 것이라 **같은 입력에 같은 판정**이 나온다.
 *
 * ## 줄 나눔·공백 판정이 Python 과 갈리는 자리 (기록)
 *
 * - `str.splitlines()` 는 `\v`·`\f`·`\x1c`~`\x1e`·`\u0085`·`\u2028`·`\u2029` 에서도 줄을
 *   나눈다. Kotlin `lineSequence()` 는 `\r\n`·`\n`·`\r` 셋뿐이다.
 * - `str.strip()` 은 `str.isspace()` 가 참인 문자를 턴다(`\u00A0` 포함). Kotlin `trim()` 은
 *   `Char.isWhitespace()` 기준이라 **`\u00A0` 를 남긴다**.
 *
 * 둘 다 좁은 쪽(Kotlin)을 골랐다. 요구(DOC-01)는 *"공백뿐인 줄 없이 개행 하나로 이어진
 * 텍스트"* 이고, 줄바꿈이 아닌 제어문자에서 줄을 나누는 것도 줄바꿈 없는 공백(`\u00A0`)을
 * 지우는 것도 그 요구가 시키는 일이 아니다. 갈림 자체는 산출물에 남긴다.
 */
internal class ExtractedTextBuilder(
    private val format: SourceFormat,
    private val uploadSize: Int,
) {
    private val builder = StringBuilder()

    /** 블록 하나를 더한다. 줄 단위로 좌우 공백을 털고 빈 줄을 버린다. */
    fun add(block: String) {
        for (line in block.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(trimmed)
            ensureWithinLimit()
        }
    }

    /** 이어 붙인 결과. */
    fun build(): String = builder.toString()

    private fun ensureWithinLimit() {
        if (builder.length <= MAX_EXTRACTED_CHARS) return
        ExtractionFailureLog.record(format, uploadSize, "extracted_too_long")
        throw DocumentExtractionException(ExtractionMessages.EXTRACTED_TOO_LONG)
    }
}
