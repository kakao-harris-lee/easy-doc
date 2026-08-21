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

/** 사적 응답 헤더의 **전역** 부착. */
@Configuration(proxyBeanMethods = false)
class PrivateResponseHeadersConfig {
    /** 헤더 필터를 서블릿 체인 **맨 앞**에 둔다. CORS 필터보다도 앞이다. */
    @Bean
    fun privateResponseHeadersFilterRegistration(): FilterRegistrationBean<PrivateResponseHeadersFilter> =
        FilterRegistrationBean(PrivateResponseHeadersFilter()).apply {
            order = PRIVATE_HEADER_FILTER_ORDER
            setDispatcherTypes(DISPATCHER_TYPES)
        }

    /** 서블릿 필터가 닿지 못하는 자리를 덮는 Tomcat Engine 밸브. */
    @Bean
    fun privateResponseHeadersValveCustomizer(): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory -> factory.addEngineValves(PrivateResponseHeadersValve()) }

    internal companion object {
        /** 체인 맨 앞. `CorsConfig` 는 이보다 뒤([CORS_FILTER_ORDER])에 둔다. */
        const val PRIVATE_HEADER_FILTER_ORDER: Int = Ordered.HIGHEST_PRECEDENCE

        /** CORS 필터 순서. 헤더 필터 바로 뒤이고, Phase 3 에서 붙을 인증 필터보다는 앞이다. */
        const val CORS_FILTER_ORDER: Int = Ordered.HIGHEST_PRECEDENCE + 10

        /** ERROR·ASYNC 디스패치까지 건다. */
        val DISPATCHER_TYPES: EnumSet<DispatcherType> =
            EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC)
    }
}

/** 모든 응답에 `Cache-Control: no-store` 와 `X-Content-Type-Options: nosniff` 를 싣는다. */
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

    /** 기본값은 `true` — ERROR 디스패치에서 필터를 **건너뛴다**. */
    override fun shouldNotFilterErrorDispatch(): Boolean = false
}

/** Tomcat Engine 파이프라인에서 헤더를 싣는다. 서블릿 필터가 닿지 못하는 응답을 덮는 층이다. */
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
