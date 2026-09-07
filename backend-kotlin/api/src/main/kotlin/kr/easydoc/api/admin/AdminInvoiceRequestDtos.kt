package kr.easydoc.api.admin

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import kr.easydoc.api.invoice.InvoiceRequestResponse
import kr.easydoc.application.invoice.InvoiceRequestPage
import kr.easydoc.application.invoice.InvoiceRequestView
import kr.easydoc.core.privacy.CONTENT_MASK

/** `GET /admin/invoice-requests` 응답 — `InvoiceRequestResponse`(기존 스키마)를 재사용한다. */
data class AdminInvoiceRequestListResponse(
    @get:JsonProperty("items") val items: List<InvoiceRequestResponse>,
    @get:JsonProperty("page") val page: Int,
    @get:JsonProperty("size") val size: Int,
    @get:JsonProperty("total") val total: Int,
) {
    companion object {
        fun of(
            result: InvoiceRequestPage,
            page: Int,
            size: Int,
        ): AdminInvoiceRequestListResponse =
            AdminInvoiceRequestListResponse(
                items = result.items.map { row -> InvoiceRequestResponse.of(InvoiceRequestView.of(row)) },
                page = page,
                size = size,
                total = result.total,
            )
    }
}

/** `POST /admin/invoice-requests/{id}/handle` 요청 본문. */
data class AdminInvoiceRequestHandleRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("status") val status: String,
        // 전역 null 처리는 실패다(`JsonRequestStrictnessConfig`) — 선택 필드는 이 표식으로
        // 그 기본을 뒤집는다(`DocumentTextRequest.title`과 같은 관행).
        @param:JsonProperty("note")
        @param:JsonSetter(nulls = Nulls.SET)
        val note: String?,
    ) {
        /** 운영자 메모를 찍지 않는다(인구조사 규약, `InvoiceHandleArgs`와 같은 판단). */
        override fun toString(): String = "AdminInvoiceRequestHandleRequest(status=$status, note=$CONTENT_MASK)"
    }
