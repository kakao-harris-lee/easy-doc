package kr.easydoc.api.config

import kr.easydoc.api.auth.AuthenticatedEndpoints
import kr.easydoc.api.auth.AuthenticatedUserArgumentResolver
import kr.easydoc.api.auth.AuthenticationInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
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
 *
 * ## 인증은 인터셉터로 붙인다
 *
 * 계약이 **인증이 입력 검증보다 먼저**임을 요구한다. 인터셉터의 `preHandle` 은 인자
 * 해석(경로 변수 변환·쿼리 범위 검사·본문 역직렬화)보다 앞이라 그 순서가 성립하고,
 * 핸들러를 찾은 뒤에만 돌아 계약 밖 경로가 404 로 남는다. 사유 전문은
 * [AuthenticationInterceptor] KDoc 에 있다.
 *
 * ## 값 자리 가드도 인터셉터다 (R-6)
 *
 * [TypedValueSlotInterceptor] 가 **공백뿐인 쿼리·경로 값**을 형식 오류와 같은 예외로 끊는다.
 * 바인딩보다 앞이어야 하는 이유와, 다른 두 후보(애너테이션 기본값 제거·커스텀 변환기)가
 * 실측에서 듣지 않은 경위는 그 클래스 KDoc 에 있다. **인증 뒤에 등재한다** — 순서가 뒤집히면
 * 토큰 없이 파라미터 형태를 탐색할 수 있고 그것이 X-A3 위반이다.
 */
@Configuration(proxyBeanMethods = false)
class WebMvcConfig(
    private val authenticationInterceptor: AuthenticationInterceptor,
    private val typedValueSlotInterceptor: TypedValueSlotInterceptor,
    private val authenticatedUserArgumentResolver: AuthenticatedUserArgumentResolver,
) : WebMvcConfigurer {
    override fun configureContentNegotiation(configurer: ContentNegotiationConfigurer) {
        configurer
            .ignoreAcceptHeader(true)
            .defaultContentType(MediaType.APPLICATION_JSON)
    }

    /**
     * 보호 경로 목록은 [AuthenticatedEndpoints] 가 들고, 그 목록이 계약과 같은지는
     * `AuthenticationCoverageContractTest` 가 계약 파일을 읽어 판정한다.
     */
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry
            .addInterceptor(authenticationInterceptor)
            .addPathPatterns(AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS)
        // **인증 뒤**에 등재한다 — 계약이 인증을 입력 검증보다 먼저로 못박았고(X-A3),
        // 인터셉터는 등재 순서대로 돈다. 경로 패턴을 좁히지 않는 이유는 이 가드의 대상이
        // 「값 자리가 있으나 그 타입으로 해석되지 않는 입력」이라는 **종류**이고, 그 종류는
        // 특정 경로에 속하지 않기 때문이다(사유 전문은 그 클래스 KDoc).
        registry.addInterceptor(typedValueSlotInterceptor)
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(authenticatedUserArgumentResolver)
    }
}
