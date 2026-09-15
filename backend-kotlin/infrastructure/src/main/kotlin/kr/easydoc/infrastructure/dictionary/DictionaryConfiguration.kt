package kr.easydoc.infrastructure.dictionary

import kr.easydoc.application.dictionary.DictionaryAttribution
import kr.easydoc.application.dictionary.DictionaryAttributionProvider
import kr.easydoc.application.dictionary.LookupRateLimiter
import kr.easydoc.application.dictionary.TermCandidateSource
import kr.easydoc.application.dictionary.TermLookupService
import kr.easydoc.core.exceptions.ConfigurationException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** API 조회와 worker 프롬프트 주입이 공유하는 사전 색인 조립 지점. */
@Configuration(proxyBeanMethods = false)
class DictionaryConfiguration {
    /**
     * 두 스위치 중 하나라도 켜져 있으면 **읽을 수 있는** 홀더를 만든다 — 실제로 읽는지는
     * [DictionaryIndexHolder] 의 `by lazy` 와 소비자가 `indexOrNull()` 을 부르는지에
     * 달렸다. 이 `@Bean` 메서드 자체는 값싸다(객체 하나를 조립할 뿐 I/O 가 없다).
     */
    @Bean
    fun dictionaryIndexHolder(
        lookupProperties: DictionaryLookupProperties,
        dictionaryProperties: DictionaryProperties,
    ): DictionaryIndexHolder =
        DictionaryIndexHolder(
            enabled = lookupProperties.enabled || dictionaryProperties.enabled,
            loader = { DictionaryIndexJsonReader().readClasspathResource() },
        )

    /**
     * 조회 기능 스위치([DictionaryLookupProperties.enabled])를 직접 본다 — 꺼져 있으면
     * [DictionaryIndexHolder.indexOrNull] 을 **부르지도 않고** [NoTermCandidateSource] 로
     * 422 를 거절한다. worker 주입만 켜져 있어도 이 소비자는 색인을 읽지
     * 않는다).
     */
    @Bean
    fun termCandidateSource(
        properties: DictionaryLookupProperties,
        dictionaryIndexHolder: DictionaryIndexHolder,
    ): TermCandidateSource =
        if (!properties.enabled) {
            NoTermCandidateSource
        } else {
            val index =
                dictionaryIndexHolder.indexOrNull()
                    ?: throw ConfigurationException(
                        "easydoc.dictionary.lookup.enabled=true 인데 사전 색인이 적재되지 않았다 " +
                            "— dictionaryIndexHolder 빈 조립을 확인한다 (구성상 발생할 수 없다)",
                    )
            IndexedTermCandidateSource(index)
        }

    /** `TermLookupService` 는 `@Component` 가 아니다 — 다른 유스케이스(`AuthService` 등)와 같이 조립 지점이 만든다. */
    @Bean
    fun termLookupService(source: TermCandidateSource): TermLookupService = TermLookupService(source)

    /**
     * `Clock.systemUTC()` 를 여기서 직접 넘긴다 — composition root 가 시간원을 고르는 자리라는
     * 관례(`AuthConfiguration.jwtAccessTokens` 등)를 그대로 따른다.
     */
    @Bean
    fun lookupRateLimiter(properties: DictionaryLookupProperties): LookupRateLimiter =
        InMemorySlidingWindowLookupRateLimiter(properties.rateLimitPerMinute, Clock.systemUTC())

    /**
     * 사전 단위 표기(계획 §3.2). `schemaVersion` 은 색인 파일의 실제 값이 아니라
     * [DictionaryIndexJsonReader] 가 적재를 성공시킨 버전([DictionaryIndexJsonReader
     * .SUPPORTED_SCHEMA_VERSION]) 이다 — 적재를 통과한 색인은 이 버전이라고 스스로
     * 확인했으므로([DictionaryIndexJsonReader.read] 의 `check`) 다른 값일 수 없다.
     */
    @Bean
    fun dictionaryAttributionProvider(properties: DictionaryLookupProperties): DictionaryAttributionProvider =
        DictionaryAttributionProvider {
            DictionaryAttribution(
                name = properties.dictionaryName,
                license = properties.dictionaryLicense,
                schemaVersion = DictionaryIndexJsonReader.SUPPORTED_SCHEMA_VERSION,
            )
        }
}
