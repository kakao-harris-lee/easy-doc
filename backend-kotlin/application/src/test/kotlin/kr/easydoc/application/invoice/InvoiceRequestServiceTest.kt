package kr.easydoc.application.invoice

import kr.easydoc.application.mail.MailDelivery
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.invoice.BusinessNumber
import kr.easydoc.core.invoice.InvoiceRequestStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** 세금계산서 요청 유스케이스 — Spring 도 DB 도 실제 메일 발송도 없이 대역으로 돈다. */
class InvoiceRequestServiceTest {
    @Test
    @DisplayName("유효한 요청은 저장소에 넘겨지고 운영자·요청자 메일 각 1통을 보낸다")
    fun `유효한 요청은 메일 두 통을 보낸다`() {
        val world = InvoiceWorld()

        val view = world.service.create(world.ownerId, world.workspaceId, world.validInput())

        assertThat(view.status).isEqualTo(InvoiceRequestStatus.REQUESTED)
        assertThat(view.businessNumber).isEqualTo(validBusinessNumber())
        assertThat(world.mail.sent).hasSize(2)
        assertThat(world.mail.sent.map { it.to.value }).containsExactlyInAnyOrder(
            OPERATOR_EMAIL,
            REQUESTER_EMAIL,
        )
    }

    @Test
    @DisplayName("체크섬이 틀린 사업자번호는 422로 매핑될 예외이고, 저장소에 아무것도 넘어가지 않는다")
    fun `체크섬이 틀리면 저장소를 부르지 않는다`() {
        val world = InvoiceWorld()

        assertThatThrownBy {
            world.service.create(world.ownerId, world.workspaceId, world.validInput(businessNumber = "1234567890"))
        }.isInstanceOf(InvalidInputException::class.java)

        assertThat(world.repository.createCalls).isZero()
        assertThat(world.mail.sent).isEmpty()
    }

