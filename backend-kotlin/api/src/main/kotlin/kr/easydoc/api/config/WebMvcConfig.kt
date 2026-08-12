package kr.easydoc.api.config

import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Spring MVC 배선 중 계약에 걸린 부분.
 *
 * ## 내용 협상을 하지 않는다
 *
 * FastAPI/Starlette 는 `Accept` 헤더를 보고 표현을 고르지 않는다 — `JSONResponse` 는
 * 언제나 JSON 을 돌려준다. 실측(2026-08-12): `GET /health` 에 `Accept: application/xml`
 * 을 붙여도 Python 은 **200 JSON** 이다.
 *
 * Spring MVC 는 기본적으로 협상을 하므로, 같은 요청에서 `HttpMediaTypeNotAcceptableException`
 * 이 나 **406** 이 된다(고치기 전에는 그 예외가 백스톱에 걸려 500 이었다 — 리뷰 C-1).
 * `ignoreAcceptHeader` 로 협상을 끄고 기본 표현을 JSON 으로 고정하면 Python 과 같은 값이
 * 나온다.
 *
 * 내려받기처럼 JSON 이 아닌 응답은 컨트롤러가 `ResponseEntity` 에 `Content-Type` 을
 * 직접 적어 내보낸다 — 명시된 Content-Type 은 협상보다 우선하므로 이 설정에 영향받지
 * 않는다(Phase 4 내보내기).
 */
@Configuration(proxyBeanMethods = false)
class WebMvcConfig : WebMvcConfigurer {
    override fun configureContentNegotiation(configurer: ContentNegotiationConfigurer) {
        configurer
            .ignoreAcceptHeader(true)
            .defaultContentType(MediaType.APPLICATION_JSON)
    }
}
