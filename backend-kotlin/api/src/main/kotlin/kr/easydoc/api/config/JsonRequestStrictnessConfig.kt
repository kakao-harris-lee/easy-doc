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
 */
@Configuration(proxyBeanMethods = false)
class JsonRequestStrictnessConfig {
    @Bean
    fun strictRequestBinding(): JsonMapperBuilderCustomizer =
        JsonMapperBuilderCustomizer { builder ->
            builder
                .withCoercionConfig(LogicalType.Textual) { coercion ->
                    COERCED_INTO_TEXT.forEach { shape -> coercion.setCoercion(shape, CoercionAction.Fail) }
                }.withCoercionConfig(LogicalType.OtherScalar) { coercion ->
                    coercion.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                }.changeDefaultNullHandling { nulls -> nulls.withValueNulls(Nulls.FAIL) }
        }

    private companion object {
        /** 문자열로 둔갑하던 입력 모양들. */
        val COERCED_INTO_TEXT =
            listOf(
                CoercionInputShape.Integer,
                CoercionInputShape.Float,
                CoercionInputShape.Boolean,
            )
    }
}
