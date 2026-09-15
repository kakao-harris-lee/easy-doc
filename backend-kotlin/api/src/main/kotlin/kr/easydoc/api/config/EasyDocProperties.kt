package kr.easydoc.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

// 검증: `ConfigurationPropertiesBindingTest`.

/** API 경계가 소유하는 애플리케이션 설정. */
@ConfigurationProperties(prefix = "easydoc")
data class EasyDocProperties(
    /** 브라우저에서 API를 부를 수 있는 오리진 목록. */
    val corsOrigins: List<String> =
        listOf(
            "http://localhost:5173",
            "http://localhost:8080",
            "http://127.0.0.1:8080",
        ),
)
