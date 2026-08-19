package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFHeaderFooter
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * DOCX(OOXML) 본문·표·머리글·바닥글을 **문서 순서대로** 뽑는다.
 *
 * 원본: `app/ingest/extractors.py::_extract_docx`·`_docx_blocks`·`_element_blocks`.
 *
 * ## POI usermodel 을 쓰지 않는다 (spike S-1·S-2)
 *
 * `XWPFDocument.getParagraphs()`/`getTables()` 는 python-docx 의 `paragraphs`/`tables` 와
 * 같은 한계를 갖는다 — 표가 문서 끝으로 밀리고, 텍스트박스·SDT·중첩 표가 보이지 않는다.
 * 머리글도 마찬가지다: `getHeaderList()` 는 **파트 목록**이라 "머리글 전부 → 바닥글 전부"
 * 순서가 되어 "구역별 (머리글, 바닥글)" 순서와 어긋난다.
 *
 * 그래서 `getDocument().getBody()` 의 **DOM 부터 직접 순회**하고, 머리글·바닥글은
 * `w:sectPr` 의 `headerReference/footerReference[@w:type="default"]` 를 `r:id` 로 풀어 붙인다.
 * POI 를 버리는 것이 아니라 **다른 층위를 쓴다** — OPC 패키지 해석·관계 해석·DOCTYPE 차단은
 * 그대로 POI 가 한다.
 *
 * ## 걷지 **않는** 것 (조용한 누락이 아니라 선언된 한계다)
 *
 * [SKIPPED_PARTS] 가 그 목록이며, 원본의 한계와 같다. 쉬운 글 변환 입력으로는 본문이
 * 핵심이라 감수한다. 목록을 코드의 상수로 두는 이유는 산출물·테스트와 대조하기 위해서다.
 */
internal class DocxExtractor {
    /** 이어 붙인 본문. */
    fun extract(data: ByteArray): String {
        val builder = ExtractedTextBuilder(SourceFormat.DOCX, data.size)
        blocks(data).forEach(builder::add)
        return builder.build()
    }

    /**
     * 정규화 **이전**의 블록 목록.
     *
     * spike 가 Python 과 대조한 단위가 이것이다 — `_join_blocks` 뒤에 대조하면 정규화가
     * 차이를 덮는다. 회귀 테스트가 `repo-fixtures-oracle.json` 의 `_raw_docx_blocks` 와
     * 이 값을 비교한다.
     */
    @Suppress("TooGenericExceptionCaught")
    fun blocks(data: ByteArray): List<String> =
        try {
            XWPFDocument(ByteArrayInputStream(data)).use { document -> collect(document) }
        } catch (cause: IOException) {
            throw broken(data.size, cause)
        } catch (cause: RuntimeException) {
            // POI 는 `POIXMLException`·`EmptyFileException`·`NotOfficeXmlFileException` 등
            // 비검사 예외로도 실패한다. 원본이 `except Exception` 으로 잡던 자리와 같다.
            throw broken(data.size, cause)
        }

    private fun collect(document: XWPFDocument): List<String> {
        val sink = mutableListOf<String>()
        val body = document.document.body.domNode
        elementBlocks(body, sink)
        for (sectionProperties in sectionPropertiesInDocumentOrder(body)) {
            for (reference in HEADER_FOOTER_ORDER) {
                val part = referencedPart(document, sectionProperties, reference) ?: continue
                elementBlocks(part, sink)
            }
        }
        return sink
    }

