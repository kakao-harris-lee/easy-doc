package kr.easydoc.api

import kr.easydoc.api.usage.UsageReportRunner
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * `usage-report` profile 의 실 배선(U3) — 계획
 * `docs/plans/2026-09-07-usage-ledger-and-report.md` §3 U3의 프로필 층 회귀 고정판. 집계
 * SQL 자체(사용자·워크스페이스별 분리·삭제된 워크스페이스 행·비용 미상)는
 * `JdbcUsageReportRepositoryTest`(infrastructure)가 재고, 이 테스트는 **그 로직이 실제로
 * `ApiApplication`으로 뜨는가** — CLI 인자(`--from --to --out`)를 읽어 BOM 붙은 UTF-8 CSV
 * 파일을 쓰고 종료 코드를 내는가를 본다(`RotateKeysProfileTest`와 같은 자리).
 *
 * 원장은 `Flyway.configure()...migrate()`로 스키마를 먼저 올린 뒤 raw SQL로 직접 심는다 —
 * 시딩이 `ApplicationRunner`(컨텍스트 초기화 중 실행)보다 먼저 끝나야 하므로, JUnit5의 정적
 * `@BeforeAll`(테스트 인스턴스 생성·Spring 컨텍스트 로딩보다 먼저 도는 자리)에서 돈다.
 * Spring Boot 자신의 Flyway 자동 기동은 이미 적용된 이력을 보고 아무것도 하지 않는다
 * (멱등) — 시딩한 행은 그대로 남는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.profiles.active=usage-report"],
    args = [
        "--from=2026-03-01",
        "--to=2026-03-31",
        "--out=build/test-usage-report/report.csv",
    ],
)
class UsageReportProfileTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    @DisplayName("시딩된 원장을 지정 기간으로 BOM 붙은 UTF-8 CSV에 쓰고 종료 코드 0이다")
    fun `지정 기간 리포트가 파일로 나온다`() {
        val runner = context.getBean(UsageReportRunner::class.java)

        assertThat(runner.exitCode).isZero()
        val csvBytes = Files.readAllBytes(File(OUT_PATH).toPath())
        assertThat(csvBytes.copyOfRange(0, 3))
            .describedAs("UTF-8 BOM(EF BB BF)이 없다")
            .isEqualTo(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        val csv = String(csvBytes, 3, csvBytes.size - 3, Charsets.UTF_8)
        assertThat(csv.lineSequence().first())
            .isEqualTo(
                "workspace_id,workspace_name,owner_email,documents,characters,credits," +
                    "llm_calls,input_tokens,output_tokens,estimated_cost_usd,cost_unknown_calls",
            )
        assertThat(csv).contains("공간1")
        assertThat(csv).contains("0.001000")
    }

    companion object {
        private const val OUT_PATH = "build/test-usage-report/report.csv"
        private val SEED_AT: Instant = Instant.parse("2026-03-15T02:00:00Z")

        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("usage_report_profile")
        }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }

        @JvmStatic
        @BeforeAll
        fun seed() {
            // 이전 실행이 남긴 파일이 있으면 이번 실행이 실제로 파일을 다시 썼는지 가려낼
            // 수 없다 — 지우고 시작한다.
            Files.deleteIfExists(File(OUT_PATH).toPath())
            migrate()
            seedLedgerRow()
        }

        private fun migrate() {
            Flyway
                .configure()
                .dataSource(database.jdbcUrl, database.username, database.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }

        private fun seedLedgerRow() {
            val dataSource: DataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
            val jdbc = JdbcClient.create(dataSource)

            val owner = UUID.randomUUID()
            jdbc
                .sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :email, :hash)")
                .param("id", owner)
                .param("email", "owner-$owner@example.com")
                .param("hash", DUMMY_PHC)
                .update()
            val workspaceId = UUID.randomUUID()
            jdbc
                .sql("INSERT INTO workspaces (id, user_id, name) VALUES (:id, :userId, :name)")
                .param("id", workspaceId)
                .param("userId", owner)
                .param("name", "공간1")
                .update()
            val documentId = UUID.randomUUID()
            jdbc
                .sql(
                    """
                    INSERT INTO documents (id, user_id, workspace_id, title, source_format,
                                           source_text_encrypted, char_count, encryption_scheme, key_version, created_at)
                    VALUES (:id, :owner, :workspace, '제목', 'text', :bytes, 1000, 'aes256gcm-v1', 1, :createdAt)
                    """.trimIndent(),
                ).param("id", documentId)
                .param("owner", owner)
                .param("workspace", workspaceId)
                .param("bytes", byteArrayOf(1, 2, 3))
                .param("createdAt", OffsetDateTime.ofInstant(SEED_AT, ZoneOffset.UTC))
                .update()
            jdbc
                .sql(
                    """
                    INSERT INTO llm_calls (
                        id, conversion_id, document_id, workspace_id, user_id,
                        purpose, provider, model, input_tokens, output_tokens,
                        latency_ms, estimated_cost_usd, pricing_input_usd_per_mtok, pricing_output_usd_per_mtok,
                        char_count, document_char_count, called_at
                    ) VALUES (
                        :id, NULL, :documentId, :workspaceId, :userId,
                        'convert', 'anthropic', 'claude-sonnet-5', 100, 50,
                        100, 0.001000, 2.00, 10.00,
                        900, 1000, :calledAt
                    )
                    """.trimIndent(),
                ).param("id", UUID.randomUUID())
                .param("documentId", documentId)
                .param("workspaceId", workspaceId)
                .param("userId", owner)
                .param("calledAt", OffsetDateTime.ofInstant(SEED_AT, ZoneOffset.UTC))
                .update()
        }

        private const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}

/**
 * 형식이 어긋난 `--to`는 [kr.easydoc.application.usage.UsagePeriodResolver]가
 * `InvalidInputException`을 던지고, `UsageReportRunner`가 그것을 잡아 종료 코드 1로
 * 끝낸다는 것을 프로필 배선으로 확인한다 — 별도 컨텍스트(다른 `args`)라 클래스를 나눴다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.profiles.active=usage-report"],
    args = [
        "--to=not-a-date",
        "--out=build/test-usage-report/invalid.csv",
    ],
)
class UsageReportProfileInvalidArgsTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    @DisplayName("형식이 어긋난 --to는 종료 코드 1이고 파일을 쓰지 않는다")
    fun `잘못된 to는 실패로 끝난다`() {
        val runner = context.getBean(UsageReportRunner::class.java)

        assertThat(runner.exitCode).isEqualTo(1)
        assertThat(File(OUT_PATH)).doesNotExist()
    }

    companion object {
        private const val OUT_PATH = "build/test-usage-report/invalid.csv"

        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("usage_report_profile_invalid")
        }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

/**
 * `--out`이 **쓸 수 없는 경로**(이미 디렉터리인 경로)면 `FileOutputStream`이
 * `java.io.FileNotFoundException`(checked, `RuntimeException`이 아니다)을 던진다 —
 * `UsageReportRunner`가 `RuntimeException`만이 아니라 `Exception` 전체를 잡아야 이 경로도
 * 메시지 한 줄 + 종료 코드 1로 끝난다는 것을 고정한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.profiles.active=usage-report"],
    args = ["--out=build"],
)
class UsageReportProfileUnwritableOutTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    @DisplayName("--out이 이미 있는 디렉터리면 종료 코드 1이다")
    fun `쓸 수 없는 out 경로는 실패로 끝난다`() {
        val runner = context.getBean(UsageReportRunner::class.java)

        assertThat(runner.exitCode).isEqualTo(1)
    }

    companion object {
        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("usage_report_profile_unwritable")
        }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}
