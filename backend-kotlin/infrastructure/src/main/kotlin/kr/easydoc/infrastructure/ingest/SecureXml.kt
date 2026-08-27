package kr.easydoc.infrastructure.ingest

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLInputFactory

/** 신뢰할 수 없는 XML 을 읽는 파서 팩터리 — **DTD 를 파서 수준에서 거부한다.** */
internal object SecureXml {
    /** DOCTYPE 자체를 막는 기능 이름. 이것 하나면 외부 엔터티가 실릴 자리가 없어진다. */
    private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"

    /**
     * 신뢰할 수 없는 XML 을 **고쳐 쓰기 위해** 읽는 DOM 파서. 매 파싱마다 새로 만든다
     * (`DocumentBuilder` 는 스레드 안전하지 않다).
     *
     * 네임스페이스를 인식한다 — 반영이 `hp:p` 와 `w:p` 를 접두사가 아니라 이름으로 봐야 한다.
     */
    fun newDocumentBuilder(): DocumentBuilder =
        DocumentBuilderFactory
            .newInstance()
            .apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature(DISALLOW_DOCTYPE, true)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isNamespaceAware = true
                isExpandEntityReferences = false
            }.newDocumentBuilder()

    /** 매 파싱마다 새로 만든다. */
    fun newInputFactory(): XMLInputFactory {
        val factory = XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        return factory
    }
}
