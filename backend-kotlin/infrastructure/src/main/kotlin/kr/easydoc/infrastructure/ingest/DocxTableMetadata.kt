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
        val look =
            OoxmlDom
                .childElements(table)
                .firstOrNull { OoxmlDom.localName(it) == "tblPr" }
                ?.let { properties ->
                    OoxmlDom.childElements(properties).firstOrNull { OoxmlDom.localName(it) == "tblLook" }
                }
        return look?.let { attributeValue(it, FIRST_ROW_ATTRIBUTE)?.lowercase() in setOf("1", "true") } == true
    }

    fun hasRowOffsetMarker(row: Node): Boolean {
        val properties = OoxmlDom.childElements(row).firstOrNull { OoxmlDom.localName(it) == "trPr" } ?: return false
        return OoxmlDom
            .childElements(properties)
            .filter { OoxmlDom.localName(it) in ROW_OFFSET_ELEMENTS }
            .any { spanValue(it) > 0 }
    }

    fun hasMergeMarker(cell: Node): Boolean {
        val nodes = ArrayDeque<Node>()
        nodes.addLast(cell)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeLast()
            val name = OoxmlDom.localName(node)
            if (name in MERGE_ELEMENTS && (name != "gridSpan" || spanValue(node) > 1)) return true
            OoxmlDom.childElements(node).forEach(nodes::addLast)
        }
        return false
    }

    private fun spanValue(node: Node): Int = attributeValue(node, "val")?.toIntOrNull() ?: 0

    private fun onOffValue(
        node: Node,
        attribute: String = "val",
    ): Boolean {
        val value = attributeValue(node, attribute)?.lowercase()
        return value !in setOf("0", "false", "off", "no")
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
