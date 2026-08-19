package kr.easydoc.infrastructure.ingest

import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory

/**
 * 신뢰할 수 없는 XML 을 읽는 StAX 팩터리 — **DTD 를 파서 수준에서 거부한다.**
 *
 * ## 왜 파서 수준인가 (`migration-safety-gate` I-10 검증 2)
 *
 * 본문을 `<!DOCTYPE` 바이트로 훑는 방식은 **UTF-16 으로 인코딩하면 그대로 뚫린다**
 * (Python 주석의 실측이자 Phase 0 spike 가 재현한 것). 파서가 선언을 만나는 즉시 끊어야
 * 인코딩과 무관하게 막히고, 그 시점에는 엔터티 확장이 **시작되지도 않는다** —
 * billion laughs 도 외부 엔터티(XXE)도 같다.
 *
 * ## 세 속성을 **명시**한다 (spike S-4)
 *
 * StAX 기본값은 안전하지 않다. JDK 구현이 기본으로 무엇을 켜 두는지에 기대지 않는다.
 *
 * ## `SUPPORT_DTD = false` 를 고른 이유 — spike 권고를 채택하지 않았다 (계획 §1.5 지점 1)
 *
 * spike 는 `SUPPORT_DTD = true` 로 두고 `XMLStreamConstants.DTD` **이벤트를 직접 받아**
 * 우리 예외를 던지는 쪽을 권고했다(Python `expat.StartDoctypeDeclHandler` 와 1:1). 그
 * 권고의 **유일한 근거는 로케일 문제**였다 — `SUPPORT_DTD = false` 면 JDK 가 던지는
 * `XMLStreamException` 의 메시지가 번역돼(spike 가 한국어 메시지를 실측했다) "DTD 거부"와
 * "손상 파일"을 메시지로 가를 수 없다.
 *
 * 그래도 `false` 로 가는 이유:
 *
 * - **OWASP 의 StAX 1차 통제가 `SUPPORT_DTD = false`** 다. 이벤트 수신 방식은 표준
 *   이벤트라 기술적으로 가능하지만 권장 통제로 문서화돼 있지 않다 — **예방이 아니라
 *   탐지**이고, "우리가 이벤트를 받아 끊는다"는 구현 순서에 의존한다.
 *   프로젝트 `CLAUDE.md` 의 리서치 규칙 1 이 "공식 문서로 현재 권장 방식을 확인한다"이므로
 *   spike 의 실측 편의보다 공식 권장이 이긴다.
 * - 로케일 문제의 답은 **사유를 메시지로 가르지 않는 것**이다. 거절은 도메인 예외 하나
 *   (`DocumentExtractionException`)로 통일하고 진단은 예외 **타입**으로만 로깅한다 —
 *   [ExtractionFailureLog] 가 이미 요구하는 규약과 **같은 규약**이라 새 장치가 아니다.
 *
 * ## 잃는 것 (명시)
 *
 * DTD 폭탄과 손상 파일의 **사용자 문구가 같아진다**(`hwpx 파일을 읽을 수 없습니다
 * (파일이 손상되었습니다)`). Python 은 전용 문구 `(DTD 선언은 허용하지 않습니다)` 를 냈다.
 * 계약은 이 구분을 요구하지 않는다 — `x-input-limits` 는 `legacy_doc_policy`·`rejected_pdf`·
 * 예산·추출 길이만 든다. 기준은 Python 이 아니라 요구사항이므로(master-plan 6.2)
 * 이 갈림은 결함이 아니라 **기록 대상**이다.
 */
internal object SecureXml {
    /**
     * 매 파싱마다 새로 만든다.
     *
     * [XMLInputFactory] 는 스레드 안전이 보장되지 않는데, 이 자리는 동시 업로드가 들어오는
     * 경로다. 재사용해 얻는 것은 인스턴스 하나이고 잃는 것은 그 보장이라 재사용하지 않는다.
     */
    fun newInputFactory(): XMLInputFactory {
        val factory = XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        return factory
    }
}
