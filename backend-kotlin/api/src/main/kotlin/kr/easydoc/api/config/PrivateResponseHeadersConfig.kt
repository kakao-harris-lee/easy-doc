package kr.easydoc.api.config

import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.valves.ValveBase
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.web.filter.OncePerRequestFilter
import java.util.EnumSet

/**
 * 사적 응답 헤더의 **전역** 부착.
 *
 * 계약 정본은 `contracts/easy-doc-v1.yaml` 의 `x-global-response-headers` 다 —
 * *"Kotlin 런타임이 내보내는 모든 HTTP 응답. 상태 코드·경로·핸들러 유무·본문 유무를
 * 가리지 않는다."*
 *
 * ## 왜 열거가 아니라 필터인가 (근거 G4)
 *
 * 종전 계약은 성공 응답 **10곳**만 열거했다. 그 열거식 범위가 이 저장소에서 **이미 두 번**
 * 누락됐다(`PUT /conversions/{id}` 하나, `/auth` 셋). 사람이 매번 기억해야 지켜지는
 * 조항은 지켜지지 않는 형태이고, 그것이 G4의 정의다. 2026-08-12 리더 판정(OQ-1 종결)이
 * 전역 부착을 승인했다 — 판정문은 `docs/migration/_workspace/02_contract-rebase.md` §3.2.
 *
 * 전역 부착의 비용이 사실상 0인 쪽이 결정을 갈랐다. 이 API 가 내보내는 것은 JSON 과 첨부
 * 파일뿐이라 정적 자원·HTML 문서가 없고, `no-store` 가 망가뜨릴 캐시 동작 자체가 없다.
 * CORS 프리플라이트 캐시는 `Access-Control-Max-Age` 가 관장하므로 영향받지 않는다.
 *
 * ## 실패 양상이 바뀐다 — 그래서 두 층으로 붙인다
 *
 * 위험이 "누가 한 자리를 빠뜨렸는가"에서 **"필터가 그 응답까지 닿는가"**로 옮겨간다.
 * 전자는 목록 대조로 잡히지만 후자는 **실행해 봐야만** 잡힌다.
 *
 * H-1 실측(2026-08-12, Tomcat 11.0.22 / Spring Boot 4.1.0)이 실제로 구멍을 하나 찾아냈다.
 * **서블릿 필터만으로는 부족하다** — 요청 줄 파싱 단계에서 거절되는 요청
 * (`Http11InputBuffer.parseRequestLine` → 400)은 서블릿에 디스패치되지 않아 필터가 아예
 * 돌지 않는다. 그래서 층을 하나 더 둔다.
 *
 * | 층 | 무엇이 붙는가 | 무엇을 덮는가 |
 * |---|---|---|
 * | [PrivateResponseHeadersValve] | Tomcat Engine 파이프라인 | **모든** 응답. 요청 줄이 깨져 서블릿까지 못 가는 400·405·505 포함 |
 * | [PrivateResponseHeadersFilter] | 서블릿 필터 체인 | 컨테이너가 서블릿으로 디스패치한 모든 응답 |
 *
 * 두 층이 같은 헤더를 쓰지만 **`set` 이라 값이 겹치지 않는다.** 겹치는지 여부는 인상이
 * 아니라 단언으로 확인한다 — 두 테스트가 값이 아니라 **개수**를 본다.
 *
 * 밸브 하나로 줄이지 않는 이유는 검증 가능성이다. `@WebMvcTest` 슬라이스에는 Tomcat 이
 * 없어 밸브가 존재하지 않는다. 필터가 없으면 계약 테스트 대부분이 헤더를 확인할 수 없게
 * 되고, 그러면 이 조항의 검증이 무거운 `@SpringBootTest` 하나에만 걸린다.
 *
 * ## 열거 10곳의 개별 테스트는 남는다
 *
 * 2026-08-12 리더 판정 부수 결정 1. 전역 규칙이 있어도 하한선은 지우지 않는다 — 필터가
 * 제거되거나 체인 순서가 어긋나면 고위험 경로(토큰·이메일·`masked_items[].original`)에서
 * **먼저** 깨져야 한다.
 */
