package kr.easydoc.application.usage

import kr.easydoc.application.workspace.WORKSPACE_NOT_FOUND_MESSAGE
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.llm.LlmCallPurpose
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 목적(purpose)별 집계 한 줄 — 계약 `WorkspaceUsageResponse.by_purpose` 항목. */
data class PurposeUsage(
    val purpose: LlmCallPurpose,
    val llmCalls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    /** 비용을 알 수 없는 호출은 0이 아니라 이 집계에서 빠진다 — [WorkspaceUsage.costUnknownCalls] 참고. */
    val estimatedCostUsd: BigDecimal?,
    /**
     * 이 목적으로 완성 결과를 확인하지 못한 호출 수(`outcome = provider_error | outcome_unknown`) — 백로그
     * 「실패 호출 원장 추적」. [llmCalls]·[inputTokens]·[outputTokens]·[estimatedCostUsd] 에는
     * 들어가지 않는다(사용량을 확정할 수 없다). 그 목적으로 실패 호출만 있고 완료가 하나도
     * 없어도 [llmCalls] 가 0인 행으로 목록에 남는다 — 실패가 조용히 사라지지 않는다.
     */
    val failedCalls: Int,
)

/**
 * 워크스페이스 × `[from, to]`(포함) 기간 집계 — 계획
 * `docs/plans/2026-09-07-usage-ledger-and-report.md` §2 결정 5(2026-09-08 리뷰로
 * [documents]·[characters]의 출처를 `documents` 표에서 `llm_calls` 로 정정).
 *
 * **[documents]·[characters]는 `documents` 표가 아니라 `llm_calls.called_at`
 * 기준으로, 그 기간에 완료된 LLM 호출이 하나라도 있던 문서만 센다**(distinct
 * `document_id`). 두 가지가 이 정의에서 곧바로 따라 나온다.
 * - **등록만 되고 한 번도 변환되지 않은 문서는 포함되지 않는다** — 비용도 크레딧도
 *   쓰지 않았으므로 셀 이유가 없다.
 * - **문서가 보존 만료·삭제로 없어져도 이 집계는 바뀌지 않는다** — `llm_calls`가
 *   `document_char_count`(그 호출이 속한 문서의 `documents.char_count` 스냅샷)를 원장
 *   행 자체에 들고 있어 `documents` 표를 다시 읽지 않는다(`LlmCallEntry.documentCharCount`
 *   KDoc, V14 머리주석 3차 정정). 청구 근거를 남기려고 만든 원장이 문서 삭제로
 *   스스로의 근거를 잃으면 안 된다는 것이 이 정정의 이유다.
 *
 * [credits]는 그 기간에 소비로 확정된 `credit_transactions`의 `consume/conversion`
 * 합이다. 따라서 최초 변환뿐 아니라 성공한 재변환도 포함한다. 차감 원장이 전혀 없는
 * V15 이전 그룹만 문서별 `ceil(document_char_count / 1000)` 합으로 대체한다.
 *
 * [llmCalls]·[inputTokens]·[outputTokens] 도 `llm_calls.called_at` 기준이며
 * `llm_calls.char_count` (그 호출이 실제로 본 마스킹 입력 길이) 는 어디에도 합산하지
 * 않는다 — 변환·보정 두 행이 문서 전체 마스킹 본문의 길이를 각자 담아 문서 단위로
 * 합치면 중복 계산이 된다(V14 머리주석, `LlmCallRecord.charCount` KDoc).
 *
 * [estimatedCostUsd] 는 `estimated_cost_usd` 가 `null` 이 아닌 행만 합한 값이다. 단가
 * 미설정 호출은 0으로 섞이지 않고 [costUnknownCalls] 로만 센다(계획 §2 결정 4).
 *
 * 워크스페이스 삭제로 `llm_calls.workspace_id` 가 `SET NULL` 된 행은 이 집계 밖이다 —
 * 그 행은 더 이상 어느 워크스페이스에도 속하지 않으므로 이 오퍼레이션(워크스페이스
 * 단위 조회)이 다룰 대상이 아니다. U3의 사용자 단위 리포트가 그 행을 다룬다.
 */
data class WorkspaceUsage(
    val documents: Int,
    val characters: Long,
    val credits: BigDecimal,
    val llmCalls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostUsd: BigDecimal?,
    val costUnknownCalls: Int,
    /**
     * 그 기간에 완성 결과를 확인하지 못한 호출 수(`outcome = provider_error | outcome_unknown`) — 백로그
     * 「실패 호출 원장 추적」, 2026-09-08). [documents]·[characters]·[llmCalls]·
     * [inputTokens]·[outputTokens]·[estimatedCostUsd] 는 전부 `outcome = 'completed'` 인
     * 행만 센다 — 실패·불명확 호출은 사용량을 확정할 수 없으므로 이 값들을
     * 왜곡하지 않는다. 대신 이 필드가 그 존재를 드러낸다 — 벤더가 실패한 요청에도
     * 과금할 수 있어(계획 §6 리스크 1) 0으로 숨기면 그 비용을 대조할 단서가 사라진다.
     */
    val failedCalls: Int,
    val byPurpose: List<PurposeUsage>,
)

