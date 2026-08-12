package kr.easydoc.api

import kr.easydoc.api.error.ContractErrorController
import kr.easydoc.api.error.ContractErrorReportValve
import kr.easydoc.api.support.COMMITTED_BODY_PREFIX
import kr.easydoc.api.support.ContainerRejectedRequest
import kr.easydoc.api.support.RawHttp
import kr.easydoc.api.support.assertContractErrorBody
import kr.easydoc.infrastructure.PostgresTestSupport
import org.apache.catalina.valves.ErrorReportValve
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.tomcat.TomcatWebServer
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.charset.StandardCharsets

/**
 * **오류 본문이 어디까지 계약 형태인가** — 실측.
 *
 * ## 왜 이 측정이 따로 필요한가
 *
 * 계약의 *"오류 본문 최상위 `detail` 하나"* 는 불변식이다. 그런데 그 불변식을 지키는 코드는
 * `GlobalExceptionHandler` 하나뿐이었고, 그것은 **`DispatcherServlet` 안에서 던져진 예외**만
 * 잡는다. 바깥에서 만들어진 오류 응답은 손이 닿지 않았다.
 *
 * 이번 게이트에서 반복해 잡힌 것과 같은 모양이다 — **선언은 전역인데 실제 도달은 일부뿐.**
 * 헤더에서 밸브로 닫은 것과 같은 자리를 본문에서 닫는다.
 *
 * ## 세 층이 각각 다른 기제로 닫힌다
 *
 * | 층 | 무엇이 만드는가 | 닫는 기제 | 여기서 그것을 재는 테스트 |
 * |---|---|---|---|
 * | advice | `DispatcherServlet` 안의 예외 | `GlobalExceptionHandler` | [advice 층이 계약 본문을 낸다] |
 * | `/error` 디스패치 | `sendError`, 필터가 던진 예외 | [ContractErrorController] | [sendError 가 만든 응답이 계약 본문이다] 외 |
 * | 컨테이너 | 서블릿에 매핑되지 못한 요청 | [ContractErrorReportValve] | [컨테이너가 직접 만드는 응답도 계약 본문이다] |
 *
 * ## 음성 대조 — 기제를 빼면 무엇이 깨지는가 (실제로 돌려 확인)
 *
 * 두 기제를 **하나씩 실제로 제거하고** 이 테스트를 돌렸다 (2026-08-12, Tomcat 11.0.22 /
 * Spring Boot 4.1.0). 전체 15건 중 매번 **정확히 6건**이 깨졌고, 깨지는 6건이 서로 겹치지
 * 않는다 — 각 기제가 자기 층만 붙잡고 있다는 뜻이다.
 *
 * **[ContractErrorController] 의 `@RestController` 를 떼면** — `/error` 층 5건과
 * `ErrorController` 구조 단언이 깨진다. 실제 실패 메시지:
 * - `오류 본문의 최상위 키가 [timestamp, status, error, path] 다`
 * - `/error` 직접 요청은 `[timestamp, status, error]` 이고 `status` 값이 `999` 다
 * - `등록된 ErrorController 가 [basicErrorController] 다`
 *
 * **[ContractErrorReportValve] 등록 `@Bean` 을 떼면** — 컨테이너 층 5건과 리포터 구조
 * 단언이 깨진다. 실제 실패 메시지:
 * - `오류 본문 Content-Type 이 'text/html;charset=utf-8' 다`
 * - `Host 파이프라인의 오류 리포터가 [org.apache.catalina.valves.ErrorReportValve] 다`
 *
 * 두 본문 모두 **최상위 키가 `detail` 이 아니다.** 그래서 판정이
 * [kr.easydoc.api.support.assertContractErrorBody] 처럼 "detail 이 있는가"가 아니라
 * "detail 말고는 없는가"여야 한다 — 존재만 보는 단언은 `ProblemDetail`
 * (`{"type","title","status","detail","instance"}`)을 통과시킨다.
 *
 * 대조의 부수 소득 하나: [ContractErrorController] 를 빼도 컨테이너 층 5건은 **그대로
 * 통과했다.** 두 기제 중 하나만 붙이고 "오류 본문을 전역으로 고쳤다"고 보고했으면 절반이
 * 빈 채로 초록이었다는 뜻이다.
 *
 * ## MockMvc 로는 잴 수 없다
 *
 * 측정 수단은 헤더 때와 같다 — `@SpringBootTest(RANDOM_PORT)` + 원시 소켓
 * ([kr.easydoc.api.support.RawHttp], [PrivateResponseHeadersReachTest] 와 공유). MockMvc 는
 * 서블릿 컨테이너를 띄우지 않아 `sendError` 의 두 번째 디스패치도, 요청 줄 파싱 단계 거절도
 * 재현하지 못한다. 거기서 재면 *측정한 것처럼 보이는 통과*가 나온다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        // 413 을 만들기 위한 상한. 기본값(10MB)으로는 테스트가 10MB를 소켓에 밀어 넣어야 한다.
        "spring.servlet.multipart.max-request-size=1KB",
        "spring.servlet.multipart.max-file-size=1KB",
    ],
)
class ContractErrorBodyReachTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var applicationContext: WebServerApplicationContext

    // ------------------------------------------------------------------ advice 층 (종전에도 닫혀 있던 자리)

    @Test
    @DisplayName("advice 층 — 핸들러 없는 404 와 도메인 예외 404 가 계약 본문이다")
    fun `advice 층이 계약 본문을 낸다`() {
        assertContractErrorBody(RawHttp.exchange(port, RawHttp.get("/nope", port)), NOT_FOUND)
        assertContractErrorBody(
            RawHttp.exchange(port, RawHttp.get("/__probe/domain/not-found", port)),
            NOT_FOUND,
        )
    }

    // ------------------------------------------------------------------ /error 디스패치 층

    /**
     * `sendError` 는 컨테이너가 `/error` 로 **두 번째 디스패치**를 돌게 만든다. 그 디스패치를
     * 받는 것이 [ContractErrorController] 다.
     *
     * 지금은 운영 코드가 `sendError` 를 부르지 않지만 **Phase 3 에서 인증 필터가 401 을
     * `sendError` 로 내는 것이 가장 흔한 구현**이다. 그때 발견하면 이미 그 위에 인증 코드가
     * 쌓여 있다.
     */
    @Test
    @DisplayName("/error 층 — sendError 가 만든 응답이 계약 본문이다")
    fun `sendError 가 만든 응답이 계약 본문이다`() {
        assertContractErrorBody(
            RawHttp.exchange(port, RawHttp.get("/__probe/send-error", port)),
            SERVICE_UNAVAILABLE,
        )
    }

    /**
     * `sendError(int, String)` 의 두 번째 인자는 `jakarta.servlet.error.message` 로 넘어온다.
     * [ContractErrorController] 가 그것을 읽지 않는다는 것을 여기서 고정한다 —
     * `sendError(401, "비밀번호가 틀렸습니다: $입력값")` 한 줄이면 입력값이 응답 본문과 액세스
     * 로그에 남는다.
     *
     * 표식 부재 단언은 [kr.easydoc.api.support.assertContractErrorBody] 안에 있다.
     */
    @Test
    @DisplayName("/error 층 — sendError 의 메시지 인자가 본문에 실리지 않는다")
    fun `sendError 메시지가 본문에 실리지 않는다`() {
        assertContractErrorBody(
            RawHttp.exchange(port, RawHttp.get("/__probe/send-error-message", port)),
            SERVICE_UNAVAILABLE,
        )
    }

    /**
     * 필터가 던진 예외. `@RestControllerAdvice` 는 이것을 잡지 못한다 —
     * `DispatcherServlet` 밖이기 때문이다. **Phase 3 의 인증 필터가 서는 자리**이고,
     * 예외 메시지에 넣어 둔 표식이 본문에 나타나지 않는지도 함께 본다.
     */
    @Test
    @DisplayName("/error 층 — 필터가 던진 예외의 500 응답이 계약 본문이다")
    fun `필터가 던진 예외도 계약 본문이다`() {
        assertContractErrorBody(
            RawHttp.exchange(port, RawHttp.get("/__probe/filter-throws", port)),
            INTERNAL_SERVER_ERROR,
        )
    }

    /**
     * `/error` 를 직접 요청한 경우.
     *
     * 고치기 전에는 `{"timestamp":"…","status":999,"error":"None"}` 이 나갔다 — 999 는
     * `DefaultErrorAttributes` 가 상태 속성이 없을 때 쓰는 자리표시자다. 그것을 상태 코드로
     * 다시 쓰면 응답을 만들다가 또 예외가 난다.
     */
    @Test
    @DisplayName("/error 층 — /error 를 직접 요청해도 계약 본문이다")
    fun `error 를 직접 요청해도 계약 본문이다`() {
        assertContractErrorBody(RawHttp.exchange(port, RawHttp.get("/error", port)), INTERNAL_SERVER_ERROR)
    }

    /**
     * 기본 `BasicErrorController` 에는 `Accept: text/html` 전용 분기(`errorHtml`)가 있어
     * **본문 형태가 요청 헤더에 따라 갈린다.** 계약은 갈리지 않는다.
     */
    @Test
    @DisplayName("/error 층 — Accept: text/html 이어도 JSON 계약 본문이다")
    fun `Accept text html 이어도 계약 본문이다`() {
        val response =
            RawHttp.exchange(
                port,
                RawHttp.get("/__probe/send-error", port, listOf("Accept: text/html")),
            )

        assertContractErrorBody(response, SERVICE_UNAVAILABLE)
    }

    /**
     * `DispatcherServlet` 이 **핸들러를 찾기도 전에** 거절하는 응답들.
     *
     * 413 은 `checkMultipart` 단계, 415·405 는 핸들러 매핑 단계에서 갈린다. advice 가 잡기는
     * 하지만 컨트롤러 코드는 한 줄도 돌지 않는 자리라, 컨트롤러에 본문을 적는 방식으로는
     * 닿지 않는다는 점에서 `/error` 층·컨테이너 층과 성격이 같다.
     */
    @Test
    @DisplayName("advice 층 — 핸들러 이전에 거절되는 413·415·405 도 계약 본문이다")
    fun `핸들러 이전 거절도 계약 본문이다`() {
        assertContractErrorBody(
            RawHttp.exchange(port, RawHttp.oversizedMultipart("/__probe/body", port)),
            PAYLOAD_TOO_LARGE,
        )
        assertContractErrorBody(
            RawHttp.exchange(
                port,
                RawHttp.post("/__probe/body", port, "text/plain", "본문".toByteArray(StandardCharsets.UTF_8)),
            ),
            UNSUPPORTED_MEDIA_TYPE,
        )
        assertContractErrorBody(
            RawHttp.exchange(
                port,
                RawHttp.post("/__probe/get-only", port, "application/json", "{}".toByteArray()),
            ),
            METHOD_NOT_ALLOWED,
        )
    }

    // ------------------------------------------------------------------ 컨테이너 층

    /**
     * **이 테스트가 이번 조치의 핵심이다.**
     *
     * 요청 줄·헤더 블록이 깨진 요청은 `Http11InputBuffer` 파싱 단계에서 거절되고 서블릿에
     * 매핑되지 않는다. `/error` 디스패치도 없으므로 [ContractErrorController] 가 돌 기회가
     * 없고, 그 자리를 Tomcat `ErrorReportValve` 가 **HTML 로** 채운다.
     *
     * 헤더 조치로는 여기가 닫히지 않았다. `PrivateResponseHeadersValve` 는 Engine 파이프라인이라
     * 이 응답들에 닿지만, 그 밸브의 `next.invoke()` 가 돌아왔을 때는 Host 파이프라인의
     * `ErrorReportValve` 가 이미 본문을 쓴 뒤다. **본문은 헤더보다 한 층 더 깊은 곳에서
     * 결정된다** — 헤더가 닿는다고 본문도 닿는다고 읽으면 안 되는 이유다.
     *
     * 다섯 갈래를 함께 보는 이유는 거절 지점이 서로 달라서다 — 요청 대상 문자 검사, 요청 줄
     * 형식, 헤더 크기 상한, `Host` 부재, 프로토콜 버전. 마지막 것은 400 이 아니라 505 라
     * 상태 코드가 달라도 같은 자리를 지나는지 함께 확인한다.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(ContainerRejectedRequest::class)
    @DisplayName("컨테이너 층 — 서블릿에 매핑되지 못한 응답도 계약 본문이다 (밸브가 덮는 자리)")
    fun `컨테이너가 직접 만드는 응답도 계약 본문이다`(kind: ContainerRejectedRequest) {
        assertContractErrorBody(RawHttp.exchange(port, kind.build(port)), kind.expectedStatus)
    }

    // ------------------------------------------------------------------ 닿지 못하는 자리 (측정된 경계)

    /**
     * **여기는 어떤 기제로도 닿지 못한다** — 그 사실을 기록으로 못박는 테스트다.
     *
     * 응답이 커밋된 뒤(상태 줄과 헤더가 소켓으로 나간 뒤) 실패하면, 이미 보낸 바이트를
     * 되돌릴 수단이 서블릿 API 에도 Tomcat 밸브에도 없다. 실측(2026-08-12): 상태 200,
     * `Content-Type: application/json;charset=ISO-8859-1`, 본문은 흘려보낸 조각뿐.
     *
     * **계약 위반은 아니다.** 상태 줄이 200 이라 이 응답은 스스로를 오류라고 말한 적이 없고,
     * 계약의 *"오류 본문 최상위 `detail` 하나"* 는 오류 응답에 걸린 조항이다. 이 자리에서
     * 실제로 위험한 것은 본문 모양이 아니라 **잘린 응답이 성공으로 보인다**는 점이고, 그것은
     * 오류 본문 계약이 아니라 내려받기 스트리밍 설계에서 다뤄야 한다(Phase 4의
     * `GET /conversions/{id}/export`).
     *
     * 이 테스트를 두는 이유는 "전역"이라는 말의 경계를 눈에 보이게 두기 위해서다. 재지 않으면
     * 아무도 어디까지가 전역인지 모른 채 전역이라고 적게 된다.
     */
    @Test
    @DisplayName("경계 — 응답 커밋 뒤의 실패는 본문을 바꿀 수 없다 (측정된 한계)")
    fun `커밋 뒤 실패는 본문을 바꿀 수 없다`() {
        val response = RawHttp.exchange(port, RawHttp.get("/__probe/commit-then-throw", port))

        assertThat(response.statusCode)
            .withFailMessage(
                "커밋 뒤 실패의 상태 코드가 %d 다 — 200 이 아니라면 컨테이너가 커밋 전에 " +
                    "실패를 잡았다는 뜻이고, 이 경계 설명을 다시 재야 한다",
                response.statusCode,
            ).isEqualTo(OK)
        assertThat(response.bodyText)
            .withFailMessage("커밋 전에 흘려보낸 조각이 응답에 없다 — 이 측정의 전제가 깨졌다")
            .isEqualTo(COMMITTED_BODY_PREFIX)
    }

    // ------------------------------------------------------------------ 기제가 그 자리에 있는가

    /**
     * 계약 본문을 내는 `ErrorController` 가 **유일**해야 한다.
     *
     * `ErrorMvcAutoConfiguration.basicErrorController` 는
     * `@ConditionalOnMissingBean(ErrorController::class, search = CURRENT)` 조건이다. 즉
     * [ContractErrorController] 가 사라지면 기본 컨트롤러가 **조용히 되살아난다** — 그때의
     * 증상은 "오류 본문이 다르다" 하나뿐이라 컴파일도 기동도 막지 못한다.
     */
    @Test
    @DisplayName("기제 — ErrorController 는 ContractErrorController 하나뿐이다")
    fun `기본 ErrorController 가 되살아나지 않는다`() {
        val controllers = applicationContext.getBeansOfType(ErrorController::class.java)

        assertThat(controllers.values)
            .withFailMessage("등록된 ErrorController 가 %s 다 — 기본 컨트롤러가 되살아났다", controllers.keys)
            .hasSize(1)
            .allMatch { it is ContractErrorController }
    }

    /**
     * Host 파이프라인의 오류 리포터가 **우리 것 하나**여야 한다.
     *
     * 기본 [ErrorReportValve] 를 넣는 주체가 둘이다(Spring Boot 의 컨텍스트 커스터마이저,
     * Tomcat `StandardHost.startInternal()`). 우리 것보다 **안쪽**에 하나라도 남으면 그쪽이
     * HTML 을 먼저 쓰고 우리 밸브는 `getContentWritten() > 0` 으로 조용히 물러난다.
     * 동작 단언(컨테이너 층 5건)이 그 상태를 잡아내지만, 여기서 한 번 더 구조로 못박아
     * **무엇이 깨졌는지**가 실패 메시지에 바로 드러나게 한다.
     */
    @Test
    @DisplayName("기제 — Host 파이프라인의 오류 리포터가 ContractErrorReportValve 하나뿐이다")
    fun `기본 오류 리포터가 남아 있지 않다`() {
        val webServer = applicationContext.webServer
        assertThat(webServer)
            .withFailMessage("내장 컨테이너가 Tomcat 이 아니다 — 이 밸브 배선의 전제가 깨졌다")
            .isInstanceOf(TomcatWebServer::class.java)

        val reporters =
            (webServer as TomcatWebServer)
                .tomcat.host.pipeline.valves
                .filterIsInstance<ErrorReportValve>()

        assertThat(reporters)
            .withFailMessage("Host 파이프라인의 오류 리포터가 %s 다", reporters.map { it::class.java.name })
            .hasSize(1)
            .allMatch { it is ContractErrorReportValve }
    }

    companion object {
        private const val OK = 200
        private const val NOT_FOUND = 404
        private const val METHOD_NOT_ALLOWED = 405
        private const val PAYLOAD_TOO_LARGE = 413
        private const val UNSUPPORTED_MEDIA_TYPE = 415
        private const val INTERNAL_SERVER_ERROR = 500
        private const val SERVICE_UNAVAILABLE = 503

        /**
         * 이 측정에는 DB 가 필요 없지만 앱 기동에는 필요하다 — Flyway 가 컨텍스트 초기화
         * 중에 돌기 때문이다.
         */
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            val database = PostgresTestSupport.createEmptyDatabase("contract_error_body_reach")
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}
