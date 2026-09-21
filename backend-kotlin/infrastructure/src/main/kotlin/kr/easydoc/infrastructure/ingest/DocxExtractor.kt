package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.segment.UnitKind
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException

/** DOCX(OOXML) 본문·표·머리글·바닥글을 **문서 순서대로** 뽑는다. */
internal class DocxExtractor : StructuredTextExtractor {
    override val format: SourceFormat = SourceFormat.DOCX

    /** 이어 붙인 본문. */
    fun extract(data: ByteArray): String = extractStructured(data).text

    /**
     * 이어 붙인 본문과, 그 줄마다 하나씩 붙은 원본 단위 종류(표·목록 구조 힌트 계획 §1.2 표) —
     * 표 칸(`w:tc` 조상)은 [UnitKind.TABLE_CELL], 목록 문단(`w:pPr/w:numPr`)은
     * [UnitKind.LIST_ITEM], 그 외는 [UnitKind.BODY]다. DOCX 는 구조 신호가 XML 에 직접
     * 있으므로 [kr.easydoc.core.segment.inferUnitKinds] 텍스트 휴리스틱을 더 얹지 않는다.
     */
    override fun extractStructured(data: ByteArray): ExtractionOutcome {
        val builder = ExtractedTextBuilder(SourceFormat.DOCX, data.size)
        val tables = DomTableCollector()
        collectInto(data, builder, tables)
        return ExtractionOutcome(builder.build(), builder.structure(), tables.finish())
    }

    /** 정규화 **이전**의 블록 목록. */
    fun blocks(data: ByteArray): List<String> {
        val collected = BlockList(SourceFormat.DOCX, data.size)
        collectInto(data, collected)
        return collected.blocks
    }

    /** 문서를 열어 블록을 [sink] 로 흘려보낸다. */
    @Suppress("TooGenericExceptionCaught")
    private fun collectInto(
        data: ByteArray,
        sink: BlockSink,
        tables: DomTableCollector? = null,
    ) {
        // `PdfExtractor.guarded` 와 같은 모양이다 — 잡은 예외를 값으로 받아 **한 자리에서**
        // 던진다. 갈래마다 던지면 "어떤 예외가 어떤 문구가 되는가"가 흩어진다.
        val failure: Throwable =
            try {
                XWPFDocument(ByteArrayInputStream(data)).use { document -> collect(document, sink, tables) }
                return
            } catch (cause: DocumentExtractionException) {
                // 우리 예외(길이 상한)는 **변환하지 않는다.** 아래 `RuntimeException` 갈래가
                // 먼저 잡으면 "파일이 손상되었습니다"로 둔갑해 사용자가 취할 조치가 달라진다.
                throw cause
            } catch (cause: IOException) {
                cause
            } catch (cause: RuntimeException) {
                // POI 는 `POIXMLException`·`EmptyFileException`·`NotOfficeXmlFileException` 등
                // 비검사 예외로도 실패한다. 원본이 `except Exception` 으로 잡던 자리와 같다.
                cause
            }
        throw broken(data.size, failure)
    }

    private fun collect(
        document: XWPFDocument,
        sink: BlockSink,
        tables: DomTableCollector? = null,
    ) {
        val body = document.document.body.domNode
        elementBlocks(body, sink, tables)
        // 파트 해석은 `DocxSectionParts` 가 소유한다 — 반영(내보내기) 쪽이 같은 순서를 봐야 한다.
        for (part in DocxSectionParts.headerFooterParts(document, body)) elementBlocks(part, sink, tables)
    }

