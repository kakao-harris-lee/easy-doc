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

/** Spring MVC 배선 중 계약에 걸린 부분. */
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