@Configuration(proxyBeanMethods = false)
class PrivateResponseHeadersConfig {
    /**
     * 헤더 필터를 서블릿 체인 **맨 앞**에 둔다. CORS 필터보다도 앞이다.
     *
     * 앞에 두는 이유는 CORS 프리플라이트 때문이다 — `CorsFilter` 는 preflight 를 스스로
     * 끝내고 체인의 나머지를 부르지 않으므로, 그 뒤에 있으면 OPTIONS 응답에는 헤더가
     * 영영 붙지 않는다(계약 `x-openapi-expressibility` ④가 지목한 자리).
     *
     * `DispatcherType.ERROR` 를 함께 등록하는 이유는 [DISPATCHER_TYPES] 에 적었다.
     */
    @Bean
    fun privateResponseHeadersFilterRegistration(): FilterRegistrationBean<PrivateResponseHeadersFilter> =
        FilterRegistrationBean(PrivateResponseHeadersFilter()).apply {
            order = PRIVATE_HEADER_FILTER_ORDER
            setDispatcherTypes(DISPATCHER_TYPES)
        }

    /**
     * 서블릿 필터가 닿지 못하는 자리를 덮는 Tomcat Engine 밸브.
     *
     * Engine 파이프라인은 `CoyoteAdapter` 바로 안쪽이라 **요청이 서블릿에 매핑되지 않아도**
     * 돈다. H-1 실측에서 요청 대상에 금지 문자가 있는 400, 콜론 없는 헤더 줄 400, 요청 줄
     * 자체가 쓰레기인 400, 헤더 상한 초과 400, `Host` 없는 400, 알 수 없는 HTTP 버전 505,
     * 알 수 없는 메서드 405가 전부 이 밸브에 닿는 것을 확인했다.
     *
     * **Tomcat 에 묶이는 지점이다.** 계획 §3.1이 Spring MVC + 내장 Tomcat 을 고정했으므로
     * 감수하는 결합이고, 컨테이너를 바꾸면 이 빈이 기동 시점에 깨진다(조용히 사라지지
     * 않는다). 계약 `x-global-response-headers.enforcement` 는 "서블릿 필터 하나"라고
     * 적고 있는데, 실측이 그것만으로 부족함을 보였으므로 계약 소유자에게 문구 갱신을
     * 요청해 둔다 — 요구를 좁히는 변경이 아니라 강제 수단을 넓히는 변경이다.
     */
    @Bean
    fun privateResponseHeadersValveCustomizer(): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory -> factory.addEngineValves(PrivateResponseHeadersValve()) }

    internal companion object {
        /**
         * 체인 맨 앞. `CorsConfig` 는 이보다 뒤([CORS_FILTER_ORDER])에 둔다.
         *
         * 두 값을 한곳에 모아 두는 이유는 순서가 두 파일에 흩어지면 한쪽만 고쳐도 빌드가
         * 통과하기 때문이다. 그 상태의 증상은 "preflight 응답에만 헤더가 없다" 하나뿐이라
         * 눈으로 잡히지 않는다.
         */
        const val PRIVATE_HEADER_FILTER_ORDER: Int = Ordered.HIGHEST_PRECEDENCE

        /** CORS 필터 순서. 헤더 필터 바로 뒤이고, Phase 3 에서 붙을 인증 필터보다는 앞이다. */
        const val CORS_FILTER_ORDER: Int = Ordered.HIGHEST_PRECEDENCE + 10

        /**
         * ERROR·ASYNC 디스패치까지 건다.
         *
         * `FilterRegistrationBean` 기본값은 `REQUEST` 하나다. `sendError()` 가 불리면
         * 컨테이너가 `/error` 로 **ERROR 디스패치**를 돌리는데, 그 디스패치는 별도의 필터
         * 체인을 탄다. H-1 의 후보 원인 ⓑ 가 지목한 자리다.
         *
         * **실측 결과는 이 등록이 지금은 필요 없다는 것이다.** 대조 실험(2026-08-12):
         * 이 줄과 [PrivateResponseHeadersFilter.shouldNotFilterErrorDispatch] 를 **둘 다
         * 기본값으로 되돌려도** `sendError` 응답에 두 헤더가 그대로 남았다. 필터가
         * `filterChain.doFilter` **앞에서** 헤더를 쓰고, Tomcat 이 `/error` 포워딩에서
         * 버퍼만 비우고 헤더는 보존하기 때문이다.
         *
         * 그래도 남겨 두는 이유는 그 통과가 **컨테이너가 헤더를 보존한다**는 전제에 기대고
         * 있어서다. 응답을 감싸거나 `reset()` 하는 코드가 뒤에 붙으면 전제가 조용히
         * 깨진다. `set` 이라 두 번 돌아도 값이 겹치지 않으므로 비용이 없다.
         * **필요해서가 아니라 전제를 줄이려고 있는 줄이다** — 필요하다고 읽지 않는다.
         */
        val DISPATCHER_TYPES: EnumSet<DispatcherType> =
            EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC)
    }
}

