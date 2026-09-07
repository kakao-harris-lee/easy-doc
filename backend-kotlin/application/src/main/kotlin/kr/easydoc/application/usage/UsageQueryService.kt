package kr.easydoc.application.usage

import kr.easydoc.application.workspace.WORKSPACE_NOT_FOUND_MESSAGE
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.llm.LlmCallPurpose
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 목적(purpose)별 집계 한 줄 — 계약 `WorkspaceUsageResponse.by_purpose` 항목. */
data class PurposeUsage(
    val purpose: LlmCallPurpose,
    val llmCalls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    /** 비용을 알 수 없는 호출은 0이 아니라 이 집계에서 빠진다 — [WorkspaceUsage.costUnknownCalls] 참고. */
    val estimatedCostUsd: BigDecimal?,
)

/**
 * 워크스페이스 × `[from, to]`(포함) 기간 집계 — 계획
 * `docs/plans/2026-09-07-usage-ledger-and-report.md` §2 결정 5(2026-09-08 리뷰로
 * [documents]·[characters]·[credits]의 출처를 `documents` 표에서 `llm_calls` 로 정정).
 *
 * **[documents]·[characters]·[credits]는 `documents` 표가 아니라 `llm_calls.called_at`
 * 기준으로, 그 기간에 완료된 LLM 호출이 하나라도 있던 문서만 센다**(distinct
 * `document_id`). 두 가지가 이 정의에서 곧바로 따라 나온다.
 * - **등록만 되고 한 번도 변환되지 않은 문서는 포함되지 않는다** — 비용도 크레딧도
 *   쓰지 않았으므로 셀 이유가 없다.
 * - **문서가 보존 만료·삭제로 없어져도 이 집계는 바뀌지 않는다** — `llm_calls`가
 *   `document_char_count`(그 호출이 속한 문서의 `documents.char_count` 스냅샷)를 원장
 *   행 자체에 들고 있어 `documents` 표를 다시 읽지 않는다(`LlmCallEntry.documentCharCount`
 *   KDoc, V12 머리주석 3차 정정). 청구 근거를 남기려고 만든 원장이 문서 삭제로
 *   스스로의 근거를 잃으면 안 된다는 것이 이 정정의 이유다.
 *
 * [credits] 는 문서별 `ceil(document_char_count / 1000)` 을 각각 올림해 합한 값이지
 * [characters] 를 나중에 한 번에 올림한 값이 **아니다**(문서 100개가 각 999자씩이면
 * 크레딧은 100이지 올림한 합계 문자수 하나로 다시 나누면 99가 된다).
 *
 * [llmCalls]·[inputTokens]·[outputTokens] 도 `llm_calls.called_at` 기준이며
 * `llm_calls.char_count` (그 호출이 실제로 본 마스킹 입력 길이) 는 어디에도 합산하지
 * 않는다 — 변환·보정 두 행이 문서 전체 마스킹 본문의 길이를 각자 담아 문서 단위로
 * 합치면 중복 계산이 된다(V12 머리주석, `LlmCallRecord.charCount` KDoc).
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
    val credits: Long,
    val llmCalls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostUsd: BigDecimal?,
    val costUnknownCalls: Int,
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
}

/** `from`/`to` 가 `YYYY-MM-DD` 로 읽히지 않는다. */
internal const val MALFORMED_USAGE_DATE_MESSAGE = "from·to는 YYYY-MM-DD 형식이어야 합니다"

/** `to`가 `from`보다 앞이다. */
internal const val USAGE_TO_BEFORE_FROM_MESSAGE = "to는 from보다 앞일 수 없습니다"

/** 조회 기간이 366일을 넘는다. */
internal const val USAGE_RANGE_TOO_WIDE_MESSAGE = "조회 기간은 366일을 넘을 수 없습니다"

/**
 * 워크스페이스 사용량 조회 유스케이스 — `GET /workspaces/{workspace_id}/usage`(계약 2.20.0).
 *
 * [zone] 은 `easydoc.usage.zone`(기본 `Asia/Seoul`) 이 정한 시간대다. `from`/`to` 는 이
 * 시간대의 날짜 경계로 `Instant` 구간 `[from 00:00, to+1일 00:00)` 으로 바뀐다 — 자정
 * 직전(`to` 날짜의 23:59:59)까지 포함하고 다음날 자정은 제외한다.
 *
 * 기본값은 [zone] 기준 **오늘**을 [clock]에서 읽어 정한다 — `from` 생략은 이번 달 1일,
 * `to` 생략은 오늘이다.
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

    private fun resolvePeriod(
        rawFrom: String?,
        rawTo: String?,
    ): Period {
        val today = LocalDate.now(clock.withZone(zone))
        val fromDate = rawFrom?.let(::parseDate) ?: today.withDayOfMonth(1)
        val toDate = rawTo?.let(::parseDate) ?: today

        if (toDate.isBefore(fromDate)) throw InvalidInputException(USAGE_TO_BEFORE_FROM_MESSAGE)
        // 포함 상한이므로 날짜 수는 (일수 차이 + 1)이다 — from·to가 같은 날이면 1일이다.
        val inclusiveDays = ChronoUnit.DAYS.between(fromDate, toDate) + 1
        if (inclusiveDays > MAX_RANGE_DAYS) {
            throw InvalidInputException(USAGE_RANGE_TOO_WIDE_MESSAGE)
        }

        return Period(
            fromInstant = fromDate.atStartOfDay(zone).toInstant(),
            // 포함 상한 `to`의 다음날 자정을 배타 상한으로 쓴다 — `to` 날짜 23:59:59.999...는
            // 포함되고 다음날 00:00:00은 제외된다.
            toExclusiveInstant = toDate.plusDays(1).atStartOfDay(zone).toInstant(),
        )
    }

    private fun parseDate(raw: String): LocalDate =
        try {
            LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: DateTimeParseException) {
            throw InvalidInputException(MALFORMED_USAGE_DATE_MESSAGE)
        }

    private data class Period(
        val fromInstant: Instant,
        val toExclusiveInstant: Instant,
    )

    private companion object {
        const val MAX_RANGE_DAYS = 366L
    }
}
