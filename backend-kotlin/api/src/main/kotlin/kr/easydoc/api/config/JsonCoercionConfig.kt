package kr.easydoc.api.config

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.type.LogicalType

/**
 * **스칼라 강제 변환을 끈다** — 타입 불일치가 조용히 문자열이 되는 경로를 막는다.
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
 * ## 범위
 *
 * 지금 끄는 것은 **문자열 필드로 들어오는 숫자·불리언**이다. 반대 방향(숫자 필드로
 * 들어오는 문자열)은 현재 요청 DTO 에 비문자열 필드가 하나도 없어 **잴 대상이 없다** —
 * Phase 4 의 첫 비문자열 필드 커밋에서 같은 판정을 함께 한다.
 */
@Configuration(proxyBeanMethods = false)
class JsonCoercionConfig {
    @Bean
    fun strictScalarCoercion(): JsonMapperBuilderCustomizer =
        JsonMapperBuilderCustomizer { builder ->
            builder.withCoercionConfig(LogicalType.Textual) { coercion ->
                COERCED_INTO_TEXT.forEach { shape -> coercion.setCoercion(shape, CoercionAction.Fail) }
            }
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
