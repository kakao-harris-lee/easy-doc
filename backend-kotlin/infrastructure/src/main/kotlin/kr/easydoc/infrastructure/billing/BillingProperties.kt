package kr.easydoc.infrastructure.billing

import kr.easydoc.core.privacy.CONTENT_MASK
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 청구 관련 운영 설정. 바인딩 접두사는 `easydoc.billing`.
 *
 * [operatorEmail] — 세금계산서 요청이 들어올 때 알림을 받을 운영자 메일 주소(계획
 * `docs/plans/2026-09-07-invoice-requests.md` §2 결정 3). 비어 있으면
 * [kr.easydoc.application.invoice.InvoiceRequestService] 가 운영자 메일을 보내지 않고
 * 경고 로그 한 줄만 남긴다 — 요청 자체는 계속 접수된다.
 */
@ConfigurationProperties(prefix = "easydoc.billing")
data class BillingProperties(val operatorEmail: String = "") {
    /**
     * `operatorEmail` 은 필드 이름이 민감 판정 토큰(`email`)에 걸린다 — `MailProperties
     * .fromAddress` 와 같은 규약으로 값 대신 길이만 남긴다.
     */
    override fun toString(): String = "BillingProperties(operatorEmail=$CONTENT_MASK)"
}
