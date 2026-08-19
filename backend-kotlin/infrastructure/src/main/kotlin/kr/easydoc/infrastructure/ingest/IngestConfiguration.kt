package kr.easydoc.infrastructure.ingest

import kr.easydoc.application.document.DocumentTextExtractor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 문서 추출 조립 — **이 모듈이 소유한다.**
 *
 * `AuthConfiguration`·`CryptoConfiguration` 과 같은 자리이고 이유도 같다: 구현 클래스를 볼 수
 * 있는 모듈이 `infrastructure` 하나뿐이다. `api`·`worker` 는 `runtimeOnly(project(":infrastructure"))`
 * 라 [DocumentExtractors] 타입도 POI·PDFBox 타입도 컴파일 시점에 보지 못한다.
 *
 * 노출하는 빈은 **포트 타입 하나**([DocumentTextExtractor])이고, 그것도 동시 진입 제한으로
 * 감싼 것이다 — 유스케이스가 제한 없는 추출기를 잡을 방법이 없다.
 */
@Configuration(proxyBeanMethods = false)
class IngestConfiguration {
    /**
     * POI 의 전역 zip 방어값을 기동 시 한 번 적용하고, 제한을 두른 추출기를 낸다.
     *
     * [PoiZipDefenses.apply] 를 여기서 부르는 이유: 그 값들은 **JVM 전역 static** 이라
     * 적용 지점이 여럿이면 마지막에 부른 쪽이 이긴다. 조립 지점 한 곳에 둔다.
     */
    @Bean
    fun documentTextExtractor(): DocumentTextExtractor {
        PoiZipDefenses.apply()
        return ConcurrencyLimitedTextExtractor(DocumentExtractors())
    }
}
