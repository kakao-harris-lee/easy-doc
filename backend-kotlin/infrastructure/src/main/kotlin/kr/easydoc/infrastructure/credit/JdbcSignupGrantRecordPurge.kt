package kr.easydoc.infrastructure.credit

import kr.easydoc.application.credit.SignupGrantRecordPurge
import kr.easydoc.application.credit.SignupGrantRecordPurgeResult
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Timestamp
import java.time.Instant

/**
 * `signup_grant_records`(V20)·`phone_trial_grant_records`(V26) 를 [grantedBefore]
 * 기준으로 표마다 고르고 지운다 — 표마다 **한 문장**으로 서버 안에서 끝낸다
 * (`JdbcExpiredAuthArtifactPurge`와 같은 여러-표 형태). `JdbcUnverifiedAccountPurge`
 * (`infrastructure.auth`)의 잠금 후 별도 `DELETE` 두 단계 구조를 여기서는 쓰지 않는다 —
 * 그 구조가 두 단계인 이유(삭제 대상 id를 감사 로그에 남겨야 함, `DELETE`에 방어 조건을
 * 하나 더 검) 가 이 파기에는 없다. 이 파기는 이메일 해시·전화번호 지문을 어디에도
 * 남기지 않기로 했고(결과 객체도 표별 건수만 담는다), 규칙도 두 표 모두
 * `granted_at < :grantedBefore` 하나뿐이다.
 *
 * 두 단계로 짜면 지울 행의 `email_hash`·`phone_fingerprint`를 애플리케이션 메모리로
 * 꺼냈다가 `DELETE ... WHERE email_hash IN (:hash0, ...)` 파라미터로 다시 밀어 넣게 된다 —
 * 그 값이 JDBC 드라이버 바인드 파라미터 로그(강제 TRACE)에 찍히는 경로를 우리가 직접
 * 만드는 것이다. 서브쿼리 하나로 끝내면 해시·지문이 JVM으로 아예 들어오지 않는다.
 *
 * `granted_at` 에 인덱스가 없다 — 두 표 모두 가입·인증 하나당 한 행이고 파기는 하루 한
 * 번이라 순차 스캔으로 충분하다(표가 커지면 그때 인덱스를 넣는다). 지금 인덱스를 넣으면
 * V22 를 소비해 `docs/plans/2026-09-10-signup-consent.md` 가 예약한 마이그레이션 번호와
 * 충돌한다 — 그래서 이 파기는 마이그레이션 없이 기존 표 그대로 돈다.
 *
 * `FOR UPDATE SKIP LOCKED` 는 그대로 유지한다 — 여러 worker 인스턴스가 같은 배치를 동시에
 * 돌려도 서로 다른 행 집합을 골라 잠그므로 경합·중복 삭제 시도가 없다.
 */
class JdbcSignupGrantRecordPurge(private val jdbc: JdbcClient) : SignupGrantRecordPurge {
    override fun purge(
        grantedBefore: Instant,
        batchSize: Int,
    ): SignupGrantRecordPurgeResult {
        val grantedBeforeTimestamp = Timestamp.from(grantedBefore)
        val signupGrantRecordsDeleted =
            deleteExpired(SIGNUP_GRANT_RECORDS_TABLE, SIGNUP_GRANT_RECORDS_ID_COLUMN, grantedBeforeTimestamp, batchSize)
        val phoneTrialGrantRecordsDeleted =
            deleteExpired(
                PHONE_TRIAL_GRANT_RECORDS_TABLE,
                PHONE_TRIAL_GRANT_RECORDS_ID_COLUMN,
                grantedBeforeTimestamp,
                batchSize,
            )
        return SignupGrantRecordPurgeResult(
            enabled = true,
            signupGrantRecordsDeleted = signupGrantRecordsDeleted,
            phoneTrialGrantRecordsDeleted = phoneTrialGrantRecordsDeleted,
        )
    }

    /**
     * [table]·[idColumn] 은 이 클래스가 정한 상수 문자열만 받으므로 문자열 보간을 SQL에
     * 직접 섞어도 인젝션 표면이 생기지 않는다(`JdbcExpiredAuthArtifactPurge`와 같은 판단) —
     * 값이 사용자 입력에서 오지 않는다.
     */
    private fun deleteExpired(
        table: String,
        idColumn: String,
        grantedBefore: Timestamp,
        batchSize: Int,
    ): Int =
        jdbc
            .sql(deleteExpiredSql(table, idColumn))
            .param("grantedBefore", grantedBefore)
            .param("limit", batchSize)
            .update()

    private companion object {
        const val SIGNUP_GRANT_RECORDS_TABLE = "signup_grant_records"
        const val SIGNUP_GRANT_RECORDS_ID_COLUMN = "email_hash"
        const val PHONE_TRIAL_GRANT_RECORDS_TABLE = "phone_trial_grant_records"
        const val PHONE_TRIAL_GRANT_RECORDS_ID_COLUMN = "phone_fingerprint"

        fun deleteExpiredSql(
            table: String,
            idColumn: String,
        ): String =
            """
            DELETE FROM $table
            WHERE $idColumn IN (
                SELECT $idColumn
                FROM $table
                WHERE granted_at < :grantedBefore
                ORDER BY granted_at ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            """.trimIndent()
    }
}