/**
 * 모든 응답에 `Cache-Control: no-store` 와 `X-Content-Type-Options: nosniff` 를 싣는다.
 *
 * ## `add` 가 아니라 `set` 이다
 *
 * 계약 `x-global-response-headers.enforcement` 가 못박은 항목이다. 열거 10곳의 컨트롤러가
 * 같은 헤더를 `ResponseEntity` 에 실을 때 이 필터가 `add` 를 쓰면
 * `Cache-Control: no-store, no-store` 가 나간다. 계약은 값을 `const: "no-store"` 로
 * 못박았으므로 그것은 위반인데, **값만 보는 단언은 통과한다** — 기능은 멀쩡하고 계약만
 * 깨지는 종류라 눈으로 잡히지 않는다. 그래서 `setHeader` 를 쓰고, 테스트는 값이 아니라
 * **개수**까지 본다.
 *
 * ## 체인에 들어가기 전에 쓴다
 *
 * `filterChain.doFilter` **앞에서** 헤더를 쓴다. 뒤에서 쓰면 응답이 이미 커밋된 뒤라
 * 조용히 무시된다 — 내려받기(`GET /conversions/{id}/export`)처럼 본문이 큰 응답에서
 * 정확히 그렇게 된다.
 */
class PrivateResponseHeadersFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
        response.setHeader(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
        filterChain.doFilter(request, response)
    }

    /**
     * 기본값은 `true` — ERROR 디스패치에서 필터를 **건너뛴다**.
     *
     * 그대로 두면 [PrivateResponseHeadersConfig.DISPATCHER_TYPES] 에 `ERROR` 를 등록해도
     * 필터가 돌지 않는다(`OncePerRequestFilter.doFilter` 가 `skipDispatch` 에서 먼저
     * 걸러낸다). 두 곳 중 하나만 고치면 조용히 무효가 되는 조합이라 짝으로 읽어야 한다.
     *
     * 이 뒤집기가 **지금은 결과를 바꾸지 않는다**는 실측과 그럼에도 남기는 이유는
     * [PrivateResponseHeadersConfig.DISPATCHER_TYPES] 에 적었다.
     */
    override fun shouldNotFilterErrorDispatch(): Boolean = false
}

/**
 * Tomcat Engine 파이프라인에서 헤더를 싣는다. 서블릿 필터가 닿지 못하는 응답을 덮는 층이다.
 *
 * ## 왜 필터로 부족한가 (H-1 실측)
 *
 * 요청 줄이나 헤더 블록이 깨진 요청은 `Http11InputBuffer` 파싱 단계에서 거절된다. Tomcat 은
 * 그 요청을 서블릿에 매핑하지 않으므로 **필터 체인이 아예 시작되지 않는다.** 반면 Engine
 * 파이프라인은 돈다 — 실측으로 확인한 도달 사례:
 *
 * | 요청 | 응답 | 필터 | 밸브 |
 * |---|---|---|---|
 * | 요청 대상에 금지 문자(`<`) | 400 | ✗ | **O** |
 * | 콜론 없는 헤더 줄 | 400 | ✗ | **O** |
 * | 요청 줄 자체가 쓰레기 | 400 | ✗ | **O** |
 * | 헤더 상한 초과 | 400 | ✗ | **O** |
 * | `Host` 없는 HTTP/1.1 | 400 | ✗ | **O** |
 * | 알 수 없는 HTTP 버전 | 505 | ✗ | **O** |
 * | 알 수 없는 메서드 | 405 | ✗ | **O** |
 *
 * ## `asyncSupported = true` 를 반드시 준다
 *
 * [ValveBase] 의 기본 생성자는 `asyncSupported = false` 다. Tomcat 은 파이프라인의 밸브가
 * **하나라도** 비동기를 지원하지 않으면 `Pipeline.isAsyncSupported()` 를 false 로 만들고,
 * 그 결과 요청 전체의 비동기 처리가 꺼진다. 헤더 두 줄을 붙이려고 등록한 밸브가 스트리밍
 * 응답을 막는 셈이 되므로 명시적으로 `true` 를 준다.
 */
class PrivateResponseHeadersValve : ValveBase(true) {
    override fun invoke(
        request: Request,
        response: Response,
    ) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
        response.setHeader(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
        next.invoke(request, response)
    }
}

/** 계약 `components.headers.CacheControlNoStore` 의 `const` 값. */
internal const val CACHE_CONTROL_NO_STORE: String = "no-store"

/** Spring `HttpHeaders` 에 상수가 없는 헤더라 여기서 이름을 고정한다. */
internal const val X_CONTENT_TYPE_OPTIONS: String = "X-Content-Type-Options"

/** 계약 `components.headers.XContentTypeOptions` 의 `const` 값. */
internal const val NOSNIFF: String = "nosniff"
