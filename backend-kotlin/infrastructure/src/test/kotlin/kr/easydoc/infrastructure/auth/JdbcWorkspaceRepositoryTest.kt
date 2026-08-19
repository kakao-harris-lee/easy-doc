package kr.easydoc.infrastructure.auth

import kr.easydoc.application.workspace.WorkspaceService
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

/**
 * 작업 공간 저장소 — **실제 PostgreSQL 에서만 잴 수 있는 것들**.
 *
 * HTTP 계약 테스트가 응답을 재는 동안 이 파일은 그 응답이 서는 **바닥**을 잰다. 인메모리
 * 대역으로는 다음 셋을 잴 수 없고, 그 셋이 이 파일의 이유다.
 *
 * ⑴ **행 잠금** — 「마지막 하나」 판정은 행 하나가 아니라 **집합**에 걸린다. 대상 행만
 *    잠그는 구현은 단일 요청 테스트를 전부 통과하고 동시 삭제에서만 무너진다.
 * ⑵ **외래 키가 실제로 막는다** — `documents.workspace_id` 는 `ON DELETE` 가 없어(NO ACTION)
 *    문서가 든 작업 공간을 DB 가 지우지 못하게 한다. 유스케이스의 사전 확인과 DELETE
 *    사이에 문서가 들어오는 창은 그것 말고는 아무것도 막지 않는다.
 * ⑶ **유일 인덱스가 던지는 예외의 종류** — `uq_workspaces_user_id_name` 이 만드는 것이
 *    `DuplicateKeyException` 인지, 그래서 409 로 옮겨지는지.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcWorkspaceRepositoryTest {
    private lateinit var database: DatabaseHandle
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var transaction: SpringTransactionRunner
    private lateinit var service: WorkspaceService
    private lateinit var jdbcClient: JdbcClient

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
    }

    // ================================================================ 소유 범위

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
        // 전역 유일이면 여기서 깨진다.
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
        // 남의 것은 바뀌지 않았다.
        assertThat(workspaces.listOwned(owner).single().workspace.name).isEqualTo("내 것")
    }

    @Test
    @DisplayName("남의 작업 공간 식별자로 이미 쓰인 이름을 보내도 409 가 아니라 null 이다 — 409 는 존재 신호다")
    fun `남의 자원에서는 유일성 위반이 일어나지 않는다`() {
        val owner = newUser()
        val stranger = newUser()
        val target = workspaces.create(owner, "가")
        workspaces.create(owner, "나")

        // 소유 조건이 WHERE 에 없으면 여기서 ConflictException 이 나고, 그것이 곧
        // "그 식별자는 존재한다"는 신호가 된다.
        assertThat(workspaces.rename(stranger, target.id, "나")).isNull()
    }

    // ================================================================ 삭제

    @Test
    @DisplayName("문서가 남은 작업 공간은 외래 키가 막고, 그 위반이 409 로 옮겨진다")
    fun `외래 키가 문서 든 작업 공간을 막는다`() {
        val owner = newUser()
        val keep = workspaces.create(owner, "남길 곳")
        val target = workspaces.create(owner, "지울 곳")
        insertDocument(owner, target.id)

        // 유스케이스의 사전 확인을 건너뛰고 저장소를 직접 부른다 — 사전 확인과 DELETE
        // 사이에 문서가 들어오는 창에서 실제로 도는 것이 이 갈래다.
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

    /**
     * **동시 삭제에서 마지막 하나가 남는다.**
     *
     * 두 요청이 같은 사용자의 서로 **다른** 작업 공간을 동시에 지운다. 대상 행만 잠그는
     * 구현은 둘 다 "아직 둘 있다"를 보고 통과해 결과가 0 개가 된다 — 그러면 업로드가 갈
     * 곳을 잃고 「기본 작업 공간」이라는 기준도 가리킬 대상이 없어진다.
     *
     * 배리어로 두 스레드를 같은 순간에 풀어 잠금 구간이 실제로 겹치게 만든다. 겹치지
     * 않으면 이 테스트는 순차 실행과 같아져 아무것도 재지 않는다.
     */
    @Test
    @DisplayName("같은 사용자의 두 작업 공간을 동시에 지워도 하나는 남는다")
    fun `동시 삭제가 직렬화된다`() {
        val owner = newUser()
        val first = workspaces.create(owner, "동시 1")
        val second = workspaces.create(owner, "동시 2")
        val barrier = CyclicBarrier(CONCURRENT_DELETERS)
        val pool = Executors.newFixedThreadPool(CONCURRENT_DELETERS)

        val outcomes =
            try {
                pool
                    .invokeAll(
                        listOf(first.id, second.id).map { id ->
                            Callable {
                                barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                                runCatching { service.delete(owner, id) }.isSuccess
                            }
                        },
                    ).map { it.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        // 하나는 성공하고 하나는 거절돼야 한다. 둘 다 성공하면 잠금이 집합에 걸리지 않은 것이다.
        assertThat(outcomes).containsExactlyInAnyOrder(true, false)
        assertThat(workspaces.listOwned(owner)).hasSize(1)
    }

    // ================================================================ 픽스처

    private fun newUser(): UUID = users.create(uniqueEmail(), PasswordHash("\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA")).id

    private fun uniqueEmail(): String = "workspace-repo${counter++}@example.test"

    /** 문서 API 는 Phase 4 다. 여기서 필요한 것은 **행의 존재**뿐이라 직접 심는다. */
    private fun insertDocument(
        ownerId: UUID,
        workspaceId: UUID,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO documents (id, user_id, title, source_format, source_text_encrypted, char_count, workspace_id)
                VALUES (:id, :userId, 'fixture', 'docx', :bytes, 1, :workspaceId)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("userId", ownerId)
            .param("bytes", byteArrayOf(0))
            .param("workspaceId", workspaceId)
            .update()
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    private companion object {
        var counter = 0

        const val CONCURRENT_DELETERS = 2
        const val BARRIER_TIMEOUT_SECONDS = 10L
        const val TASK_TIMEOUT_SECONDS = 30L
    }
}
