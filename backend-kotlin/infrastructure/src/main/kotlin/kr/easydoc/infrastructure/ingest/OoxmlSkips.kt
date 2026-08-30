package kr.easydoc.infrastructure.ingest

import org.w3c.dom.Node

/**
 * OOXML 순회에서 **걷지 않는 가지**. 추출과 반영이 같은 규칙을 봐야 하므로 한 곳에 둔다.
 *
 * 두 쪽이 갈리면 반영이 추출보다 단위를 더(또는 덜) 세고, 검수본 문단이 한 칸씩 밀린다.
 */
internal object OoxmlSkips {
    private const val MARKUP_COMPATIBILITY_NAMESPACE =
        "http://schemas.openxmlformats.org/markup-compatibility/2006"

    /** `mc:AlternateContent` 의 `mc:Fallback` 인가 — **네임스페이스까지 본다.** */
    fun isAlternateContentFallback(node: Node): Boolean =
        OoxmlDom.localName(node) == "Fallback" && node.namespaceURI == MARKUP_COMPATIBILITY_NAMESPACE
}
