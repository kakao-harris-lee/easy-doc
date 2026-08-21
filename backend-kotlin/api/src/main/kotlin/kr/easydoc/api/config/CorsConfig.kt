package kr.easydoc.api.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter

/**
 * CORS 설정. 원본은 `app/main.py` 의 `CORSMiddleware` 이고, 동결된 값은
 * `contracts/easy-doc-v1.yaml` 의 `x-cors` 다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EasyDocProperties::class)
class CorsConfig {
    /**
     * CORS 필터를 서블릿 체인 앞쪽에 둔다 — 단, [PrivateResponseHeadersConfig] 의 헤더
     * 필터보다는 **한 칸 뒤**다.
     */
    @Bean
    fun corsFilterRegistration(properties: EasyDocProperties): FilterRegistrationBean<CorsFilter> {
        val configuration =
            CorsConfiguration().apply {
                allowedOrigins = properties.corsOrigins
                // 명시적으로 false 를 적어 둔다. Spring 은 true 일 때만 헤더를 내보내므로
                // 값이 없어도 결과는 같지만, "쿠키를 쓰지 않는다"는 판단을 코드에 남긴다.
                allowCredentials = false
                allowedMethods = ALLOWED_METHODS
                allowedHeaders = ALLOWED_REQUEST_HEADERS
                exposedHeaders = EXPOSED_RESPONSE_HEADERS
                maxAge = PREFLIGHT_MAX_AGE_SECONDS
            }
        val source =
            UrlBasedCorsConfigurationSource().apply {
                registerCorsConfiguration("/**", configuration)
            }
        return FilterRegistrationBean(CorsFilter(source)).apply {
            order = PrivateResponseHeadersConfig.CORS_FILTER_ORDER
        }
    }

    private companion object {
        /**
         * `app/main.py` 의 `allow_methods` 그대로. OPTIONS 는 목록에 넣지 않는다 —
         * preflight 는 필터가 스스로 처리한다.
         */
        val ALLOWED_METHODS: List<String> =
            listOf(
                HttpMethod.GET,
                HttpMethod.POST,
                HttpMethod.PUT,
                HttpMethod.PATCH,
                HttpMethod.DELETE,
            ).map(HttpMethod::name)

        /** 계약 `x-cors.allow_headers`. 안전 목록(Accept 등)은 브라우저가 알아서 허용한다. */
        val ALLOWED_REQUEST_HEADERS: List<String> =
            listOf(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE)

        /** 안전 목록 밖의 응답 헤더는 명시하지 않으면 브라우저 JS 가 읽지 못한다. */
        val EXPOSED_RESPONSE_HEADERS: List<String> =
            listOf(HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.LOCATION)

        /** Starlette `CORSMiddleware` 의 기본 `max_age`. Spring 기본값(1800)과 다르다. */
        const val PREFLIGHT_MAX_AGE_SECONDS = 600L
    }
}
