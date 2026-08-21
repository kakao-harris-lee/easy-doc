package kr.easydoc.api.health

import kr.easydoc.application.health.DependencyProbe
import kr.easydoc.application.health.HealthDiagnosis
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

// 계약 검증: `HealthContractTest`.

/** `GET /health` — 서비스 생존 확인과 **의존 서비스 진단**. */
@RestController
class HealthController(probes: ObjectProvider<DependencyProbe>) {
    /** 배선된 probe 들. **기동 시점에 한 번 모은다.** */
    private val probes: List<DependencyProbe> = probes.orderedStream().toList()

    @GetMapping("/health")
    fun health(): HealthResponse {
        val report = HealthDiagnosis.diagnose(probes)
        return HealthResponse(status = report.status, checks = report.checks)
    }
}

/** 헬스 체크 응답. 필드는 계약 `HealthResponse.required` 의 둘이다. */
data class HealthResponse(
    val status: String,
    val checks: Map<String, Boolean>,
)
