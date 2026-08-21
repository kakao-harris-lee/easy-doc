package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFHeaderFooter
import org.w3c.dom.Element
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
        for (sectionProperties in sectionPropertiesInDocumentOrder(body)) {
            for (reference in HEADER_FOOTER_ORDER) {
                val part = referencedPart(document, sectionProperties, reference) ?: continue
                elementBlocks(part, sink)
            }
        }
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
            if (isAlternateContentFallback(node)) continue
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

    /** `mc:AlternateContent` 의 `mc:Fallback` 인가 — **네임스페이스까지 본다.** */
    private fun isAlternateContentFallback(node: Node): Boolean =
        OoxmlDom.localName(node) == "Fallback" && node.namespaceURI == MARKUP_COMPATIBILITY_NAMESPACE

    /** 구역 속성(`w:sectPr`)을 문서 순서로 모은다. */
    private fun sectionPropertiesInDocumentOrder(body: Node): List<Element> {
        val found = mutableListOf<Element>()
        for (child in OoxmlDom.childElements(body)) {
            when (OoxmlDom.localName(child)) {
                "sectPr" -> {
                    found += child
                }

                "p" -> {
                    OoxmlDom
                        .childElements(child)
                        .firstOrNull { OoxmlDom.localName(it) == "pPr" }
                        ?.let(::sectionPropertiesOf)
                        ?.let { found += it }
                }
            }
        }
        return found
    }

    /** `w:pPr` 안의 `w:sectPr`. 구역 나눔이 문단 속성에 실리는 자리다. */
    private fun sectionPropertiesOf(paragraphProperties: Element): Element? =
        OoxmlDom.childElements(paragraphProperties).firstOrNull { OoxmlDom.localName(it) == "sectPr" }

    /** `headerReference`/`footerReference` 중 `w:type="default"` 하나를 풀어 그 파트의 DOM 을 준다. */
    private fun referencedPart(
        document: XWPFDocument,
        sectionProperties: Element,
        referenceName: String,
    ): Node? {
        val relationId =
            OoxmlDom
                .childElements(sectionProperties)
                .firstOrNull {
                    OoxmlDom.localName(it) == referenceName &&
                        it.getAttributeNS(WORDPROCESSING_NAMESPACE, "type") == DEFAULT_REFERENCE_TYPE
                }?.getAttributeNS(RELATIONSHIP_NAMESPACE, "id")
                ?.takeIf { it.isNotEmpty() }
                ?: return null
        return (document.getRelationById(relationId) as? XWPFHeaderFooter)?._getHdrFtr()?.domNode
    }

    private fun broken(
        uploadSize: Int,
        cause: Throwable,
    ): DocumentExtractionException {
        ExtractionFailureLog.recordCause(SourceFormat.DOCX, uploadSize, cause)
        return DocumentExtractionException(ExtractionMessages.broken(SourceFormat.DOCX))
    }

    companion object {
        /** `w:sectPr` 안에서 우리가 인정하는 참조 유형. `even`/`first` 는 걷지 않는다. */
        const val DEFAULT_REFERENCE_TYPE: String = "default"

        private const val WORDPROCESSING_NAMESPACE =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        private const val RELATIONSHIP_NAMESPACE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val MARKUP_COMPATIBILITY_NAMESPACE =
            "http://schemas.openxmlformats.org/markup-compatibility/2006"

        /** 구역마다 머리글 → 바닥글 순서. 원본 `for part in (section.header, section.footer)` 와 같다. */
        private val HEADER_FOOTER_ORDER = listOf("headerReference", "footerReference")

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
