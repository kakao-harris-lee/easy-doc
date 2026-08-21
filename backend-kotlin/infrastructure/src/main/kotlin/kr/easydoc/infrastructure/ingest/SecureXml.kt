package kr.easydoc.infrastructure.ingest

import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory

/** 신뢰할 수 없는 XML 을 읽는 StAX 팩터리 — **DTD 를 파서 수준에서 거부한다.** */
internal object SecureXml {
    /** 매 파싱마다 새로 만든다. */
    fun newInputFactory(): XMLInputFactory {
        val factory = XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        return factory
    }
}
