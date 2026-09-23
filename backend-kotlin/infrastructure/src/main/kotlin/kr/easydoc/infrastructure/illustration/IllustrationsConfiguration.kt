package kr.easydoc.infrastructure.illustration

import kr.easydoc.application.illustration.IllustrationCatalogSource
import kr.easydoc.application.illustration.IllustrationsService
import kr.easydoc.infrastructure.crypto.MIGRATE_PROFILE
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * 그림 카탈로그(ER-15) 조립 지점. [IllustrationsProperties] 는 `DocumentConfiguration` 이
 * `@EnableConfigurationProperties` 로 등록한다(`ExplanationsProperties`와 같은 자리 —
 * `conversionQueryService` 가 두 속성 모두를 필요로 하므로 그 조립 지점이 정본이다).
 *
 * `migrate` 프로필에서는 서지 않는다 — 형제 조립 지점(`ExplanationsConfiguration` 등)과
 * 같은 이유다. e2e 전용 fake 가 없다 — 실제 카탈로그가 리소스에 있으니 e2e 도 그것을 쓴다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!$MIGRATE_PROFILE")
class IllustrationsConfiguration {
    @Bean
    fun illustrationCatalogSource(): IllustrationCatalogSource = ResourceIllustrationCatalogSource()

    @Bean
    fun illustrationsService(
        properties: IllustrationsProperties,
        source: IllustrationCatalogSource,
    ): IllustrationsService = IllustrationsService(enabled = properties.enabled, source = source)
}
