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

    /** 이 파라미터가 값 자리를 갖고 그 값이 공백뿐이면 형식 오류와 **같은 예외**로 끊는다. */
    private fun rejectBlank(
        request: HttpServletRequest,
        parameter: MethodParameter,
    ) {
        // 문자열 파라미터는 강제 변환이 없어 흡수가 일어나지 않는다 — 대상이 아니다.
        if (CharSequence::class.java.isAssignableFrom(parameter.parameterType)) return

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
