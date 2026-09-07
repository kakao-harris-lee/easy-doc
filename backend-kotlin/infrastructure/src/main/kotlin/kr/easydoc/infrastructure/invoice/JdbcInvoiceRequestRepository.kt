package kr.easydoc.infrastructure.invoice

import kr.easydoc.application.invoice.InvoiceRequestCreation
import kr.easydoc.application.invoice.InvoiceRequestHandling
import kr.easydoc.application.invoice.InvoiceRequestPage
import kr.easydoc.application.invoice.InvoiceRequestRepository
import kr.easydoc.application.invoice.InvoiceRequestRow
import kr.easydoc.core.invoice.InvoiceRequestStatus
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** `invoice_requests` 접근. 스키마는 `V16__invoice_requests.sql`. */
class JdbcInvoiceRequestRepository(private val jdbc: JdbcClient) : InvoiceRequestRepository {
    /**
     * 소유 술어는 `INSERT ... SELECT ... WHERE EXISTS` 안에 있다 — `EXISTS` 가 거짓이면
     * (남의 워크스페이스거나 없는 워크스페이스) 0행이 들어간다. 부분 유니크 색인
     * (`ux_invoice_requests_open_period`)이 같은 기간의 `requested` 중복을 막는다.
     */
    @Suppress("LongParameterList")
    override fun create(
        id: UUID,
        ownerId: UUID,
        workspaceId: UUID,
        businessNumber: String,
        companyName: String,
        representativeName: String?,
        contactEmail: String,
        address: String?,
        periodFrom: LocalDate,
        periodTo: LocalDate,
        requestedAt: Instant,
    ): InvoiceRequestCreation =
        try {
            val inserted =
                jdbc
                    .sql(CREATE_SQL)
                    .param("id", id)
                    .param("workspaceId", workspaceId)
                    .param("ownerId", ownerId)
                    .param("businessNumber", businessNumber)
                    .param("companyName", companyName)
                    .param("representativeName", representativeName)
                    .param("contactEmail", contactEmail)
                    .param("address", address)
                    .param("periodFrom", periodFrom)
                    .param("periodTo", periodTo)
                    .param("requestedAt", requestedAt.atOffset(java.time.ZoneOffset.UTC))
                    .query { rs, _ -> toRow(rs) }
                    .optional()
            if (inserted.isPresent) {
                InvoiceRequestCreation.Created(inserted.get())
            } else {
                InvoiceRequestCreation.WorkspaceNotFound
            }
        } catch (_: DuplicateKeyException) {
            // **좁혀 잡는다** — 부분 유니크 색인(`ux_invoice_requests_open_period`) 위반만
            // 이 갈래로 온다. 상위 타입 `DataIntegrityViolationException` 을 잡으면 CHECK
            // 제약(사업자번호 형식·기간 순서)·FK 위반 같은 **다른** 무결성 위반까지 전부
            // 「같은 기간의 요청이 처리 대기 중입니다」(409)로 잘못 보고하게 된다 — 그런
            // 위반은 여기서 잡지 않고 그대로 전파해 전역 예외 처리기가 500으로 낸다
            // (도메인이 이미 걸러야 했던 입력이 여기까지 왔다는 뜻이라 버그로 취급한다).
            InvoiceRequestCreation.DuplicateOpenPeriod
        }

    /**
     * 소유 확인을 먼저 하고(`JdbcCreditAccountRepository.read`와 같은 규약), 내 것이
     * 아니면 `null`을 돌려준다 — 빈 목록과 구분해야 404를 낼 수 있다.
     */
    override fun listForOwner(
        ownerId: UUID,
        workspaceId: UUID,
        limit: Int,
    ): List<InvoiceRequestRow>? {
        val owned =
            jdbc
                .sql("SELECT 1 FROM workspaces WHERE id = :workspaceId AND user_id = :ownerId")
                .param("workspaceId", workspaceId)
                .param("ownerId", ownerId)
                .query { rs, _ -> rs.getInt(1) }
                .optional()
                .isPresent
        if (!owned) return null

        return jdbc
            .sql(LIST_FOR_OWNER_SQL)
            .param("workspaceId", workspaceId)
            .param("limit", limit)
            .query { rs, _ -> toRow(rs) }
            .list()
    }

