package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.ConversionFeedbackRepository
import kr.easydoc.application.document.LockedFeedbackComment
import kr.easydoc.application.document.StoredFeedback
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.exceptions.StorageException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** `conversion_feedback` 테이블 접근. 스키마는 `V2__conversion_feedback.sql` 이 정한다. */
class JdbcConversionFeedbackRepository(private val jdbc: JdbcClient) : ConversionFeedbackRepository {
    /**
     * 피드백 한 행을 쓰거나 덮어쓴다 — **`INSERT ... ON CONFLICT` 한 문장이다.**
     *
     * 「있으면 UPDATE 없으면 INSERT」를 두 문장으로 나누면 그 사이에 같은 변환의 두 번째
     * 제출이 끼어들 수 있고, 그때 한쪽이 기본 키 위반으로 죽는다. 멱등성을 트랜잭션 격리가
     * 아니라 **문장 하나**가 지게 두는 것이 계약 #15 가 `PUT` 인 사유와 같은 판단이다.
     */
    override fun upsert(
        ownerId: UUID,
        feedback: StoredFeedback,
    ): Instant =
        try {
            jdbc
                .sql(UPSERT_SQL)
                .param("conversionId", feedback.conversionId)
                .param("ownerId", ownerId)
                .param("publishIntent", feedback.publishIntent.wireName)
                .param("qualityScore", feedback.qualityScore.value)
                .param("minutesSpent", feedback.minutesSpent.value)
                .param("comment", feedback.comment?.bytes)
                .param("scheme", feedback.comment?.scheme)
                .param("keyVersion", feedback.comment?.keyVersion)
                .param("easyCharCount", feedback.easyCharCount)
                .param("editedCharCount", feedback.editedCharCount)
                .param("editDistance", feedback.editDistance)
                .query { rs, _ -> rs.getObject("submitted_at", OffsetDateTime::class.java).toInstant() }
                .single()
        } catch (failure: DataIntegrityViolationException) {
            // 여기 닿는 것은 CHECK 위반(척도 밖 값·짝 없는 봉투)뿐이다 — 그 값들은 서비스와
            // 도메인 타입이 이미 끊었으므로 도달하면 **코드 버그**이고, 사용자 입력 오류가
            // 아니다. 그래서 422 가 아니라 500 으로 나간다.
            DocumentStorageLog.constraintViolation(FEEDBACK_TABLE, failure)
            throw StorageException(STORAGE_FAILURE_MESSAGE)
        }

    /**
     * 회전 대상 행의 봉인된 의견과 봉투를 읽고 **행을 잠근다**(`FOR NO KEY UPDATE`).
     * `JdbcConversionRepository.lockEnvelope` 와 같은 형태다.
     */
    override fun lockComment(conversionId: UUID): LockedFeedbackComment? =
        jdbc
            .sql(LOCK_COMMENT_SQL)
            .param("id", conversionId)
            .query { rs, _ -> LockedFeedbackComment(sealedCommentOf(rs)) }
            .optional()
            .orElse(null)

    /** 봉인된 의견과 봉투 두 값을 **한 UPDATE 로** 바꾼다. */
    override fun rewriteComment(
        conversionId: UUID,
        expected: EncryptedContent,
        comment: EncryptedContent,
    ): Boolean =
        jdbc
            .sql(REWRITE_COMMENT_SQL)
            .param("comment", comment.bytes)
            .param("scheme", comment.scheme)
            .param("keyVersion", comment.keyVersion)
            .param("id", conversionId)
            .param("expectedScheme", expected.scheme)
            .param("expectedKeyVersion", expected.keyVersion)
            .param("expectedComment", expected.bytes)
            .update() > 0

