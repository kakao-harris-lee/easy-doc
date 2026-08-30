package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException

/** DOCX(OOXML) 본문·표·머리글·바닥글을 **문서 순서대로** 뽑는다. */
internal class DocxExtractor {
    /** 이어 붙인 본문. */
    fun extract(data: ByteArray): String {
        val builder = ExtractedTextBuilder(SourceFormat.DOCX, data.size)
        collectInto(data, builder)
        return builder.build()
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

    /** OOXML 조각을 **문서 순서대로** 훑어 문단 단위 텍스트를 모은다. */
    private fun elementBlocks(
        root: Node,
        sink: BlockSink,
    ) {
        val current = StringBuilder()
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (OoxmlSkips.isAlternateContentFallback(node)) continue
            when (OoxmlDom.localName(node)) {
                "p" -> {
                    sink.add(current.toString())
                    current.setLength(0)
                }

                "t" -> {
                    val text = OoxmlDom.leadingText(node)
                    // 붙이기 **전에** 예산을 묻는다 — 문단 하나가 통째로 거대한 입력을 막는다.
                    sink.ensureRoomFor(current.length + text.length)
                    current.append(text)
                }
            }
            // 자식을 역순으로 쌓아야 pop 순서가 문서 순서가 된다.
            OoxmlDom.childElements(node).asReversed().forEach(stack::addLast)
        }
        sink.add(current.toString())
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
    }
}
