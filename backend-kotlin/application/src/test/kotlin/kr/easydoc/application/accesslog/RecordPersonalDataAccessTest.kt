package kr.easydoc.application.accesslog

import kr.easydoc.core.accesslog.PersonalDataAccessOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 접속기록 유스케이스 — Spring 도 DB 도 없이 대역으로 돈다. 여기서 잴 것은 outcome 분기와
 * `accessedAt` 계산뿐이다 — 삽입 SQL 자체는 `JdbcPersonalDataAccessLogWriterTest`
 * (infrastructure)가 잰다.
 */
class RecordPersonalDataAccessTest {
    @Test
    @DisplayName("성공 기록은 outcome=SUCCESS 로 쓴다")
    fun `성공 기록`() {
        val writer = RecordingWriter()
        val recorder = RecordPersonalDataAccess(writer, Clock.fixed(FIXED_NOW, ZoneOffset.UTC))
        val actorId = UUID.randomUUID()

        recorder.recordSuccess(actorId, "127.0.0.1", "listAdminWorkspaces", "page=1&size=20")

        val entry = writer.seen.single()
        assertThat(entry.actorUserId).isEqualTo(actorId)
        assertThat(entry.accessedAt).isEqualTo(FIXED_NOW)
        assertThat(entry.clientIp).isEqualTo("127.0.0.1")
        assertThat(entry.operation).isEqualTo("listAdminWorkspaces")
        assertThat(entry.subjectScope).isEqualTo("page=1&size=20")
        assertThat(entry.outcome).isEqualTo(PersonalDataAccessOutcome.SUCCESS)
    }

    @Test
    @DisplayName("거절 기록은 outcome=REJECTED 로 쓴다")
    fun `거절 기록`() {
        val writer = RecordingWriter()
        val recorder = RecordPersonalDataAccess(writer, Clock.fixed(FIXED_NOW, ZoneOffset.UTC))
        val actorId = UUID.randomUUID()

        recorder.recordRejection(actorId, "127.0.0.1", "listAdminWorkspaces", null)

        val entry = writer.seen.single()
        assertThat(entry.outcome).isEqualTo(PersonalDataAccessOutcome.REJECTED)
        assertThat(entry.subjectScope).isNull()
    }

    @Test
    @DisplayName("결과 문자열에 client_ip 원문이 없다")
    fun `client_ip 가 로그에 새지 않는다`() {
        val writer = RecordingWriter()
        val recorder = RecordPersonalDataAccess(writer, Clock.fixed(FIXED_NOW, ZoneOffset.UTC))

        recorder.recordSuccess(UUID.randomUUID(), "203.0.113.42", "readAdminUsage", null)

        assertThat(writer.seen.single().toString()).doesNotContain("203.0.113.42")
    }

    private class RecordingWriter : PersonalDataAccessLogWriter {
        val seen = mutableListOf<PersonalDataAccessLogEntry>()

        override fun insert(entry: PersonalDataAccessLogEntry) {
            seen += entry
        }
    }

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-09-11T00:00:00Z")
    }
}
