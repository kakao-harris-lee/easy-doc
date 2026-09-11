package kr.easydoc.application.accesslog

import kr.easydoc.core.accesslog.PersonalDataAccessOutcome
import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * 점검 보고서 유스케이스 — Spring 도 DB 도 없이 대역으로 돈다. 날짜 파싱·범위 검증 자체는
 * `UsagePeriodResolver`(이미 `UsageReportServiceTest`가 잰다)를 그대로 재사용하므로 여기서는
 * 집계(취급자별·업무별·거절 건수)만 잰다.
 */
class PersonalDataAccessReportServiceTest {
    @Test
    @DisplayName("기본 기간은 지난달 전체다")
    fun `기본 기간`() {
        val repository = FakeRepository()
        val service =
            PersonalDataAccessReportService(repository, ZoneId.of("Asia/Seoul"), Clock.fixed(FIXED_NOW, ZoneOffset.UTC))

        val report = service.generate(from = null, to = null)

        assertThat(report.from.toString()).isEqualTo("2026-08-01")
        assertThat(report.to.toString()).isEqualTo("2026-08-31")
    }

    @Test
    @DisplayName("취급자별·업무별 횟수와 거절 건수를 집계한다")
    fun `집계`() {
        val actorA = UUID.randomUUID()
        val actorB = UUID.randomUUID()
        val repository =
            FakeRepository(
                rows =
                    listOf(
                        row(actorA, "listAdminWorkspaces", PersonalDataAccessOutcome.SUCCESS),
                        row(actorA, "listAdminWorkspaces", PersonalDataAccessOutcome.REJECTED),
                        row(actorB, "readAdminUsage", PersonalDataAccessOutcome.SUCCESS),
                    ),
            )
        val service =
            PersonalDataAccessReportService(repository, ZoneId.of("Asia/Seoul"), Clock.fixed(FIXED_NOW, ZoneOffset.UTC))

        val report = service.generate(from = "2026-08-01", to = "2026-08-31")

        assertThat(report.totalCount).isEqualTo(3)
        assertThat(report.rejectedCount).isEqualTo(1)
        assertThat(report.countsByActor).containsEntry(actorA, 2).containsEntry(actorB, 1)
        assertThat(report.countsByOperation).containsEntry("listAdminWorkspaces", 2).containsEntry("readAdminUsage", 1)
        assertThat(report.rows).hasSize(3)
    }

    @Test
    @DisplayName("형식이 어긋난 날짜는 UsagePeriodResolver 와 같은 예외로 거절된다")
    fun `잘못된 날짜는 거절된다`() {
        val clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        val service = PersonalDataAccessReportService(FakeRepository(), ZoneId.of("Asia/Seoul"), clock)

        assertThatExceptionOfType(InvalidInputException::class.java)
            .isThrownBy { service.generate(from = "not-a-date", to = null) }
    }

    @Test
    @DisplayName("빈 결과는 헤더만 있는 CSV다 — UsageReportService와 같은 헤더 계약")
    fun `빈 결과는 헤더만 낸다`() {
        val service =
            PersonalDataAccessReportService(
                FakeRepository(),
                ZoneId.of("Asia/Seoul"),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
            )

        val report = service.generate(from = "2026-08-01", to = "2026-08-31")

        assertThat(report.csv).isEqualTo("id,actor_user_id,accessed_at,client_ip,operation,subject_scope,outcome\r\n")
    }

    @Test
    @DisplayName("행 값이 그대로 CSV에 실린다")
    fun `행 값이 CSV에 그대로 실린다`() {
        val actorId = UUID.randomUUID()
        val logRow =
            PersonalDataAccessLogRow(
                id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                actorUserId = actorId,
                accessedAt = Instant.parse("2026-08-15T00:00:00Z"),
                clientIp = "127.0.0.1",
                operation = "listAdminWorkspaces",
                subjectScope = "page=1",
                outcome = PersonalDataAccessOutcome.SUCCESS,
            )
        val service =
            PersonalDataAccessReportService(
                FakeRepository(listOf(logRow)),
                ZoneId.of("Asia/Seoul"),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
            )

        val report = service.generate(from = "2026-08-01", to = "2026-08-31")

        val dataLine =
            report.csv
                .lineSequence()
                .drop(1)
                .first()
        assertThat(dataLine).isEqualTo(
            "11111111-1111-1111-1111-111111111111,$actorId,2026-08-15T00:00:00Z,127.0.0.1," +
                "listAdminWorkspaces,page=1,success",
        )
    }

    @Test
    @DisplayName("subject_scope 에 콤마·큰따옴표가 있으면 RFC 4180대로 감싸진다 — UsageReportService와 같은 이스케이프")
    fun `콤마와 큰따옴표가 있으면 인용된다`() {
        val logRow =
            row(UUID.randomUUID(), "listAdminWorkspaces", PersonalDataAccessOutcome.SUCCESS)
                .copy(subjectScope = "q=\"특별\",1")
        val service =
            PersonalDataAccessReportService(
                FakeRepository(listOf(logRow)),
                ZoneId.of("Asia/Seoul"),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
            )

        val report = service.generate(from = "2026-08-01", to = "2026-08-31")

        val dataLine =
            report.csv
                .lineSequence()
                .drop(1)
                .first()
        assertThat(dataLine).contains("\"q=\"\"특별\"\",1\"")
    }

    private fun row(
        actorId: UUID,
        operation: String,
        outcome: PersonalDataAccessOutcome,
    ): PersonalDataAccessLogRow =
        PersonalDataAccessLogRow(
            id = UUID.randomUUID(),
            actorUserId = actorId,
            accessedAt = Instant.parse("2026-08-15T00:00:00Z"),
            clientIp = "127.0.0.1",
            operation = operation,
            subjectScope = null,
            outcome = outcome,
        )

    private class FakeRepository(private val rows: List<PersonalDataAccessLogRow> = emptyList()) :
        PersonalDataAccessLogRepository {
        override fun findBetween(
            fromInstant: Instant,
            toExclusiveInstant: Instant,
        ): List<PersonalDataAccessLogRow> = rows
    }

    private companion object {
        // 2026-09-11 KST — 지난달은 2026-08.
        val FIXED_NOW: Instant = Instant.parse("2026-09-10T15:00:00Z")
    }
}
