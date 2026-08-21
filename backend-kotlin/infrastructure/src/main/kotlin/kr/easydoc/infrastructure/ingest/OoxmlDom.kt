package kr.easydoc.infrastructure.ingest

import org.w3c.dom.Element
import org.w3c.dom.Node

/** OOXML DOM 을 훑을 때 반복되는 세 가지 — 자식 요소 · 로컬 이름 · **lxml `.text` 의미의 텍스트**. */
internal object OoxmlDom {
    /** 요소 자식만 문서 순서로. 주석·처리 명령은 걷지 않는다(원본도 태그가 문자열이 아니라 자연히 빠졌다). */
    fun childElements(node: Node): List<Element> {
        val children = mutableListOf<Element>()
        var child = node.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) children += child as Element
            child = child.nextSibling
        }
        return children
    }

    /** 네임스페이스 접두사를 뗀 이름. 파서가 네임스페이스를 모르는 경우까지 흡수한다. */
    fun localName(node: Node): String = node.localName ?: node.nodeName.substringAfterLast(':')

    /** 시작 태그와 **첫 자식 노드** 사이의 텍스트 — lxml `element.text` 와 같은 의미다 (spike S-3). */
    fun leadingText(node: Node): String {
        val text = StringBuilder()
        var child = node.firstChild
        while (child != null && (child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE)) {
            text.append(child.nodeValue.orEmpty())
            child = child.nextSibling
        }
        return text.toString()
    }
}
