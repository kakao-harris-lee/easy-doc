package kr.easydoc.application.usage

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** `UsageReportService`(U3) — 기간 기본값(지난달)·검증·CSV 렌더링. */
class UsageReportServiceTest {
    private val zone = ZoneId.of("Asia/Seoul")

    // 2026-09-07 12:00 KST 로 고정.
    private val clock = Clock.fixed(Instant.parse("2026-09-07T03:00:00Z"), ZoneId.of("UTC"))

    private class FakeUsageReportRepository(private val rows: List<UsageReportRow>) : UsageReportRepository {
        var lastFrom: Instant? = null
        var lastToExclusive: Instant? = null

        override fun reportRows(
            fromInstant: Instant,
            toExclusiveInstant: Instant,
        ): List<UsageReportRow> {
            lastFrom = fromInstant
            lastToExclusive = toExclusiveInstant
            return rows
        }
    }

    @Suppress("LongParameterList")
    private fun row(
        userId: UUID? = UUID.randomUUID(),
        ownerEmail: String?,
        workspaceId: UUID? = UUID.randomUUID(),
        workspaceName: String? = "워크스페이스",
        documents: Int = 1,
        characters: Long = 100,
        credits: Long = 1,
        llmCalls: Int = 1,
        inputTokens: Long = 10,
        outputTokens: Long = 5,
        estimatedCostUsd: BigDecimal? = BigDecimal("0.001000"),
        costUnknownCalls: Int = 0,
        failedCalls: Int = 0,
    ): UsageReportRow =
        UsageReportRow(
            userId = userId,
            ownerEmail = ownerEmail,
            workspaceId = workspaceId,
            workspaceName = workspaceName,
            documents = documents,
            characters = characters,
            credits = credits,
            llmCalls = llmCalls,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            estimatedCostUsd = estimatedCostUsd,
            costUnknownCalls = costUnknownCalls,
            failedCalls = failedCalls,
        )

    @Test
    @DisplayName("from·to를 생략하면 지난달 1일부터 지난달 마지막날까지다")
    fun `기본 기간은 지난달 전체다`() {
        val repository = FakeUsageReportRepository(emptyList())
        val service = UsageReportService(repository, zone, clock)

        val report = service.generateCsv(from = null, to = null)

        // 오늘(고정) 2026-09-07 KST → 지난달은 2026-08, 1일~31일.
        assertThat(report.from.toString()).isEqualTo("2026-08-01")
        assertThat(report.to.toString()).isEqualTo("2026-08-31")
        assertThat(repository.lastFrom).isEqualTo(Instant.parse("2026-07-31T15:00:00Z"))
        assertThat(repository.lastToExclusive).isEqualTo(Instant.parse("2026-08-31T15:00:00Z"))
    }

    @Test
    @DisplayName("from·to를 주면 그 날짜의 zone 자정 경계로 변환한다")
    fun `명시한 기간이 자정 경계로 변환된다`() {
        val repository = FakeUsageReportRepository(emptyList())
        val service = UsageReportService(repository, zone, clock)

        val report = service.generateCsv(from = "2026-01-01", to = "2026-01-31")

        assertThat(report.from.toString()).isEqualTo("2026-01-01")
        assertThat(report.to.toString()).isEqualTo("2026-01-31")
        assertThat(repository.lastFrom).isEqualTo(Instant.parse("2025-12-31T15:00:00Z"))
        assertThat(repository.lastToExclusive).isEqualTo(Instant.parse("2026-01-31T15:00:00Z"))
    }

    @Test
    @DisplayName("to가 from보다 앞이면 422(InvalidInputException)")
    fun `to가 from보다 앞이면 예외`() {
        val service = UsageReportService(FakeUsageReportRepository(emptyList()), zone, clock)

        assertThatThrownBy { service.generateCsv(from = "2026-02-01", to = "2026-01-01") }
            .hasMessage(USAGE_TO_BEFORE_FROM_MESSAGE)
    }

    @Test
    @DisplayName("366일을 넘는 구간은 예외")
    fun `366일 초과 구간은 예외`() {
        val service = UsageReportService(FakeUsageReportRepository(emptyList()), zone, clock)

        assertThatThrownBy { service.generateCsv(from = "2025-01-01", to = "2026-01-02") }
            .hasMessage(USAGE_RANGE_TOO_WIDE_MESSAGE)
    }

