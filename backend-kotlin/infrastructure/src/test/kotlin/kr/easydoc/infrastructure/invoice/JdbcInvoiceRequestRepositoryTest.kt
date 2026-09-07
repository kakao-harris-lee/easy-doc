package kr.easydoc.infrastructure.invoice

import kr.easydoc.application.invoice.InvoiceRequestCreation
import kr.easydoc.application.invoice.InvoiceRequestHandling
import kr.easydoc.core.invoice.InvoiceRequestStatus
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/** `invoice_requests` — 실제 PostgreSQL에서만 잴 수 있는 것들. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcInvoiceRequestRepositoryTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var repository: JdbcInvoiceRequestRepository

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("invoice_request_repository")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        jdbc = JdbcClient.create(dataSource())
        repository = JdbcInvoiceRequestRepository(jdbc)
    }

    @Test
    @DisplayName("소유한 워크스페이스로 요청하면 만들어지고 상태는 requested다")
    fun `요청은 만들어진다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()

        val result =
            repository.create(
                id = UUID.randomUUID(),
                ownerId = ownerId,
                workspaceId = workspaceId,
                businessNumber = "2208162517",
                companyName = "쉬운글 주식회사",
                representativeName = "홍길동",
                contactEmail = "req@example.test",
                address = "서울시 어딘가",
                periodFrom = LocalDate.of(2026, 8, 1),
                periodTo = LocalDate.of(2026, 8, 31),
                requestedAt = Instant.parse("2026-09-07T00:00:00Z"),
            )

        assertThat(result).isInstanceOf(InvoiceRequestCreation.Created::class.java)
        val row = (result as InvoiceRequestCreation.Created).row
        assertThat(row.workspaceId).isEqualTo(workspaceId)
        assertThat(row.businessNumber).isEqualTo("2208162517")
        assertThat(row.status).isEqualTo(InvoiceRequestStatus.REQUESTED)
        assertThat(row.handledAt).isNull()
    }

    @Test
    @DisplayName("남의 워크스페이스로 요청하면 WorkspaceNotFound다 — 행이 남지 않는다")
    fun `남의 워크스페이스는 WorkspaceNotFound다`() {
        val (_, workspaceId) = newOwnedWorkspace()
        val stranger = UUID.randomUUID()
        newUser(stranger)

        val result =
            repository.create(
                id = UUID.randomUUID(),
                ownerId = stranger,
                workspaceId = workspaceId,
                businessNumber = "2208162517",
                companyName = "쉬운글 주식회사",
                representativeName = null,
                contactEmail = "req@example.test",
                address = null,
                periodFrom = LocalDate.of(2026, 8, 1),
                periodTo = LocalDate.of(2026, 8, 31),
                requestedAt = Instant.now(),
            )

        assertThat(result).isEqualTo(InvoiceRequestCreation.WorkspaceNotFound)
        assertThat(
            database.queryInt("SELECT count(*) FROM invoice_requests WHERE workspace_id = '$workspaceId'"),
        ).isZero()
    }

    @Test
    @DisplayName("같은 워크스페이스·같은 기간의 requested 요청이 이미 있으면 DuplicateOpenPeriod다")
    fun `같은 기간 중복은 DuplicateOpenPeriod다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        createRequest(ownerId, workspaceId, from = LocalDate.of(2026, 8, 1), to = LocalDate.of(2026, 8, 31))

        val second =
            repository.create(
                id = UUID.randomUUID(),
                ownerId = ownerId,
                workspaceId = workspaceId,
                businessNumber = "2208162517",
                companyName = "쉬운글 주식회사",
                representativeName = null,
                contactEmail = "req2@example.test",
                address = null,
                periodFrom = LocalDate.of(2026, 8, 1),
                periodTo = LocalDate.of(2026, 8, 31),
                requestedAt = Instant.now(),
            )

        assertThat(second).isEqualTo(InvoiceRequestCreation.DuplicateOpenPeriod)
    }

    @Test
    @DisplayName("처리(issued)된 뒤에는 같은 기간을 다시 요청할 수 있다 — 부분 유니크 색인이 requested만 막는다")
    fun `처리된 뒤에는 같은 기간을 다시 요청할 수 있다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        val first = createRequest(ownerId, workspaceId, from = LocalDate.of(2026, 8, 1), to = LocalDate.of(2026, 8, 31))
        repository.handle(first.id, InvoiceRequestStatus.ISSUED, note = null, handledAt = Instant.now())

        val second =
            repository.create(
                id = UUID.randomUUID(),
                ownerId = ownerId,
                workspaceId = workspaceId,
                businessNumber = "2208162517",
                companyName = "쉬운글 주식회사",
                representativeName = null,
                contactEmail = "req2@example.test",
                address = null,
                periodFrom = LocalDate.of(2026, 8, 1),
                periodTo = LocalDate.of(2026, 8, 31),
                requestedAt = Instant.now(),
            )

        assertThat(second).isInstanceOf(InvoiceRequestCreation.Created::class.java)
    }

    @Test
    @DisplayName("목록은 최신순으로 최근 limit건만 준다")
    fun `목록은 최신순이다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        val first =
            createRequest(ownerId, workspaceId, from = LocalDate.of(2026, 1, 1), to = LocalDate.of(2026, 1, 31))
        val second =
            createRequest(ownerId, workspaceId, from = LocalDate.of(2026, 2, 1), to = LocalDate.of(2026, 2, 28))

        val items = repository.listForOwner(ownerId, workspaceId, limit = 50)

        assertThat(items).isNotNull()
        assertThat(items!!.map { it.id }).containsExactly(second.id, first.id)
    }

    @Test
    @DisplayName("남의 워크스페이스 목록 조회는 null이다 — 빈 목록과 구분한다")
    fun `남의 워크스페이스 목록은 null이다`() {
        val (_, workspaceId) = newOwnedWorkspace()
        val stranger = UUID.randomUUID()
        newUser(stranger)

        assertThat(repository.listForOwner(stranger, workspaceId, limit = 50)).isNull()
    }

    @Test
    @DisplayName("발급 처리는 상태·메모·handled_at을 갱신한다")
    fun `발급 처리는 상태를 갱신한다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        val created = createRequest(ownerId, workspaceId)
        val handledAt = Instant.parse("2026-09-08T00:00:00Z")

        val result = repository.handle(created.id, InvoiceRequestStatus.ISSUED, note = null, handledAt = handledAt)

        assertThat(result).isInstanceOf(InvoiceRequestHandling.Handled::class.java)
        val row = (result as InvoiceRequestHandling.Handled).row
        assertThat(row.status).isEqualTo(InvoiceRequestStatus.ISSUED)
        assertThat(row.handledAt).isEqualTo(handledAt)
    }

    @Test
    @DisplayName("거절 처리는 운영자 메모를 저장한다")
    fun `거절 처리는 메모를 저장한다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        val created = createRequest(ownerId, workspaceId)

        val result =
            repository.handle(created.id, InvoiceRequestStatus.REJECTED, note = "확인 불가", handledAt = Instant.now())

        val row = (result as InvoiceRequestHandling.Handled).row
        assertThat(row.status).isEqualTo(InvoiceRequestStatus.REJECTED)
        assertThat(row.operatorNote).isEqualTo("확인 불가")
    }

    @Test
    @DisplayName("존재하지 않는 id는 NotFound다")
    fun `존재하지 않는 id는 NotFound다`() {
        val result = repository.handle(UUID.randomUUID(), InvoiceRequestStatus.ISSUED, null, Instant.now())

        assertThat(result).isEqualTo(InvoiceRequestHandling.NotFound)
    }

    @Test
    @DisplayName("이미 처리된 요청을 다시 처리하면 AlreadyHandled다")
    fun `이미 처리된 요청은 AlreadyHandled다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace()
        val created = createRequest(ownerId, workspaceId)
        repository.handle(created.id, InvoiceRequestStatus.ISSUED, null, Instant.now())

        val second = repository.handle(created.id, InvoiceRequestStatus.REJECTED, null, Instant.now())

        assertThat(second).isEqualTo(InvoiceRequestHandling.AlreadyHandled)
    }

    private fun createRequest(
        ownerId: UUID,
        workspaceId: UUID,
        from: LocalDate = LocalDate.of(2026, 8, 1),
        to: LocalDate = LocalDate.of(2026, 8, 31),
    ) = (
        repository.create(
            id = UUID.randomUUID(),
            ownerId = ownerId,
            workspaceId = workspaceId,
            businessNumber = "2208162517",
            companyName = "쉬운글 주식회사",
            representativeName = "홍길동",
            contactEmail = "req-${UUID.randomUUID()}@example.test",
            address = "서울시 어딘가",
            periodFrom = from,
            periodTo = to,
            requestedAt = Instant.now(),
        ) as InvoiceRequestCreation.Created
    ).row

    private fun newUser(id: UUID) {
        jdbc
            .sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :email, :hash)")
            .param("id", id)
            .param("email", "invoice-$id@example.test")
            .param("hash", "\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA")
            .update()
    }

    private fun newOwnedWorkspace(): Pair<UUID, UUID> {
        val ownerId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        newUser(ownerId)
        jdbc
            .sql("INSERT INTO workspaces (id, user_id, name) VALUES (:id, :userId, :name)")
            .param("id", workspaceId)
            .param("userId", ownerId)
            .param("name", "invoice-ws-${workspaceId.toString().take(8)}")
            .update()
        return ownerId to workspaceId
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
}
