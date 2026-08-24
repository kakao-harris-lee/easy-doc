package kr.easydoc.infrastructure.auth

import kr.easydoc.application.workspace.WorkspaceService
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** 작업 공간 저장소 — 실제 PostgreSQL 에서만 잴 수 있는 것들. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcWorkspaceRepositoryTest {
    private lateinit var database: DatabaseHandle
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var transaction: SpringTransactionRunner
    private lateinit var service: WorkspaceService
    private lateinit var jdbcClient: JdbcClient
    private lateinit var counting: CountingDataSource
    private lateinit var countedService: WorkspaceService

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("workspace_repository")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val dataSource = dataSource()
        jdbcClient = JdbcClient.create(dataSource)
        users = JdbcUserRepository(jdbcClient)
        workspaces = JdbcWorkspaceRepository(jdbcClient)
        transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource)))
        service = WorkspaceService(workspaces, transaction)

        counting = CountingDataSource(dataSource())
        countedService =
            WorkspaceService(
                JdbcWorkspaceRepository(JdbcClient.create(counting)),
                SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(counting))),
            )
    }

    @Test
    @DisplayName("목록이 소유자 범위이고 만든 순서다")
    fun `목록이 소유자 범위이고 만든 순서다`() {
        val owner = newUser()
        val stranger = newUser()
        val first = workspaces.create(owner, "가")
        val second = workspaces.create(owner, "나")
        workspaces.create(stranger, "남의 것")

        val listed = workspaces.listOwned(owner)

        assertThat(listed.map { it.workspace.id }).containsExactly(first.id, second.id)
        assertThat(listed.map { it.documentCount }).containsOnly(0)
    }

    @Test
    @DisplayName("document_count 가 LEFT JOIN 의 NULL 행을 세지 않는다 — count(*) 로 쓰면 빈 공간이 1 이 된다")
    fun `빈 작업 공간의 문서 수가 0 이다`() {
        val owner = newUser()
        val empty = workspaces.create(owner, "빈 곳")
        val filled = workspaces.create(owner, "찬 곳")
        insertDocument(owner, filled.id)

        val counts = workspaces.listOwned(owner).associate { it.workspace.id to it.documentCount }

        assertThat(counts[empty.id]).isZero()
        assertThat(counts[filled.id]).isEqualTo(1)
    }

    @Test
    @DisplayName("같은 사용자 안에서만 이름이 유일하다 — 유일 인덱스가 던지는 것이 409 로 옮겨진다")
    fun `이름 유일성이 사용자 범위다`() {
        val owner = newUser()
        val stranger = newUser()
        workspaces.create(owner, "같은 이름")

        assertThatThrownBy { workspaces.create(owner, "같은 이름") }.isInstanceOf(ConflictException::class.java)

        assertThat(workspaces.create(stranger, "같은 이름").name).isEqualTo("같은 이름")
    }

    @Test
    @DisplayName("남의 작업 공간은 이름을 바꿀 수 없고, 그 실패가 「없음」과 같은 null 이다")
    fun `남의 작업 공간은 바꿀 수 없다`() {
        val owner = newUser()
        val stranger = newUser()
        val mine = workspaces.create(owner, "내 것")

        assertThat(workspaces.rename(stranger, mine.id, "빼앗기")).isNull()
        assertThat(workspaces.rename(stranger, UUID.randomUUID(), "빼앗기")).isNull()

        assertThat(
            workspaces
                .listOwned(owner)
                .single()
                .workspace.name,
        ).isEqualTo("내 것")
    }

    @Test
    @DisplayName("남의 작업 공간 식별자로 이미 쓰인 이름을 보내도 409 가 아니라 null 이다 — 409 는 존재 신호다")
    fun `남의 자원에서는 유일성 위반이 일어나지 않는다`() {
        val owner = newUser()
        val stranger = newUser()
        val target = workspaces.create(owner, "가")
        workspaces.create(owner, "나")

        assertThat(workspaces.rename(stranger, target.id, "나")).isNull()
    }

    @Test
    @DisplayName("문서가 남은 작업 공간은 외래 키가 막고, 그 위반이 409 로 옮겨진다")
    fun `외래 키가 문서 든 작업 공간을 막는다`() {
        val owner = newUser()
        val keep = workspaces.create(owner, "남길 곳")
        val target = workspaces.create(owner, "지울 곳")
        insertDocument(owner, target.id)

        assertThatThrownBy { workspaces.delete(owner, target.id) }.isInstanceOf(ConflictException::class.java)
        assertThat(workspaces.listOwned(owner).map { it.workspace.id }).contains(target.id, keep.id)
    }

    @Test
    @DisplayName("마지막 하나는 지워지지 않는다 — 삭제 판정이 집합에 걸린다")
    fun `마지막 하나는 지워지지 않는다`() {
        val owner = newUser()
        val only = workspaces.create(owner, "하나뿐")

        assertThatThrownBy { service.delete(owner, only.id) }.isInstanceOf(ConflictException::class.java)
        assertThat(workspaces.listOwned(owner)).hasSize(1)
    }

    /** A-2 — `delete` 의 「터질 수 있는 제약은 하나뿐」 전제를 지키는 장치. */
    @Test
    @DisplayName("workspaces 를 참조하는 외래 키가 정확히 하나다 — delete 의 409 단정이 서는 전제")
    fun `작업 공간을 참조하는 제약이 하나뿐이다`() {
        val referencing =
            jdbcClient
                .sql(
                    """
                    SELECT conname FROM pg_constraint
                    WHERE contype = 'f' AND confrelid = 'workspaces'::regclass
                    """.trimIndent(),
                ).query { rs, _ -> rs.getString("conname") }
                .list()

        assertThat(referencing)
            .withFailMessage(
                "workspaces 를 참조하는 FK 가 %s 다. delete 는 무결성 위반을 메시지도 보지 않고 " +
                    "「문서가 남아 있습니다」 409 로 옮기므로, 참조가 늘면 다른 위반이 그 문구로 둔갑한다. " +
                    "제약을 늘렸다면 delete 의 예외 분류를 함께 고친다.",
                referencing,
            ).containsExactly(DOCUMENTS_WORKSPACE_FK)
    }

    /** X-3ⓒ + F-4 — 「같은 DB 왕복 구조」를 요청 하나 단위로 강제한다. */
    @Test
    @DisplayName("이름 변경 요청 하나가 소유 결과와 무관하게 같은 수의 SQL 문을 낸다 — 서비스 경계 기준")
    fun `이름 변경의 왕복 구조가 소유 여부로 갈리지 않는다`() {
        val owner = newUser()
        val stranger = newUser()
        val mine = workspaces.create(owner, "내 것")
        val others = workspaces.create(stranger, "남의 것")

        val absentCount = countRequest { countedService.rename(owner, UUID.randomUUID(), "새 이름 1") }
        val othersCount = countRequest { countedService.rename(owner, others.id, "새 이름 2") }
        val mineCount = countRequest { countedService.rename(owner, mine.id, "새 이름 3") }

        assertThat(listOf(absentCount, othersCount, mineCount))
            .withFailMessage(
                "이름 변경 요청의 SQL 문 수가 소유 결과에 따라 갈리거나 %d 가 아니다 — 없음=%d 타인=%d 내것=%d. " +
                    "소유 조건이 WHERE 를 떠났거나(저장소), 유스케이스가 선행 조회를 얹었다(서비스).",
                RENAME_STATEMENTS,
                absentCount,
                othersCount,
                mineCount,
            ).containsExactly(RENAME_STATEMENTS, RENAME_STATEMENTS, RENAME_STATEMENTS)
    }

    /** 삭제 거절 두 갈래의 왕복 구조 — `lockForDeletion` 이 도는 자리다. */
    @Test
    @DisplayName("삭제 거절 두 갈래가 같은 수의 SQL 문을 내고, 성공 경로의 수도 못박힌다")
    fun `삭제의 왕복 구조가 소유 여부로 갈리지 않는다`() {
        val owner = newUser()
        val stranger = newUser()
        val others = workspaces.create(stranger, "남의 것")
        workspaces.create(owner, "내 것 1")
        val target = workspaces.create(owner, "내 것 2")

        val absentCount = countRequest { countedService.delete(owner, UUID.randomUUID()) }
        val othersCount = countRequest { countedService.delete(owner, others.id) }
        val ownedCount = countRequest { countedService.delete(owner, target.id) }

        assertThat(listOf(absentCount, othersCount))
            .withFailMessage(
                "삭제 거절 두 갈래의 SQL 문 수가 갈리거나 %d 가 아니다 — 없음=%d 타인=%d. " +
                    "존재 여부가 일하는 양으로 샌다.",
                DELETE_MISS_STATEMENTS,
                absentCount,
                othersCount,
            ).containsExactly(DELETE_MISS_STATEMENTS, DELETE_MISS_STATEMENTS)
        assertThat(ownedCount)
            .withFailMessage(
                "소유 자원 삭제가 %d 문장이 아니라 %d 문장이다 — 잠금·문서 수·삭제 말고 무엇이 늘었는지 확인하라.",
                DELETE_OWNED_STATEMENTS,
                ownedCount,
            ).isEqualTo(DELETE_OWNED_STATEMENTS)
    }

    /** 목록 한 번이 질의 한 번이다. */
    @Test
    @DisplayName("목록 요청 하나가 소유 자원 수와 무관하게 한 문장이다 — N+1 금지")
    fun `목록의 왕복 구조가 자원 수로 갈리지 않는다`() {
        val owner = newUser()
        val first = workspaces.create(owner, "목록 1")
        workspaces.create(owner, "목록 2")
        insertDocument(owner, first.id)

        val listed = countRequest { countedService.list(owner) }

        assertThat(listed)
            .withFailMessage(
                "목록이 %d 문장이 아니라 %d 문장이다 — 줄마다 COUNT 를 따로 내는 N+1 이 들어왔는지 확인하라.",
                LIST_STATEMENTS,
                listed,
            ).isEqualTo(LIST_STATEMENTS)
    }

    @Test
    @DisplayName("없는 자원과 남의 자원이 같은 NotFound 로 끝난다")
    fun `삭제도 소유권을 숨긴다`() {
        val owner = newUser()
        val stranger = newUser()
        workspaces.create(stranger, "남의 것 1")
        val others = workspaces.create(stranger, "남의 것 2")
        workspaces.create(owner, "내 것 1")
        workspaces.create(owner, "내 것 2")

        assertThatThrownBy { service.delete(owner, others.id) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { service.delete(owner, UUID.randomUUID()) }.isInstanceOf(NotFoundException::class.java)
    }

    /** 동시 삭제에서 마지막 하나가 남는다. */
    @Test
    @DisplayName("같은 사용자의 두 작업 공간을 동시에 지워도 하나는 남는다")
    fun `동시 삭제가 직렬화된다`() {
        val owner = newUser()
        val first = workspaces.create(owner, "동시 1")
        val second = workspaces.create(owner, "동시 2")
        val barrier = CyclicBarrier(CONCURRENT_DELETERS)
        val pool = Executors.newFixedThreadPool(CONCURRENT_DELETERS)

        val outcomes: List<Result<Unit>> =
            try {
                pool
                    .invokeAll(
                        listOf(first.id, second.id).map { id ->
                            Callable {
                                barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                                runCatching { service.delete(owner, id) }
                            }
                        },
                    ).map { it.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        val failures = outcomes.mapNotNull { it.exceptionOrNull() }
        assertThat(outcomes.count { it.isSuccess })
            .withFailMessage("성공이 정확히 한 건이 아니다 — 결과: %s", outcomes)
            .isEqualTo(1)

        assertThat(failures)
            .withFailMessage("둘째 요청이 ConflictException 이 아닌 것으로 실패했다: %s", failures)
            .singleElement()
            .isInstanceOf(ConflictException::class.java)
        assertThat(workspaces.listOwned(owner)).hasSize(1)
    }

    private fun newUser(): UUID = users.create(uniqueEmail(), FIXTURE_HASH).id

    private fun uniqueEmail(): String = "workspace-repo${counter++}@example.test"

    /** 문서 API 는 Phase 4 다. 여기서 필요한 것은 행의 존재뿐이라 직접 심는다. */
    private fun insertDocument(
        ownerId: UUID,
        workspaceId: UUID,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO documents
                    (id, user_id, title, source_format, source_text_encrypted, char_count, workspace_id,
                     encryption_scheme, key_version)
                VALUES (:id, :userId, 'fixture', 'docx', :bytes, 1, :workspaceId, :scheme, 1)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("userId", ownerId)
            .param("bytes", byteArrayOf(0))
            .param("workspaceId", workspaceId)
            .param("scheme", EncryptionScheme.AES_256_GCM_V1)
            .update()
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    /** 유스케이스 호출 한 번이 내는 SQL 문 수. */
    private fun countRequest(request: () -> Unit): Int = counting.countStatements { runCatching(request) }

    private companion object {
        var counter = 0

        /** 형태만 맞으면 되는 더미 PHC — 이 파일은 비밀번호 검증을 재지 않는다. */
        val FIXTURE_HASH = PasswordHash("\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA")

        /** 이름 변경 요청 하나가 도는 SQL 문 수. 소유 판정과 갱신이 한 문장이라 1 이다. */
        const val RENAME_STATEMENTS = 1

        /** 삭제가 거절되는 두 갈래(없음·타인)가 도는 SQL 문 수 — `lockForDeletion` 의 잠금 질의 하나. */
        const val DELETE_MISS_STATEMENTS = 1

        /** 소유 자원 삭제가 도는 SQL 문 수 — 잠금 + 문서 수 + DELETE. */
        const val DELETE_OWNED_STATEMENTS = 3

        /** 목록 요청 하나가 도는 SQL 문 수. 문서 수가 같은 질의에 담기므로 1 이다. */
        const val LIST_STATEMENTS = 1

        /** `V1__initial_schema.sql` 이 준 제약 이름이다. 값이 아니라 이름이다. */
        const val DOCUMENTS_WORKSPACE_FK = "fk_documents_workspace_id_workspaces"

        const val CONCURRENT_DELETERS = 2
        const val BARRIER_TIMEOUT_SECONDS = 10L
        const val TASK_TIMEOUT_SECONDS = 30L
    }
}
