package kr.easydoc.infrastructure.ingest

import kr.easydoc.application.document.DocumentTextExtractor
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IngestProperties::class)
class IngestConfiguration {
    /** POI 의 전역 zip 방어값을 기동 시 한 번 적용하고, 제한을 두른 추출기를 낸다. */
    @Bean
    fun documentTextExtractor(properties: IngestProperties): DocumentTextExtractor {
        PoiZipDefenses.apply()
        return ConcurrencyLimitedTextExtractor(DocumentExtractors(), properties.maxConcurrentExtractions)
    }
}