    private companion object {
        /** 저장소가 만든 고정 문자열. 계약 `InternalError` 의 `storage` 갈래다. */
        const val STORAGE_FAILURE_MESSAGE = "요청을 처리하지 못했습니다"

        const val FEEDBACK_TABLE = "conversion_feedback"

        /**
         * 멱등 upsert. **`submitted_at` 을 재제출 때 함께 민다.**
         *
         * 계약 `ConversionFeedbackResponse.submitted_at` 이 그 값을 「이 피드백을 **마지막으로
         * 저장한** 시각 … 덮어쓰면 갱신된다」로 정의한다 — 최초 제출 시각을 유지하면 응답이
         * 계약과 어긋난다. 판정이 쓰는 것도 마지막 값이다: 담당자가 결과를 다시 보고 의향을
         * 바꿨다면 게이트 ① 이 세는 것은 **바뀐 뒤의 답**이고, 그 답이 언제 나온 것인지가
         * 표본의 시각이다(`docs/pilot-runbook.md` 「집계」의 `min`/`max` 는 그 시각의 범위다).
         *
         * 그래서 `updated_at` 과 값이 같아진다. 두 열을 남겨 두는 것은 스키마의 판단이고
         * (`V2__conversion_feedback.sql`), 저장 문장이 그 관행을 따라 **둘 다 적는다** —
         * 한쪽만 적으면 「행이 언제 바뀌었나」와 「피드백이 언제 나왔나」가 이 표에서 갈린다.
         *
         * `user_id` 는 `SET` 에 없고 **덮어쓰기의 `WHERE` 에 있다.** 소유 판정은 이 호출
         * 앞에서 이미 끝나므로 잉여로 보이지만, 그 판정이 무너진 상태를 **0행으로 드러내는**
         * fail-closed 카나리다(`JdbcConversionRepository.SAVE_REVIEW_SQL` 의 같은 판단).
         * 0행이면 `RETURNING` 이 비어 저장이 조용히 성공하지 못한다 — 남의 판정 근거를
         * 덮어쓰는 것보다 500 이 낫다. 열 자체를 `SET` 하지 않는 것도 같은 이유다: 참여자
         * 축(`docs/pilot-runbook.md` 「대상과 규모」)이 재제출로 바뀔 수 있으면 안 된다.
         */
        val UPSERT_SQL =
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
            """.trimIndent()

        /**
         * 회전이 잠그는 질의. 소유 술어가 없다 — **회전 배치에 「내 것」이 없다.**
         * (`JdbcConversionRepository.lockEnvelope` 와 같은 자리이고, 그쪽도 같은 이유로
         * `OwnershipPredicateGuardTest` 의 미방어 열거에 핀으로 적혀 있다.)
         */
        val LOCK_COMMENT_SQL =
            """
            SELECT comment_encrypted, encryption_scheme, key_version
            FROM conversion_feedback WHERE conversion_id = :id
            FOR NO KEY UPDATE
            """.trimIndent()

        /**
         * 회전 UPDATE. 암호문과 봉투 두 값을 **같은 문장에서** SET 한다 — 나누면 「세대는 v1
         * 인데 암호문은 v2」인 행이 남고 그 행은 영원히 열리지 않는다.
         *
         * `WHERE` 의 봉투·암호문 조건은 잠금 아래에서 잉여로 보이지만, 잠금이 서지 않은
         * 상태를 **0행으로** 드러내는 fail-closed 카나리다([UPSERT_SQL] 의 `user_id` 조건과
         * 같은 판단).
         *
         * `updated_at` 은 밀고 `submitted_at` 은 두지 않는다. 회전은 행이 **바뀐** 사건이지
         * 검수자가 피드백을 **다시 낸** 사건이 아니고, 계약이 `submitted_at` 을 후자로
         * 정의한다 — 두 열을 나눠 둔 스키마의 판단이 여기서 실제로 갈린다.
         */
        val REWRITE_COMMENT_SQL =
            """
            UPDATE conversion_feedback
            SET comment_encrypted = :comment,
                encryption_scheme = :scheme,
                key_version = :keyVersion,
                updated_at = now()
            WHERE conversion_id = :id
              AND encryption_scheme = :expectedScheme
              AND key_version = :expectedKeyVersion
              AND comment_encrypted IS NOT DISTINCT FROM CAST(:expectedComment AS bytea)
            """.trimIndent()

        /**
         * 봉인된 의견 한 열을 봉투와 묶어 읽는다. 세 열이 함께 NULL 이면 `null` 이다 —
         * 자유 의견이 선택 항목이라 그것이 정상 상태다(V2 의 짝 제약이 「셋이 함께」를 진다).
         */
        fun sealedCommentOf(rs: ResultSet): EncryptedContent? {
            val bytes = rs.getBytes("comment_encrypted") ?: return null
            return EncryptedContent(bytes, rs.getString("encryption_scheme"), rs.getInt("key_version"))
        }
    }
}
