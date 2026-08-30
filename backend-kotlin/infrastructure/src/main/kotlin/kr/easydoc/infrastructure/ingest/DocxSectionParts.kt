package kr.easydoc.infrastructure.ingest

import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFHeaderFooter
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * DOCX 의 **구역 → 머리글·바닥글 파트** 해석. 추출(`DocxExtractor`)과 반영
 * (`export/DocxOriginalReflector`) 이 이 한 곳을 함께 쓴다.
 *
 * 나뉘어 있으면 두 쪽의 순서가 조용히 갈린다 — 추출이 본 파트와 반영이 세는 파트가 다르면
 * 검수본 문단이 한 칸씩 밀린 채 원본에 들어가고, 그 어긋남은 파일을 열어 보기 전까지 아무
 * 신호도 내지 않는다.
 */
internal object DocxSectionParts {
    /** 구역마다 머리글 → 바닥글 순서. 원본 `for part in (section.header, section.footer)` 와 같다. */
    private val HEADER_FOOTER_ORDER = listOf("headerReference", "footerReference")

    /** `w:sectPr` 안에서 우리가 인정하는 참조 유형. `even`/`first` 는 걷지 않는다. */
    const val DEFAULT_REFERENCE_TYPE: String = "default"

    private const val WORDPROCESSING_NAMESPACE =
        "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val RELATIONSHIP_NAMESPACE =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** 본문에 딸린 머리글·바닥글 파트의 DOM 을 **문서 순서대로** 준다. */
    fun headerFooterParts(
        document: XWPFDocument,
        body: Node,
    ): List<Node> =
        buildList {
            for (sectionProperties in sectionPropertiesInDocumentOrder(body)) {
                for (reference in HEADER_FOOTER_ORDER) {
                    referencedPart(document, sectionProperties, reference)?.let { add(it) }
                }
            }
        }

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
}
