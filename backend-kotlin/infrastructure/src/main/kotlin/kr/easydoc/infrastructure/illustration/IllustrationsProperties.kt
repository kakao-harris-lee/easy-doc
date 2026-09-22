package kr.easydoc.infrastructure.illustration

import org.springframework.boot.context.properties.ConfigurationProperties

/** ER-15 그림 카탈로그는 옵트인이다. 이 값이 false 인 동안 조회 엔드포인트는 404 로 답한다. */
@ConfigurationProperties(prefix = "easydoc.illustrations")
data class IllustrationsProperties(val enabled: Boolean = false)
