package kr.easydoc.application.health

/** 의존 서비스 하나가 지금 쓸 수 있는지 묻는 포트. */
interface DependencyProbe {
    /** 계약 `HealthResponse.checks` 의 키. */
    val dependency: String

    /** 지금 쓸 수 있으면 `true`. **던지지 않는다.** */
    fun isReachable(): Boolean
}

/** `/health` 응답의 두 필드. 계약 `HealthResponse.required` 그대로다. */
data class HealthReport(
    val status: String,
    val checks: Map<String, Boolean>,
)

/** `checks` 를 모아 `status` 를 **유도한다** — 별개로 계산하지 않는다. */
object HealthDiagnosis {
    /** 계약 `HealthResponse.properties.status.enum` 의 두 값. */
    const val STATUS_OK: String = "ok"
    const val STATUS_DEGRADED: String = "degraded"

    fun diagnose(probes: List<DependencyProbe>): HealthReport {
        val names = probes.map { it.dependency }
        require(names.size == names.distinct().size) {
            "의존 서비스 이름이 겹친다: ${names.sorted()} — 한쪽이 조용히 사라지면 진단이 거짓말이 된다"
        }
        val checks =
            probes
                .associate { probe -> probe.dependency to reachable(probe) }
                .toSortedMap()
        return HealthReport(
            status = if (checks.values.all { it }) STATUS_OK else STATUS_DEGRADED,
            checks = checks,
        )
    }

    /** 구현이 규약을 어기고 던져도 응답이 성립하게 한다. */
    private fun reachable(probe: DependencyProbe): Boolean = runCatching { probe.isReachable() }.getOrDefault(false)
}
