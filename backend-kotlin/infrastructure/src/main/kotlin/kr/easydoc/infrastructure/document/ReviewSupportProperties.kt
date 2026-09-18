package kr.easydoc.infrastructure.document

import org.springframework.boot.context.properties.ConfigurationProperties

/** R1 검수 지원 토글. migration과 본문 버전은 항상 유지하고 신규 endpoint 노출만 제어한다. */
@ConfigurationProperties(prefix = "easydoc.review-support")
data class ReviewSupportProperties(val enabled: Boolean = false)
