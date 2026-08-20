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
 * ## 블록이 되기 **전**의 조각도 이 상한이 진다 (게이트 27 지적 ②)
 *
 * 위 문단은 이 클래스 안에서만 참이었다. 파서 쪽은 문단 하나를 `StringBuilder` 에 통째로
 * 모은 **뒤에야** [add] 를 불렀으므로, "구역 하나 = 문단 하나"인 입력에서는 전량이 먼저 힙에
 * 올라왔다 — 위 문단이 **원본의 결함으로 기술한 바로 그 형태**가 한 층 위에 남아 있었다.
 *
 * 그래서 파서가 조각을 덧붙이기 전에 [ensureRoomFor] 를 부른다. 판정 기준은 하나다 —
 * **「지금까지 확정된 결과 + 조립 중인 조각」이 [MAX_EXTRACTED_CHARS] 를 넘으면 끊는다.**
 *
 * **갈리는 자리(정직하게 적는다)**: 확정 결과는 줄 단위로 다듬은 값이고 조립 중 조각은
 * 아직 다듬기 전이라, **공백만 잔뜩 든 거대한 문단**은 예전 판이 받아들이던 것을 지금은
 * 거절한다. 넘어가는 것이 아니라 더 이르게 끊는 방향이고(fail-closed), 그런 입력은 실제로
 * "500,000자를 넘는 문서"가 맞다. 이 갈림은 `04_kotlin-implementer_documents-plan.md`
 * §9.2-quater 에 기록했다.
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
) : BlockSink {
    private val builder = StringBuilder()

    /** 블록 하나를 더한다. 줄 단위로 좌우 공백을 털고 빈 줄을 버린다. */
    override fun add(block: String) {
        for (line in block.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(trimmed)
            ensureWithinLimit(builder.length.toLong())
        }
    }

    /**
     * 아직 블록이 되지 못한 **조립 중 조각**이 [pendingChars] 자까지 자랐다 —
     * 확정 결과와 합쳐 상한을 넘으면 여기서 끊는다.
     *
     * 파서가 문자를 덧붙이기 **전에** 부른다. 붙인 뒤에 부르면 그 한 번의 할당이 이미
     * 일어난 뒤라, 문단 하나가 통째로 거대한 입력에서는 막지 못한다.
     */
    override fun ensureRoomFor(pendingChars: Int) {
        // `Int` 로 더하면 넘칠 수 있다 — 조각 길이는 파서가 주는 값이라 상한이 없다.
        ensureWithinLimit(builder.length.toLong() + pendingChars)
    }

    /** 이어 붙인 결과. */
    fun build(): String = builder.toString()

    private fun ensureWithinLimit(measured: Long) {
        if (measured <= MAX_EXTRACTED_CHARS) return
        ExtractionFailureLog.record(format, uploadSize, "extracted_too_long")
        throw DocumentExtractionException(ExtractionMessages.EXTRACTED_TOO_LONG)
    }
}

/**
 * 블록을 받아 가는 곳. 파서는 **다 모은 뒤 넘기지 않고** 이 포트로 흘려보낸다.
 *
 * 두 메서드가 하는 일이 다르다.
 *
 * - [add] — 블록 하나가 **완성됐다**. 정규화와 확정은 받는 쪽이 한다.
 * - [ensureRoomFor] — 아직 완성되지 않은 조각이 이만큼 자랐다. 받는 쪽이 예산을 넘겼다고
 *   판단하면 여기서 예외로 끊는다.
 *
 * 이 포트가 있는 이유는 **예산을 아는 구현과 모르는 구현을 가르기 위해서**가 아니라, 파서가
 * 「전부 만든 뒤 넘기는」 모양을 갖지 못하게 하기 위해서다 — 그 모양이 게이트 27 지적 ② 의
 * 결함이었다. 구현은 오늘 [ExtractedTextBuilder] 와 [BlockList] 둘이고 **둘 다 같은 예산을
 * 진다.**
 */
internal interface BlockSink {
    fun add(block: String)

    fun ensureRoomFor(pendingChars: Int)
}

/**
 * 블록을 **정규화하지 않고** 목록으로 모으는 sink. 원본과 블록 단위로 대조하는 자리에서 쓴다
 * (`DocxExtractor.blocks` → `repo-fixtures-oracle.json` 의 `_raw_docx_blocks`).
 *
 * 정규화를 하지 않을 뿐 **예산은 똑같이 진다.** 예산 없는 sink 를 하나라도 두면 그것이
 * 무제한 경로가 되고, 그런 경로는 언제나 「테스트 전용」이라는 이름으로 시작한다.
 */
internal class BlockList(
    private val format: SourceFormat,
    private val uploadSize: Int,
) : BlockSink {
    private val collected = mutableListOf<String>()
    private var totalChars = 0L

    val blocks: List<String> get() = collected

    override fun add(block: String) {
        ensureRoomFor(block.length)
        totalChars += block.length
        collected += block
    }

    override fun ensureRoomFor(pendingChars: Int) {
        if (totalChars + pendingChars <= MAX_EXTRACTED_CHARS) return
        ExtractionFailureLog.record(format, uploadSize, "extracted_too_long")
        throw DocumentExtractionException(ExtractionMessages.EXTRACTED_TOO_LONG)
    }
}
