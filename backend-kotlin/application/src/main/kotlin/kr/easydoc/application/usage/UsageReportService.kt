package kr.easydoc.application.usage

import kr.easydoc.core.privacy.CONTENT_MASK
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 운영 리포트(U3, `usage-report` 프로필) 행 하나 — 사용자 × 워크스페이스 × 기간.
 *
 * [workspaceId]가 `null`이면 그 기간에 그 사용자가 낸 호출 중 워크스페이스가 나중에
 * 삭제된(`llm_calls.workspace_id` `SET NULL`, V14) 행들을 묶은 것이다 — U2의
 * [WorkspaceUsage]와 달리 이 리포트는 그 행까지 다뤄야 사용자별 월간 청구 총액이
 * 워크스페이스 삭제로 누락되지 않는다(계획
 * `docs/plans/2026-09-07-usage-ledger-and-report.md` §2 결정 5).
 *
 * [ownerEmail]·[workspaceName]은 청구서 발송에 필요한 운영 출력이라 CSV에는 싣지만,
 * 로그에는 남기지 않는다(`UsageReportRunner`).
 */
data class UsageReportRow(
    /**
     * `null`이면 그 사용자가 탈퇴했다(회원 탈퇴 계획 `docs/plans/2026-09-09-account-deletion.md`,
     * V19 — `llm_calls.user_id ON DELETE SET NULL`). `ownerEmail`도 그때 함께 `null`이다 —
     * 이 원장 행 자체는 원가·사용량 근거로 남지만 소유자 신원은 사라진다.
     */
    val userId: UUID?,
    val ownerEmail: String?,
    val workspaceId: UUID?,
    val workspaceName: String?,
    val documents: Int,
    val characters: Long,
    val credits: Long,
    val llmCalls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostUsd: BigDecimal?,
    val costUnknownCalls: Int,
    /**
     * 완성 자체가 나지 않은 호출 수(`outcome = provider_error`, V18) — 백로그 「실패 호출
     * 원장 추적」, 2026-09-08. [llmCalls]·[inputTokens]·[outputTokens]·[estimatedCostUsd]
     * 에는 들어가지 않는다.
     */
    val failedCalls: Int,
) {
    /** **이메일·워크스페이스 이름을 찍지 않는다** — `User`·`Workspace`와 같은 규약. */
    override fun toString(): String =
        "UsageReportRow(userId=$userId, ownerEmail=$CONTENT_MASK, workspaceId=$workspaceId, " +
            "workspaceName=${workspaceName?.let { CONTENT_MASK }}, documents=$documents, " +
            "characters=$characters, credits=$credits, llmCalls=$llmCalls, inputTokens=$inputTokens, " +
            "outputTokens=$outputTokens, estimatedCostUsd=$estimatedCostUsd, costUnknownCalls=$costUnknownCalls, " +
            "failedCalls=$failedCalls)"
}

/**
 * 운영 리포트(U3) 집계 읽기 포트. [UsageReadRepository]와 달리 **소유자 전체**를 한 번에
 * `(user_id, workspace_id)`로 묶어 훑는다 — `workspace_id IS NULL`(워크스페이스가 삭제된)
 * 행도 포함한다.
 */
interface UsageReportRepository {
    fun reportRows(
        fromInstant: Instant,
        toExclusiveInstant: Instant,
    ): List<UsageReportRow>
}

/** [UsageReportService.generateCsv]의 결과 — 실행부(`UsageReportRunner`)가 파일에 쓰고 카운트만 표준출력에 낸다. */
data class UsageReport(
    val csv: String,
    val rowCount: Int,
    val from: LocalDate,
    val to: LocalDate,
) {
    /** [csv] 본문에는 소유자 이메일·워크스페이스 이름이 실린다 — 길이만 남긴다. */
    override fun toString(): String = "UsageReport(csvLength=${csv.length}, rowCount=$rowCount, from=$from, to=$to)"
}

/**
 * [UsageReportService.rows]의 결과 — `GET /admin/usage`(어드민 최소 계획
 * `docs/plans/2026-09-07-admin-minimum.md` §2 결정 4)가 CSV로 굳히지 않고 JSON으로 낼 때
 * 쓴다. [rows]의 각 항목은 이미 `toString()`을 손으로 쥐고 있다([UsageReportRow]) — 이
 * 컨테이너는 기본 생성 `toString()`으로 둬도 값이 새지 않는다.
 */
data class UsageReportRows(
    val rows: List<UsageReportRow>,
    val from: LocalDate,
    val to: LocalDate,
)

/**
 * 운영 리포트 유스케이스(U3) — `usage-report` 프로필이 부른다. 기본 기간은 [zone] 기준
 * **지난달 전체**다 — U2([UsageQueryService], 이번 달 1일~오늘)와 달리 리포트는 이미 끝난
 * 달을 청구 대상으로 삼는다.
 *
 * 날짜 형식·역순·366일 초과 검증은 [UsagePeriodResolver]를 U2와 공유한다(계획 §3 U3
 * 「U2의 집계 서비스를 재사용」).
 */
