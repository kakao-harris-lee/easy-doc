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

/** 오류 본문이 어디까지 계약 형태인가 — 실측. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [

        "spring.servlet.multipart.max-request-size=1KB",
        "spring.servlet.multipart.max-file-size=1KB",
    ],
)
class ContractErrorBodyReachTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var applicationContext: WebServerApplicationContext

    @Test
    @DisplayName("advice 층 — 핸들러 없는 404 와 도메인 예외 404 가 계약 본문이다")
    fun `advice 층이 계약 본문을 낸다`() {
        assertContractErrorBody(RawHttp.exchange(port, RawHttp.get("/nope", port)), NOT_FOUND)
        assertContractErrorBody(
            RawHttp.exchange(port, RawHttp.get("/__probe/domain/not-found", port)),
            NOT_FOUND,
        )
    }

    /**
     * `sendError` 는 컨테이너가 `/error` 로 두 번째 디스패치를 돌게 만든다. 그 디스패치를
     * 받는 것이 [ContractErrorController] 다.
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
     * `DispatcherServlet` 밖이기 때문이다. Phase 3 의 인증 필터가 서는 자리이고,
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

    /** `/error` 를 직접 요청한 경우. */
    @Test
    @DisplayName("/error 층 — /error 를 직접 요청해도 계약 본문이다")
    fun `error 를 직접 요청해도 계약 본문이다`() {
        assertContractErrorBody(RawHttp.exchange(port, RawHttp.get("/error", port)), INTERNAL_SERVER_ERROR)
    }

    /**
     * 기본 `BasicErrorController` 에는 `Accept: text/html` 전용 분기(`errorHtml`)가 있어
     * 본문 형태가 요청 헤더에 따라 갈린다. 계약은 갈리지 않는다.
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

    /** `DispatcherServlet` 이 핸들러를 찾기도 전에 거절하는 응답들. */
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

    /** 이 테스트가 이번 조치의 핵심이다. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(ContainerRejectedRequest::class)
    @DisplayName("컨테이너 층 — 서블릿에 매핑되지 못한 응답도 계약 본문이다 (밸브가 덮는 자리)")
    fun `컨테이너가 직접 만드는 응답도 계약 본문이다`(kind: ContainerRejectedRequest) {
        assertContractErrorBody(RawHttp.exchange(port, kind.build(port)), kind.expectedStatus)
    }

    /** 여기는 어떤 기제로도 닿지 못한다 — 그 사실을 기록으로 못박는 테스트다. */
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

    /** 계약 본문을 내는 `ErrorController` 가 유일해야 한다. */
    @Test
    @DisplayName("기제 — ErrorController 는 ContractErrorController 하나뿐이다")
    fun `기본 ErrorController 가 되살아나지 않는다`() {
        val controllers = applicationContext.getBeansOfType(ErrorController::class.java)

        assertThat(controllers.values)
            .withFailMessage("등록된 ErrorController 가 %s 다 — 기본 컨트롤러가 되살아났다", controllers.keys)
            .hasSize(1)
            .allMatch { it is ContractErrorController }
    }

    /** Host 파이프라인의 오류 리포터가 우리 것 하나여야 한다. */
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
