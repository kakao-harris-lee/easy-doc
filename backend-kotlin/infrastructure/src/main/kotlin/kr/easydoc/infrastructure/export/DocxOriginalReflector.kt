package kr.easydoc.infrastructure.export

import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.easyread.exportFileOf
import kr.easydoc.infrastructure.ingest.DocxSectionParts
import kr.easydoc.infrastructure.ingest.OoxmlDom
import kr.easydoc.infrastructure.ingest.OoxmlSkips
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.XMLConstants

/**
 * **원본 DOCX 를 고쳐서** 내보낸다 — 새 문서를 만들지 않는다.
 *
 * 원본 패키지를 통째로 열어 텍스트만 갈아 끼우고 다시 쓴다. 그래서 본문에 없던 것 — 이미지,
 * 표 테두리, 머리글·바닥글, 스타일, 쪽 나눔, 글꼴 표, 문서 속성 — 은 **손대지 않은 채 남는다.**
 * 그것이 §6.5 가 말하는 「서식 유지」의 실체다.
 *
 * 단위 순회는 `ingest/DocxExtractor` 와 같은 규칙이다: 같은 `Fallback` 건너뛰기
 * ([OoxmlSkips]), 같은 머리글·바닥글 파트 해석([DocxSectionParts]), 같은 자식 역순 스택.
 * 두 순회가 같은 차례를 낸다는 것은 `PackagedOriginalReflectorTest` 가 fixture 로 잰다.
 */
internal class DocxOriginalReflector {
    private val walk = TextUnitWalk(skip = OoxmlSkips::isAlternateContentFallback)

    /** 반영하면 무엇이 달라지는지 미리 센다. 파일은 만들지 않는다. */
    fun outline(
        data: ByteArray,
        lines: List<String>,
    ): ReflectionPlan? = opened(data) { document -> planOf(unitsOf(document), lines) }

    /** 원본 구조에 [lines] 를 반영한 파일. */
    fun reflect(
        data: ByteArray,
        title: String,
        lines: List<String>,
    ): ExportFile? =
        opened(data) { document ->
            val plan = planOf(unitsOf(document), lines)
            apply(document, plan)
            exportFileOf(title, ExportFormat.DOCX, bytesOf(document))
        }

    /**
     * 원본 전체 단위 수(본문+머리글·꼬리말). **A7 대조 전용** — 추출기가 낸 줄 수와
     * `TextUnitWalk` 가 낸 단위 수가 같은지 재는 시험이 이 값을 쓴다. 열리지 않으면 `null`.
     */
    internal fun unitCount(data: ByteArray): Int? = opened(data) { document -> unitsOf(document).size }

    /**
     * 원본을 열어 [use] 에 넘긴다. **열리지 않으면 `null`** — 부르는 쪽이 그것을 오류와
     * `failed` 판정으로 바꾼다. 여기서 새 문서로 접으면 §6.5 가 금지한 조용한 대체가 된다.
     *
     * 예외 갈래는 `DocxExtractor.collectInto` 와 같다: POI 는 손상 입력을 검사 예외로도
     * 비검사 예외로도 낸다.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun <T> opened(
        data: ByteArray,
        use: (XWPFDocument) -> T,
    ): T? =
        try {
            XWPFDocument(ByteArrayInputStream(data)).use(use)
        } catch (cause: Exception) {
            // **사유를 로그에 적지 않는다** — 예외 메시지에 문서 조각이 실려 나올 수 있다.
            null
        }

    /** 본문 → 머리글·바닥글 순서. 추출기가 블록을 낸 순서 그대로다. */
    private fun unitsOf(document: XWPFDocument): List<WalkedUnit> {
        val body = document.document.body.domNode
        return walk.walk(body) +
            DocxSectionParts.headerFooterParts(document, body).flatMap { part ->
                walk.walk(part, insideHeaderFooter = true)
            }
    }

