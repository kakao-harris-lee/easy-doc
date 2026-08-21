package kr.easydoc.infrastructure.ingest

import kr.easydoc.application.document.DocumentTextExtractor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 문서 추출 조립 — **이 모듈이 소유한다.** */
@Configuration(proxyBeanMethods = false)
class IngestConfiguration {
    /** POI 의 전역 zip 방어값을 기동 시 한 번 적용하고, 제한을 두른 추출기를 낸다. */
    @Bean
    fun documentTextExtractor(): DocumentTextExtractor {
        PoiZipDefenses.apply()
        return ConcurrencyLimitedTextExtractor(DocumentExtractors())
    }
}
