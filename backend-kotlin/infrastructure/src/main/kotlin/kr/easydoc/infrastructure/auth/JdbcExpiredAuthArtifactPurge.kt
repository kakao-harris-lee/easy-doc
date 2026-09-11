package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.ExpiredAuthArtifactPurge
import kr.easydoc.application.auth.ExpiredAuthArtifactPurgeResult
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Timestamp
import java.time.Instant

/**
 * `email_verification_codes`·`password_reset_codes`·`oauth_states` 세 표를
 * [createdBefore] 기준으로 고르고 지운다 — 표마다 **한 문장** `DELETE`로 서버 안에서
 * 끝낸다(`JdbcSignupGrantRecordPurge`와 같은 형태). `code_hash`·`salt`·`state`·`nonce`를
 * 애플리케이션 메모리로 꺼내지 않는다 — 서브쿼리 하나로 끝내면 그 값들이 JVM으로 아예
 * 들어오지 않는다.
 *
 * **파기 기준은 `created_at`이지 `consumed_at`·`expires_at`이 아니다** — 이유는
 * [ExpiredAuthArtifactPurge] KDoc(재발송 쿨다운 무력화 회귀 방지).
 *
 * `oauth_states.user_id`는 NULL일 수 있다(가입·로그인 전 흐름이 채운다) — 이 조건이
 * `created_at` 하나뿐이라 `user_id` NULL 여부와 무관하게 오래된 행이 지워진다.
 *
 * `email_verification_codes`·`password_reset_codes`에는 `(user_id, created_at)` 복합
 * 인덱스가 있다(`ix_email_verification_codes_user_id_created_at`·
 * `ix_password_reset_codes_user_id_created_at`, `V7__email_verification.sql`·
 * `V11__password_reset_codes.sql`) — 하지만 선두 열이 `user_id`라, `user_id` 조건 없이
 * `created_at` 범위만 보는 이 질의는 그 인덱스를 쓰지 못한다. `oauth_states`에는
 * `created_at` 인덱스가 아예 없다(`V6__user_identities.sql`). 그래서 세 표 모두 이
 * 질의에서는 순차 스캔이다 — `JdbcSignupGrantRecordPurge`와 같은 판단이다: 파기는 하루
 * 한 번이고 행 수명(코드 TTL 10분·쿨다운 60초 대비 보존기간 24시간)이 짧아 각 표가
 * 작게 유지된다. 지금 전용 인덱스를 추가하려면 마이그레이션이 필요한데 V22는
 * `docs/plans/2026-09-10-signup-consent.md`가 예약했다 — 그래서 순차 스캔으로 간다.
 */
class JdbcExpiredAuthArtifactPurge(private val jdbc: JdbcClient) : ExpiredAuthArtifactPurge {
    override fun purge(
        createdBefore: Instant,
        batchSize: Int,
    ): ExpiredAuthArtifactPurgeResult {
        val createdBeforeTimestamp = Timestamp.from(createdBefore)
        val emailVerificationCodesDeleted =
            deleteExpired(EMAIL_VERIFICATION_CODES_TABLE, createdBeforeTimestamp, batchSize)
        val passwordResetCodesDeleted =
            deleteExpired(PASSWORD_RESET_CODES_TABLE, createdBeforeTimestamp, batchSize)
        val oauthStatesDeleted = deleteExpired(OAUTH_STATES_TABLE, createdBeforeTimestamp, batchSize)
        return ExpiredAuthArtifactPurgeResult(
            enabled = true,
            emailVerificationCodesDeleted = emailVerificationCodesDeleted,
            passwordResetCodesDeleted = passwordResetCodesDeleted,
            oauthStatesDeleted = oauthStatesDeleted,
        )
    }

    /**
     * [table] 은 이 클래스가 정한 세 상수 문자열만 받으므로 문자열 보간을 SQL에 직접
     * 섞어도 인젝션 표면이 생기지 않는다(`JdbcOneTimeCodeStore`와 같은 판단) — 값이
     * 사용자 입력에서 오지 않는다.
     */
    private fun deleteExpired(
        table: String,
        createdBefore: Timestamp,
        batchSize: Int,
    ): Int =
        jdbc
            .sql(deleteExpiredSql(table))
            .param("createdBefore", createdBefore)
            .param("limit", batchSize)
            .update()

    private companion object {
        const val EMAIL_VERIFICATION_CODES_TABLE = "email_verification_codes"
        const val PASSWORD_RESET_CODES_TABLE = "password_reset_codes"
        const val OAUTH_STATES_TABLE = "oauth_states"

        fun deleteExpiredSql(table: String): String =
            """
            DELETE FROM $table
            WHERE id IN (
                SELECT id
                FROM $table
                WHERE created_at < :createdBefore
                ORDER BY created_at ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            """.trimIndent()
    }
}
