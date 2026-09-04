package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.DocumentDraft
import kr.easydoc.application.document.StoredFeedback
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.pilot.EditDistanceSkipReason
import kr.easydoc.core.pilot.MinutesSpent
import kr.easydoc.core.pilot.PublishIntent
import kr.easydoc.core.pilot.QualityScore
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.auth.JdbcUserRepository
import kr.easydoc.infrastructure.auth.JdbcWorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * 조회·목록이 `conversion_feedback` 을 **어떻게 붙이는가** — 실제 PostgreSQL 에서만 잴 수 있다.
 *
 * `JdbcDocumentStoreTest` 와 갈라 두는 것은 그쪽이 이미 detekt `LargeClass` 문턱에 닿아
 * 있어서다. 축도 다르다: 이 파일이 보는 것은 **조인의 방향**(왼쪽이라 행이 사라지지 않는다)과
 * **소유 술어**(제출자가 갈린 행은 「없음」으로 접는다) 둘뿐이다.
 *
 * 문서·변환 행을 유스케이스가 아니라 저장소로 직접 세운다 — 재는 것이 SQL 이지 업로드 경로가
 * 아니고, 암호기·트랜잭션 배선을 끌고 오면 이 파일이 보는 축이 흐려진다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedbackJoinStorageTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var documents: JdbcDocumentRepository
    private lateinit var conversions: JdbcConversionRepository
    private lateinit var feedback: JdbcConversionFeedbackRepository

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("feedback_join")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        jdbc = JdbcClient.create(DriverManagerDataSource(database.jdbcUrl, database.username, database.password))
        users = JdbcUserRepository(jdbc)
        workspaces = JdbcWorkspaceRepository(jdbc)
        documents = JdbcDocumentRepository(jdbc)
        conversions = JdbcConversionRepository(jdbc)
        feedback = JdbcConversionFeedbackRepository(jdbc)
    }

    @Test
    @DisplayName("피드백 제출 시각이 목록·조회에 실리고, 의견을 내지 않은 문서도 **사라지지 않는다**")
    fun `피드백을 왼쪽 조인으로 읽는다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "의견").id
        val spoke = seedConversion(owner, workspace)
        val silent = seedConversion(owner, workspace)

        val submittedAt = feedback.upsert(owner, feedbackFor(spoke))

        val listed = documents.listOwned(owner, workspace, LIST_LIMIT, 0).associateBy { it.conversionId }
        assertThat(listed)
            .describedAs("의견을 내지 않은 문서가 목록에서 사라졌다 — 조인이 왼쪽 조인이 아니다")
            .hasSize(2)
        assertThat(listed.getValue(spoke).feedbackSubmittedAt).isEqualTo(submittedAt)
        assertThat(listed.getValue(silent).feedbackSubmittedAt).isNull()
        assertThat(listed.values.map { it.reviewedAt })
            .describedAs("피드백 제출이 검수 시각까지 찍었다 — 둘은 다른 사실이다")
            .containsOnlyNulls()

        val stored = conversions.findOwnedResult(owner, spoke)
        assertThat(stored?.feedbackSubmittedAt).isEqualTo(submittedAt)
        assertThat(stored?.reviewedAt).isNull()
        assertThat(conversions.findOwnedResult(owner, silent)?.feedbackSubmittedAt).isNull()
    }

    @Test
    @DisplayName("제출자가 문서 소유자와 갈린 피드백 행은 **「없음」으로 접는다** (fail-closed)")
    fun `남이 남긴 피드백 행은 내 응답에 서지 않는다`() {
        val owner = newUser()
        val stranger = newUser()
        val workspace = workspaces.create(owner, "남의 의견").id
        val conversionId = seedConversion(owner, workspace)

        // FK 가 없는 표라 이런 행이 물리적으로 만들어질 수 있다(`V2` 의 「FK 를 걸지 않는다」).
        feedback.upsert(stranger, feedbackFor(conversionId))

        assertThat(conversions.findOwnedResult(owner, conversionId)?.feedbackSubmittedAt)
            .describedAs("남이 남긴 행의 제출 시각이 내 조회에 실렸다")
            .isNull()
        assertThat(documents.listOwned(owner, workspace, LIST_LIMIT, 0).single().feedbackSubmittedAt).isNull()
    }

    /** 문서 한 건과 그에 딸린 대기 변환 한 건. 돌려주는 것은 변환 식별자다. */
    private fun seedConversion(
        ownerId: UUID,
        workspaceId: UUID,
    ): UUID {
        val document =
            documents.insert(
                ownerId,
                DocumentDraft(
                    id = UUID.randomUUID(),
                    workspaceId = workspaceId,
                    title = "안내문",
                    sourceFormat = SourceFormat.TEXT,
                    charCount = 1,
                ),
                // 이 파일은 봉인 내용을 열지 않는다 — 열이 NOT NULL 이라 자리만 채운다.
                EncryptedContent(byteArrayOf(0), EncryptionScheme.AES_256_GCM_V1, KEY_VERSION),
            )
        return conversions
            .insertPending(
                id = UUID.randomUUID(),
                documentId = document.id,
                scheme = EncryptionScheme.AES_256_GCM_V1,
                keyVersion = KEY_VERSION,
            ).id
    }

    /**
     * 저장할 피드백 한 행. 수기 값은 **아무래도 좋다** — 이 파일이 재는 것은 조인이지 값이
     * 아니다. 파생 지표 셋은 함께 비운다(`EditMetrics` 의 「초안 없음」 갈래와 같은 조합이다).
     */
    private fun feedbackFor(conversionId: UUID): StoredFeedback =
        StoredFeedback(
            conversionId = conversionId,
            publishIntent = PublishIntent.entries.first(),
            qualityScore = QualityScore(FIXTURE_QUALITY_SCORE),
            minutesSpent = MinutesSpent(FIXTURE_MINUTES_SPENT),
            comment = null,
            easyCharCount = null,
            editedCharCount = null,
            editDistance = null,
            editDistanceSkipReason = EditDistanceSkipReason.NO_REVIEW,
        )

    private fun newUser(): UUID = users.create("f${UUID.randomUUID()}@example.com", PasswordHash(DUMMY_PHC)).id

    private companion object {
        const val LIST_LIMIT = 10
        const val KEY_VERSION = 1

        /** 범위 안이기만 하면 되는 수기 값 둘. 판정에 쓰이지 않는다. */
        const val FIXTURE_QUALITY_SCORE = 4
        const val FIXTURE_MINUTES_SPENT = 12

        /** 사용자 행을 만들기 위한 자리 채움 PHC. `JdbcDocumentStoreTest` 와 같은 값이다. */
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}
