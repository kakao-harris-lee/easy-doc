package kr.easydoc.infrastructure.export

import kr.easydoc.infrastructure.ingest.OoxmlDom
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * 원본에서 검수본 문단 하나와 짝지어지는 **텍스트 덩어리 하나**.
 *
 * 「문단 하나」가 아니라 「덩어리 하나」인 것이 요점이다. 추출기는 문단 **시작**마다 블록을
 * 끊으므로, 한 덩어리의 텍스트가 문단 경계를 넘어 흩어져 있을 수 있다(표를 품은 문단이
 * 그렇다 — 표 뒤에 남은 조각은 마지막 셀 문단의 덩어리에 붙는다). 그래서 [texts] 가 **그
 * 덩어리를 이룬 `t` 요소 전부**를 순서대로 들고, 쓰기는 그 전부를 대상으로 한다.
 */
internal class TextUnit(
    /** 이 덩어리가 시작된 문단 요소. 덧붙이기의 서식 본이 된다. 문단 밖 텍스트면 `null`. */
    val anchor: Element?,
    /** 이 덩어리를 이룬 `t` 요소들 — **추출 순서 그대로**. 비어 있지 않다. */
    val texts: List<Element>,
) {
    /** 추출기가 이 덩어리에서 읽어 간 문자열과 같다. */
    val text: String get() = texts.joinToString("") { OoxmlDom.leadingText(it) }

    /** 길이만 남긴다 — 이 값은 **사용자 문서 본문**이다. */
    override fun toString(): String = "TextUnit(${text.length}자, t ${texts.size}개)"

    /**
     * 이 덩어리의 텍스트를 [line] 으로 **갈아 끼운다**. 첫 `t` 에 쓰고 나머지는 비운다.
     *
     * 원본 문구를 남기지 않는 것이 규칙이다 — 검수를 지나지 않은 원본 문장이 「쉬운 글」
     * 파일에 섞이면 그 파일이 조용히 거짓말을 한다.
     */
    fun rewrite(line: String) {
        texts.forEachIndexed { index, element -> setLeadingText(element, if (index == 0) line else "") }
    }

    /** 시작 태그 뒤의 연속 텍스트 노드를 [text] 하나로 바꾼다 — `OoxmlDom.leadingText` 가 읽는 범위 그대로. */
    private fun setLeadingText(
        element: Element,
        text: String,
    ) {
        var child = element.firstChild
        while (child != null && (child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE)) {
            val next = child.nextSibling
            element.removeChild(child)
            child = next
        }
        if (text.isEmpty()) return
        element.insertBefore(element.ownerDocument.createTextNode(text), element.firstChild)
    }
}

/**
 * 추출기와 **같은 순서로** 텍스트 덩어리를 모으는 순회.
 *
 * 추출기(`ingest/DocxExtractor.elementBlocks`·`ingest/HwpxExtractor.SectionBlocks`)를 그대로
 * 쓰지 못하는 이유는 둘이 필요로 하는 것이 다르기 때문이다: 추출기는 **문자열을 흘려보내며**
 * 길이 예산을 검사하고 빈 블록까지 순서대로 내야 하지만(오라클 대조), 반영은 나중에 고쳐 쓸
 * **노드를 쥐고 있어야** 한다. 대신 순서가 갈리지 않는다는 것은 문서로 두지 않고 테스트가
 * 지킨다 — `PackagedOriginalReflectorTest` 가 fixture 마다 「추출이 본 차례에 그대로 썼는가」를 잰다.
 */
internal class TextUnitWalk(
    /** 덩어리를 끊는 요소의 로컬 이름 — 두 형식 모두 `p` 다. */
    private val paragraphName: String = "p",
    /** 텍스트를 담는 요소의 로컬 이름 — 두 형식 모두 `t` 다. */
    private val textName: String = "t",
    /** 걷지 않을 가지. */
    private val skip: (Node) -> Boolean = { false },
    /** 이 요소 **아래**는 머리말·꼬리말이다 — 검수본을 쓰지 않고 원본 문구를 그대로 둔다. */
    private val headerFooter: (Node) -> Boolean = { false },
) {
    /** [root] 아래의 덩어리를 문서 순서로 모은다. **빈 덩어리는 내지 않는다**(추출기가 버린다). */
    fun walk(
        root: Node,
        insideHeaderFooter: Boolean = false,
    ): List<WalkedUnit> {
        val collected = Collector()
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, insideHeaderFooter))
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            val node = frame.node
            if (skip(node)) continue
            val inHeaderFooter = frame.inHeaderFooter || headerFooter(node)
            when (OoxmlDom.localName(node)) {
                paragraphName -> collected.startParagraph(node as Element, inHeaderFooter)
                textName -> collected.addText(node as Element)
            }
            // 자식을 역순으로 쌓아야 pop 순서가 문서 순서가 된다 — 추출기와 같다.
            OoxmlDom.childElements(node).asReversed().forEach { stack.addLast(Frame(it, inHeaderFooter)) }
        }
        return collected.finish()
    }

    private class Frame(
        val node: Node,
        val inHeaderFooter: Boolean,
    )

    /** 덩어리를 모으는 상태 기계. 문단 **시작**에서 앞 덩어리를 끊는 것이 추출기와 같은 지점이다. */
    private class Collector {
        private val units = mutableListOf<WalkedUnit>()
        private val pending = mutableListOf<Element>()
        private var anchor: Element? = null
        private var anchorInHeaderFooter = false

        fun startParagraph(
            paragraph: Element,
            inHeaderFooter: Boolean,
        ) {
            flush()
            anchor = paragraph
            anchorInHeaderFooter = inHeaderFooter
        }

        fun addText(text: Element) {
            pending += text
        }

        fun finish(): List<WalkedUnit> {
            flush()
            return units.toList()
        }

        private fun flush() {
            val texts = pending.toList()
            pending.clear()
            if (texts.isEmpty()) return
            val unit = TextUnit(anchor, texts)
            // 공백뿐인 블록은 추출기가 버린다(`ExtractedTextBuilder` 가 줄마다 trim 후 빈 줄을 버린다).
            if (unit.text.isBlank()) return
            units += WalkedUnit(unit, inHeaderFooter = anchorInHeaderFooter)
        }
    }
}

/**
 * 순회가 만난 덩어리 하나와 그 **자리의 성격**.
 *
 * 머리말·꼬리말이 본문 뒤에 오는 형식(DOCX — 파트가 따로 있다)과 본문 **사이에** 오는 형식
 * (HWPX — 머리말이 첫 문단의 컨트롤로 들어간다)이 둘 다 있어서, 「본문 몇 개 · 머리말 몇 개」로
 * 나눠 세면 자리가 어긋난다. 순서를 그대로 든 목록에 표시만 얹는 것이 그것을 없앤다.
 */
internal class WalkedUnit(
    val unit: TextUnit,
    val inHeaderFooter: Boolean,
) {
    val isBody: Boolean get() = !inHeaderFooter
}
