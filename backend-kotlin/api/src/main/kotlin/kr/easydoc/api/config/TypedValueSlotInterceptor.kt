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

/**
 * **값 자리가 있으나 그 타입으로 해석되지 않는 입력을 거절한다** — 공백뿐인 쿼리·경로 값.
 *
 * ## 왜 필요한가 — 프레임워크가 그것을 「미지정」으로 흡수한다 (R-6, 전부 실측)
 *
 * 관측한 흡수 경로가 둘이고 결과가 셋이다(2026-08-21, 실제 소켓).
 *
 * | 요청 | 고치기 전 | 기제 |
 * |---|---|---|
 * | `GET /documents?limit=` | **200 · `limit: 20`** | 인자 해석기가 **빈 문자열**을 `defaultValue` 로 갈아치운다(`null` 일 때와 별개 분기) |
 * | `GET /documents?workspace_id=` · `=%20` | **200 · 작업 공간 필터가 사라져 전체가 돌아온다** | 널 허용 타입으로 바인딩되는 공백 값이 `null` 이 된다 |
 * | `PATCH`·`DELETE /workspaces/%20` | **400 — 계약 밖 상태 코드** | 널화 뒤 경로 변수가 널일 수 없어 `MissingPathVariableException` |
 *
 * 둘째 줄이 **범위의 조용한 확대**다. 소유자 술어는 남으니 타 사용자 노출은 아니지만, 한
 * 작업 공간으로 좁히려던 요청이 전체를 받는다. 셋째 줄은 계약 전체에 `'400'` 선언이
 * **0건**인데도 나가던 상태 코드다(실측).
 *
 * ## 왜 이 층인가 — 다른 두 후보는 **재 봤더니 듣지 않았다**
 *
 * ⑴ `@RequestParam(defaultValue = …)` 를 떼고 널 허용 `Int?` + 본문 기본값으로 바꾸기 →
 *    `?limit=` 은 여전히 200 이고 **`?limit=%20` 이 422 에서 200 으로 나빠졌다.** 널 허용
 *    타입에서는 공백도 널이 되므로 흡수 범위가 오히려 넓어진다.
 * ⑵ 공백을 거절하는 `Converter<String, UUID>` 빈을 더하기 → **불리지 않았다.** 유효한
 *    UUID 에도 실패하도록 사보타주해도 요청이 성공했으므로, 그 변환기는 MVC 변환 서비스에
 *    등재되지 않거나 흡수가 변환보다 앞선다.
 *
 * 그래서 **바인딩 앞**에서 막는다. `preHandle` 은 인자 해석보다 앞이고(그 순서를 이미
 * [kr.easydoc.api.auth.AuthenticationInterceptor] 가 쓴다) 핸들러를 찾은 뒤에만 돌므로
 * 계약 밖 경로는 그대로 404 다.
 *
 * ## 열거하지 않는다 — 파라미터 **선언**에서 유도한다
 *
 * 이름 목록을 두지 않고 매칭된 핸들러의 파라미터를 읽는다. 대상은 **문자열이 아닌 타입**으로
 * 선언된 `@RequestParam`·`@PathVariable` 이다 — 그 자리에서만 프레임워크가 값을 강제 변환하고
 * 흡수가 일어난다. 문자열 파라미터는 공백이 그대로 전달되므로 이 가드의 대상이 아니다
 * (그쪽 판정은 계약이 서비스 층에 맡긴 축이다).
 *
 * 그래서 **다음에 추가되는 쿼리·경로 파라미터가 자동으로 이 가드 안에 든다.** 오늘 대상은
 * 셋(`limit`·`offset`·`workspace_id`)과 경로 변수 둘이고, 그 수를 여기 적지 않는다.
 *
 * ## 왜 [MethodArgumentTypeMismatchException] 을 던지는가
 *
 * 형식이 틀린 값(`?workspace_id=abc`)이 이미 밟는 경로와 **같은 예외**다. 그러면 전역 매퍼가
 * 이미 하는 일이 그대로 적용돼 422 **배열** `detail` 과 `loc: ["query"|"path", 이름]` 이
 * 나가고, **공백과 형식 오류의 응답 바이트가 같아진다** — 둘 다 「그 타입으로 해석되지 않는
 * 입력」이라는 한 종류이기 때문이다. 새 예외 타입과 새 매핑을 더하면 같은 종류에 두 모양이
 * 생기고, 그 둘이 갈리는 날 어느 쪽이 계약인지 알 수 없다.
 *
 * 계약 근거: 계약이 `limit`·`offset` 을 **진짜 스키마 제약**으로 못박았고
 * (`x-request-field-constraints.x-contrast-case`) 스키마 층 실패는 422 배열이다.
 * `workspace_id` 는 `anyOf: [{string, uuid}, {null}]` 이라 공백은 **uuid 도 널도 아니다.**
 * 다만 계약이 「빈 값」을 명시하지는 않았다 — 그 사실은 `contract-keeper` 레인에 조항
 * 요청으로 올렸다(산출물 「C4-R6」).
 */
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

    /**
     * 애너테이션이 적은 이름, 없으면 파라미터 이름.
     *
     * 이름을 못 읽으면 **끊지 않고 지나간다** — 이름 없이는 요청에서 값 자리를 찾을 수 없고,
     * 여기서 추측하면 엉뚱한 파라미터를 거절한다. 그 상태를 드러내는 것은 이 클래스가 아니라
     * 계약 케이스다(빈 값이 통과하면 그쪽이 빨개진다).
     */
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
