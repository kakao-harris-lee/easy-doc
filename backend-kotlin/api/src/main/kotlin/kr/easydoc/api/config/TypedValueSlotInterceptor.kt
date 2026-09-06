package kr.easydoc.api.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.method.HandlerMethod
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping

/** **값 자리가 있으나 그 타입으로 해석되지 않는 입력을 거절한다** — 공백뿐인 쿼리·경로 값. */
@Component
class TypedValueSlotInterceptor : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val method = handler as? HandlerMethod ?: return true
        method.methodParameters.forEach { parameter ->
            rejectBlank(request, parameter)
        }
        return true
    }

    /**
     * 이 파라미터가 값 자리를 갖고 그 값이 공백뿐이면 형식 오류와 **같은 예외**로 끊는다.
     *
     * **문자열 파라미터도 대상이다** (2.20.0, U2 `from`/`to` 추가로 드러난 자리 —
     * 이전에는 강제 변환이 있는 타입만 대상이었다: 그때까지 선언된 쿼리·경로 값 자리가
     * 전부 `Int`·`UUID`·enum 이라 문자열 자리에서 흡수가 일어난 적이 없었다). `String`
     * 값 자리는 Spring 이 타입 변환을 하지 않아 공백이 그대로 컨트롤러에 들어간다 —
     * 다른 타입처럼 여기서 걸러 주지 않으면 서비스 층 규칙(도메인 형식 오류, `detail`
     * 문자열)으로 새는데, 계약 `ValidationFailed`의 경계(§ 배열 — 스키마에 실제로 제약이
     * 선언된 쿼리 파라미터)는 **값 자체가 없는 것과 같은 자리**(빈 문자열·공백뿐)를
     * 스키마 층으로 본다 — 그 값으로는 무엇을 읽을지조차 정할 수 없기 때문이다.
     */
    private fun rejectBlank(
        request: HttpServletRequest,
        parameter: MethodParameter,
    ) {
        queryName(parameter)?.let { name ->
            request.getParameterValues(name)?.firstOrNull { it.isBlank() }?.let {
                throw mismatch(it, name, parameter)
            }
        }
        pathName(parameter)?.let { name ->
            val blank = pathVariables(request)[name]?.takeIf { it.isBlank() }
            if (blank != null) throw mismatch(blank, name, parameter)
        }
    }

    private fun queryName(parameter: MethodParameter): String? =
        parameter.getParameterAnnotation(RequestParam::class.java)?.let { nameOf(it.name, it.value, parameter) }

    private fun pathName(parameter: MethodParameter): String? =
        parameter.getParameterAnnotation(PathVariable::class.java)?.let { nameOf(it.name, it.value, parameter) }

    private fun pathVariables(request: HttpServletRequest): Map<String, String> {
        val raw =
            request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<*, *> ?: return emptyMap()
        return raw.entries
            .mapNotNull { (key, value) ->
                if (key is String && value is String) key to value else null
            }.toMap()
    }

    /** 애너테이션이 적은 이름, 없으면 파라미터 이름. */
    private fun nameOf(
        name: String,
        value: String,
        parameter: MethodParameter,
    ): String? = name.ifEmpty { value }.ifEmpty { parameter.parameterName ?: "" }.ifEmpty { null }

    private fun mismatch(
        value: String,
        name: String,
        parameter: MethodParameter,
    ): MethodArgumentTypeMismatchException =
        MethodArgumentTypeMismatchException(
            value,
            parameter.parameterType,
            name,
            parameter,
            // 원인을 담지만 **제출값을 메시지로 쓰지 않는다** — 전역 매퍼가 예외 메시지를
            // `detail` 로 옮기지 않는다는 규약과 짝을 이룬다.
            IllegalArgumentException("blank value slot"),
        )
}
