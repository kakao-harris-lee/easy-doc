package kr.easydoc.application.health

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `status` 유도 규칙 — Spring 도 DB 도 없이 돈다. */
class HealthDiagnosisTest {
    @Test
    @DisplayName("전부 도달 가능하면 `ok`")
    fun `전부 도달하면 ok 다`() {
        val report = HealthDiagnosis.diagnose(listOf(probe("database", true), probe("queue", true)))

        assertThat(report.status).isEqualTo(HealthDiagnosis.STATUS_OK)
        assertThat(report.checks).containsExactly(entry("database", true), entry("queue", true))
    }

    @Test
    @DisplayName("**하나라도** 도달 불가면 `degraded` — 나머지가 참이어도 그렇다")
    fun `하나라도 실패하면 degraded 다`() {
        val report = HealthDiagnosis.diagnose(listOf(probe("database", true), probe("queue", false)))

        assertThat(report.status).isEqualTo(HealthDiagnosis.STATUS_DEGRADED)

        assertThat(report.checks).containsExactly(entry("database", true), entry("queue", false))
    }

    @Test
    @DisplayName("배선된 것이 하나도 없으면 `{}` 이고 `ok` — 계약이 그 상태를 정상으로 정했다")
    fun `배선이 없으면 빈 검사와 ok 다`() {
        val report = HealthDiagnosis.diagnose(emptyList())

        assertThat(report.checks).isEmpty()
        assertThat(report.status).isEqualTo(HealthDiagnosis.STATUS_OK)
    }

    @Test
    @DisplayName("확인하지 못한 의존 서비스는 **키가 없다** — `false` 가 아니다")
    fun `배선되지 않은 것은 키 자체가 없다`() {
        val report = HealthDiagnosis.diagnose(listOf(probe("database", true)))

        assertThat(report.checks).containsOnlyKeys("database")
        assertThat(report.status).isEqualTo(HealthDiagnosis.STATUS_OK)
    }

    @Test
    @DisplayName("probe 가 던져도 응답이 성립한다 — 그 항목만 `false` 다")
    fun `던지는 probe 는 false 로 접힌다`() {
        val report = HealthDiagnosis.diagnose(listOf(probe("database", true), throwing("queue")))

        assertThat(report.checks).containsExactly(entry("database", true), entry("queue", false))
        assertThat(report.status).isEqualTo(HealthDiagnosis.STATUS_DEGRADED)
    }

    @Test
    @DisplayName("검사 키는 **정렬된 순서**다 — 배선 순서가 응답 바이트를 흔들지 않는다")
    fun `키 순서가 배선 순서에 좌우되지 않는다`() {
        val forward = HealthDiagnosis.diagnose(listOf(probe("database", true), probe("queue", true)))
        val reversed = HealthDiagnosis.diagnose(listOf(probe("queue", true), probe("database", true)))

        assertThat(reversed.checks.keys.toList()).isEqualTo(forward.checks.keys.toList())
    }

    @Test
    @DisplayName("같은 이름의 probe 가 둘이면 **끊는다** — 한쪽이 조용히 사라지지 않는다")
    fun `이름이 겹치면 끊는다`() {
        assertThatThrownBy {
            HealthDiagnosis.diagnose(listOf(probe("queue", true), probe("queue", false)))
        }.hasMessageContaining("겹친다")
    }

    private fun probe(
        name: String,
        reachable: Boolean,
    ): DependencyProbe =
        object : DependencyProbe {
            override val dependency: String = name

            override fun isReachable(): Boolean = reachable
        }

    /** 규약을 어기고 던지는 대역. 실제 드라이버 예외가 새는 경로를 흉내 낸다. */
    private fun throwing(name: String): DependencyProbe =
        object : DependencyProbe {
            override val dependency: String = name

            override fun isReachable(): Boolean = error("jdbc:postgresql://host/db 접속 실패")
        }

    private fun entry(
        key: String,
        value: Boolean,
    ) = org.assertj.core.api.Assertions
        .entry(key, value)
}
