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
 *
 * ## 왜 `addCorsMappings` 가 아니라 필터인가
 *
 * Starlette 의 `CORSMiddleware` 는 **라우터 바깥**에 있다. 그래서 Python 은 경로가 없는
 * 404 와 메서드가 틀린 405 에도 CORS 헤더를 붙인다(실측 2026-08-12). Spring 의
 * `WebMvcConfigurer.addCorsMappings` 는 `HandlerMapping` 안에서 도므로 **핸들러를 찾은
 * 요청에만** 헤더가 붙고, `/nope` 같은 요청에서는 사라진다 — 브라우저는 그 응답을 읽지
 * 못해 진짜 사유(404)를 잃는다. 서블릿 필터가 미들웨어와 같은 자리다.
 *
 * ## 값의 출처
 *
 * | 항목 | 값 | 출처 |
 * |---|---|---|
 * | `allow_origins` | 설정값(기본 `http://localhost:5173`) | `EasyDocProperties.corsOrigins` ← `app/config.py` |
 * | `allow_credentials` | **false** | 쿠키를 쓰지 않는다 — 켜면 CSRF 노출 면적만 넓어진다 |
 * | 메서드 | `GET, POST, PUT, PATCH, DELETE` | `app/main.py` (OPTIONS 는 필터가 스스로 처리) |
 * | 요청 헤더 | `Authorization, Content-Type` | 계약 `x-cors.allow_headers` |
 * | **노출 헤더** | `Content-Disposition, Location` | 빠지면 브라우저 JS 만 헤더를 못 읽는다 |
 * | preflight 캐시 | 600초 | Starlette 기본값. Spring 기본값은 1800초라 명시하지 않으면 어긋난다 |
 *
 * ## Python 과 다른 지점 (의도적, 리더에게 보고함)
 *
 * - **허용하지 않은 오리진**: Starlette 는 단순 요청을 200으로 처리하고 `Allow-Origin`
 *   만 빼(브라우저가 막는다), Spring 의 `DefaultCorsProcessor` 는 **403 으로 끊는다.**
 *   브라우저에서 보이는 결과는 양쪽 다 "차단"으로 같고, Spring 쪽이 핸들러를 아예 돌리지
 *   않아 더 안전하다. 오리진 헤더를 보내지 않는 서버 간 호출에는 영향이 없다.
 * - **`Allow-Headers` 문자열**: Starlette 는 설정값과 안전 목록을 합친 5개를 고정으로
 *   내보내고, Spring 은 요청이 물어본 헤더 중 허용된 것만 되돌려준다. 브라우저 판정
 *   결과는 같다.
 * - **`Vary`**: Starlette 는 `Origin` 하나, Spring 은 preflight 관련 헤더까지 셋을 적는다.
 *   캐시 정확도가 더 높은 방향이다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EasyDocProperties::class)
class CorsConfig {
    /**
     * CORS 필터를 서블릿 체인 앞쪽에 둔다 — 단, [PrivateResponseHeadersConfig] 의 헤더
     * 필터보다는 **한 칸 뒤**다.
     *
     * 앞에 두는 이유는 preflight(OPTIONS)가 인증 필터나 컨트롤러까지 내려가지 않고 여기서
     * 끝나야 하기 때문이다 — Phase 3 에서 인증 필터가 붙으면 preflight 에는
     * `Authorization` 헤더가 실리지 않으므로, 뒤에 두면 모든 preflight 가 401 이 된다.
     *
     * 맨 앞을 내준 이유는 그 "여기서 끝난다"는 성질 때문이다. [CorsFilter] 는 preflight 를
     * 스스로 처리하고 체인의 나머지를 부르지 않으므로, 사적 응답 헤더 필터가 뒤에 있으면
     * OPTIONS 응답에만 `no-store`·`nosniff` 가 빠진다. 순서 상수는 두 파일에 흩어지지
     * 않도록 [PrivateResponseHeadersConfig] 한곳에 모아 두었다.
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
         *
         * **메서드를 추가하면 이 목록도 함께 갱신해야 한다.** 빠뜨리면 서버 테스트는 전부
         * 통과하고 브라우저에서만 실패한다.
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

        /**
         * 안전 목록 밖의 응답 헤더는 명시하지 않으면 브라우저 JS 가 읽지 못한다.
         *
         * 내려받기 파일명(`Content-Disposition` → React `parseFilename`)과 업로드 접수
         * 주소(`Location`)가 그 대상이다. 빠져도 서버 응답은 완전하고 로그·`curl` 로는
         * 아무 이상이 보이지 않는다 — 브라우저에서만 드러나는 실패다.
         */
        val EXPOSED_RESPONSE_HEADERS: List<String> =
            listOf(HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.LOCATION)

        /** Starlette `CORSMiddleware` 의 기본 `max_age`. Spring 기본값(1800)과 다르다. */
        const val PREFLIGHT_MAX_AGE_SECONDS = 600L
    }
}