    @ParameterizedTest(name = "「{0}」은 형식 오류다")
    @ValueSource(strings = ["2026-9-7", "2026-09-31", "2026/01/01"])
    @DisplayName("형식이 어긋난 날짜는 예외")
    fun `형식 오류는 예외`(malformed: String) {
        val service = UsageReportService(FakeUsageReportRepository(emptyList()), zone, clock)

        assertThatThrownBy { service.generateCsv(from = malformed, to = null) }
            .hasMessage(MALFORMED_USAGE_DATE_MESSAGE)
    }

    @Test
    @DisplayName("빈 결과는 헤더만 있는 CSV다")
    fun `빈 결과는 헤더만 낸다`() {
        val service = UsageReportService(FakeUsageReportRepository(emptyList()), zone, clock)

        val report = service.generateCsv(from = "2026-01-01", to = "2026-01-31")

        assertThat(report.rowCount).isEqualTo(0)
        assertThat(report.csv).isEqualTo(
            "workspace_id,workspace_name,owner_email,documents,characters,credits," +
                "llm_calls,failed_calls,input_tokens,output_tokens,estimated_cost_usd,cost_unknown_calls\r\n",
        )
    }

    @Test
    @DisplayName("헤더가 정확히 계약대로다")
    fun `헤더 열 이름이 정확하다`() {
        val service = UsageReportService(FakeUsageReportRepository(emptyList()), zone, clock)

        val report = service.generateCsv(from = "2026-01-01", to = "2026-01-31")

        assertThat(report.csv.lineSequence().first())
            .isEqualTo(
                "workspace_id,workspace_name,owner_email,documents,characters,credits," +
                    "llm_calls,failed_calls,input_tokens,output_tokens,estimated_cost_usd,cost_unknown_calls",
            )
    }

    @Test
    @DisplayName("행 값이 그대로 CSV에 실린다 — 비용은 소수 문자열")
    fun `행 값이 CSV에 그대로 실린다`() {
        val workspaceId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val rowValue =
            row(
                ownerEmail = "owner@example.com",
                workspaceId = workspaceId,
                workspaceName = "공간1",
                documents = 3,
                characters = 3000,
                credits = 3,
                llmCalls = 5,
                inputTokens = 100,
                outputTokens = 200,
                estimatedCostUsd = BigDecimal("0.012345"),
                costUnknownCalls = 1,
            )
        val service = UsageReportService(FakeUsageReportRepository(listOf(rowValue)), zone, clock)

        val report = service.generateCsv(from = "2026-01-01", to = "2026-01-31")

        val dataLine =
            report.csv
                .lineSequence()
                .drop(1)
                .first()
        assertThat(dataLine).isEqualTo(
            "$workspaceId,공간1,owner@example.com,3,3000,3,5,0,100,200,0.012345,1",
        )
    }

    @Test
    @DisplayName("실패 호출 수는 llm_calls 바로 뒤 열에 실린다 (V18)")
    fun `실패 호출 수는 llm_calls 다음 열이다`() {
        val rowValue =
            row(ownerEmail = "owner@example.com", llmCalls = 5, failedCalls = 2)
        val service = UsageReportService(FakeUsageReportRepository(listOf(rowValue)), zone, clock)

        val report = service.generateCsv(from = "2026-01-01", to = "2026-01-31")

        val dataLine =
            report.csv
                .lineSequence()
                .drop(1)
                .first()
        assertThat(dataLine.split(",")).containsSequence("5", "2")
    }

    @Test
    @DisplayName("비용이 null이면 빈 칸이다 — 0으로 섞이지 않는다")
    fun `비용 미상은 빈 칸이다`() {
        val rowValue = row(ownerEmail = "owner@example.com", estimatedCostUsd = null, costUnknownCalls = 1)
        val service = UsageReportService(FakeUsageReportRepository(listOf(rowValue)), zone, clock)

        val report = service.generateCsv(from = "2026-01-01", to = "2026-01-31")

        val dataLine =
            report.csv
                .lineSequence()
                .drop(1)
                .first()
        assertThat(dataLine).endsWith(",,1")
    }

