package kr.easydoc.application.accesslog

import kr.easydoc.application.auth.TransactionRunner
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Period
import java.time.ZoneOffset

/**
 * 접속기록(`personal_data_access_logs`, V22) 보관기간 경과 후 파기 결과 — 건수만 담는다.
 * `client_ip`(접속지 정보)·`actor_user_id`·`subject_scope`는 담지 않는다
 * (계획 `docs/plans/2026-09-17-access-log-purge.md` §2.4, `SignupGrantRecordPurgeResult`와
 * 같은 판단).
 */
class PersonalDataAccessLogPurgeResult(
    val enabled: Boolean,
    val deleted: Int,
) {
    override fun toString(): String = "PersonalDataAccessLogPurgeResult(enabled=$enabled, deleted=$deleted)"
}

/**
 * 접속기록 파기 전용 포트 — 삽입 포트 [PersonalDataAccessLogWriter]와 **다른 인터페이스**다.
 * `PersonalDataAccessLogWriter`에 삭제 메서드를 두지 않기로 한 결정(위·변조 방지 최소
 * 대응, 수용 기준 5)을 지키기 위해서다. worker 프로필에서만 조립한다 — api 프로세스에는
 * 이 표를 지우는 코드 경로가 존재하지 않는다.
 */
interface PersonalDataAccessLogPurge {
    /** [accessedBefore] 이전(`accessed_at < accessedBefore`)에 접속한 행을 [batchSize]건까지 지운다. */
    fun purge(
        accessedBefore: Instant,
        batchSize: Int,
    ): PersonalDataAccessLogPurgeResult
}

/** 파기 결과를 감사·메트릭으로 남긴다. */
fun interface PersonalDataAccessLogPurgeObserver {
    fun record(result: PersonalDataAccessLogPurgeResult)
}

/**
 * worker 스케줄이 넘기는 정책. 값은 설정(`easydoc.access-log`)에서 온다.
 *
 * 보관기간 하한은 고시(개인정보의 안전성 확보조치 기준)가 요구하는 최소 보관기간
 * 1년이다 — 법정 불변식이므로 구성값이 아니라 코드 상수([MINIMUM_RETENTION])다.
 * `Period`는 단위가 섞이면(`P1Y` vs `P365D`) 직접 비교가 애매하므로, 고정 기준일에서
 * 뺀 날짜를 비교해 판정한다(계획 §2.2).
 */
class PersonalDataAccessLogPurgePolicy(
    val enabled: Boolean,
    val retention: Period,
    val batchSize: Int,
) {
    init {
        require(batchSize >= 1) { "접속기록 파기 배치 크기가 1보다 작다" }
        require(meetsMinimumRetention(retention)) {
            "접속기록 보관기간(retention=$retention)이 법정 최소 보관기간(${MINIMUM_RETENTION}) 미만이다"
        }
    }

    companion object {
        val MINIMUM_RETENTION: Period = Period.ofYears(1)

        /** 산술만을 위한 임의 고정 기준일 — 특정 연도의 의미는 없다. */
        private val EPOCH: LocalDate = LocalDate.of(EPOCH_YEAR, 1, 1)

        /** 기준일에서 각각을 뺀 날짜를 비교한다 — 뺀 결과가 더 뒤(늦은 날짜)면 보관기간이 짧다는 뜻이다. */
        private fun meetsMinimumRetention(retention: Period): Boolean =
            !EPOCH.minus(retention).isAfter(EPOCH.minus(MINIMUM_RETENTION))

        private const val EPOCH_YEAR: Int = 2000
    }
}

/**
 * 보관기간이 지난 `personal_data_access_logs`(V22) 행을 지운다. 일일 파기 배치
 * `RetentionPurgeScheduler`의 여섯 번째 단계다. `PurgeSignupGrantRecords`
 * (`application.credit`)와 같은 배치 흐름 — 대상량이 배치를 넘으면 배치가 짧아질
 * 때까지 트랜잭션을 반복한다.
 */
class PurgePersonalDataAccessLogs(
    private val store: PersonalDataAccessLogPurge,
    private val transaction: TransactionRunner,
    private val observer: PersonalDataAccessLogPurgeObserver,
    private val policy: PersonalDataAccessLogPurgePolicy,
    private val clock: Clock,
) {
    fun run(): PersonalDataAccessLogPurgeResult {
        val result = if (policy.enabled) drainPurges() else inactiveResult()
        observer.record(result)
        return result
    }

    private fun oneBatch(accessedBefore: Instant): PersonalDataAccessLogPurgeResult =
        transaction.inTransaction {
            store.purge(accessedBefore = accessedBefore, batchSize = policy.batchSize)
        }

    /**
     * `accessedBefore`는 이 실행에서 한 번만 계산한다 — `PurgeSignupGrantRecords.drainPurges`와
     * 같은 이유(배치를 반복하는 동안 시각이 흘러도 같은 실행 안에서는 같은 기준으로
     * 대상을 고른다). UTC 고정 오프셋으로 [Period]를 뺀다 — 순수 보존기간 판정이라
     * 시간대(KST) 변환이 필요 없다.
     */
    private fun drainPurges(): PersonalDataAccessLogPurgeResult {
        val accessedBefore =
            OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).minus(policy.retention).toInstant()
        var deleted = 0
        var rounds = 0
        do {
            rounds++
            check(rounds <= MAX_ROUNDS) { "접속기록 파기 배치가 ${MAX_ROUNDS}회를 넘었다" }
            val batch = oneBatch(accessedBefore)
            deleted += batch.deleted
        } while (batch.deleted >= policy.batchSize)
        return PersonalDataAccessLogPurgeResult(enabled = true, deleted = deleted)
    }

    private fun inactiveResult(): PersonalDataAccessLogPurgeResult =
        PersonalDataAccessLogPurgeResult(enabled = false, deleted = 0)

    private companion object {
        const val MAX_ROUNDS: Int = 10_000
    }
}

/** 파기 건수만 남긴다. `client_ip`·`actor_user_id`·`subject_scope`는 자리에 없다. */
class LoggingPersonalDataAccessLogPurgeObserver : PersonalDataAccessLogPurgeObserver {
    private val log = LoggerFactory.getLogger(LoggingPersonalDataAccessLogPurgeObserver::class.java)

    override fun record(result: PersonalDataAccessLogPurgeResult) {
        log.info("접속기록 파기: enabled={} deleted={}", result.enabled, result.deleted)
    }
}
