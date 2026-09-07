package kr.easydoc.api.invoice

import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.application.invoice.InvoiceRequestView
import kr.easydoc.core.privacy.CONTENT_MASK
import java.time.Instant

/** `POST /workspaces/{workspace_id}/invoice-requests` 요청 본문. 계약 `InvoiceRequestCreate`. */
data class InvoiceRequestCreate(
    @get:JsonProperty("business_number") val businessNumber: String,
    @get:JsonProperty("company_name") val companyName: String,
    @get:JsonProperty("representative_name") val representativeName: String?,
    @get:JsonProperty("contact_email") val contactEmail: String,
    @get:JsonProperty("address") val address: String?,
    @get:JsonProperty("period_from") val periodFrom: String,
    @get:JsonProperty("period_to") val periodTo: String,
) {
    /** 사업자 정보를 찍지 않는다(인구조사 규약). */
    override fun toString(): String =
        "InvoiceRequestCreate(businessNumber=$CONTENT_MASK, companyName=$CONTENT_MASK, " +
            "representativeName=$CONTENT_MASK, contactEmail=$CONTENT_MASK, address=$CONTENT_MASK, " +
            "periodFrom=$periodFrom, periodTo=$periodTo)"
}

/** `GET`·`POST /workspaces/{workspace_id}/invoice-requests` 응답 항목. 계약 `InvoiceRequestResponse`. */
data class InvoiceRequestResponse(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("workspace_id") val workspaceId: String?,
    @get:JsonProperty("business_number") val businessNumber: String,
    @get:JsonProperty("company_name") val companyName: String,
    @get:JsonProperty("representative_name") val representativeName: String?,
    @get:JsonProperty("contact_email") val contactEmail: String,
    @get:JsonProperty("address") val address: String?,
    @get:JsonProperty("period_from") val periodFrom: String,
    @get:JsonProperty("period_to") val periodTo: String,
    @get:JsonProperty("status") val status: String,
    @get:JsonProperty("operator_note") val operatorNote: String?,
    @get:JsonProperty("requested_at") val requestedAt: Instant,
    @get:JsonProperty("handled_at") val handledAt: Instant?,
) {
    /** 사업자 정보를 찍지 않는다(인구조사 규약). */
    override fun toString(): String =
        "InvoiceRequestResponse(id=$id, workspaceId=$workspaceId, businessNumber=$CONTENT_MASK, " +
            "companyName=$CONTENT_MASK, representativeName=$CONTENT_MASK, contactEmail=$CONTENT_MASK, " +
            "address=$CONTENT_MASK, periodFrom=$periodFrom, periodTo=$periodTo, status=$status, " +
            "operatorNote=$CONTENT_MASK, requestedAt=$requestedAt, handledAt=$handledAt)"

    companion object {
        fun of(view: InvoiceRequestView): InvoiceRequestResponse =
            InvoiceRequestResponse(
                id = view.id.toString(),
                workspaceId = view.workspaceId?.toString(),
                businessNumber = view.businessNumber,
                companyName = view.companyName,
                representativeName = view.representativeName,
                contactEmail = view.contactEmail,
                address = view.address,
                periodFrom = view.periodFrom.toString(),
                periodTo = view.periodTo.toString(),
                status = view.status.wireName,
                operatorNote = view.operatorNote,
                requestedAt = view.requestedAt,
                handledAt = view.handledAt,
            )
    }
}

/** `GET /workspaces/{workspace_id}/invoice-requests` 응답. 계약 `InvoiceRequestListResponse`. */
data class InvoiceRequestListResponse(
    @get:JsonProperty("items") val items: List<InvoiceRequestResponse>,
)