    private fun apply(
        document: XWPFDocument,
        plan: ReflectionPlan,
    ) {
        plan.written.forEach { assignment ->
            assignment.unit.rewrite(assignment.line)
            // 앞뒤 공백이 있는 줄은 이 표시가 없으면 Word 가 삼킨다.
            assignment.unit.texts
                .firstOrNull()
                ?.let(::preserveSpace)
        }
        plan.emptied.forEach { it.rewrite("") }
        if (plan.appended.isNotEmpty()) {
            append(document.document.body.domNode, plan.appendTemplate, plan.appended)
        }
    }

    /**
     * 자리를 찾지 못한 문단을 **본문 끝에** 붙인다.
     *
     * 마지막 단위 **뒤**가 아니라 본문 끝인 것은, 마지막 단위가 표 셀 안일 수 있기 때문이다 —
     * 그 자리에 붙이면 원본에 없던 행이 표 안에서 자란다. 본문 끝은 표 다음이기도 하다.
     *
     * 서식은 [ReflectionPlan.appendTemplate] 에서 **속성만** 베낀다. 문단을 통째로 복제하면 그
     * 안의 그림·표가 함께 복제된다.
     */
    private fun append(
        body: Node,
        template: TextUnit?,
        lines: List<String>,
    ) {
        val owner = body.ownerDocument
        // 끝의 `w:sectPr` 은 본문이 아니라 구역 속성이다 — 그 앞에 넣어야 순서가 유효하다.
        val tail = OoxmlDom.childElements(body).lastOrNull()?.takeIf { OoxmlDom.localName(it) == "sectPr" }
        for (line in lines) {
            val paragraph = owner.createElementNS(WORDPROCESSING_NAMESPACE, "w:p")
            template?.anchor?.let { anchor -> copiedProperties(anchor, "pPr", owner)?.let(paragraph::appendChild) }
            val run = owner.createElementNS(WORDPROCESSING_NAMESPACE, "w:r")
            template?.texts?.firstOrNull()?.parentNode?.let { source ->
                copiedProperties(source, "rPr", owner)?.let(run::appendChild)
            }
            val text = owner.createElementNS(WORDPROCESSING_NAMESPACE, "w:t")
            preserveSpace(text)
            text.appendChild(owner.createTextNode(line))
            run.appendChild(text)
            paragraph.appendChild(run)
            body.insertBefore(paragraph, tail)
        }
    }

    /**
     * [source] 의 속성 요소(`w:pPr`·`w:rPr`) 사본을 [owner] 문서로 가져온다. 없으면 `null`.
     *
     * `w:pPr` 안의 `w:sectPr` 은 **떼어 낸다** — 구역 나눔이 실린 문단을 본으로 삼으면 덧붙인
     * 문단 수만큼 구역이 늘어난다.
     *
     * 본을 뜬 문단이 머리글·바닥글 파트에 있으면 그 노드는 **다른 DOM 문서**의 것이다. 옮겨
     * 심지 않고 붙이면 `WRONG_DOCUMENT_ERR` 로 내보내기가 통째로 실패한다.
     */
    private fun copiedProperties(
        source: Node,
        name: String,
        owner: Document,
    ): Node? {
        val properties = OoxmlDom.childElements(source).firstOrNull { OoxmlDom.localName(it) == name } ?: return null
        val copy = owner.importNode(properties, true)
        OoxmlDom
            .childElements(copy)
            .filter { OoxmlDom.localName(it) == "sectPr" }
            .forEach { copy.removeChild(it) }
        return copy
    }

    /** `xml:space="preserve"` — 앞뒤 공백을 지우지 말라는 표시. */
    private fun preserveSpace(text: Element) {
        text.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve")
    }

    private fun bytesOf(document: XWPFDocument): ByteArray =
        ByteArrayOutputStream().use { sink ->
            document.write(sink)
            sink.toByteArray()
        }

    private companion object {
        const val WORDPROCESSING_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    }
}
