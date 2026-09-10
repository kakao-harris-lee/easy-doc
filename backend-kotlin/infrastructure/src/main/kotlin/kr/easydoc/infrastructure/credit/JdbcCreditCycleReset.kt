package kr.easydoc.infrastructure.credit

import kr.easydoc.application.credit.CreditCycleReset
import kr.easydoc.application.credit.CreditCycleResetResult
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.credit.CreditTransactionKind
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * 주기가 끝난 계정을 초기화한다 — `JdbcExpiredDocumentPurge`·`JdbcUnverifiedAccountPurge`와
 * 같은 잠금(`FOR UPDATE OF a SKIP LOCKED`) 후 행별 갱신 구조다. 크레딧을 「구독 주기에
 * 포함된 이용량」으로 바꾼 사용자 결정(2026-09-10)의 구현. 계정당 이용량은 하나뿐이라
 * (무료 체험·플랜을 별도 잔액으로 쪼개지 않는다), 이 배치 하나가 둘 다 처리한다 —
 * [Candidate.renews] 로 갈래를 가른다(2026-09-10 확정 추가).
 *
 * - **`renews = true`**(플랜) — [renewOne] : `balance = allowance`로 다시 채우고, 주기를
 *   [cycleEndsAt][Candidate.cycleEndsAt] 이 [reset] 의 `now` 를 넘어설 때까지 한 달씩
 *   민다(말일 clamp, [nextCycleEnd]). `cycle_ends_at`이 남아 다음 배치가 계속 본다.
 * - **`renews = false`**(무료 체험, 기본값) — [closeOne] : `balance`·`allowance`를 0으로,
 *   `cycle_ends_at`을 `NULL`로 만들어 주기를 **닫는다** — 다시 채우지 않고, 주기가
 *   없어졌으므로 다음 배치가 다시 건드리지 않는다.
 *
 * 두 갈래 모두 `reserved`는 건드리지 않고, `kind = 'cycle_reset'` 거래 1건을 남긴다
 * (`balance_delta` 가 그 증감을 그대로 담는다 — 갱신은 보통 양수, 종료는 음수).
 *
 * **여러 달치가 밀려도 갱신은 한 번만 반영한다** — 배치가 며칠 멈췄다 돌아도
 * [renewOne] 은 건너뛴 개월 수를 세면서 `cycle_ends_at`을 `now` 이후로 밀 때까지
 * 반복하되, `balance`는 `allowance` **한 번**으로 설정하고 거래도 **한 건**만
 * 남긴다(리뷰 지적) — 지나간 달치를 소급해서 여러 번 지급한 것처럼 보이면 안 된다.
 * 건너뛴 달이 있으면([renewOne] 결과의 `note`) `"N개 주기를 건너뛰었다"`를 거래의
 * `note`에 남긴다 — 몇 달이 그냥 지나갔는지 감사에서 보이게 하려는 것이다. 정상
 * 운영(배치가 매일 도는 상태)에서는 건너뛴 개월이 0 이라 `note`가 `null`이다.
 *
 * 다음 주기 종료일은 [zoneId] 달력으로 한 달을 더한다([nextCycleEnd] —
 * `ZonedDateTime.plusMonths`는 `LocalDate`와 같은 규칙을 쓴다: 말일은 그 달의 마지막
 * 날로 clamp 된다, 1/31 → 2/28(윤년은 2/29)). **UTC가 아니라 [zoneId]를 쓴다**(리뷰
 * 지적) — 사용량 집계(`UsageQueryService`·`UsageReportService`)가 이미
 * `easydoc.usage.zone`(기본 `Asia/Seoul`)으로 "한 달"의 경계를 정하므로, 이 배치도
 * 같은 시간대를 재사용해야 한다(`CreditCycleResetConfiguration`이 같은 값을 넘긴다) —
 * UTC로 계산하면 UTC 자정과 KST 자정이 갈리는 매일 9시간 창에서 월말 판정이
 * 어긋난다. `timestamp with time zone` 은 시간대를 기억하지 않고 순간만 저장하므로,
 * 그 순간을 [zoneId] 로 해석해 달력 연산을 한 뒤 다시 순간으로 되돌린다.
 *
 * 초기화 거래의 사유는 갈래마다 다르다 — **무엇을 했는지는 `kind`, 왜 그랬는지는
 * `reason`**이라는 구분을 지킨다(`kind`는 두 갈래 모두 `cycle_reset`). 갱신
 * (`renews = true`)은 [CreditReason.PLAN_MONTHLY] — 그 주기의 제공량을 다시 준 것이
 * 맞다. 종료(`renews = false`)는 [CreditReason.CYCLE_END] — 갱신 없이 주기가 닫혔다는
 * 뜻이다. 무료 체험 전용 사유가 아니다 — 운영자가 `credit-grant` CLI의
 * `--cycle-ends-at`만 주고 `--cycle-renews` 없이 연 유상 주기도 같은 사유로 닫힌다.
 * 「이용량은 계정당 하나」 결정(2026-09-10)이 잔액을 출처별로 쪼개지 않는 대신 이
 * 사유의 정확성에 기댄다 — `plan_monthly`로 잘못 적으면 없었던 구독이 있었던 것처럼
 * 보인다.
 */
