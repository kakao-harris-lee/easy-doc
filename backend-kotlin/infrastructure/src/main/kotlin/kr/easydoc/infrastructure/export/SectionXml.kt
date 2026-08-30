package kr.easydoc.infrastructure.export

import kr.easydoc.infrastructure.ingest.SecureXml
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.XMLConstants
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * 고쳐 쓰기 위해 여는 XML 조각의 **읽기·쓰기 한 쌍**.
 *
 * 반영기와 나눠 두는 이유: 여기 있는 것은 HWPX 지식이 아니라 XML I/O 다. 신뢰할 수 없는
 * 입력을 여는 설정(DTD 금지)과 다시 쓰는 설정(선언·인코딩)이 한 자리에 있어야 둘이 갈리지
 * 않는다.
 */
internal object SectionXml {
    fun parse(xml: ByteArray): Document = SecureXml.newDocumentBuilder().parse(ByteArrayInputStream(xml))

    /** DOM 을 다시 바이트로. 선언과 인코딩은 원본이 쓰던 것과 같은 모양으로 둔다. */
    fun serialize(document: Document): ByteArray {
        val sink = ByteArrayOutputStream()
        transformer().transform(DOMSource(document), StreamResult(sink))
        return sink.toByteArray()
    }

    private fun transformer() =
        TransformerFactory
            .newInstance()
            .apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
            }.newTransformer()
            .apply {
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                setOutputProperty(OutputKeys.STANDALONE, "yes")
                setOutputProperty(OutputKeys.INDENT, "no")
            }
}
