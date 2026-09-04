package kr.easydoc.infrastructure.document

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.EnvelopeRotation
import kr.easydoc.application.document.RotationOutcome
import kr.easydoc.application.document.SealedStores
import kr.easydoc.application.document.StoredFeedback
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.pilot.EditDistanceSkipReason
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
        assertThatThrownBy { insertMetrics(easy = null, edited = null, distance = 42, reason = null) }
            .describedAs("집계는 이 행을 「무엇에 견준 값인지」 모른 채 수정률에 넣는다")
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    @DisplayName("**음수 지표** — 글자 수도 편집 거리도 0 아래로 갈 수 없다")
    fun `음수 지표를 거절한다`() {
        assertThatThrownBy { insertMetrics(easy = -5, edited = -9, distance = -100, reason = null) }
            .describedAs("음수 한 건이 수정률을 음수로 만들고 집계는 그것을 「거의 안 고쳤다」로 읽는다")
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    @DisplayName("**짝 없는 검수본 글자 수** — 편집 거리 없이 검수본 글자 수만 있는 행은 들어가지 않는다")
    fun `짝 없는 검수본 글자 수를 거절한다`() {
        assertThatThrownBy { insertMetrics(easy = null, edited = 77, distance = null, reason = null) }
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    @DisplayName("`EditMetrics` 가 만들 수 있는 **네 조합은 전부 통과한다** — 위 셋이 「언제나 거절」로 통과하지 않는다")
    fun `서비스가 만드는 조합은 전부 통과한다`() {
        // `ConversionFeedbackService.EditMetrics.of` 의 네 갈래 그대로다:
        // 초안 없음 · 검수본 없음(이 둘은 사유 no_review) · 예산 초과(사유 budget_exceeded) ·
        // 둘 다 있고 예산 안. 스키마가 이 중 하나라도 거절하면 프로덕션의 피드백 저장이
        // 500 으로 죽는다 — 이 케이스가 그 방향을 고정한다.
        insertMetrics(easy = null, edited = null, distance = null, reason = "no_review")
        insertMetrics(easy = 120, edited = null, distance = null, reason = "no_review")
        insertMetrics(easy = 120, edited = 118, distance = null, reason = "budget_exceeded")
        insertMetrics(easy = 120, edited = 118, distance = 7, reason = null)

        // 「하나도 고치지 않았다」는 정상 값이다 — 0 을 음수 금지가 함께 막으면 안 된다.
        insertMetrics(easy = 120, edited = 120, distance = 0, reason = null)

        assertThat(metricRowCount()).isEqualTo(EXPECTED_ACCEPTED_ROWS)
    }

    @Test
    @DisplayName(
        "**사유 없는 예산 초과 모양** — 거리만 비고 사유가 없으면 더는 거절하지 않는다, " +
            "구버전 쓰기 호환 트리거가 budget_exceeded 로 되짚어 채운다",
    )
    fun `사유 없이 거리만 비우면 트리거가 budget_exceeded 를 채운다`() {
        // V4 이전에는 이 조합(거리 없음·사유 없음)이 짝 CHECK 로 바로 거절됐다. 지금은
        // `conversion_feedback_derive_skip_reason` 트리거가 이 조합을 「사유를 명시하지 않은
        // 쓰기」로 보고 지표만으로 사유를 되짚어 채운다 — 옛 애플리케이션의 UPSERT 가 사유
        // 컬럼을 아예 모르는 것과 SQL 수준에서 구분되지 않기 때문이다([oldUpsert] 케이스들
        // 참고). 그래서 거절이 아니라 파생이 맞는 동작이다.
        val conversionId =
            insertMetrics(easy = 120, edited = 118, distance = null, reason = null, minutesSpent = OLD_WRITE_MINUTES)

        assertThat(metricRowOf(conversionId).editDistanceSkipReason).isEqualTo("budget_exceeded")
    }

    @Test
    @DisplayName("**검수본 없음 사유인데 검수본 글자 수가 있다** — 짝이 어긋나 거절한다")
    fun `검수본 없음 사유에 글자 수가 있으면 거절한다`() {
        assertThatThrownBy { insertMetrics(easy = 120, edited = 118, distance = null, reason = "no_review") }
            .describedAs("검수본이 없다는 사유인데 검수본 글자 수가 채워졌다")
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    @DisplayName("**예산 초과 사유인데 검수본 글자 수가 없다** — 짝이 어긋나 거절한다")
    fun `예산 초과 사유에 글자 수가 없으면 거절한다`() {
        assertThatThrownBy { insertMetrics(easy = 120, edited = null, distance = null, reason = "budget_exceeded") }
            .describedAs("예산 초과는 글자 수를 재고 거리 계산만 포기한다 — 글자 수가 없으면 안 된다")
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    @DisplayName("**셀 예산 초과 행을 실물 upsert 로 저장한다** — Codex 리뷰(PR #13)가 잡은 결함: 인메모리 대역만 통과하고 실물 DB 는 CHECK 로 죽었었다")
    fun `예산 초과 지표를 실물로 저장하고 사유와 함께 읽는다`() {
        val conversionId = UUID.randomUUID()

        feedback.upsert(
            ownerId = OWNER,
            feedback =
                StoredFeedback(
                    conversionId = conversionId,
                    publishIntent = PublishIntent.WITH_EDITS,
                    qualityScore = QualityScore(QUALITY_SCORE),
                    minutesSpent = MinutesSpent(MINUTES_SPENT),
                    comment = null,
                    easyCharCount = EASY_CHAR_COUNT_FIXTURE,
                    editedCharCount = EDITED_CHAR_COUNT_FIXTURE,
                    editDistance = null,
                    editDistanceSkipReason = EditDistanceSkipReason.BUDGET_EXCEEDED,
                ),
        )

        val row = metricRowOf(conversionId)
        assertThat(row.easyCharCount).isEqualTo(EASY_CHAR_COUNT_FIXTURE)
        assertThat(row.editedCharCount).isEqualTo(EDITED_CHAR_COUNT_FIXTURE)
        assertThat(row.editDistance).isNull()
        assertThat(row.editDistanceSkipReason).isEqualTo("budget_exceeded")
    }

    @Test
    @DisplayName("**검수본 없음 행을 실물 upsert 로 저장한다**")
    fun `검수본 없음 지표를 실물로 저장하고 사유와 함께 읽는다`() {
        val conversionId = UUID.randomUUID()

        feedback.upsert(
            ownerId = OWNER,
            feedback =
                StoredFeedback(
                    conversionId = conversionId,
                    publishIntent = PublishIntent.AS_IS,
                    qualityScore = QualityScore(QUALITY_SCORE),
                    minutesSpent = MinutesSpent(MINUTES_SPENT),
                    comment = null,
                    easyCharCount = EASY_CHAR_COUNT_FIXTURE,
                    editedCharCount = null,
                    editDistance = null,
                    editDistanceSkipReason = EditDistanceSkipReason.NO_REVIEW,
                ),
        )

        val row = metricRowOf(conversionId)
        assertThat(row.easyCharCount).isEqualTo(EASY_CHAR_COUNT_FIXTURE)
        assertThat(row.editedCharCount).isNull()
        assertThat(row.editDistance).isNull()
        assertThat(row.editDistanceSkipReason).isEqualTo("no_review")
    }

    @Test
    @DisplayName(
        "**구버전 쓰기 호환 — INSERT, 검수본 없음** — 4e0c1b0 의 옛 UPSERT(사유 컬럼을 모른다)로 " +
            "검수본 없는 최초 제출을 넣으면 트리거가 no_review 를 되짚어 채운다",
    )
    fun `옛 UPSERT 로 검수본 없는 행을 넣으면 트리거가 no_review 를 채운다`() {
        val conversionId = UUID.randomUUID()

        oldUpsert(conversionId, editedCharCount = null, editDistance = null)

        val row = metricRowOf(conversionId)
        assertThat(row.editedCharCount).isNull()
        assertThat(row.editDistance).isNull()
        assertThat(row.editDistanceSkipReason)
            .describedAs("옛 애플리케이션(롤백 대상)은 이 컬럼을 아예 쓰지 않는다 — 트리거가 채워야 새 CHECK 를 통과한다")
            .isEqualTo("no_review")
    }

    @Test
    @DisplayName(
        "**구버전 쓰기 호환 — INSERT, 예산 초과** — 검수본 글자 수는 채우고 거리만 비운 옛 UPSERT 는 " +
            "트리거가 budget_exceeded 로 구분해 채운다",
    )
    fun `옛 UPSERT 로 예산 초과 모양 행을 넣으면 트리거가 budget_exceeded 를 채운다`() {
        val conversionId = UUID.randomUUID()

        oldUpsert(conversionId, editedCharCount = EDITED_CHAR_COUNT_FIXTURE, editDistance = null)

        val row = metricRowOf(conversionId)
        assertThat(row.editedCharCount).isEqualTo(EDITED_CHAR_COUNT_FIXTURE)
        assertThat(row.editDistance).isNull()
        assertThat(row.editDistanceSkipReason).isEqualTo("budget_exceeded")
    }

    @Test
    @DisplayName(
        "**구버전 쓰기 호환 — ON CONFLICT UPDATE, 측정 성공** — 옛 재제출이 거리를 실제로 채우면 " +
            "트리거가 이전에 남은 사유를 지운다",
    )
    fun `옛 UPSERT 재제출이 거리를 채우면 트리거가 사유를 지운다`() {
        val conversionId = UUID.randomUUID()
        oldUpsert(conversionId, editedCharCount = null, editDistance = null)
        assertThat(metricRowOf(conversionId).editDistanceSkipReason).isEqualTo("no_review")

        oldUpsert(conversionId, editedCharCount = EDITED_CHAR_COUNT_FIXTURE, editDistance = EDIT_DISTANCE_FIXTURE)

        val row = metricRowOf(conversionId)
        assertThat(row.editedCharCount).isEqualTo(EDITED_CHAR_COUNT_FIXTURE)
        assertThat(row.editDistance).isEqualTo(EDIT_DISTANCE_FIXTURE)
        assertThat(row.editDistanceSkipReason)
            .describedAs("사유가 남아 있으면 「거리도 있고 사유도 있다」로 짝 CHECK 를 어긴다")
            .isNull()
    }

    @Test
    @DisplayName(
        "**INSERT, 거리와 사유 동시 지정** — 짝 CHECK 가 곧바로 거절한다. Codex 2차 재심사(PR #13, " +
            "medium)가 잡은 결함: 옛 단일 트리거는 `edit_distance` 가 있으면 사유를 항상 지워 이 모순을 " +
            "조용히 고쳐썼다",
    )
    fun `INSERT 에서 거리와 사유를 함께 주면 거절한다`() {
        assertThatThrownBy {
            insertMetrics(
                easy = EASY_CHAR_COUNT_FIXTURE,
                edited = EDITED_CHAR_COUNT_FIXTURE,
                distance = EDIT_DISTANCE_FIXTURE,
                reason = "no_review",
            )
        }.describedAs("거리를 실제로 쟀다면서 검수본이 없다는 사유도 함께 왔다 — 모순이다")
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    @DisplayName(
        "**UPDATE, 거리와 사유 동시 지정** — 사유 컬럼을 아는 현재 앱 UPSERT 가 검수본 없음 행 위에 " +
            "거리와 명시적 사유를 함께 보내면 거절 트리거가 막는다. 옛 단일 트리거는 이 모순을 " +
            "`edit_distance_skip_reason := NULL` 로 조용히 고쳐써 CHECK 를 우회시켰다(Codex 2차 재심사)",
    )
    fun `현재 앱 UPSERT 재제출이 거리와 사유를 함께 보내면 거절 트리거가 막는다`() {
        val conversionId = UUID.randomUUID()
        upsertMetricsFeedback(
            conversionId,
            editedCharCount = null,
            editDistance = null,
            editDistanceSkipReason = EditDistanceSkipReason.NO_REVIEW,
        )

        assertThatThrownBy {
            upsertMetricsFeedback(
                conversionId,
                editedCharCount = EDITED_CHAR_COUNT_FIXTURE,
                editDistance = EDIT_DISTANCE_FIXTURE,
                editDistanceSkipReason = EditDistanceSkipReason.NO_REVIEW,
            )
        }.describedAs("애플리케이션 버그로 거리와 사유가 함께 온 것 — 조용히 고쳐쓰지 않고 500 으로 드러나야 한다")
            .isInstanceOf(StorageException::class.java)
    }

    @Test
    @DisplayName(
        "**UPDATE, 측정 성공** — 현재 앱 UPSERT 가 검수본 없음 행 위에 거리를 재고 사유를 비워 보내면 " +
            "거절 트리거를 통과하고 사유가 지워진다 — 정상 재제출까지 막으면 안 된다",
    )
    fun `현재 앱 UPSERT 재제출이 측정에 성공하면 거절 트리거를 통과하고 사유가 지워진다`() {
        val conversionId = UUID.randomUUID()
        upsertMetricsFeedback(
            conversionId,
            editedCharCount = null,
            editDistance = null,
            editDistanceSkipReason = EditDistanceSkipReason.NO_REVIEW,
        )

        upsertMetricsFeedback(
            conversionId,
            editedCharCount = EDITED_CHAR_COUNT_FIXTURE,
            editDistance = EDIT_DISTANCE_FIXTURE,
            editDistanceSkipReason = null,
        )

        val row = metricRowOf(conversionId)
        assertThat(row.editedCharCount).isEqualTo(EDITED_CHAR_COUNT_FIXTURE)
        assertThat(row.editDistance).isEqualTo(EDIT_DISTANCE_FIXTURE)
        assertThat(row.editDistanceSkipReason).isNull()
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

    /**
     * 지표 셋(+사유)만 다른 행 하나를 **원시 SQL 로** 넣는다. 도메인 타입을 지나지 않는 것이 요점이다.
     *
     * `minutesSpent` 는 기본이 [METRIC_ROW_MINUTES] 다 — [metricRowCount] 가 그 값으로 표식을
     * 삼아 이 헬퍼가 넣은 행만 센다. 트리거가 사유를 되짚어 채워 CHECK 를 통과시키는 케이스처럼
     * [EXPECTED_ACCEPTED_ROWS] 의 셈에 끼면 안 되는 행은 다른 값을 넘긴다.
     *
     * 넣은 행의 `conversion_id` 를 돌려준다 — 호출부가 [metricRowOf] 로 되읽어 트리거가 실제로
     * 무엇을 파생했는지 잴 수 있게 한다.
     */
    private fun insertMetrics(
        easy: Int?,
        edited: Int?,
        distance: Int?,
        reason: String?,
        minutesSpent: Int = METRIC_ROW_MINUTES,
    ): UUID {
        val conversionId = UUID.randomUUID()
        database.execute(
            """
            INSERT INTO conversion_feedback
                (conversion_id, user_id, publish_intent, quality_score, minutes_spent,
                 easy_char_count, edited_char_count, edit_distance, edit_distance_skip_reason)
            VALUES ('$conversionId', '${UUID.randomUUID()}', 'as_is', 4, $minutesSpent,
                    ${easy ?: "NULL"}, ${edited ?: "NULL"}, ${distance ?: "NULL"},
                    ${reason?.let { "'$it'" } ?: "NULL"});
            """.trimIndent(),
        )
        return conversionId
    }

    /**
     * **현재 앱 UPSERT** — [JdbcConversionFeedbackRepository.upsert] 그대로다(`edit_distance_skip_reason`
     * 을 항상 `SET` 에 올린다). [oldUpsert] 와 짝이다: 이쪽은 사유 컬럼을 **아는** 쓰기가
     * 거리와 사유를 동시에 명시적으로 보내는 모순을 거절 트리거가 막는지 재는 자리다.
     */
    private fun upsertMetricsFeedback(
        conversionId: UUID,
        editedCharCount: Int?,
        editDistance: Int?,
        editDistanceSkipReason: EditDistanceSkipReason?,
    ): Instant =
        feedback.upsert(
            ownerId = OWNER,
            feedback =
                StoredFeedback(
                    conversionId = conversionId,
                    publishIntent = PublishIntent.AS_IS,
                    qualityScore = QualityScore(QUALITY_SCORE),
                    minutesSpent = MinutesSpent(MINUTES_SPENT),
                    comment = null,
                    easyCharCount = EASY_CHAR_COUNT_FIXTURE,
                    editedCharCount = editedCharCount,
                    editDistance = editDistance,
                    editDistanceSkipReason = editDistanceSkipReason,
                ),
        )

    /**
     * **4e0c1b0 시점의 옛 UPSERT** — `edit_distance_skip_reason` 컬럼을 전혀 언급하지 않는다.
     * `JdbcConversionFeedbackRepository.UPSERT_SQL` 이 그 컬럼을 더하기 **전** 문장을 그대로
     * 옮긴 것이 요점이다 — 롤백되거나 아직 안 올라간 옛 애플리케이션 인스턴스가 실제로
     * 이 문장을 낸다. 구버전 쓰기 호환 트리거(`V4` 의
     * `conversion_feedback_derive_skip_reason`)가 이 경로에서도 CHECK 를 지키게 하는지가
     * 이 테스트들의 대상이다.
     */
    private fun oldUpsert(
        conversionId: UUID,
        editedCharCount: Int?,
        editDistance: Int?,
    ) {
        jdbc
            .sql(OLD_UPSERT_SQL)
            .param("conversionId", conversionId)
            .param("ownerId", OWNER)
            .param("publishIntent", PublishIntent.AS_IS.wireName)
            .param("qualityScore", QUALITY_SCORE)
            .param("minutesSpent", OLD_WRITE_MINUTES)
            .param("comment", null as ByteArray?)
            .param("scheme", null as String?)
            .param("keyVersion", null as Int?)
            .param("easyCharCount", EASY_CHAR_COUNT_FIXTURE)
            .param("editedCharCount", editedCharCount)
            .param("editDistance", editDistance)
            // `RETURNING submitted_at` 이 있는 문장이다 — PgJDBC 는 `executeUpdate()` 로 그런
            // 문장을 내면 "A result was returned when none was expected" 로 거절한다
            // (`JdbcConversionFeedbackRepository.upsert` 가 실제로 `.query { … }.single()` 을
            // 쓰는 이유와 같다). `.update()` 를 그대로 옮기면 이 헬퍼가 트리거보다 먼저 깨진다.
            .query { rs, _ -> rs.getObject("submitted_at", OffsetDateTime::class.java).toInstant() }
            .single()
    }

    /** 지표 케이스 행 하나를 되읽는다 — 실물 upsert 가 실제로 무엇을 저장했는지 잰다. */
    private fun metricRowOf(conversionId: UUID): MetricRow =
        jdbc
            .sql(
                """
                SELECT easy_char_count, edited_char_count, edit_distance, edit_distance_skip_reason
                FROM conversion_feedback WHERE conversion_id = :id
                """.trimIndent(),
            ).param("id", conversionId)
            .query { rs, _ ->
                MetricRow(
                    easyCharCount = rs.getObject("easy_char_count") as Int?,
                    editedCharCount = rs.getObject("edited_char_count") as Int?,
                    editDistance = rs.getObject("edit_distance") as Int?,
                    editDistanceSkipReason = rs.getString("edit_distance_skip_reason"),
                )
            }.single()

    private class MetricRow(
        val easyCharCount: Int?,
        val editedCharCount: Int?,
        val editDistance: Int?,
        val editDistanceSkipReason: String?,
    )

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
                editDistanceSkipReason = EditDistanceSkipReason.NO_REVIEW,
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
        const val EXPECTED_ACCEPTED_ROWS = 5

        /** 실물 upsert 지표 케이스(예산 초과·검수본 없음)가 쓰는 글자 수 고정값. */
        const val EASY_CHAR_COUNT_FIXTURE = 120
        const val EDITED_CHAR_COUNT_FIXTURE = 118

        /** 구버전 쓰기 호환 케이스가 「측정 성공」 갈래에서 쓰는 편집 거리 고정값. */
        const val EDIT_DISTANCE_FIXTURE = 7

        /** 구버전 쓰기 호환 케이스가 넣는 행의 표식. [METRIC_ROW_MINUTES]·[MINUTES_SPENT] 와 갈라 센다. */
        const val OLD_WRITE_MINUTES = 15

        /**
         * `JdbcConversionFeedbackRepository.UPSERT_SQL` 의 4e0c1b0 시점 문장 — `edit_distance_skip_reason`
         * 을 더하기 **전**이다. 옛 애플리케이션이 실제로 내는 문장을 그대로 옮겨야 구버전 쓰기
         * 호환 트리거를 검증하는 뜻이 서므로, 새 문장을 손으로 줄여 만들지 않는다.
         */
        const val OLD_UPSERT_SQL =
            """
            INSERT INTO conversion_feedback (
                conversion_id, user_id, publish_intent, quality_score, minutes_spent,
                comment_encrypted, encryption_scheme, key_version,
                easy_char_count, edited_char_count, edit_distance
            ) VALUES (
                :conversionId, :ownerId, :publishIntent, :qualityScore, :minutesSpent,
                CAST(:comment AS bytea), CAST(:scheme AS varchar), CAST(:keyVersion AS smallint),
                CAST(:easyCharCount AS integer), CAST(:editedCharCount AS integer),
                CAST(:editDistance AS integer)
            )
            ON CONFLICT (conversion_id) DO UPDATE SET
                publish_intent = EXCLUDED.publish_intent,
                quality_score = EXCLUDED.quality_score,
                minutes_spent = EXCLUDED.minutes_spent,
                comment_encrypted = EXCLUDED.comment_encrypted,
                encryption_scheme = EXCLUDED.encryption_scheme,
                key_version = EXCLUDED.key_version,
                easy_char_count = EXCLUDED.easy_char_count,
                edited_char_count = EXCLUDED.edited_char_count,
                edit_distance = EXCLUDED.edit_distance,
                submitted_at = now(),
                updated_at = now()
            WHERE conversion_feedback.user_id = :ownerId
            RETURNING submitted_at
            """

        val KEY_MATERIAL: Map<Int, Secret> =
            listOf(OLD_GENERATION, NEW_GENERATION).associateWith {
                Secret(Base64.getEncoder().encodeToString(ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)))
            }
    }
}
