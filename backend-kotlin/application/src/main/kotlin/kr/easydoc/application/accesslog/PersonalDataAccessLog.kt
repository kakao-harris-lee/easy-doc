package kr.easydoc.application.accesslog

import kr.easydoc.core.accesslog.PersonalDataAccessOutcome
import kr.easydoc.core.privacy.CONTENT_MASK
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 접속기록(`personal_data_access_logs`, V22) 한 건 — 고시(개인정보의 안전성 확보조치
 * 기준)가 요구하는 항목을 그대로 담는다(계획 `docs/plans/2026-09-11-access-log-retention.md`
 * §3.1).
 *
 * [clientIp]는 관리자 API 경로에서는 실제 접속지 정보이고, CLI 실행 프로필(usage-report·
 * credit-grant·admin-grant)에서는 HTTP 요청이 아니라 접속지가 없으므로 실행 주체를
 * 대신 담은 값이다(같은 계획 §3.2, `CliActor` 참고).
 */
data class PersonalDataAccessLogEntry(
    val actorUserId: UUID,
    val accessedAt: Instant,
    val clientIp: String,
    val operation: String,
    val subjectScope: String?,
    val outcome: PersonalDataAccessOutcome,
) {
    /** [clientIp]는 접속지 정보라 로그에 그대로 찍히지 않게 가린다 — `User`와 같은 규약. */
    override fun toString(): String =
        "PersonalDataAccessLogEntry(actorUserId=$actorUserId, accessedAt=$accessedAt, " +
            "clientIp=$CONTENT_MASK, operation=$operation, subjectScope=$subjectScope, outcome=$outcome)"
}

/**
 * 접속기록 삽입 전용 포트. **갱신·삭제 메서드를 두지 않는다** — 위·변조 방지 요구에 대한
 * 최소 대응이다(계획 §3.1 「애플리케이션에 수정·삭제 경로를 두지 않는다」, 수용 기준 5).
 * 새 메서드를 이 인터페이스에 추가하기 전에 그 결정을 다시 확인하라.
 */
fun interface PersonalDataAccessLogWriter {
    fun insert(entry: PersonalDataAccessLogEntry)
}

/**
 * 접속기록 유스케이스 — 잡는 자리는 관리자 API 경계 하나([kr.easydoc.api.admin.AdminAccessInterceptor],
 * `api` 모듈)와 CLI 실행 프로필 셋(usage-report·credit-grant·admin-grant)뿐이다(계획 §3.2).
 * `accessedAt`을 여기서 계산해 호출자가 [Clock]을 직접 다루지 않게 한다.
 */
class RecordPersonalDataAccess(
    private val writer: PersonalDataAccessLogWriter,
    private val clock: Clock,
) {
    /** 관리자 확인을 통과했거나(HTTP 200대) CLI 실행이 목적한 작업을 마쳤을 때. */
    fun recordSuccess(
        actorUserId: UUID,
        clientIp: String,
        operation: String,
        subjectScope: String?,
    ) = record(actorUserId, clientIp, operation, subjectScope, PersonalDataAccessOutcome.SUCCESS)

    /** 관리자 확인에 실패했거나(HTTP 403) CLI 실행이 대상을 찾지 못해 거절됐을 때. */
    fun recordRejection(
        actorUserId: UUID,
        clientIp: String,
        operation: String,
        subjectScope: String?,
    ) = record(actorUserId, clientIp, operation, subjectScope, PersonalDataAccessOutcome.REJECTED)

    private fun record(
        actorUserId: UUID,
        clientIp: String,
        operation: String,
        subjectScope: String?,
        outcome: PersonalDataAccessOutcome,
    ) {
        writer.insert(
            PersonalDataAccessLogEntry(
                actorUserId = actorUserId,
                accessedAt = Instant.now(clock),
                clientIp = clientIp,
                operation = operation,
                subjectScope = subjectScope,
                outcome = outcome,
            ),
        )
    }
}
