package kr.easydoc.infrastructure.document

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.EnvelopeRotation
import kr.easydoc.application.document.RotationOutcome
import kr.easydoc.application.document.SealedStores
import kr.easydoc.application.document.StoredFeedback
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.pilot.MinutesSpent
import kr.easydoc.core.pilot.PublishIntent
import kr.easydoc.core.pilot.QualityScore
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
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
import java.security.SecureRandom
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * `conversion_feedback` 의 **마지막 방어선과 회전 경로** — 실제 PostgreSQL 에서만 잴 수 있는 것.
 *
 * 두 축을 함께 둔다. 파생 지표의 CHECK 는 서비스가 먼저 막으므로 대역에서는 지워도 초록이고,
 * 회전 SQL 의 `WHERE` 조건도 같은 성질이다 — 둘 다 여기가 보는 유일한 자리다.
 *
 * FK 가 없는 표라(`V2` 의 「FK 를 걸지 않는다」) 지표 케이스는 문서·변환 행을 세우지 않고
 * 임의 식별자로 직접 INSERT 한다. 그것이 이 표의 설계이지 테스트의 지름길이 아니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversionFeedbackStorageTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var feedback: JdbcConversionFeedbackRepository
    private lateinit var writeCipher: ContentCipher
    private lateinit var rotatedCipher: ContentCipher
    private lateinit var rotation: EnvelopeRotation

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("conversion_feedback_storage")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val dataSource = dataSource()
        jdbc = JdbcClient.create(dataSource)
        feedback = JdbcConversionFeedbackRepository(jdbc)
        writeCipher = cipherWith(OLD_GENERATION)
        rotatedCipher = cipherWith(NEW_GENERATION)
        rotation =
            EnvelopeRotation(
                stores =
                    SealedStores(
                        documents = JdbcDocumentRepository(jdbc),
                        originals = JdbcDocumentOriginalRepository(jdbc),
                        conversions = JdbcConversionRepository(jdbc),
                        feedback = feedback,
                    ),
                cipher = rotatedCipher,
                transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            )
    }

    @Test
    @DisplayName("**분모 없는 분자** — 글자 수 둘이 없는데 편집 거리만 있는 행은 들어가지 않는다")
    fun `짝 없는 편집 거리를 거절한다`() {
        assertThatThrownBy { insertMetrics(easy = null, edited = null, distance = 42) }
            .describedAs("집계는 이 행을 「무엇에 견준 값인지」 모른 채 수정률에 넣는다")
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    @DisplayName("**음수 지표** — 글자 수도 편집 거리도 0 아래로 갈 수 없다")
    fun `음수 지표를 거절한다`() {
        assertThatThrownBy { insertMetrics(easy = -5, edited = -9, distance = -100) }
            .describedAs("음수 한 건이 수정률을 음수로 만들고 집계는 그것을 「거의 안 고쳤다」로 읽는다")
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    @DisplayName("**짝 없는 검수본 글자 수** — 편집 거리 없이 검수본 글자 수만 있는 행은 들어가지 않는다")
    fun `짝 없는 검수본 글자 수를 거절한다`() {
        assertThatThrownBy { insertMetrics(easy = null, edited = 77, distance = null) }
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    @DisplayName("`EditMetrics` 가 만들 수 있는 **세 조합은 전부 통과한다** — 위 셋이 「언제나 거절」로 통과하지 않는다")
    fun `서비스가 만드는 조합은 전부 통과한다`() {
        // `ConversionFeedbackService.EditMetrics.of` 의 세 갈래 그대로다:
        // 초안 없음 · 검수본 없음 · 둘 다 있음. 스키마가 이 중 하나라도 거절하면
        // 프로덕션의 피드백 저장이 500 으로 죽는다 — 이 케이스가 그 방향을 고정한다.
        insertMetrics(easy = null, edited = null, distance = null)
        insertMetrics(easy = 120, edited = null, distance = null)
        insertMetrics(easy = 120, edited = 118, distance = 7)

        // 「하나도 고치지 않았다」는 정상 값이다 — 0 을 음수 금지가 함께 막으면 안 된다.
        insertMetrics(easy = 120, edited = 120, distance = 0)

        assertThat(metricRowCount()).isEqualTo(EXPECTED_ACCEPTED_ROWS)
    }

    @Test
    @DisplayName("v1 로 봉한 의견이 회전 뒤 **같은 평문**으로 열리고 봉투 두 값이 새 세대다")
    fun `봉인된 의견을 회전한다`() {
        val conversionId = UUID.randomUUID()
        saveComment(conversionId, COMMENT_BODY, writeCipher)
        assertThat(envelopeOf(conversionId)).isEqualTo(EncryptionScheme.AES_256_GCM_V1 to OLD_GENERATION)

        assertThat(rotation.rotateFeedback(conversionId)).isEqualTo(RotationOutcome.ROTATED)

        assertThat(envelopeOf(conversionId)).isEqualTo(EncryptionScheme.AES_256_GCM_V1 to NEW_GENERATION)
        val stored = checkNotNull(feedback.lockComment(conversionId)?.comment)
        assertThat(rotatedCipher.decrypt(stored, conversionId, EncryptedField.CONVERSION_FEEDBACK_COMMENT).value)
            .describedAs("회전이 평문을 바꾸면 판정 근거가 조용히 달라진다")
            .isEqualTo(COMMENT_BODY)
        assertThat(rotation.rotateFeedback(conversionId))
            .describedAs("두 번째 회전은 할 일이 없어야 한다")
            .isEqualTo(RotationOutcome.ALREADY_CURRENT)
    }

    @Test
    @DisplayName("의견이 없는 행은 **아무것도 쓰지 않는다** — 봉투 세 열이 NULL 로 남는다")
    fun `의견이 없는 행은 회전이 건드리지 않는다`() {
        val conversionId = UUID.randomUUID()
        saveComment(conversionId, comment = null, cipher = writeCipher)

        assertThat(rotation.rotateFeedback(conversionId)).isEqualTo(RotationOutcome.NOTHING_SEALED)

        assertThat(envelopeOf(conversionId))
            .describedAs("빈 의견을 봉해 넣으면 선택 항목이던 칸이 「빈 의견을 남겼다」로 바뀐다")
            .isNull()
    }

    @Test
    @DisplayName("행이 없으면 MISSING — 「의견이 없다」와 구분한다")
    fun `없는 행은 MISSING 이다`() {
        assertThat(rotation.rotateFeedback(UUID.randomUUID())).isEqualTo(RotationOutcome.MISSING)
    }

    @Test
    @DisplayName("**낙관적 조건이 SQL 에 실제로 걸려 있다** — 읽은 뒤 암호문이 바뀌면 0행이다")
    fun `읽은 뒤 바뀐 행은 회전되지 않는다`() {
        val conversionId = UUID.randomUUID()
        saveComment(conversionId, COMMENT_BODY, writeCipher)
        val stale = checkNotNull(feedback.lockComment(conversionId)?.comment)

        // 우리가 읽은 뒤 검수자가 의견을 고쳤다 — 잠금이 서 있었다면 불가능한 상태다.
        saveComment(conversionId, "그사이 바뀐 의견", writeCipher)

        val rewritten =
            feedback.rewriteComment(
                conversionId,
                expected = stale,
                comment =
                    rotatedCipher.encrypt(
                        PlainBody(COMMENT_BODY),
                        conversionId,
                        EncryptedField.CONVERSION_FEEDBACK_COMMENT,
                    ),
            )

        assertThat(rewritten)
            .describedAs("읽은 행이 그대로가 아닌데 회전됐다 — 잠금 전제가 깨진 것이 0행으로 드러나지 않는다")
            .isFalse()
        assertThat(envelopeOf(conversionId)?.second)
            .describedAs("세대가 올라갔다면 그사이 저장된 의견이 열리지 않는다")
            .isEqualTo(OLD_GENERATION)
    }

    @Test
    @DisplayName("회전은 `submitted_at` 을 밀지 않는다 — 행이 바뀐 것과 피드백이 다시 나온 것은 다른 사건이다")
    fun `회전이 제출 시각을 밀지 않는다`() {
        val conversionId = UUID.randomUUID()
        val submittedAt = saveComment(conversionId, COMMENT_BODY, writeCipher)

        rotation.rotateFeedback(conversionId)

        assertThat(submittedAtOf(conversionId))
            .describedAs("계약이 `submitted_at` 을 「마지막으로 **저장한**」 시각으로 정의한다")
            .isEqualTo(submittedAt)
    }

    /** 지표 셋만 다른 행 하나를 **원시 SQL 로** 넣는다. 도메인 타입을 지나지 않는 것이 요점이다. */
    private fun insertMetrics(
        easy: Int?,
        edited: Int?,
        distance: Int?,
    ) {
        database.execute(
            """
            INSERT INTO conversion_feedback
                (conversion_id, user_id, publish_intent, quality_score, minutes_spent,
                 easy_char_count, edited_char_count, edit_distance)
            VALUES ('${UUID.randomUUID()}', '${UUID.randomUUID()}', 'as_is', 4, $METRIC_ROW_MINUTES,
                    ${easy ?: "NULL"}, ${edited ?: "NULL"}, ${distance ?: "NULL"});
            """.trimIndent(),
        )
    }

    /** 실경로 upsert 로 피드백 한 행을 남긴다. 의견이 `null` 이면 봉투 세 열이 NULL 이다. */
    private fun saveComment(
        conversionId: UUID,
        comment: String?,
        cipher: ContentCipher,
    ) = feedback.upsert(
        // 같은 변환에 두 번 저장하는 케이스가 있다 — upsert 의 덮어쓰기 팔에 소유 술어가
        // 걸려 있어(`UPSERT_SQL`) 소유자가 달라지면 0행이 되고 `RETURNING` 이 비어 버린다.
        ownerId = OWNER,
        feedback =
            StoredFeedback(
                conversionId = conversionId,
                publishIntent = PublishIntent.WITH_EDITS,
                qualityScore = QualityScore(QUALITY_SCORE),
                minutesSpent = MinutesSpent(MINUTES_SPENT),
                comment =
                    comment?.let {
                        cipher.encrypt(PlainBody(it), conversionId, EncryptedField.CONVERSION_FEEDBACK_COMMENT)
                    },
                easyCharCount = null,
                editedCharCount = null,
                editDistance = null,
            ),
    )

    /** 그 행의 봉투 두 값. 봉인된 의견이 없으면 `null`. */
    private fun envelopeOf(conversionId: UUID): Pair<String, Int>? =
        jdbc
            .sql("SELECT encryption_scheme, key_version FROM conversion_feedback WHERE conversion_id = :id")
            .param("id", conversionId)
            .query { rs, _ -> rs.getString(1)?.let { it to rs.getInt(2) } }
            .optional()
            .orElse(null)

    private fun submittedAtOf(conversionId: UUID): Instant =
        jdbc
            .sql("SELECT submitted_at FROM conversion_feedback WHERE conversion_id = :id")
            .param("id", conversionId)
            .query { rs, _ -> rs.getObject(1, OffsetDateTime::class.java).toInstant() }
            .single()

    /** 지표 케이스가 넣은 행만 센다 — 같은 표를 쓰는 회전 케이스와 섞이면 이 정수가 흔들린다. */
    private fun metricRowCount(): Int =
        database.queryInt("SELECT count(*) FROM conversion_feedback WHERE minutes_spent = $METRIC_ROW_MINUTES")

    /** 두 세대의 키를 **둘 다** 들고 있는 암호기. 회전은 옛 세대를 열어 새 세대로 봉한다. */
    private fun cipherWith(writeKeyVersion: Int): ContentCipher =
        AesGcmContentCipher(keyMaterial = KEY_MATERIAL, writeKeyVersion = writeKeyVersion)

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    private companion object {
        /** 이 파일의 모든 피드백 행의 제출자. 재저장이 같은 소유 술어를 지나야 한다. */
        val OWNER: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000f1")

        const val OLD_GENERATION = 1
        const val NEW_GENERATION = 2
        const val KEY_BYTES = 32
        const val QUALITY_SCORE = 4
        const val MINUTES_SPENT = 12

        /** 지표 케이스 행의 표식. 회전 케이스([MINUTES_SPENT])와 갈라 세려고 값을 나눈다. */
        const val METRIC_ROW_MINUTES = 10

        /** 「본문 조각이 섞인 의견」 — V2 의 「자유 의견」 주석이 적은 그 상황이다. */
        const val COMMENT_BODY = "○○동 ○○○ 님께 안내드립니다 부분이 어색합니다"

        /** 지표 케이스가 넣는 정상 행 수. 「언제나 거절」로 통과하지 않는 것을 이 정수가 잰다. */
        const val EXPECTED_ACCEPTED_ROWS = 4

        val KEY_MATERIAL: Map<Int, Secret> =
            listOf(OLD_GENERATION, NEW_GENERATION).associateWith {
                Secret(Base64.getEncoder().encodeToString(ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)))
            }
    }
}
