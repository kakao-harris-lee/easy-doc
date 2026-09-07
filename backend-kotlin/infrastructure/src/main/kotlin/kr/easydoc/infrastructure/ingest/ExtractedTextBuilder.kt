package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.segment.SourceStructure
import kr.easydoc.core.segment.UnitKind

/** 블록(문단 · 표 셀 · 페이지 · 구역) 텍스트를 정규화해 개행 하나로 잇는다. */
internal class ExtractedTextBuilder(
    private val format: SourceFormat,
    private val uploadSize: Int,
) : BlockSink {
    private val builder = StringBuilder()

    /**
     * 유지되는 줄마다 하나씩 쌓는 종류 — [add] 가 버리는 빈 줄은 여기 들어오지 않으므로
     * 크기가 언제나 [build] 결과의 줄 수와 같다(표·목록 구조 힌트 계획 §1.2).
     */
    private val kinds = mutableListOf<UnitKind>()

    /** 블록 하나를 더한다. 줄 단위로 좌우 공백을 털고 빈 줄을 버린다. 남는 줄마다 [kind] 를 붙인다. */
    override fun add(
        block: String,
        kind: UnitKind,
    ) {
        for (line in block.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(trimmed)
            kinds += kind
            ensureWithinLimit(builder.length.toLong())
        }
    }

    /**
     * 아직 블록이 되지 못한 **조립 중 조각**이 [pendingChars] 자까지 자랐다 —
     * 확정 결과와 합쳐 상한을 넘으면 여기서 끊는다.
     */
    override fun ensureRoomFor(pendingChars: Int) {
        // `Int` 로 더하면 넘칠 수 있다 — 조각 길이는 파서가 주는 값이라 상한이 없다.
        ensureWithinLimit(builder.length.toLong() + pendingChars)
    }

    /** 이어 붙인 결과. */
    fun build(): String = builder.toString()

    /** [build] 결과의 줄마다 하나씩 붙은 종류 — 크기가 [build] 결과의 줄 수와 같다. */
    fun structure(): SourceStructure = SourceStructure(kinds.toList())

    private fun ensureWithinLimit(measured: Long) {
        if (measured <= MAX_EXTRACTED_CHARS) return
        ExtractionFailureLog.record(format, uploadSize, "extracted_too_long")
        throw DocumentExtractionException(ExtractionMessages.EXTRACTED_TOO_LONG)
    }
}

/**
 * 이어 붙은 본문과, 그 줄마다 하나씩 붙은 [SourceStructure] — [ExtractedTextBuilder.build] +
 * [ExtractedTextBuilder.structure] 를 한 값으로 묶는다(표·목록 구조 힌트 계획 §1.2).
 */
internal class ExtractionOutcome(
    val text: String,
    val structure: SourceStructure,
) {
    /** 길이만 남긴다. 본문은 나가지 않는다. */
    override fun toString(): String = "ExtractionOutcome(${text.length}자, $structure)"
}

/** 블록을 받아 가는 곳. 파서는 **다 모은 뒤 넘기지 않고** 이 포트로 흘려보낸다. */
internal interface BlockSink {
    /** [kind] 기본값은 [UnitKind.BODY] — 구조를 모르는 호출자는 종전처럼 한 인자로 부른다. */
    fun add(
        block: String,
        kind: UnitKind = UnitKind.BODY,
    )

    fun ensureRoomFor(pendingChars: Int)
}

/**
 * 블록을 **정규화하지 않고** 목록으로 모으는 sink. 원본과 블록 단위로 대조하는 자리에서 쓴다
 * (`DocxExtractor.blocks` → `repo-fixtures-oracle.json` 의 `_raw_docx_blocks`).
 */
internal class BlockList(
    private val format: SourceFormat,
    private val uploadSize: Int,
) : BlockSink {
    private val collected = mutableListOf<String>()
    private var totalChars = 0L

    val blocks: List<String> get() = collected

    /** 종류는 받지 않는다 — 이 sink 는 원본과 블록 단위로 대조하는 오라클이고 구조를 재지 않는다. */
    override fun add(
        block: String,
        kind: UnitKind,
    ) {
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
