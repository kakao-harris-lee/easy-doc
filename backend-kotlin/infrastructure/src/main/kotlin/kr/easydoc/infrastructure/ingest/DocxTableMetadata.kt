package kr.easydoc.infrastructure.ingest

import org.w3c.dom.Node

/** Pure OOXML metadata checks shared by the DOCX table collector. */
internal object DocxTableMetadata {
    private val MERGE_ELEMENTS = setOf("gridSpan", "vMerge", "hMerge")
    private val ROW_OFFSET_ELEMENTS = setOf("gridBefore", "gridAfter")
    private const val GRID_ELEMENT = "tblGrid"
    private const val GRID_COLUMN_ELEMENT = "gridCol"
    private const val FIRST_ROW_ATTRIBUTE = "firstRow"
    private const val FOOTNOTE_REFERENCE_ELEMENT = "footnoteReference"

    // ST_OnOff(ECMA-376): "1"/"true"/"on" 은 참, "0"/"false"/"off"/"no" 는 거짓이다. 값이 그 밖의
    // 형태로 오는 일은 없으므로 부정 목록만 유지하면 두 표기 계열을 모두 참으로 받아들인다.
    private val OFF_VALUES = setOf("0", "false", "off", "no")

    fun declaredColumnCount(node: Node): Int? =
        OoxmlDom
            .childElements(node)
            .firstOrNull { OoxmlDom.localName(it) == GRID_ELEMENT }
            ?.let { grid -> OoxmlDom.childElements(grid).count { OoxmlDom.localName(it) == GRID_COLUMN_ELEMENT } }
            ?.takeIf { it > 0 }

    fun hasHeaderMarker(row: Node): Boolean {
        val properties = OoxmlDom.childElements(row).firstOrNull { OoxmlDom.localName(it) == "trPr" } ?: return false
        return OoxmlDom
            .childElements(properties)
            .filter { OoxmlDom.localName(it) == "tblHeader" }
            .any(::onOffValue)
    }

    fun hasFirstRowMarker(table: Node): Boolean {
        // tblLook 자체가 firstRow 속성을 아예 안 갖고 있으면(속성 부재) 헤더 근거가 없는 것이지,
        // onOffValue의 "값 없으면 참"이라는 기본값(다른 on/off 요소의 존재 자체가 근거인 경우 전용)을
        // 그대로 물려받으면 안 된다. 속성이 있을 때만 onOffValue와 같은 부정 목록 규약으로 판정한다.
        val value =
            OoxmlDom
                .childElements(table)
                .firstOrNull { OoxmlDom.localName(it) == "tblPr" }
                ?.let { properties ->
                    OoxmlDom.childElements(properties).firstOrNull { OoxmlDom.localName(it) == "tblLook" }
                }?.let { look -> attributeValue(look, FIRST_ROW_ATTRIBUTE) }
                ?: return false
        return value.lowercase() !in OFF_VALUES
    }

    fun hasRowOffsetMarker(row: Node): Boolean {
        val properties = OoxmlDom.childElements(row).firstOrNull { OoxmlDom.localName(it) == "trPr" } ?: return false
        return OoxmlDom
            .childElements(properties)
            .filter { OoxmlDom.localName(it) in ROW_OFFSET_ELEMENTS }
            .any { spanValue(it)?.let { value -> value > 0 } ?: true }
    }

    fun hasMergeMarker(cell: Node): Boolean {
        val nodes = ArrayDeque<Node>()
        nodes.addLast(cell)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeLast()
            val name = OoxmlDom.localName(node)
            if (name in MERGE_ELEMENTS &&
                (name != "gridSpan" || (spanValue(node)?.let { value -> value > 1 } ?: true))
            ) {
                return true
            }
            OoxmlDom.childElements(node).forEach(nodes::addLast)
        }
        return false
    }

    /**
     * `w:val` 을 정수로 읽는다. **없거나 숫자가 아니면 `null`** 이다 — `0` 으로 접으면
     * 「값이 없다」와 「해석할 수 없다」가 같은 뜻이 되어, 표 구조 표시를 못 읽은 셀이
     * 「표시가 없다」로 통과한다. 해석 실패는 보수적으로 **표시가 있는 쪽**으로 접는다.
     */
    private fun spanValue(node: Node): Int? = attributeValue(node, "val")?.toIntOrNull()

    private fun onOffValue(
        node: Node,
        attribute: String = "val",
    ): Boolean {
        val value = attributeValue(node, attribute)?.lowercase()
        return value !in OFF_VALUES
    }

    private fun attributeValue(
        node: Node,
        name: String,
    ): String? {
        val attributes = node.attributes ?: return null
        return (0 until attributes.length)
            .map { attributes.item(it) }
            .firstOrNull { (it.localName ?: it.nodeName.substringAfterLast(':')) == name }
            ?.nodeValue
    }

    fun containsFootnoteReference(node: Node): Boolean {
        val nodes = ArrayDeque<Node>()
        nodes.addLast(node)
        while (nodes.isNotEmpty()) {
            val current = nodes.removeLast()
            if (OoxmlDom.localName(current) == FOOTNOTE_REFERENCE_ELEMENT) return true
            OoxmlDom.childElements(current).forEach(nodes::addLast)
        }
        return false
    }
}
