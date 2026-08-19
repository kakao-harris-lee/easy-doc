package kr.easydoc.api.config

import com.fasterxml.jackson.annotation.Nulls
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.type.LogicalType

/**
 * **요청 본문 바인딩을 엄격하게 둔다** — 타입 불일치와 `null`·누락이 조용히 통과하거나
 * 원인이 뭉개지는 경로를 막는다.
 *
 * ## 실측 (게이트 20 codex C4)
 *
 * Jackson 기본 설정은 숫자·불리언을 문자열 필드에 넣을 때 **말없이 변환한다.** 그래서
 * 계약이 「타입 불일치 → 422 배열」로 정한 자리가 다른 모양으로 나갔고, 한 건은 아예
 * 성공했다:
 *
 * ```
 * {"email":"…","password":12345678}  → 201  (숫자가 비밀번호가 됐다)
 * {"email":true,"password":"…"}      → 422 문자열 detail (형식 오류로 둔갑)
 * {"email":12345,"password":"…"}     → 422 문자열 detail
 * {"email":"…","password":true}      → 422 문자열 detail (길이 미달로 둔갑)
 * ```
 *
 * 첫 줄이 특히 나쁘다 — **타입이 맞지 않는 요청으로 계정이 만들어진다.** 나머지 셋도
 * 계약이 정한 배열 모양 대신 도메인 규칙의 문자열 모양으로 나가, 계약 위반이면서
 * 「입력이 이상하다」는 사실을 클라이언트가 잘못된 이유로 받는다.
 *
 * ## 왜 설정이 아니라 코드인가
 *
 * `spring.jackson.*` 에는 coercion 을 여는 속성이 없다. Boot 4 의
 * [JsonMapperBuilderCustomizer] 가 유일한 자리이고, 여기서 끄면 **모든 요청 본문**에
 * 적용된다 — DTO 마다 애너테이션을 다는 방식이면 다음 DTO 에서 빠뜨린다.
 *
 * ## `null`·누락도 여기서 끊는다 (게이트 21 codex C-2)
 *
 * 종전에는 필수 필드를 빠뜨리거나 `null` 을 넣으면 Kotlin 생성자의 널 검사가 NPE 를 내고
 * 그것이 `ValueInstantiationException`(경로 정보 없음)으로 감싸여, **깨진 JSON 과
 * 바이트 동일한 응답**(`loc:["body"]`·`json_invalid`)이 나갔다. 필드를 빠뜨린 사용자가
 * 화면에서 "JSON decode error" 를 본다.
 *
 * [Nulls.FAIL] 을 **전역 기본값**으로 두면 두 경우 모두 `InvalidNullException` 이 되고,
 * 그 예외는 **어느 프로퍼티인지**를 들고 있다. `GlobalExceptionHandler.bodyReadItem` 이
 * 그것을 계약 `ValidationFailed` 의 `field_missing` 모양으로 옮긴다.
 *
 * **DTO 마다 `@JsonSetter(nulls = Nulls.FAIL)` 를 달지 않는 이유**는 coercion 과 같다 —
 * 애너테이션 방식이면 다음 DTO 에서 빠뜨리고, 빠뜨린 상태가 조용하다. `null` 을 실제로
 * 받아야 하는 필드가 생기면 그 필드에 `Nulls.SET` 을 명시적으로 달아 여는데, 그때는
 * **여는 것이 눈에 보인다.**
 *
 * ## 범위
 *
 * 지금 끄는 강제 변환은 **문자열 필드로 들어오는 숫자·불리언**이다. 반대 방향(숫자 필드로
 * 들어오는 문자열)은 현재 요청 DTO 에 비문자열 필드가 하나도 없어 **잴 대상이 없다** —
 * Phase 4 의 첫 비문자열 필드 커밋에서 같은 판정을 함께 한다. enum 갈래도 같다(CON-2).
 */
@Configuration(proxyBeanMethods = false)
class JsonRequestStrictnessConfig {
    @Bean
    fun strictRequestBinding(): JsonMapperBuilderCustomizer =
        JsonMapperBuilderCustomizer { builder ->
            builder
                .withCoercionConfig(LogicalType.Textual) { coercion ->
                    COERCED_INTO_TEXT.forEach { shape -> coercion.setCoercion(shape, CoercionAction.Fail) }
                }.changeDefaultNullHandling { nulls -> nulls.withValueNulls(Nulls.FAIL) }
        }

    private companion object {
        /**
         * 문자열로 둔갑하던 입력 모양들.
         *
         * `EmptyString`·`String` 은 대상이 아니다 — 빈 문자열은 타입이 맞는 값이고,
         * 그 판정은 계약이 `x-request-field-constraints` 로 서비스 층에 맡겼다.
         */
        val COERCED_INTO_TEXT =
            listOf(
                CoercionInputShape.Integer,
                CoercionInputShape.Float,
                CoercionInputShape.Boolean,
            )
    }
}