    /**
     * OOXML 조각을 **문서 순서대로** 훑어 문단 단위 텍스트를 모은다.
     *
     * `w:p` 를 만나면 블록을 끊고 `w:t` 의 문자를 모은다. 문단·표를 각각 순회하는 대신
     * XML 을 직접 훑는 이유:
     *
     * - 표가 본문 안 **제자리**에 남는다(문단을 먼저 모으면 표가 문서 끝으로 밀린다).
     * - 텍스트박스(`w:txbxContent`)·중첩 표·SDT 안의 문단도 딸려 온다.
     * - 변경 추적에서 **삽입문(`w:ins`)은 포함되고 삭제문은 빠진다** — 삭제된 글자는
     *   `w:t` 가 아니라 `w:delText` 에 담기므로 태그 이름만으로 자연히 갈린다.
     *
     * 네임스페이스 URI 가 아니라 **로컬 이름**으로 판별해 `a:t`(도형)·`m:t`(수식)까지 걷는다.
     *
     * 재귀가 아니라 스택으로 내려가는 이유는 [MARKUP_COMPATIBILITY_NAMESPACE] 의 `Fallback`
     * 에서 **하강을 멈추기** 위해서다.
     */
    private fun elementBlocks(
        root: Node,
        sink: MutableList<String>,
    ) {
        val current = StringBuilder()
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (isAlternateContentFallback(node)) continue
            when (OoxmlDom.localName(node)) {
                "p" -> {
                    sink += current.toString()
                    current.setLength(0)
                }

                "t" -> {
                    current.append(OoxmlDom.leadingText(node))
                }
            }
            // 자식을 역순으로 쌓아야 pop 순서가 문서 순서가 된다.
            OoxmlDom.childElements(node).asReversed().forEach(stack::addLast)
        }
        sink += current.toString()
    }

    /**
     * `mc:AlternateContent` 의 `mc:Fallback` 인가 — **네임스페이스까지 본다.**
     *
     * 워드 2010+ 는 텍스트박스 하나를 `mc:Choice`(DrawingML)와 `mc:Fallback`(VML) 두 벌로
     * 저장한다. 양쪽을 다 걷으면 같은 문구가 정확히 두 번 나와 크레딧이 두 배로 청구되고
     * 프롬프트·마스킹 결과까지 오염된다. 규격상 `mc:AlternateContent` 에는 `mc:Choice` 가
     * 최소 하나 있으므로 Fallback 을 버려도 내용이 사라지지 않는다.
     *
     * **원본과 다른 지점**(계획 §9 질문 ⑫): 원본은 로컬 이름만 보아 `*:Fallback` 이면
     * 무엇이든 잘랐다. 목적은 `mc:AlternateContent` 의 이중 수집 방지 하나이고, 그 범위를
     * 넘는 절단은 **조용한 누락**이라 네임스페이스를 확인해 좁혔다.
     */
    private fun isAlternateContentFallback(node: Node): Boolean =
        OoxmlDom.localName(node) == "Fallback" && node.namespaceURI == MARKUP_COMPATIBILITY_NAMESPACE

    /**
     * 구역 속성(`w:sectPr`)을 문서 순서로 모은다.
     *
     * python-docx `document.sections` 와 같은 자리만 본다 — `body/sectPr`(마지막 구역)과
     * `body/p/pPr/sectPr`(구역 나눔). 본문 깊숙한 곳의 동명 요소를 끌어오지 않는다.
     */
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

    /**
     * `headerReference`/`footerReference` 중 `w:type="default"` 하나를 풀어 그 파트의 DOM 을 준다.
     *
     * 참조가 **없으면 앞 구역을 물려받은 것**이다(python-docx `is_linked_to_previous`).
     * 물려받은 쪽까지 걷으면 같은 문구가 구역 수만큼 반복되므로 `null` 을 돌려 건너뛴다.
     * `even`/`first` 전용 머리글은 [SKIPPED_PARTS] 대로 걷지 않는다.
     */
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

        /**
         * **걷지 않는다고 선언한 것** (계획 §9 질문 ⑩ — DOC-02 「조용한 누락 금지」).
         *
         * "문서화했다"는 자동 게이트가 아니므로 목록을 코드에 두고, 회귀 테스트가 이 목록과
         * 산출물·동작을 대조한다. 여기서 항목을 빼면 그 요소를 걷기 시작했다는 뜻이어야 한다.
         */
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