class UsageReportService(
    private val repository: UsageReportRepository,
    private val zone: ZoneId,
    private val clock: Clock,
) {
    /** `from`·`to` 생략 시 지난달 1일~지난달 마지막날. 형식 오류·역순·366일 초과는 [UsagePeriodResolver]가 던진다. */
    fun generateCsv(
        from: String?,
        to: String?,
    ): UsageReport {
        val resolved = resolveRows(from, to)
        return UsageReport(
            csv = renderCsv(resolved.rows),
            rowCount = resolved.rows.size,
            from = resolved.from,
            to = resolved.to,
        )
    }

    /**
     * [generateCsv]와 같은 행·같은 기간 규칙을 CSV로 굳히지 않고 그대로 준다 —
     * `GET /admin/usage`(어드민 최소 계획 §2 결정 4)가 JSON으로 낼 때 쓴다.
     */
    fun rows(
        from: String?,
        to: String?,
    ): UsageReportRows {
        val resolved = resolveRows(from, to)
        return UsageReportRows(resolved.rows, resolved.from, resolved.to)
    }

    private fun resolveRows(
        from: String?,
        to: String?,
    ): UsageReportRows {
        val today = LocalDate.now(clock.withZone(zone))
        val previousMonth = today.minusMonths(1)
        val fromDate = from?.let(UsagePeriodResolver::parseDate) ?: previousMonth.withDayOfMonth(1)
        val toDate =
            to?.let(UsagePeriodResolver::parseDate)
                ?: previousMonth.withDayOfMonth(previousMonth.lengthOfMonth())
        val period = UsagePeriodResolver.resolve(fromDate, toDate, zone)

        val rows = repository.reportRows(period.fromInstant, period.toExclusiveInstant)
        val sorted =
            rows.sortedWith(
                compareBy(
                    { it.ownerEmail ?: DELETED_ACCOUNT_LABEL },
                    { it.workspaceName ?: DELETED_WORKSPACE_LABEL },
                    { it.workspaceId?.toString().orEmpty() },
                ),
            )

        return UsageReportRows(sorted, fromDate, toDate)
    }

    private fun renderCsv(rows: List<UsageReportRow>): String =
        buildString {
            append(HEADER)
            append(CRLF)
            rows.forEach { row ->
                append(csvLineOf(row))
                append(CRLF)
            }
        }

    private fun csvLineOf(row: UsageReportRow): String =
        listOf(
            row.workspaceId?.toString().orEmpty(),
            row.workspaceName ?: DELETED_WORKSPACE_LABEL,
            row.ownerEmail ?: DELETED_ACCOUNT_LABEL,
            row.documents.toString(),
            row.characters.toString(),
            row.credits.toString(),
            row.llmCalls.toString(),
            // llm_calls 바로 뒤 — 완성 자체가 나지 않은 호출 수(V18).
            row.failedCalls.toString(),
            row.inputTokens.toString(),
            row.outputTokens.toString(),
            row.estimatedCostUsd?.toPlainString().orEmpty(),
            row.costUnknownCalls.toString(),
        ).joinToString(",", transform = ::csvField)

    /**
     * CSV 인젝션 방어(RFC 4180 quoting과 별개 축) + RFC 4180 quoting 순으로 적용한다.
     *
     * 워크스페이스 이름·소유자 이메일은 사용자가 정한 문자열이라, `=`·`+`·`-`·`@`로
     * 시작하면 엑셀·시트가 그 필드를 **수식**으로 해석해 실행할 수 있다(CSV/수식
     * 인젝션). 그 자리에 작은따옴표(`'`)를 하나 앞세우면 대부분의 스프레드시트가
     * 문자열로 취급한다. 숫자 열(documents·characters·…)은 전부 음이 아닌 정수/소수라
     * `-`로 시작할 일이 없으므로, 이 이스케이프를 **모든 필드에 예외 없이** 적용해도
     * 값이 달라지지 않는다 — 열마다 분기하지 않는다.
     */
    private fun csvField(value: String): String {
        val escaped = escapeFormulaInjection(value)
        return if (escaped.any(NEEDS_QUOTING_CHARS::contains)) {
            "\"" + escaped.replace("\"", "\"\"") + "\""
        } else {
            escaped
        }
    }

    /** `=`·`+`·`-`·`@`·탭으로 시작하는 필드 앞에 `'`를 붙여 수식으로 해석되지 않게 한다. */
    private fun escapeFormulaInjection(value: String): String =
        if (value.isNotEmpty() && value[0] in FORMULA_TRIGGER_CHARS) "'$value" else value

    private companion object {
        /** 워크스페이스가 나중에 삭제된 행(`workspace_id IS NULL`)의 표시명. */
        const val DELETED_WORKSPACE_LABEL = "(삭제된 워크스페이스)"

        /**
         * 소유자가 탈퇴한 행(`owner_email IS NULL`, V19 `llm_calls.user_id SET NULL`)의
         * 표시명이다. **여러 탈퇴 계정이 이 한 줄로 합쳐질 수 있다** — 저장소의 `GROUP BY
         * user_id`가 NULL끼리를 한 그룹으로 묶기 때문이다(`JdbcUsageReportRepository`
         * KDoc). `user_id`가 사라진 시점에 그 사용량이 누구 것이었는지는 설계상 복구
         * 불가능하므로(회원 탈퇴는 귀속을 없애는 것이 목적이다), 합쳐서 하나의 합계로
         * 내는 쪽을 택했다 — 그래서 이름에 "합계"를 명시해 운영자가 계정 하나로
         * 오해하지 않게 한다.
         */
        const val DELETED_ACCOUNT_LABEL = "(탈퇴한 계정 합계)"
        const val CRLF = "\r\n"

        /** RFC 4180 — 이 문자 중 하나라도 있으면 필드를 큰따옴표로 감싼다. */
        val NEEDS_QUOTING_CHARS = charArrayOf(',', '"', '\n', '\r')

        /** 스프레드시트가 수식 시작으로 해석하는 선두 문자. */
        val FORMULA_TRIGGER_CHARS = charArrayOf('=', '+', '-', '@', '\t')
        const val HEADER =
            "workspace_id,workspace_name,owner_email,documents,characters,credits," +
                "llm_calls,failed_calls,input_tokens,output_tokens,estimated_cost_usd,cost_unknown_calls"
    }
}
