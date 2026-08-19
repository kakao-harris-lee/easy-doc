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

        // 구조 축 계측용 두 번째 배선. 같은 DB 를 보되 나가는 SQL 문 수를 센다.
        //
        // **트랜잭션 관리자도 같은 DataSource 를 받아야 한다.** 다른 것을 주면
        // `delete` 의 `inTransaction` 이 계측되지 않은 커넥션을 잡아 `FOR UPDATE` 가
        // 유스케이스 밖에서 돌고, 그러면 세는 대상과 도는 대상이 갈린다.
        counting = CountingDataSource(dataSource())
        countedService =
            WorkspaceService(
                JdbcWorkspaceRepository(JdbcClient.create(counting)),
                SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(counting))),
            )
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

    /**
     * **A-2 — `delete` 의 「터질 수 있는 제약은 하나뿐」 전제를 지키는 장치.**
     *
     * `JdbcWorkspaceRepository.delete` 는 `DataIntegrityViolationException` 을 메시지도
     * SQLState 도 읽지 않고 곧장 「작업 공간에 문서가 남아 있습니다」 409 로 옮긴다.
     * 근거는 *"이 DELETE 에서 터질 수 있는 제약은 그 FK 하나뿐"* 이고, 오늘 참이다.
     *
     * **그 전제를 지키는 장치가 0이었다.** Phase 4·5 가 `workspaces` 를 참조하는 테이블을
     * 하나만 더 만들면 그 위반이 조용히 같은 409 로 둔갑한다 — 사용자는 문서를 다 지워도
     * 계속 거절당하고 원인을 알 수 없다. 참조가 늘어나는 **그 마이그레이션 커밋에서**
     * 빨개지게 둔다.
     *
     * 스키마를 직접 묻는다(`pg_constraint`). 마이그레이션 파일을 문자열로 읽으면 Flyway 가
     * 실제로 적용한 것이 아니라 **적힌 것**을 재게 된다.
     */
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

    /**
     * **X-3ⓒ + F-4 — 「같은 DB 왕복 구조」를 요청 하나 단위로 강제한다.**
     *
     * 소유권 은닉의 시간 축 게이트가 잡지 못하는 것이 여기 있다. 소유 조건을 SQL `WHERE`
     * 에서 빼고 **읽은 뒤 Kotlin 에서 비교**하도록 바꾼 변이를 일회용 worktree 에서 돌린
     * 실측: 응답 시간 비 1.013 · 1.090 · 1.051 — 문턱 1.5 는 물론 종전 2.0 에도 닿지
     * 않았다. 인덱스 적중과 불발의 차이가 밀리초 응답의 잡음에 묻힌다.
     *
     * 그 변이가 실제로 바꾸는 것은 **문장의 수**다. 지금 구현은 소유 판정과 갱신을
     * `UPDATE … WHERE id = ? AND user_id = ? RETURNING …` **한 문장**으로 끝내므로,
     * 없는 자원·남의 자원·내 자원 셋이 전부 같은 왕복을 돈다.
     *
     * ## 계측이 **서비스 경계**에서 들어간다 (게이트 23 F-4)
     *
     * 종전 판은 `JdbcWorkspaceRepository.rename` 을 직접 감쌌고, 그래서 소유 판정을
     * `WorkspaceService` 로 올린 변이(서비스가 `listOwned()` 로 먼저 확인)가 구조 축
     * 11/11 · 시간 축 22/22 로 **두 게이트를 모두 빠져나갔다**. 선언된 주제(「소유 조건이
     * SQL 을 떠났는가」)가 한 층 위에서 검사되지 않았던 것이다.
     *
     * 지금은 [WorkspaceService] 호출 한 번 — 즉 **요청 하나** — 이 내는 문장 수를 센다.
     * 조회가 어느 층에 늘든 그 정수가 움직인다.
     *
     * **「셋이 같다」만으로는 부족해 개수 자체를 못박는다** — 셋 다 두 문장이 되는 구현도
     * 「같다」를 만족한다. 그 형태는 소유 판정이 갱신과 분리됐다는 뜻이라 잡아야 한다.
     */
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

    /**
     * **삭제 거절 두 갈래의 왕복 구조** — `lockForDeletion` 이 도는 자리다.
     *
     * 없는 자원과 남의 자원은 [WorkspaceService.delete] 안에서 같은 `NotFoundException`
     * 으로 끝나는데, **끝나기까지 하는 일의 양**도 같아야 숨김이 성립한다. 오늘 그것은
     * `SELECT id … WHERE user_id = :ownerId … FOR UPDATE` 한 문장이고, 문서 수 질의는
     * 소유가 확인된 뒤에만 돈다.
     *
     * 소유한 자원의 삭제는 **일부러 다른 수**를 못박는다([DELETE_OWNED_STATEMENTS]) —
     * 성공 경로가 더 일하는 것은 정상이고, 숨겨야 하는 것은 「없음 ↔ 타인」이기 때문이다.
     * 그 수를 함께 고정해 두는 이유는 F-4 가 실증한 우회의 형태가 **내 자원일 때만 조회를
     * 하나 더 내는 것**이라, 성공 경로의 정수를 놓아 두면 그 자리가 그대로 열려 있어서다.
     */
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

    /**
     * **목록 한 번이 질의 한 번이다.**
     *
     * 문서 수를 `LEFT JOIN` + `count` 로 같은 질의에 담는다는 성질이 여기 걸린다. 줄마다
     * COUNT 를 따로 내는 N+1 구현은 기능 테스트를 전부 통과하고 이 정수에서만 무너진다 —
     * 「빈 작업 공간의 문서 수가 0 이다」가 값을 재는 반면 이쪽은 **몇 번 물었는가**를 잰다.
     *
     * 소유 자원 수와 무관하게 1 이어야 하므로 두 건을 만들어 놓고 잰다. 하나만 두면 N+1
     * 구현도 2 가 아니라 2 를 내지만, 두 건이면 3 이 되어 격차가 커진다.
     */
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

    /**
     * **동시 삭제에서 마지막 하나가 남는다.**
     *
     * 두 요청이 같은 사용자의 서로 **다른** 작업 공간을 동시에 지운다. 대상 행만 잠그는
     * 구현은 둘 다 "아직 둘 있다"를 보고 통과해 결과가 0 개가 된다 — 그러면 업로드가 갈
     * 곳을 잃고 「기본 작업 공간」이라는 기준도 가리킬 대상이 없어진다.
     *
     * 배리어로 두 스레드를 같은 순간에 풀어 잠금 구간이 실제로 겹치게 만든다. 겹치지
     * 않으면 이 테스트는 순차 실행과 같아져 아무것도 재지 않는다.
     *
     * ## 「실패했다」가 아니라 **어떻게** 실패했는지를 단언한다
     *
     * 종전에는 `runCatching { … }.isSuccess` 로 불리언 하나만 남겼다. 그러면 둘째 요청이
     * [ConflictException] 이 아니라 **교착·타임아웃·[kr.easydoc.core.exceptions.StorageException]**
     * 으로 실패해도 `false` 가 되어 같은 단언을 만족한다 — 잠금이 퇴행해 500 을 내기
     * 시작해도 초록이다(codex #7). 결과 객체를 보존해 **정확히 한 건 성공 + 한 건
     * `ConflictException`** 을 단언한다.
     */
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

        // 하나는 성공한다. 둘 다 성공하면 잠금이 집합에 걸리지 않은 것이다.
        val failures = outcomes.mapNotNull { it.exceptionOrNull() }
        assertThat(outcomes.count { it.isSuccess })
            .withFailMessage("성공이 정확히 한 건이 아니다 — 결과: %s", outcomes)
            .isEqualTo(1)
        // 나머지 하나는 **거절**이어야 한다. 교착·타임아웃·StorageException 은 거절이 아니라
        // 결함이고, 여기서 통과시키면 잠금 퇴행이 500 으로 나가는 동안에도 초록이다.
        assertThat(failures)
            .withFailMessage("둘째 요청이 ConflictException 이 아닌 것으로 실패했다: %s", failures)
            .singleElement()
            .isInstanceOf(ConflictException::class.java)
        assertThat(workspaces.listOwned(owner)).hasSize(1)
    }

    // ================================================================ 픽스처

    private fun newUser(): UUID = users.create(uniqueEmail(), FIXTURE_HASH).id

    private fun uniqueEmail(): String = "workspace-repo${counter++}@example.test"

    /** 문서 API 는 Phase 4 다. 여기서 필요한 것은 **행의 존재**뿐이라 직접 심는다. */
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

    /**
     * 유스케이스 호출 **한 번**이 내는 SQL 문 수.
     *
     * 거절 경로는 예외로 끝나므로 [runCatching] 으로 받는다 — 예외를 흘리면 계측 자체가
     * 중단돼 「거절 경로가 몇 문장을 도는가」를 영영 잴 수 없다. 결과를 쓰지 않는 것은
     * 의도적이다: 이 단언이 재는 것은 **결과가 아니라 일한 양**이고, 결과 쪽은 같은 파일의
     * 다른 케이스들이 이미 못박는다.
     */
    private fun countRequest(request: () -> Unit): Int = counting.countStatements { runCatching(request) }

    private companion object {
        var counter = 0

        /** 형태만 맞으면 되는 더미 PHC — 이 파일은 비밀번호 검증을 재지 않는다. */
        val FIXTURE_HASH = PasswordHash("\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA")

        /** 이름 변경 **요청 하나**가 도는 SQL 문 수. 소유 판정과 갱신이 한 문장이라 1 이다. */
        const val RENAME_STATEMENTS = 1

        /** 삭제가 거절되는 두 갈래(없음·타인)가 도는 SQL 문 수 — `lockForDeletion` 의 잠금 질의 하나. */
        const val DELETE_MISS_STATEMENTS = 1

        /** 소유 자원 삭제가 도는 SQL 문 수 — 잠금 + 문서 수 + DELETE. */
        const val DELETE_OWNED_STATEMENTS = 3

        /** 목록 **요청 하나**가 도는 SQL 문 수. 문서 수가 같은 질의에 담기므로 1 이다. */
        const val LIST_STATEMENTS = 1

        /** `V1__python_schema_baseline.sql` 이 준 제약 **이름**이다. 값이 아니라 이름이다. */
        const val DOCUMENTS_WORKSPACE_FK = "fk_documents_workspace_id_workspaces"

        const val CONCURRENT_DELETERS = 2
        const val BARRIER_TIMEOUT_SECONDS = 10L
        const val TASK_TIMEOUT_SECONDS = 30L
    }
}
