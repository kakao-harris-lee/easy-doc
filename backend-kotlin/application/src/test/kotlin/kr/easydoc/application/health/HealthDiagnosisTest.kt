package kr.easydoc.application.health

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `status` 유도 규칙 — **Spring 도 DB 도 없이** 돈다.
 *
 * 계약 `HealthResponse.description` 이 정한 것: *"`status` 는 `checks` 에서 유도한다 —
 * 별개로 계산하지 않는다. `checks` 의 값이 모두 true 면 `ok`, 하나라도 false 면 `degraded`
 * 다. 둘이 어긋난 응답은 계약 위반이다(단언하기 쉬운 조항이므로 contract test 로 고정한다)."*
 *
 * 여기서 재는 것은 **규칙**이고, 그 규칙이 실제 응답 바이트로 나가는지는
 * `kr.easydoc.api.HealthContractTest` 가 잰다. 두 축이 필요한 이유는 규칙이 옳아도 배선이
 * 다른 값을 실을 수 있기 때문이다.
 */
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
        // 유도가 실제로 `checks` 를 보는지 확인한다 — 상수 `degraded` 를 내는 구현도 위 단언을
        // 통과하므로, 두 값이 **서로 맞는지**까지 본다.
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

        // 「죽었다」와 「확인 안 했다」는 다른 말이다(계약). `queue: false` 를 채워 넣으면
        // 진단이 거짓말이 되고, 배포 담당자가 없는 장애를 쫓는다.
        assertThat(report.checks).containsOnlyKeys("database")
        assertThat(report.status).isEqualTo(HealthDiagnosis.STATUS_OK)
    }

    @Test
    @DisplayName("probe 가 던져도 응답이 성립한다 — 그 항목만 `false` 다")
    fun `던지는 probe 는 false 로 접힌다`() {
        val report = HealthDiagnosis.diagnose(listOf(probe("database", true), throwing("queue")))

        // 예외가 올라가면 `/health` 자신이 5xx 가 되고 계약의 「항상 200」이 깨진다.
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
