package kr.easydoc.infrastructure.document

import org.springframework.boot.context.properties.ConfigurationProperties

/** R6 은 옵트인이다. 이 값이 false 인 동안 조회 엔드포인트는 404 로 답하고 검수 화면은 섹션을 숨긴다. */
@ConfigurationProperties(prefix = "easydoc.explanations")
data class ExplanationsProperties(val enabled: Boolean = false)