class JdbcCreditCycleReset(
    private val jdbc: JdbcClient,
    private val zoneId: ZoneId,
) : CreditCycleReset {
    override fun reset(
        now: Instant,
        batchSize: Int,
    ): CreditCycleResetResult {
        val candidates = lockCandidates(now, batchSize)
        candidates.forEach { resetOne(it, now) }
        return CreditCycleResetResult(enabled = true, resetCount = candidates.size)
    }

    private fun lockCandidates(
        now: Instant,
        limit: Int,
    ): List<Candidate> =
        jdbc
            .sql(LOCK_CANDIDATES_SQL)
            .param("now", Timestamp.from(now))
            .param("limit", limit)
            .query { rs, _ -> toCandidate(rs) }
            .list()

    /**
     * 계정 한 건을 초기화한다 — [Candidate.renews] 로 [renewOne]/[closeOne] 을 가른다.
     * [Candidate.balance] 는 [lockCandidates] 가 **잠근 뒤** 읽은 값이라 이 사이 다른
     * 트랜잭션이 바꿀 수 없다.
     */
    private fun resetOne(
        candidate: Candidate,
        now: Instant,
    ) {
        val outcome = if (candidate.renews) renewOne(candidate, now) else closeOne(candidate)
        insertTransaction(candidate.workspaceId, candidate.ownerId, outcome)
    }

    /**
     * 갱신 — `balance = allowance`로 **한 번만** 설정하고, `cycle_ends_at`이 [now] 를
     * 넘어설 때까지 한 달씩 민다(밀린 달만큼 반복, 클래스 KDoc 「여러 달치가 밀려도」
     * 참고). 사유는 [CreditReason.PLAN_MONTHLY], 건너뛴 달이 있으면 `note`에 남긴다.
     */
    private fun renewOne(
        candidate: Candidate,
        now: Instant,
    ): ResetOutcome {
        var cycleStartedAt = candidate.cycleEndsAt
        var cycleEndsAt = nextCycleEnd(cycleStartedAt)
        var skippedPeriods = 0
        while (cycleEndsAt <= now) {
            check(skippedPeriods < MAX_CATCHUP_PERIODS) {
                "크레딧 주기 갱신이 ${MAX_CATCHUP_PERIODS}개월 넘게 밀렸다 — workspace=${candidate.workspaceId}"
            }
            cycleStartedAt = cycleEndsAt
            cycleEndsAt = nextCycleEnd(cycleStartedAt)
            skippedPeriods++
        }
        jdbc
            .sql(RENEW_SQL)
            .param("workspaceId", candidate.workspaceId)
            .param("allowance", candidate.allowance)
            .param("cycleStartedAt", Timestamp.from(cycleStartedAt))
            .param("cycleEndsAt", Timestamp.from(cycleEndsAt))
            .update()
        val note = if (skippedPeriods > 0) "${skippedPeriods}개 주기를 건너뛰었다" else null
        return ResetOutcome(candidate.allowance - candidate.balance, CreditReason.PLAN_MONTHLY, note)
    }

    /**
     * 종료 — 잔액·이용량을 0으로, 주기를 닫는다(`cycle_ends_at = NULL`). 사유는
     * [CreditReason.CYCLE_END] — 갱신이 아니므로 [CreditReason.PLAN_MONTHLY] 를 쓰지 않는다.
     */
    private fun closeOne(candidate: Candidate): ResetOutcome {
        jdbc
            .sql(CLOSE_SQL)
            .param("workspaceId", candidate.workspaceId)
            .update()
        return ResetOutcome(-candidate.balance, CreditReason.CYCLE_END, note = null)
    }

    private fun insertTransaction(
        workspaceId: UUID,
        ownerId: UUID,
        outcome: ResetOutcome,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO credit_transactions
                    (id, workspace_id, owner_user_id, document_id, kind, balance_delta, reserved_delta, reason, note,
                     actor_user_id)
                VALUES (:id, :workspaceId, :ownerId, NULL, :kind, :balanceDelta, 0, :reason, :note, NULL)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("workspaceId", workspaceId)
            .param("ownerId", ownerId)
            .param("kind", CreditTransactionKind.CYCLE_RESET.wireName)
            .param("balanceDelta", outcome.balanceDelta)
            .param("reason", outcome.reason.wireName)
            .param("note", outcome.note)
            .update()
    }

    /** 말일 clamp 규칙 — 클래스 KDoc 참고. [zoneId] 는 생성자로 받은 값을 그대로 쓴다. */
    private fun nextCycleEnd(previousEnd: Instant): Instant =
        ZonedDateTime.ofInstant(previousEnd, zoneId).plusMonths(1).toInstant()

    private fun toCandidate(rs: ResultSet): Candidate =
        Candidate(
            workspaceId = rs.getObject("workspace_id", UUID::class.java),
            ownerId = rs.getObject("owner_id", UUID::class.java),
            balance = rs.getInt("balance"),
            allowance = rs.getInt("allowance"),
            cycleEndsAt = rs.getObject("cycle_ends_at", OffsetDateTime::class.java).toInstant(),
            renews = rs.getBoolean("cycle_renews"),
        )

    private data class Candidate(
        val workspaceId: UUID,
        val ownerId: UUID,
        val balance: Int,
        val allowance: Int,
        val cycleEndsAt: Instant,
        val renews: Boolean,
    )

    /** [renewOne]/[closeOne] 의 결과 — [insertTransaction] 이 그대로 거래 한 건에 싣는다. */
    private data class ResetOutcome(
        val balanceDelta: Int,
        val reason: CreditReason,
        val note: String?,
    )

    private companion object {
        /** [renewOne] 의 무한루프 방지 — 클래스 KDoc 「여러 달치가 밀려도」 참고. */
        const val MAX_CATCHUP_PERIODS = 1200

        /**
         * 주기가 끝난 계정만 고른다(`cycle_ends_at IS NOT NULL AND <= :now`) — 주기가 없는
         * 계정(`cycle_ends_at IS NULL`)은 이 WHERE 절 자체가 걸러낸다. `owner_id`는
         * `credit_transactions.owner_user_id`(NOT NULL FK)를 채우려고 함께 읽는다.
         */
        val LOCK_CANDIDATES_SQL =
            """
            SELECT a.workspace_id, w.user_id AS owner_id, a.balance, a.allowance, a.cycle_ends_at, a.cycle_renews
            FROM workspace_credit_accounts a
            JOIN workspaces w ON w.id = a.workspace_id
            WHERE a.cycle_ends_at IS NOT NULL
              AND a.cycle_ends_at <= :now
            ORDER BY a.cycle_ends_at ASC, a.workspace_id ASC
            LIMIT :limit
            FOR UPDATE OF a SKIP LOCKED
            """.trimIndent()

        /** `reserved`는 SET 목록에 없다 — 건드리지 않는다(설계 결정). */
        val RENEW_SQL =
            """
            UPDATE workspace_credit_accounts
            SET balance = :allowance,
                cycle_started_at = :cycleStartedAt,
                cycle_ends_at = :cycleEndsAt,
                updated_at = now()
            WHERE workspace_id = :workspaceId
            """.trimIndent()

        /**
         * 주기를 닫는다 — `allowance`도 함께 0으로 만든다(화면의 "이번 주기 N 중 M 남음"
         * 표시가 닫힌 뒤에도 0/0으로 정합하도록). `reserved`는 SET 목록에 없다(설계 결정).
         */
        val CLOSE_SQL =
            """
            UPDATE workspace_credit_accounts
            SET balance = 0,
                allowance = 0,
                cycle_ends_at = NULL,
                updated_at = now()
            WHERE workspace_id = :workspaceId
            """.trimIndent()
    }
}