    @Test
    @DisplayName("workspace_id가 null이면 워크스페이스 열이 빈 칸과 고정 안내문이다 — 삭제된 워크스페이스")
    fun `워크스페이스가 삭제된 행은 안내문으로 표시된다`() {
        val rowValue = row(ownerEmail = "owner@example.com", workspaceId = null, workspaceName = null)
        val service = UsageReportService(FakeUsageReportRepository(listOf(rowValue)), zone, clock)

        val report = service.generateCsv(from = "2026-01-01", to = "2026-01-31")

        val dataLine =
            report.csv
                .lineSequence()
                .drop(1)
                .first()
        assertThat(dataLine).startsWith(",(삭제된 워크스페이스),owner@example.com,")
    }

    @Test
    @DisplayName("owner_email이 null이면 소유자 열이 고정 안내문이다 — 탈퇴한 계정(V19)")
    fun `탈퇴한 계정의 행은 안내문으로 표시된다`() {
        val rowValue = row(userId = null, ownerEmail = null)
        val service = UsageReportService(FakeUsageReportRepository(listOf(rowValue)), zone, clock)

        val report = service.generateCsv(from = "2026-01-01", to = "2026-01-31")

        val dataLine =
            report.csv
                .lineSequence()
                .drop(1)
                .first()
        assertThat(dataLine).contains(",(탈퇴한 계정 합계),")
    }

    @Test
    @DisplayName("탈퇴한 계정 행도 다른 행과 함께 정렬되고 사라지지 않는다")
    fun `탈퇴한 계정 행도 정렬 목록에 남는다`() {
        val rows =
            listOf(
                row(ownerEmail = "b@example.com", workspaceName = "가"),
                row(userId = null, ownerEmail = null, workspaceName = "탈퇴한 계정의 공간"),
                row(ownerEmail = "a@example.com", workspaceName = "나"),
            )
        val service = UsageReportService(FakeUsageReportRepository(rows), zone, clock)

        val report = service.generateCsv(from = "2026-01-01", to = "2026-01-31")

        val emails =
            report.csv
                .lineSequence()
                .drop(1)
                .filter { it.isNotBlank() }
                .map { it.split(",")[2] }
                .toList()
        assertThat(emails).hasSize(3).contains("a@example.com", "b@example.com", "(탈퇴한 계정 합계)")
    }

    @Test
    @DisplayName("콤마·큰따옴표가 든 워크스페이스 이름은 RFC 4180대로 감싸진다")
    fun `콤마와 큰따옴표가 있으면 인용된다`() {
        val rowValue = row(ownerEmail = "owner@example.com", workspaceName = "공간, \"특별\"")
        val service = UsageReportService(FakeUsageReportRepository(listOf(rowValue)), zone, clock)

        val report = service.generateCsv(from = "2026-01-01", to = "2026-01-31")

        val dataLine =
            report.csv
                .lineSequence()
                .drop(1)
                .first()
        assertThat(dataLine).contains("\"공간, \"\"특별\"\"\"")
    }

    @Test
    @DisplayName("=·@로 시작하는 워크스페이스 이름은 CSV 인젝션 방어로 작은따옴표가 붙는다")
    fun `수식으로 해석될 수 있는 값은 이스케이프된다`() {
        val formulaRow = row(ownerEmail = "a@example.com", workspaceName = "=1+1")
        val atRow = row(ownerEmail = "b@example.com", workspaceName = "@x")
        val service = UsageReportService(FakeUsageReportRepository(listOf(formulaRow, atRow)), zone, clock)

        val report = service.generateCsv(from = "2026-01-01", to = "2026-01-31")

        val dataLines =
            report.csv
                .lineSequence()
                .drop(1)
                .filter { it.isNotBlank() }
                .toList()
        assertThat(dataLines[0]).contains(",'=1+1,")
        assertThat(dataLines[1]).contains(",'@x,")
    }

    @Test
    @DisplayName("행은 owner_email 다음 workspace_name 순으로 정렬된다")
    fun `정렬 순서는 owner_email 다음 workspace_name이다`() {
        val rows =
            listOf(
                row(ownerEmail = "b@example.com", workspaceName = "가"),
                row(ownerEmail = "a@example.com", workspaceName = "나"),
                row(ownerEmail = "a@example.com", workspaceName = "가"),
            )
        val service = UsageReportService(FakeUsageReportRepository(rows), zone, clock)

        val report = service.generateCsv(from = "2026-01-01", to = "2026-01-31")

        val emails =
            report.csv
                .lineSequence()
                .drop(1)
                .filter { it.isNotBlank() }
                .map { it.split(",")[2] }
                .toList()
        assertThat(emails).containsExactly("a@example.com", "a@example.com", "b@example.com")
    }
}
