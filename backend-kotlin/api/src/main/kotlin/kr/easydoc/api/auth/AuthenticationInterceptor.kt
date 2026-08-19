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

/**
 * 인증된 사용자 식별자. 컨트롤러 파라미터로 받는다.
 *
 * `UUID` 를 그대로 받지 않는 이유: 타입이 곧 "이 값은 검증된 토큰에서 왔다"는 표시가
 * 되어야 한다. 컨트롤러가 요청 본문의 UUID 와 인증 주체를 같은 타입으로 다루면 둘을
 * 바꿔 쓰는 실수가 타입 검사를 통과한다.
 *
 * **`value class` 로 두지 않는다.** Kotlin 은 value class 를 파라미터 자리에서 **풀어
 * 버리고**(컨트롤러 메서드 시그니처가 `UUID` 가 된다) 함수 이름까지 맹글링한다. 그러면
 * [AuthenticatedUserArgumentResolver.supportsParameter] 의 타입 비교가 성립하지 않는다.
 */
data class AuthenticatedUser(val id: UUID)

/**
 * `Authorization: Bearer <token>` 을 검증하는 인터셉터.
 *
 * ## 왜 필터가 아니라 인터셉터인가
 *
 * 계약이 **인증이 입력 검증보다 먼저**임을 요구한다(`info.description`). 인터셉터의
 * `preHandle` 은 `HandlerAdapter` 가 인자를 해석하기 **전에** 돌므로, 경로 변수 UUID
 * 변환·쿼리 파라미터 범위 검사·본문 역직렬화가 모두 그 뒤가 된다. 순서가 뒤집히면
 * 토큰 없이도 API 표면을 탐색할 수 있게 된다.
 *
 * 필터로 두면 반대 문제가 생긴다 — 필터는 **핸들러가 없는 요청까지** 지나므로 계약에
 * 없는 경로(`GET /nope`)가 404 가 아니라 401 이 된다. 인터셉터는 핸들러를 찾은 뒤에만
 * 돌아 그 자리가 그대로 404 로 남는다.
 *
 * ## 401 을 `sendError` 로 내지 않는다
 *
 * 예외를 던져 `GlobalExceptionHandler` 가 응답을 만들게 한다. `sendError` 를 쓰면
 * 컨테이너가 `/error` 로 재디스패치하고 Spring 기본 본문
 * (`{"timestamp":…,"status":…,"error":…,"path":…}`)이 나가 계약의 "최상위 키는 `detail`
 * 하나" 불변식이 깨진다(계약 `x-error-body-universality` E-2). 인증 필터가 그렇게 내는
 * 것이 가장 흔한 구현이라 계약이 이 자리를 따로 지목했다.
 *
 * ## 실패 문구는 두 갈래다
 *
 * 계약 `components/responses/Unauthorized` 가 그렇게 적는다 — 헤더가 아예 없으면 하나,
 * 토큰이 있으나 유효하지 않으면 다른 하나. `failure_uniformity` 가 요구하는 것은
 * **무효 사유를 구분하지 않는 것**이지 두 갈래의 통합이 아니다. 그래서 만료·위조·
 * 클레임 누락·용도 불일치·계정 삭제는 전부 같은 문구로 모인다.
 *
 * **「계정 삭제」가 이 목록에 있는 근거는 [AuthService.authenticate] 다** — 그 함수가
 * 서명 검증 직후 사용자 존재를 확인한다. 종전에는 이 KDoc 이 계정 삭제까지 선언하면서
 * 실제로는 서명만 봤고, 그래서 폐기된 토큰이 여기를 지나 경로마다 다른 코드
 * (200·500·404)로 갈렸다. 선언과 도달을 맞춘 자리이므로, 확인을 걷어내려면 이 문장도
 * 함께 지워야 한다(`DeletedAccountTokenReachTest` 가 그 전에 먼저 깨진다).
 */
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

/**
 * [AuthenticatedUser] 파라미터를 채운다.
 *
 * 속성이 없으면 **예외를 던진다**. 보호되지 않는 경로의 핸들러가 이 타입을 받으면
 * 인터셉터가 돌지 않아 속성이 비는데, 그 상태를 `null` 로 넘기면 인증 없이 도는
 * 엔드포인트가 조용히 생긴다. 배선 실수를 500 으로 시끄럽게 끊는 편이 안전하다.
 */
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