    @Test
    @DisplayName("기간이 366일을 넘으면 422다")
    fun `기간이 너무 넓으면 거절된다`() {
        val world = InvoiceWorld()

        assertThatThrownBy {
            world.service.create(
                world.ownerId,
                world.workspaceId,
                world.validInput(periodFrom = "2025-01-01", periodTo = "2026-12-31"),
            )
        }.isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    @DisplayName("period_to가 period_from보다 앞이면 422다")
    fun `기간이 뒤집히면 거절된다`() {
        val world = InvoiceWorld()

        assertThatThrownBy {
            world.service.create(
                world.ownerId,
                world.workspaceId,
                world.validInput(periodFrom = "2026-09-01", periodTo = "2026-08-01"),
            )
        }.isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    @DisplayName("남의 워크스페이스(또는 없는 워크스페이스)는 404로 매핑될 예외다")
    fun `소유가 아니면 404다`() {
        val world = InvoiceWorld()
        world.repository.nextCreationResult = InvoiceRequestCreation.WorkspaceNotFound

        assertThatThrownBy { world.service.create(world.ownerId, world.workspaceId, world.validInput()) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(InvoiceRequestService.WORKSPACE_NOT_FOUND_MESSAGE)
        assertThat(world.mail.sent).isEmpty()
    }

    @Test
    @DisplayName("같은 기간의 요청이 이미 처리 대기 중이면 409로 매핑될 예외다")
    fun `중복 기간은 409다`() {
        val world = InvoiceWorld()
        world.repository.nextCreationResult = InvoiceRequestCreation.DuplicateOpenPeriod

        assertThatThrownBy { world.service.create(world.ownerId, world.workspaceId, world.validInput()) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(InvoiceRequestService.DUPLICATE_OPEN_PERIOD_MESSAGE)
        assertThat(world.mail.sent).isEmpty()
    }

    @Test
    @DisplayName("운영자 주소가 비어 있으면 운영자 메일 없이 요청자 메일 1통만 보낸다")
    fun `운영자 주소 미설정은 요청자 메일만 보낸다`() {
        val world = InvoiceWorld(operatorEmail = "")

        world.service.create(world.ownerId, world.workspaceId, world.validInput())

        assertThat(world.mail.sent).hasSize(1)
        assertThat(
            world.mail.sent
                .single()
                .to.value,
        ).isEqualTo(REQUESTER_EMAIL)
    }

    @Test
    @DisplayName("메일 발송이 실패해도 요청 자체는 성공한다 — 최선 노력")
    fun `메일 발송 실패는 요청을 실패시키지 않는다`() {
        val world = InvoiceWorld(mailFails = true)

        val view = world.service.create(world.ownerId, world.workspaceId, world.validInput())

        assertThat(view.status).isEqualTo(InvoiceRequestStatus.REQUESTED)
    }

    @Test
    @DisplayName("목록은 저장소 값을 그대로 뷰로 옮긴다")
    fun `목록은 저장소를 그대로 옮긴다`() {
        val world = InvoiceWorld()
        world.service.create(world.ownerId, world.workspaceId, world.validInput())

        val items = world.service.list(world.ownerId, world.workspaceId)

        assertThat(items).hasSize(1)
        assertThat(items.single().companyName).isEqualTo("쉬운글 주식회사")
    }

    @Test
    @DisplayName("남의 워크스페이스(또는 없는 워크스페이스)의 목록 조회는 404다")
    fun `목록 조회도 소유가 아니면 404다`() {
        val world = InvoiceWorld()
        world.repository.unownedWorkspaceIds += world.workspaceId

        assertThatThrownBy { world.service.list(world.ownerId, world.workspaceId) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(InvoiceRequestService.WORKSPACE_NOT_FOUND_MESSAGE)
    }

    @Test
    @DisplayName("발급 처리는 상태·handled_at을 바꾸고 요청자에게 메일을 보낸다")
    fun `발급 처리는 요청자 메일을 보낸다`() {
        val world = InvoiceWorld()
        val created = world.service.create(world.ownerId, world.workspaceId, world.validInput())
        world.mail.sent.clear()

        val handled = world.service.handle(created.id, InvoiceRequestStatus.ISSUED, note = null)

        assertThat(handled.status).isEqualTo(InvoiceRequestStatus.ISSUED)
        assertThat(handled.handledAt).isNotNull()
        assertThat(
            world.mail.sent
                .single()
                .to.value,
        ).isEqualTo(REQUESTER_EMAIL)
        assertThat(
            world.mail.sent
                .single()
                .subject,
        ).contains("발급")
    }

    @Test
    @DisplayName("거절 처리는 메모를 함께 저장하고 메일 본문에 사유를 담는다")
    fun `거절 처리는 메모를 담는다`() {
        val world = InvoiceWorld()
        val created = world.service.create(world.ownerId, world.workspaceId, world.validInput())
        world.mail.sent.clear()

        val handled = world.service.handle(created.id, InvoiceRequestStatus.REJECTED, note = "사업자번호 확인 불가")

        assertThat(handled.status).isEqualTo(InvoiceRequestStatus.REJECTED)
        assertThat(handled.operatorNote).isEqualTo("사업자번호 확인 불가")
        assertThat(
            world.mail.sent
                .single()
                .textBody,
        ).contains("사업자번호 확인 불가")
    }

    @Test
    @DisplayName("존재하지 않는 요청 id는 404로 매핑될 예외다")
    fun `없는 id는 404다`() {
        val world = InvoiceWorld()

        assertThatThrownBy { world.service.handle(UUID.randomUUID(), InvoiceRequestStatus.ISSUED, null) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(InvoiceRequestService.REQUEST_NOT_FOUND_MESSAGE)
    }

    @Test
    @DisplayName("이미 처리된 요청을 다시 처리하면 409로 매핑될 예외다")
    fun `이미 처리된 요청은 409다`() {
        val world = InvoiceWorld()
        val created = world.service.create(world.ownerId, world.workspaceId, world.validInput())
        world.service.handle(created.id, InvoiceRequestStatus.ISSUED, null)

        assertThatThrownBy { world.service.handle(created.id, InvoiceRequestStatus.REJECTED, null) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(InvoiceRequestService.ALREADY_HANDLED_MESSAGE)
    }

    private companion object {
        const val OPERATOR_EMAIL = "billing-operator@example.test"
        const val REQUESTER_EMAIL = "requester@example.test"

        /**
         * 이 파일 전용 유효 사업자번호 — `BusinessNumberTest`와 같은 알고리즘으로 체크
         * 자릿수를 계산해 만든다(리터럴을 그대로 신뢰하지 않는다).
         */
        fun validBusinessNumber(): String {
            val nine = "123456789"
            val digits = nine.map { it - '0' }
            val weights = intArrayOf(1, 3, 7, 1, 3, 7, 1, 3, 5)
            var sum = 0
            for (index in weights.indices) sum += digits[index] * weights[index]
            sum += (digits[8] * 5) / 10
            val check = (10 - sum % 10) % 10
            return "$nine$check"
        }
    }

    /** 유스케이스 하나를 돌리는 데 필요한 최소 세계. */
    private class InvoiceWorld(
        operatorEmail: String = OPERATOR_EMAIL,
        mailFails: Boolean = false,
    ) {
        val ownerId: UUID = UUID.randomUUID()
        val workspaceId: UUID = UUID.randomUUID()
        val repository = RecordingInvoiceRequestRepository()
        val mail = RecordingMailSender(fails = mailFails)
        val service =
            InvoiceRequestService(
                repository = repository,
                mail = mail,
                operatorEmail = operatorEmail,
                clock = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
            )

        fun validInput(
            businessNumber: String = validBusinessNumber(),
            periodFrom: String = "2026-08-01",
            periodTo: String = "2026-08-31",
        ): InvoiceRequestInput =
            InvoiceRequestInput(
                businessNumber = businessNumber,
                companyName = "쉬운글 주식회사",
                representativeName = "홍길동",
                contactEmail = REQUESTER_EMAIL,
                address = "서울시 어딘가",
                periodFrom = periodFrom,
                periodTo = periodTo,
            )
    }

    /** [InvoiceRequestRepository] 대역 — 발급된 값을 그대로 기억한다. */
    private class RecordingInvoiceRequestRepository : InvoiceRequestRepository {
        var createCalls: Int = 0
            private set

        /** 다음 [create] 호출이 낼 결과를 미리 정한다. `null`이면 정상 생성한다. */
        var nextCreationResult: InvoiceRequestCreation? = null

        private val rows = mutableMapOf<UUID, InvoiceRequestRow>()

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
        ): InvoiceRequestCreation {
            createCalls++
            nextCreationResult?.let { return it }
            val row =
                InvoiceRequestRow(
                    id = id,
                    workspaceId = workspaceId,
                    ownerUserId = ownerId,
                    businessNumber = businessNumber,
                    companyName = companyName,
                    representativeName = representativeName,
                    contactEmail = contactEmail,
                    address = address,
                    periodFrom = periodFrom,
                    periodTo = periodTo,
                    status = InvoiceRequestStatus.REQUESTED,
                    operatorNote = null,
                    requestedAt = requestedAt,
                    handledAt = null,
                )
            rows[id] = row
            return InvoiceRequestCreation.Created(row)
        }

        /** 이 대역에서는 「소유하지 않음」을 이 집합으로 흉내 낸다. */
        val unownedWorkspaceIds = mutableSetOf<UUID>()

        override fun listForOwner(
            ownerId: UUID,
            workspaceId: UUID,
            limit: Int,
        ): List<InvoiceRequestRow>? {
            if (workspaceId in unownedWorkspaceIds) return null
            return rows.values
                .filter { it.ownerUserId == ownerId && it.workspaceId == workspaceId }
                .sortedByDescending { it.requestedAt }
                .take(limit)
        }

        override fun handle(
            id: UUID,
            status: InvoiceRequestStatus,
            note: String?,
            handledAt: Instant,
            handledBy: UUID?,
        ): InvoiceRequestHandling {
            val existing = rows[id]
            return when {
                existing == null -> {
                    InvoiceRequestHandling.NotFound
                }

                existing.status != InvoiceRequestStatus.REQUESTED -> {
                    InvoiceRequestHandling.AlreadyHandled
                }

                else -> {
                    val updated = existing.copy(status = status, operatorNote = note, handledAt = handledAt)
                    rows[id] = updated
                    InvoiceRequestHandling.Handled(updated)
                }
            }
        }

        override fun listAll(
            status: InvoiceRequestStatus?,
            page: Int,
            size: Int,
        ): InvoiceRequestPage {
            val filtered =
                rows.values
                    .filter { status == null || it.status == status }
                    .sortedByDescending { it.requestedAt }
            val offset = (page - 1) * size
            return InvoiceRequestPage(filtered.drop(offset).take(size), filtered.size)
        }
    }

    private class RecordingMailSender(private val fails: Boolean) : MailSender {
        val sent: MutableList<OutboundMail> = mutableListOf()

        override fun send(message: OutboundMail): MailDelivery {
            if (fails) error("네트워크 실패")
            sent += message
            return MailDelivery.Sent()
        }
    }
}
