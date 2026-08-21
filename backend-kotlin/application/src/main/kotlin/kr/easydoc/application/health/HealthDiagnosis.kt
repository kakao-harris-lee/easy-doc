package kr.easydoc.application.health

/**
 * 의존 서비스 하나가 지금 쓸 수 있는지 묻는 포트.
 *
 * ## 왜 `application` 에 있나
 *
 * 계약이 `/health` 응답의 **모양과 유도 규칙**을 정했고(`HealthResponse` — `status` 는
 * `checks` 에서 유도한다), 그 규칙은 HTTP 표현도 JDBC 도 아니다. 컨트롤러에 두면 규칙이
 * 라우터의 판단이 되고(`CLAUDE.md` 아키텍처 규칙 3), `infrastructure` 에 두면 진단 대상이
 * 하나 늘 때마다 규칙이 그 모듈 안에서 재정의될 자리가 생긴다.
 *
 * ## 무엇을 돌려주는가 — **불리언 하나**다
 *
 * 예외 메시지·호스트·드라이버 버전·지연 시간을 담지 않는다. 계약이 그것을 금지했다 —
 * *"인증 없이 누구나 부를 수 있으므로 호스트명·포트·드라이버 버전·예외 메시지는 넣지
 * 않는다. 의존 서비스 **이름과 up/down**까지다."* 타입이 `Boolean` 이면 그보다 많은 것을
 * 실어 보낼 통로가 없다.
 *
 * ## 던지지 않는다
 *
 * 구현은 실패를 `false` 로 접는다. 예외가 여기서 올라오면 `/health` 자신이 5xx 가 되고,
 * 그러면 계약이 못박은 *"항상 200 이다. degraded 여도 503 을 내지 않는다"* 가 깨진다 —
 * 그 조항의 이유는 오케스트레이터가 멀쩡히 읽기를 처리하던 프로세스를 재시작시키지 않게
 * 하는 것이다. [HealthDiagnosis] 는 그래도 한 번 더 접는다(구현이 규약을 어겨도 응답이
 * 성립해야 한다).
 */
interface DependencyProbe {
    /**
     * 계약 `HealthResponse.checks` 의 키.
     *
     * 계약이 현재 정의된 키를 `database`·`queue` 둘로 적었다. 이름을 여기 상수로 두지 않는
     * 이유는 그것이 **진단 대상의 이름**이지 이 포트의 성질이 아니기 때문이다 — 구현이
     * 자기 이름을 말하고, 그 이름이 계약과 같은지는 계약 테스트가 잰다.
     */
    val dependency: String

    /** 지금 쓸 수 있으면 `true`. **던지지 않는다.** */
    fun isReachable(): Boolean
}

/**
 * `/health` 응답의 두 필드. 계약 `HealthResponse.required` 그대로다.
 *
 * `checks` 를 **정렬된 맵**으로 들고 있다 — 키 순서가 배선 순서(빈 발견 순서)에 좌우되면
 * 같은 배포가 요청마다 다른 바이트를 낼 수 있고, 그 차이는 진단이 아니라 잡음이다.
 *
 * `data class` 로 두어도 `toString()` 이 샐 것이 없다 — 담긴 것이 고정 문자열과 불리언뿐이다.
 */
data class HealthReport(
    val status: String,
    val checks: Map<String, Boolean>,
)

/**
 * `checks` 를 모아 `status` 를 **유도한다** — 별개로 계산하지 않는다.
 *
 * ## 유도 규칙이 계약 조항이다
 *
 * *"`checks` 의 값이 모두 true 면 `ok`, 하나라도 false 면 `degraded` 다. 둘이 어긋난 응답은
 * 계약 위반이다"*(`HealthResponse.description`). 그래서 `status` 를 인자로 받는 통로를 두지
 * 않는다 — 받을 수 있으면 두 값이 어긋난 응답을 만들 수 있고, 계약이 금지한 것이 정확히
 * 그 상태다.
 *
 * ## 확인하지 못한 의존 서비스는 **키를 생략한다**
 *
 * 계약: *"아직 배선되지 않아 확인할 수 없는 의존 서비스는 키 자체를 생략한다(`false` 가
 * 아니다 — "죽었다"와 "확인 안 했다"는 다른 말이다)."* 그 생략은 이 함수가 하는 일이
 * 아니라 **[probes] 에 그 항목이 없다**는 사실로 표현된다. 배선되지 않은 것은 빈이 없고,
 * 빈이 없으면 여기 오지 않는다.
 *
 * 그 귀결로 **아무것도 배선되지 않으면 `{}` 이고 `ok`** 다. 계약이 그렇게 적었고,
 * `[emptyMap]` 에 대해 `all {}` 이 참이라는 Kotlin 의 성질이 그 조항과 그대로 맞는다 —
 * 특례 분기를 두지 않는 이유다.
 *
 * ## 이름이 겹치면 **끊는다**
 *
 * 두 probe 가 같은 [DependencyProbe.dependency] 를 말하면 한쪽이 조용히 사라져 「전부
 * true」가 거짓이 될 수 있다. 조용한 소실보다 시끄러운 실패가 낫다 — 이 함수가 던지면
 * 기동이 아니라 그 요청이 500 이 되지만, 그 상태는 배선 버그이지 의존 서비스 장애가 아니다.
 */
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

    /**
     * 구현이 규약을 어기고 던져도 응답이 성립하게 한다.
     *
     * 넓은 타입을 잡는 것이 의도다 — 여기서 가려야 할 것은 「불리언을 못 받았다」 하나이고,
     * 그 원인 타입은 드라이버·풀·설정마다 다르다. 잡은 것을 **로그에도 남기지 않는다**:
     * 이 층은 로거를 갖지 않고(포트 선언 모듈이다), 예외 메시지에 접속 문자열과 자격증명이
     * 실리는 것이 드라이버의 흔한 동작이다.
     */
    private fun reachable(probe: DependencyProbe): Boolean = runCatching { probe.isReachable() }.getOrDefault(false)
}