/**
 * 사용량 집계 읽기 포트. 경계는 `[from, toExclusive)` — 이미 zone 자정 기준으로 변환된
 * `Instant` 쌍이다(`UsageQueryService.resolvePeriod`가 변환한다). 어댑터는 시간대를
 * 다시 알 필요가 없다.
 *
 * **소유자 전체 워크스페이스를 한 번에 모으는 메서드는 여기 없다.** U3(운영 리포트)가
 * 다룰 집계는 워크스페이스 단위가 아니라 **사용자 단위**이고 `workspace_id IS NULL`
 * (워크스페이스가 삭제된) 행까지 포함해야 하므로 이 포트의 모양과 다르다 — U3가 그때
 * 자기 질의를 새로 추가한다(2026-09-08 리뷰로 `aggregateAllOwned`를 걷어냈다 — U2가
 * 쓰지 않는 죽은 코드였고, 모양도 U3가 실제로 필요로 하는 것과 달랐다).
 */
interface UsageReadRepository {
    /**
     * [workspaceId]가 [ownerId] 소유가 아니거나 존재하지 않으면 `null` — 남의 자원의 존재를
     * 숨기는 404 판정을 이 반환값 하나로 맡긴다(`UsageQueryService.usageOf`).
     */
    fun aggregate(
        ownerId: UUID,
        workspaceId: UUID,
        from: Instant,
        toExclusive: Instant,
    ): WorkspaceUsage?

    /**
     * [now]에 유효한 이용 주기의 정확한 시작 시각. 결제 실패·주기 종료처럼 제공량이 0인
     * 계정과 아직 주기가 없는 계정은 `null`이다. 소유 여부는 [aggregate]가 최종 판정한다.
     */
    fun activeCycleStartedAt(
        ownerId: UUID,
        workspaceId: UUID,
        now: Instant,
    ): Instant? = null
}

/**
 * 워크스페이스 사용량 조회 유스케이스 — `GET /workspaces/{workspace_id}/usage`(계약 2.20.0).
 *
 * [zone] 은 `easydoc.usage.zone`(기본 `Asia/Seoul`) 이 정한 시간대다. `from`/`to` 는 이
 * 시간대의 날짜 경계로 `Instant` 구간 `[from 00:00, to+1일 00:00)` 으로 바뀐다 — 자정
 * 직전(`to` 날짜의 23:59:59)까지 포함하고 다음날 자정은 제외한다.
 *
 * 날짜를 하나라도 명시하면 [zone] 기준 **오늘**을 [clock]에서 읽어 기존 날짜 조회 규칙을
 * 적용한다 — `from` 생략은 이번 달 1일, `to` 생략은 오늘이다. 둘 다 생략한 사용자 화면
 * 조회는 [currentCycleUsageOf]가 담당한다.
 */
class UsageQueryService(
    private val repository: UsageReadRepository,
    private val zone: ZoneId,
    private val clock: Clock,
) {
    /** 단일 워크스페이스 집계. 소유가 아니면 [NotFoundException]. */
    fun usageOf(
        ownerId: UUID,
        workspaceId: UUID,
        from: String?,
        to: String?,
    ): WorkspaceUsage {
        val period = resolvePeriod(from, to)
        return repository.aggregate(ownerId, workspaceId, period.fromInstant, period.toExclusiveInstant)
            ?: throw NotFoundException(WORKSPACE_NOT_FOUND_MESSAGE)
    }

    /**
     * 현재 유효한 이용 주기의 사용량. 결제가 완료돼 제공량이 설정된 정확한 시각부터 이
     * 요청 시각 직전까지 센다. 미결제·결제 실패·만료처럼 유효한 주기가 없으면 빈 구간을
     * 조회해 0을 돌려주되, [UsageReadRepository.aggregate]의 소유권 404 판정은 유지한다.
     * 잔액이 0이어도 allowance가 남아 있는 소진 완료 주기는 저장소가 유효 주기로 돌려준다.
     */
    fun currentCycleUsageOf(
        ownerId: UUID,
        workspaceId: UUID,
    ): WorkspaceUsage {
        val now = Instant.now(clock)
        val startedAt = repository.activeCycleStartedAt(ownerId, workspaceId, now)
        val from = startedAt?.takeIf { it < now } ?: now
        return repository.aggregate(ownerId, workspaceId, from, now)
            ?: throw NotFoundException(WORKSPACE_NOT_FOUND_MESSAGE)
    }

    /** 날짜 파싱·범위 검증은 [UsagePeriodResolver]가 U3([UsageReportService])와 공유한다. */
    private fun resolvePeriod(
        rawFrom: String?,
        rawTo: String?,
    ): UsagePeriod {
        val today = LocalDate.now(clock.withZone(zone))
        val fromDate = rawFrom?.let(UsagePeriodResolver::parseDate) ?: today.withDayOfMonth(1)
        val toDate = rawTo?.let(UsagePeriodResolver::parseDate) ?: today
        return UsagePeriodResolver.resolve(fromDate, toDate, zone)
    }
}
