package kr.easydoc.api.health

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /health` — 서비스 생존 확인.
 *
 * ## 의존 서비스를 진단하지 않는다
 *
 * 계약 정본(`contracts/easy-doc-v1.yaml`)이 현행 Python 동작을 그대로 동결했다.
 *
 * > **현재 구현은 의존 서비스를 진단하지 않고 상수 `{"status": "ok"}`만 돌려준다.**
 * > 계획 §2.2 주변 서술의 "의존 서비스 상태 진단"과 어긋나며, 코드가 이겼다.
 * > DB·Redis·설정이 모두 죽어도 200 ok가 나온다. v1은 현행대로 동결하고, 진단 확장은
 * > v2 후보다.
 *
 * 그러므로 여기에 DB 핑이나 설정 검사를 **넣지 않는다.** 넣으면 계약이 바뀌고,
 * 전환의 목표("관측 가능한 동작을 그대로 둔 채 런타임만 바꾼다")에서 벗어난다.
 * 진단이 필요하다는 판단은 v2 논의로 넘긴다.
 *
 * ## Actuator 를 쓰지 않는 이유
 *
 * `spring-boot-starter-actuator` 는 `/actuator` 하위 경로를 함께 노출한다. 계약은 14개
 * 엔드포인트로 동결돼 있고 그 목록에 actuator 경로가 없다. 또 actuator 의
 * `/actuator/health` 응답 모양(`{"status":"UP"}`)은 우리 계약(`{"status":"ok"}`)과 다르다.
 *
 * ## 캐시 금지 헤더를 붙이지 않는다
 *
 * 계약: "인증이 필요 없고 캐시 금지 헤더도 붙지 않는다(실측 확인 2026-08-12)."
 * 이 응답에는 개인정보도 자격증명도 없다. `PRIVATE_RESPONSE_HEADERS` 대상 10곳에
 * `/health` 는 들어 있지 않다.
 */
@RestController
class HealthController {
    @GetMapping("/health")
    fun health(): HealthResponse = HealthResponse(status = "ok")
}

/**
 * 헬스 체크 응답.
 *
 * 필드는 `status` 하나뿐이다 — Python `app/main.py` 의 `HealthResponse` 와 같다.
 * 버전·기동 시각 같은 값을 덧붙이지 않는다. 계약에 없는 필드는 계약 위반이고,
 * 기동 시각은 배포 시점을 외부에 알려 주는 정보이기도 하다.
 */
data class HealthResponse(val status: String)
