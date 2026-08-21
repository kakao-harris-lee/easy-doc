package kr.easydoc.api.health

import kr.easydoc.application.health.DependencyProbe
import kr.easydoc.application.health.HealthDiagnosis
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /health` — 서비스 생존 확인과 **의존 서비스 진단**.
 *
 * ## 진단한다 (2026-08-21, 게이트 28 P-8)
 *
 * 계약 `HealthResponse` 가 `required: [status, checks]` 이고, 경로 산문이
 * *"**Kotlin 은 진단하는 쪽으로 구현한다**"* 로 못박았다. 그 전에 이 클래스는 `status`
 * 하나만 든 상수 응답을 냈다 — **필드 부재**이므로 계약 위반이었다.
 *
 * 종전 KDoc 은 계약을 인용하며 *"v1 은 현행대로 동결"* 이라고 적고 있었는데 **그 문면은
 * 현재 계약에 없다.** 2026-08-12 개정(`x-change-policy` G2)이 정반대로 바꿨고, 인용만 옛
 * 계약에 남아 「지키고 있다」는 잘못된 근거를 만들었다. 그 종류(주석이 저장소에 없는 문면을
 * 근거로 든다)는 이제 [kr.easydoc.api.NamedReferenceGuardTest] 가 잰다 — 계약 확장 노드와
 * 테스트·클래스 이름 축이고, **산문 인용 축은 그 탐지기가 덮지 않는다**(그 파일의 「범위를
 * 좁힌 사유」). 그래서 이 KDoc 은 인용을 걷어내고 **계약의 자리만 가리킨다**: 조항이
 * 바뀌면 옛 문면이 여기 남는 대신 `HealthContractTest` 가 빨개진다.
 *
 * ## 상태를 따로 계산하지 않는다
 *
 * `status` 는 [HealthDiagnosis] 가 `checks` 에서 유도한다. 컨트롤러가 그 규칙을 알면
 * 라우터에 비즈니스 판단이 생기고(`CLAUDE.md` 아키텍처 규칙 3), 두 값이 어긋난 응답을
 * 만들 통로가 열린다 — 계약이 *"둘이 어긋난 응답은 계약 위반"* 이라고 적은 그 상태다.
 *
 * ## 항상 200 이다
 *
 * `degraded` 여도 503 을 내지 않는다. 계약이 사유를 적었다 — 이 엔드포인트 하나가 liveness
 * 와 readiness 를 겸하므로, 의존 서비스가 잠깐 죽었다고 503 을 내면 오케스트레이터가 멀쩡히
 * 읽기를 처리하던 프로세스를 재시작시켜 장애를 키운다. 상태 코드를 바꾸는 변경은
 * `x-change-policy.escalate_to_leader` 4번이다.
 *
 * ## [ObjectProvider] 로 받는 이유
 *
 * 계약이 *"의존 서비스가 하나도 배선되지 않았으면 `{}` 이고 `status` 는 `ok` 다"* 로 그
 * 상태를 **정상 응답**으로 정했다. `List<DependencyProbe>` 를 생성자로 받으면 후보 빈이
 * 0개일 때 Spring 이 주입에 실패해 컨텍스트가 뜨지 않고, 그러면 그 조항을 잴 자리가
 * 사라진다(`@WebMvcTest` 슬라이스에는 DataSource 가 없다).
 *
 * ## Actuator 를 쓰지 않는 이유
 *
 * `spring-boot-starter-actuator` 는 `/actuator` 하위 경로를 함께 노출한다. 계약이 서비스되는
 * (경로, 메서드) 집합을 정하고 그 목록에 actuator 경로가 없다. 또 actuator 의
 * `/actuator/health` 응답 모양(`{"status":"UP"}`)은 우리 계약(`status` 는 `ok`·`degraded`)과
 * 다르다. 진단 **방식**은 그쪽 지표와 같다(검증 질의 한 방) — 가져오지 않은 것은 노출면이다.
 *
 * ## 캐시 금지 헤더를 개별로 붙이지 않는다
 *
 * 계약이 남긴 하한선 10곳에 `/health` 가 없다. 그래도 헤더는 나간다 — 전역 부착
 * (`x-global-response-headers`)이 요구의 정본이고 그 절이 `GET /health` 를 명시적으로
 * 포함한다. `HealthContractTest` 가 그 사실을 값으로 단언한다.
 */
@RestController
class HealthController(probes: ObjectProvider<DependencyProbe>) {
    /**
     * 배선된 probe 들. **기동 시점에 한 번 모은다.**
     *
     * 요청마다 다시 모으지 않는 이유: 빈 목록은 컨텍스트가 뜬 뒤 바뀌지 않고, 요청 경로에서
     * 빈 팩토리를 훑으면 인증 없는 엔드포인트에 붙는 일이 늘어난다.
     */
    private val probes: List<DependencyProbe> = probes.orderedStream().toList()

    @GetMapping("/health")
    fun health(): HealthResponse {
        val report = HealthDiagnosis.diagnose(probes)
        return HealthResponse(status = report.status, checks = report.checks)
    }
}

/**
 * 헬스 체크 응답. 필드는 계약 `HealthResponse.required` 의 둘이다.
 *
 * 버전·기동 시각·지연 시간을 덧붙이지 않는다. 계약에 없는 필드는 계약 위반이고, 기동 시각은
 * 배포 시점을 인증 없이 외부에 알려 주는 정보이기도 하다.
 *
 * `checks` 의 값 타입이 `Boolean` 인 것이 계약 `additionalProperties: { type: boolean }` 이다 —
 * 예외 메시지·호스트·버전이 들어갈 통로를 타입이 막는다.
 */
data class HealthResponse(
    val status: String,
    val checks: Map<String, Boolean>,
)
