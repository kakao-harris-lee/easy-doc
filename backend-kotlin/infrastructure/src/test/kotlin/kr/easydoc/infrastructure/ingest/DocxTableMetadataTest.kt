package kr.easydoc.infrastructure.ingest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream

/**
 * `w:val` 이 숫자가 아니거나 없을 때 병합·행 오프셋 판정이 보수적인 쪽(있음)으로 접히는지 고정한다.
 * `0` 으로 접으면 못 읽은 표시가 「표시 없음」과 같아지므로, 해석 실패는 항상 참으로 취급해야 한다.
 */
class DocxTableMetadataTest {
    private val ns = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main""""

    private fun parse(xml: String): Element =
        SecureXml.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray())).documentElement

    @Test
    fun `gridSpan 값이 숫자가 아니면 병합으로 판정한다`() {
        val cell = parse("""<w:tc $ns><w:tcPr><w:gridSpan w:val="abc"/></w:tcPr></w:tc>""")

        assertThat(DocxTableMetadata.hasMergeMarker(cell)).isTrue()
    }

    @Test
    fun `gridSpan 값이 1 이면 병합이 아니다`() {
        val cell = parse("""<w:tc $ns><w:tcPr><w:gridSpan w:val="1"/></w:tcPr></w:tc>""")

        assertThat(DocxTableMetadata.hasMergeMarker(cell)).isFalse()
    }

    @Test
    fun `gridSpan 에 값 자체가 없으면 여전히 병합으로 판정한다 (회귀)`() {
        val cell = parse("""<w:tc $ns><w:tcPr><w:gridSpan/></w:tcPr></w:tc>""")

        assertThat(DocxTableMetadata.hasMergeMarker(cell)).isTrue()
    }

    @Test
    fun `gridBefore 값이 숫자가 아니면 행 오프셋으로 판정한다`() {
        val row = parse("""<w:tr $ns><w:trPr><w:gridBefore w:val="abc"/></w:trPr></w:tr>""")

        assertThat(DocxTableMetadata.hasRowOffsetMarker(row)).isTrue()
    }
}