    /**
     * 소유 술어가 없다 — 운영자 전용(`invoice-handle` 프로필)이 id 하나로 처리한다
     * ([InvoiceRequestRepository] KDoc). 갱신이 0행이면 별도 조회로 「없음」과 「이미
     * 처리됨」을 가른다 — 단일 운영자 CLI 라 그 사이 경쟁을 걱정할 동시성이 없다.
     */
    override fun handle(
        id: UUID,
        status: InvoiceRequestStatus,
        note: String?,
        handledAt: Instant,
        handledBy: UUID?,
    ): InvoiceRequestHandling {
        val updated =
            jdbc
                .sql(HANDLE_SQL)
                .param("id", id)
                .param("status", status.wireName)
                .param("note", note)
                .param("handledAt", handledAt.atOffset(java.time.ZoneOffset.UTC))
                .param("handledBy", handledBy)
                .query { rs, _ -> toRow(rs) }
                .optional()
        if (updated.isPresent) {
            return InvoiceRequestHandling.Handled(updated.get())
        }
        val exists =
            jdbc
                .sql("SELECT 1 FROM invoice_requests WHERE id = :id")
                .param("id", id)
                .query { rs, _ -> rs.getInt(1) }
                .optional()
                .isPresent
        return if (exists) InvoiceRequestHandling.AlreadyHandled else InvoiceRequestHandling.NotFound
    }

    /**
     * 관리자 목록(`GET /admin/invoice-requests`) — 소유 술어 없이 전체를 훑는다
     * ([InvoiceRequestRepository] KDoc). [status]가 `null`이면 전체 상태.
     */
    override fun listAll(
        status: InvoiceRequestStatus?,
        page: Int,
        size: Int,
    ): InvoiceRequestPage {
        val statusWire = status?.wireName
        val total =
            jdbc
                .sql(
                    "SELECT count(*) FROM invoice_requests WHERE :status::text IS NULL OR status = :status",
                ).param("status", statusWire)
                .query { rs, _ -> rs.getInt(1) }
                .single()
        val items =
            jdbc
                .sql(
                    """
                    SELECT $RETURNING_COLUMNS
                    FROM invoice_requests
                    WHERE :status::text IS NULL OR status = :status
                    ORDER BY requested_at DESC, id DESC
                    LIMIT :limit OFFSET :offset
                    """.trimIndent(),
                ).param("status", statusWire)
                .param("limit", size)
                // Int 곱은 큰 page·size 조합에서 넘칠 수 있다 — Long 산술로 막는다
                // (`JdbcAdminWorkspaceQueryRepository.search`와 같은 방어).
                .param("offset", (page.toLong() - 1) * size)
                .query { rs, _ -> toRow(rs) }
                .list()
        return InvoiceRequestPage(items, total)
    }

    private fun toRow(rs: ResultSet): InvoiceRequestRow =
        InvoiceRequestRow(
            id = rs.getObject("id", UUID::class.java),
            workspaceId = rs.getObject("workspace_id", UUID::class.java),
            ownerUserId = rs.getObject("owner_user_id", UUID::class.java),
            businessNumber = rs.getString("business_number"),
            companyName = rs.getString("company_name"),
            representativeName = rs.getString("representative_name"),
            contactEmail = rs.getString("contact_email"),
            address = rs.getString("address"),
            periodFrom = rs.getObject("period_from", LocalDate::class.java),
            periodTo = rs.getObject("period_to", LocalDate::class.java),
            status = InvoiceRequestStatus.ofWireName(rs.getString("status")),
            operatorNote = rs.getString("operator_note"),
            requestedAt = rs.getObject("requested_at", OffsetDateTime::class.java).toInstant(),
            handledAt = rs.getObject("handled_at", OffsetDateTime::class.java)?.toInstant(),
        )

    private companion object {
        const val RETURNING_COLUMNS =
            "id, workspace_id, owner_user_id, business_number, company_name, representative_name, " +
                "contact_email, address, period_from, period_to, status, operator_note, requested_at, handled_at"

        val CREATE_SQL =
            """
            INSERT INTO invoice_requests (
                id, workspace_id, owner_user_id, business_number, company_name, representative_name,
                contact_email, address, period_from, period_to, status, requested_at
            )
            SELECT :id, :workspaceId, :ownerId, :businessNumber, :companyName, :representativeName,
                   :contactEmail, :address, :periodFrom, :periodTo, 'requested', :requestedAt
            WHERE EXISTS (SELECT 1 FROM workspaces WHERE id = :workspaceId AND user_id = :ownerId)
            RETURNING $RETURNING_COLUMNS
            """.trimIndent()

        val LIST_FOR_OWNER_SQL =
            """
            SELECT $RETURNING_COLUMNS
            FROM invoice_requests
            WHERE workspace_id = :workspaceId
            ORDER BY requested_at DESC, id DESC
            LIMIT :limit
            """.trimIndent()

        val HANDLE_SQL =
            """
            UPDATE invoice_requests
            SET status = :status, operator_note = :note, handled_at = :handledAt, handled_by = :handledBy
            WHERE id = :id AND status = 'requested'
            RETURNING $RETURNING_COLUMNS
            """.trimIndent()
    }
}
