package kr.easydoc.api.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kr.easydoc.application.auth.AuthService
import kr.easydoc.core.exceptions.InvalidCredentialsException
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.HandlerInterceptor
import java.util.UUID

/** 인증된 사용자 식별자. 컨트롤러 파라미터로 받는다. */
data class AuthenticatedUser(val id: UUID)

// 검증: `DeletedAccountTokenReachTest`.

/** `Authorization: Bearer <token>` 을 검증하는 인터셉터. */
@Component
class AuthenticationInterceptor(private val authService: AuthService) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        val token = bearerTokenOf(header) ?: throw InvalidCredentialsException(AUTHENTICATION_REQUIRED_MESSAGE)

        // 검증 실패는 authService 가 계약 문구로 던진다. 여기서 사유를 가르지 않는다.
        request.setAttribute(AUTHENTICATED_USER_ATTRIBUTE, AuthenticatedUser(authService.authenticate(token)))
        return true
    }

    /**
     * `Bearer <token>` 을 읽는다. 스킴 비교는 **대소문자를 가리지 않는다**(RFC 9110) —
     * `bearer` 로 보내는 클라이언트를 "헤더 없음"으로 취급하면 실패 문구가 갈린다.
     */
    private fun bearerTokenOf(header: String?): String? {
        if (header == null || !header.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) {
            return null
        }
        return header.substring(BEARER_PREFIX.length).trim().ifEmpty { null }
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "

        /** 계약 `components/responses/Unauthorized` 의 `no_header` 예시와 같은 값. */
        const val AUTHENTICATION_REQUIRED_MESSAGE = "인증이 필요합니다"
    }
}

/** 인터셉터가 검증 결과를 넘기는 요청 속성 이름. 이 문자열을 아는 곳은 이 파일뿐이다. */
private const val AUTHENTICATED_USER_ATTRIBUTE = "kr.easydoc.api.auth.AuthenticatedUser"

/** [AuthenticatedUser] 파라미터를 채운다. */
@Component
class AuthenticatedUserArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == AuthenticatedUser::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): AuthenticatedUser =
        webRequest
            .getNativeRequest(HttpServletRequest::class.java)
            ?.getAttribute(AUTHENTICATED_USER_ATTRIBUTE) as? AuthenticatedUser
            ?: error("인증 인터셉터가 돌지 않은 경로에서 AuthenticatedUser 를 요구했다")
}
