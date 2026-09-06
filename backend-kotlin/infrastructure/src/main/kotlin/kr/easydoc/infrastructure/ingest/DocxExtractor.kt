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
internal class DocxExtractor {
    /** 이어 붙인 본문. */
    fun extract(data: ByteArray): String = extractStructured(data).text

    /**
     * 이어 붙인 본문과, 그 줄마다 하나씩 붙은 원본 단위 종류(표·목록 구조 힌트 계획 §1.2 표) —
     * 표 칸(`w:tc` 조상)은 [UnitKind.TABLE_CELL], 목록 문단(`w:pPr/w:numPr`)은
     * [UnitKind.LIST_ITEM], 그 외는 [UnitKind.BODY]다. DOCX 는 구조 신호가 XML 에 직접
     * 있으므로 [kr.easydoc.core.segment.inferUnitKinds] 텍스트 휴리스틱을 더 얹지 않는다.
     */
    fun extractStructured(data: ByteArray): ExtractionOutcome {
        val builder = ExtractedTextBuilder(SourceFormat.DOCX, data.size)
        collectInto(data, builder)
        return ExtractionOutcome(builder.build(), builder.structure())
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
    ) {
        // `PdfExtractor.guarded` 와 같은 모양이다 — 잡은 예외를 값으로 받아 **한 자리에서**
        // 던진다. 갈래마다 던지면 "어떤 예외가 어떤 문구가 되는가"가 흩어진다.
        val failure: Throwable =
            try {
                XWPFDocument(ByteArrayInputStream(data)).use { document -> collect(document, sink) }
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
    ) {
        val body = document.document.body.domNode
        elementBlocks(body, sink)
        // 파트 해석은 `DocxSectionParts` 가 소유한다 — 반영(내보내기) 쪽이 같은 순서를 봐야 한다.
        for (part in DocxSectionParts.headerFooterParts(document, body)) elementBlocks(part, sink)
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
    ) {
        val current = StringBuilder()
        var currentKind = UnitKind.BODY
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, inCell = false))
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            val node = frame.node
            if (OoxmlSkips.isAlternateContentFallback(node)) continue
            val inCell = frame.inCell || OoxmlDom.localName(node) == TABLE_CELL_ELEMENT
            when (OoxmlDom.localName(node)) {
                "p" -> {
                    sink.add(current.toString(), currentKind)
                    current.setLength(0)
                    currentKind = kindOf(node as Element, inCell)
                }

                "t" -> {
                    val text = OoxmlDom.leadingText(node)
                    // 붙이기 **전에** 예산을 묻는다 — 문단 하나가 통째로 거대한 입력을 막는다.
                    sink.ensureRoomFor(current.length + text.length)
                    current.append(text)
                }
            }
            // 자식을 역순으로 쌓아야 pop 순서가 문서 순서가 된다.
            OoxmlDom.childElements(node).asReversed().forEach { stack.addLast(Frame(it, inCell)) }
        }
        sink.add(current.toString(), currentKind)
    }

    /** [paragraph] 가 표 칸 안이면 [UnitKind.TABLE_CELL], `w:pPr/w:numPr` 가 있으면 [UnitKind.LIST_ITEM], 그 외 [UnitKind.BODY]. */
    private fun kindOf(
        paragraph: Element,
        inCell: Boolean,
    ): UnitKind =
        when {
            inCell -> UnitKind.TABLE_CELL
            hasNumberingProperties(paragraph) -> UnitKind.LIST_ITEM
            else -> UnitKind.BODY
        }

    /** `w:pPr` 의 자식으로 `w:numPr` 이 있는가 — 목록 문단의 OOXML 표시(계획 §1.2 표). */
    private fun hasNumberingProperties(paragraph: Element): Boolean {
        val paragraphProperties =
            OoxmlDom.childElements(paragraph).firstOrNull { OoxmlDom.localName(it) == PARAGRAPH_PROPERTIES_ELEMENT }
                ?: return false
        return OoxmlDom.childElements(paragraphProperties).any {
            OoxmlDom.localName(it) == NUMBERING_PROPERTIES_ELEMENT
        }
    }

    /** 스택 프레임 — [inCell] 은 이 노드가 `w:tc` 조상 안인가를, DOM 을 걷는 동안만 든다. */
    private class Frame(
        val node: Node,
        val inCell: Boolean,
    )

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

        /** 문단 속성 요소(`w:pPr`) — [NUMBERING_PROPERTIES_ELEMENT] 가 이 밑에 온다. */
        private const val PARAGRAPH_PROPERTIES_ELEMENT = "pPr"

        /** 번호 매김 속성 요소(`w:numPr`) — 목록 문단의 표시(계획 §1.2 표). */
        private const val NUMBERING_PROPERTIES_ELEMENT = "numPr"
    }
}