    /**
     * OOXML 조각을 **문서 순서대로** 훑어 문단 단위 텍스트를 모은다.
     *
     * 표 칸 여부는 `TextUnitWalk`(`infrastructure/export`)의 `inHeaderFooter` 프레임과 같은
     * 요령으로 스택 프레임에 실어 내려보낸다 — `w:tc` 조상 여부는 DOM 을 걷는 동안만 알 수
     * 있는 값이라 블록이 끊기는 시점(다음 `p`)에 이미 사라져 있으면 안 되기 때문이다.
     */
    private fun elementBlocks(
        root: Node,
        sink: BlockSink,
        tables: DomTableCollector? = null,
    ) {
        val state = DocxBlockState(sink, tables)
        val stack = ArrayDeque<DocxFrame>()
        stack.addLast(DocxFrame(root, inCell = false))
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            val node = frame.node
            if (OoxmlSkips.isAlternateContentFallback(node)) continue
            val name = OoxmlDom.localName(node)
            if (name == TABLE_ELEMENT) tables?.startTable(node, frame.cell)
            val directCell = tables?.cellFor(node)
            val cell = directCell ?: frame.cell
            val inCell = frame.inCell || name == TABLE_CELL_ELEMENT
            state.process(node, inCell, cell)
            // 자식을 역순으로 쌓아야 pop 순서가 문서 순서가 된다.
            OoxmlDom.childElements(node).asReversed().forEach { stack.addLast(DocxFrame(it, inCell, cell)) }
        }
        state.flush()
    }

    private fun broken(
        uploadSize: Int,
        cause: Throwable,
    ): DocumentExtractionException {
        ExtractionFailureLog.recordCause(SourceFormat.DOCX, uploadSize, cause)
        return DocumentExtractionException(ExtractionMessages.broken(SourceFormat.DOCX))
    }

    companion object {
        /** **걷지 않는다고 선언한 것** (계획 §9 질문 ⑩ — DOC-02 「조용한 누락 금지」). */
        val SKIPPED_PARTS: List<String> =
            listOf(
                "머리글/바닥글 중 w:type=even (짝수 쪽 전용)",
                "머리글/바닥글 중 w:type=first (첫 쪽 전용)",
                "각주(footnotes.xml)",
                "미주(endnotes.xml)",
                "주석(comments.xml)",
                "변경 추적의 삭제문(w:delText)",
                "mc:AlternateContent 의 mc:Fallback 가지",
            )

        /** 표 칸 요소(`w:tc`) — 이 조상 안의 문단은 [UnitKind.TABLE_CELL] 이다. */
        private const val TABLE_CELL_ELEMENT = "tc"

        private const val TABLE_ELEMENT = "tbl"
    }
}

private class DocxFrame(
    val node: Node,
    val inCell: Boolean,
    val cell: MutableTableCell? = null,
)

/** Preserve the existing paragraph boundaries while remembering each paragraph's owning cell. */
private class DocxBlockState(
    private val sink: BlockSink,
    private val tables: DomTableCollector?,
) {
    private val current = StringBuilder()
    private var kind = UnitKind.BODY
    private var pendingCell: MutableTableCell? = null

    fun process(
        node: Node,
        inCell: Boolean,
        cell: MutableTableCell?,
    ) {
        when (OoxmlDom.localName(node)) {
            "p" -> {
                flush()
                kind = paragraphKind(node, inCell)
                pendingCell = cell
            }

            "t" -> {
                val text = OoxmlDom.leadingText(node)
                if (pendingCell !== cell && text.isNotBlank()) tables?.markCoordinatesLost(pendingCell)
                sink.ensureRoomFor(current.length + text.length)
                current.append(text)
            }
        }
    }

    fun flush() {
        val block = current.toString()
        val indexes = sink.add(block, kind)
        tables?.attach(pendingCell, indexes, block)
        current.setLength(0)
    }
}

private fun paragraphKind(
    paragraph: Node,
    inCell: Boolean,
): UnitKind =
    when {
        inCell -> {
            UnitKind.TABLE_CELL
        }

        OoxmlDom
            .childElements(paragraph)
            .firstOrNull { OoxmlDom.localName(it) == "pPr" }
            ?.let { properties ->
                OoxmlDom.childElements(properties).any { OoxmlDom.localName(it) == "numPr" }
            } == true -> {
            UnitKind.LIST_ITEM
        }

        else -> {
            UnitKind.BODY
        }
    }
