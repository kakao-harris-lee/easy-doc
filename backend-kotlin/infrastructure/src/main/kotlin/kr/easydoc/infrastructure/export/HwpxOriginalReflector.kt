package kr.easydoc.infrastructure.export

import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.easyread.exportFileOf
import kr.easydoc.infrastructure.ingest.OoxmlDom
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * **원본 HWPX 를 고쳐서** 내보낸다 — 새 문서를 만들지 않는다.
 *
 * 구역 XML(`Contents/section*.xml`)만 DOM 으로 고치고 **나머지 항목은 바이트 그대로** 다시
 * 담는다. `Contents/header.xml` 의 글꼴·문단 모양, 표·그림, `version.xml`, manifest 가 손대지
 * 않은 채 남는 것이 그 결과다.
 *
 * hwpxlib 의 객체 모델을 쓰지 않는 이유가 여기 있다. `HWPXReader` → `HWPXWriter` 왕복은
 * 라이브러리가 아는 요소만 다시 쓰므로, 모르는 요소가 있으면 **조용히 사라진다.** 우리가
 * 약속한 것은 「본문 말고는 그대로」라서, 아는 것만 남기는 왕복은 그 약속을 지킬 수 없다.
 * 순회 규칙은 `ingest/HwpxExtractor` 와 같다(문단 시작에서 끊고, `t` 를 이어 붙인다).
 */
@Suppress("TooManyFunctions")
internal class HwpxOriginalReflector {
    private val walk = TextUnitWalk(headerFooter = ::isHeaderFooter)

    /** 반영하면 무엇이 달라지는지 미리 센다. 파일은 만들지 않는다. */
    fun outline(
        data: ByteArray,
        lines: List<String>,
    ): ReflectionPlan? = guarded(data) { opened -> planOf(unitsOf(opened), lines) }

    /** 원본 구조에 [lines] 를 반영한 파일. */
    fun reflect(
        data: ByteArray,
        title: String,
        lines: List<String>,
    ): ExportFile? =
        guarded(data) { opened ->
            val plan = planOf(unitsOf(opened), lines)
            apply(opened, plan)
            val parts = LinkedHashMap(opened.parts)
            opened.sections.forEach { section -> parts[section.name] = SectionXml.serialize(section.document) }
            exportFileOf(title, ExportFormat.HWPX, hwpxPackageOf(parts))
        }

    /**
     * 원본 전체 단위 수(본문+머리글·꼬리말). **A7 대조 전용** — 추출기가 낸 줄 수와
     * `TextUnitWalk` 가 낸 단위 수가 같은지 재는 시험이 이 값을 쓴다. 열리지 않으면 `null`.
     */
    internal fun unitCount(data: ByteArray): Int? = guarded(data) { opened -> unitsOf(opened).size }

    /**
     * 원본을 열어 [use] 에 넘긴다. **열리지 않으면 `null`** — 부르는 쪽이 그것을 오류와
     * `failed` 판정으로 바꾼다. 여기서 새 문서로 접으면 §6.5 가 금지한 조용한 대체가 된다.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun <T> guarded(
        data: ByteArray,
        use: (OpenedPackage) -> T,
    ): T? =
        try {
            use(opened(data))
        } catch (cause: Exception) {
            // **사유를 로그에 적지 않는다** — 파서 예외 메시지에 문서 조각이 실려 나올 수 있다.
            null
        }

    private fun opened(data: ByteArray): OpenedPackage {
        val parts = hwpxZipEntries(data)
        val sections =
            parts.keys
                .filter { SECTION_NAME.matches(it) }
                .sortedBy(::sectionNumber)
                .map { name -> SectionDocument(name, SectionXml.parse(parts.getValue(name))) }
        // 구역이 하나도 없으면 hwpx 패키지가 아니거나 껍데기다 — 추출기와 같은 판정이다.
        check(sections.isNotEmpty()) { "HWPX 패키지에 구역이 없다" }
        return OpenedPackage(parts, sections)
    }

    /** 구역을 **번호 순서로** 이어 붙인다. `HwpxExtractor.readSections` 와 같은 정렬이다. */
    private fun unitsOf(opened: OpenedPackage): List<WalkedUnit> =
        opened.sections.flatMap { section -> walk.walk(section.document.documentElement) }

    private fun apply(
        opened: OpenedPackage,
        plan: ReflectionPlan,
    ) {
        plan.written.forEach { assignment -> assignment.unit.rewrite(assignment.line) }
        plan.emptied.forEach { unit -> unit.rewrite("") }
        if (plan.appended.isEmpty()) return
        val template =
            plan.appendTemplate
                // 원본에 단위가 하나도 없으면 문단의 본을 뜰 데가 없다. `hp:p` 의 문단 모양 참조를
                // 지어내는 대신 **실패로 끝낸다** — 한글이 열지 못할 파일을 내보내지 않는다.
                ?: error("HWPX 원본에 본을 뜰 문단이 없다")
        append(
            opened.sections
                .last()
                .document.documentElement,
            template,
            plan.appended,
        )
    }

    /**
     * 자리를 찾지 못한 문단을 **마지막 구역 끝에** 붙인다. 서식은 [ReflectionPlan.appendTemplate]
     * 에서 **속성만** 베낀다 — 문단을 통째로 복제하면 그 안의 그림·표가 함께 복제된다.
     */
    private fun append(
        section: Element,
        template: TextUnit,
        lines: List<String>,
    ) {
        val anchor = template.anchor ?: error("HWPX 단위에 문단 조상이 없다")
        val sourceText = template.texts.first()
        val sourceRun = sourceText.parentNode as Element
        // **본을 뜬 문단이 다른 구역에 있을 수 있다.** 구역마다 DOM 문서가 따로라 요소를 그 문서에서
        // 만들지 않으면 붙이는 순간 `WRONG_DOCUMENT_ERR` 로 내보내기가 통째로 실패한다.
        val owner = section.ownerDocument
        for (line in lines) {
            val paragraph = copiedShell(anchor, owner)
            val run = copiedShell(sourceRun, owner)
            val text = owner.createElementNS(sourceText.namespaceURI, sourceText.nodeName)
            text.appendChild(owner.createTextNode(line))
            run.appendChild(text)
            paragraph.appendChild(run)
            section.appendChild(paragraph)
        }
    }

    /**
     * [source] 와 **같은 이름·같은 속성**의 빈 요소를 [owner] 문서에 만든다. 자식은 베끼지 않는다.
     *
     * `id` 만 뺀다 — 같은 값이 두 문단에 실리면 한글이 문단을 구분하지 못한다.
     */
    private fun copiedShell(
        source: Element,
        owner: Document,
    ): Element {
        val copy = owner.createElementNS(source.namespaceURI, source.nodeName)
        val attributes = source.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if (attribute.localName == "id" || attribute.nodeName == "id") continue
            copy.setAttributeNS(attribute.namespaceURI, attribute.nodeName, attribute.nodeValue)
        }
        return copy
    }

    /** 머리말·꼬리말 컨트롤. HWPX 는 이것이 **본문 문단 안에** 들어 있어 순서가 뒤섞인다. */
    private fun isHeaderFooter(node: Node): Boolean = OoxmlDom.localName(node) in HEADER_FOOTER_NAMES

    private fun sectionNumber(name: String): Int =
        SECTION_NAME
            .matchEntire(name)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: Int.MAX_VALUE

    private class OpenedPackage(
        val parts: LinkedHashMap<String, ByteArray>,
        val sections: List<SectionDocument>,
    )

    private class SectionDocument(
        val name: String,
        val document: Document,
    )

    private companion object {
        /** OWPML 패키지에서 본문을 담는 항목. 번호가 구역 순서다 — `HwpxExtractor` 와 같은 정규식이다. */
        val SECTION_NAME = Regex("""Contents/section(\d+)\.xml""")

        val HEADER_FOOTER_NAMES = setOf("header", "footer")
    }
}
