package kr.easydoc.infrastructure.dictionary

import kr.easydoc.application.dictionary.DictionaryAttribution
import kr.easydoc.application.dictionary.DictionaryAttributionProvider
import kr.easydoc.application.dictionary.LookupRateLimiter
import kr.easydoc.application.dictionary.TermCandidateSource
import kr.easydoc.application.dictionary.TermLookupService
import kr.easydoc.core.dictionary.DictionaryIndex
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 사전 색인을 worker 프로필 밖에서도 적재할 수 있게 하는 조립 지점 (P0-5 조각 3, S1).
 *
 * 이전에는 `ConversionWorkerConfiguration`(`@Profile("worker")`)만 색인을 읽어, API
 * 프로세스는 이 색인에 닿을 방법이 없었다(계획 §1 "API 프로세스는 색인을 읽지 않는다").
 * 이 설정은 **프로필에 묶이지 않는다** - 나중에 조회 엔드포인트(조각 4)가 API 프로세스에서
 * 같은 색인을 쓰려면 worker 프로필 밖에서도 조립 가능한 자리가 있어야 하기 때문이다.
 *
 * **꺼져 있으면 읽지도 않는다.** [dictionaryIndex] 는 [DictionaryLookupProperties.enabled]
 * 가 거짓이면 `null` 을 돌려주고, Spring 은 `@Bean` 메서드가 `null` 을 돌려주면 그 빈을
 * 등록하지 않는다 - 소비자는 nullable 파라미터로 받아 "적재되지 않았다"를 그대로 잇는다.
 * 기본값이 꺼짐인 이유는 [DictionaryLookupProperties] KDoc 을 본다.
 *
 * **`ConversionWorkerConfiguration.dictionaryContextSource` 는 이 빈을 쓰지 않는다.** 그
 * 배선은 `DictionaryProperties.enabled`(기본 켜짐)라는 다른 스위치로 이미 동작하고 있고,
 * 이 빈을 공유하게 바꾸면 두 스위치의 합집합 논리가 필요해져 "worker 를 그대로 둔다"는
 * 조건을 벗어난다. 색인 적재 호출이 두 곳(worker, 이 빈)에 남는 대신 worker 회귀가 없다 -
 * 실제 공유 배선은 조각 4 가 API 컨트롤러를 놓을 때 함께 정리한다.
 *
 * 2026-09-05 리뷰 - 이 저장소에서 **첫 nullable `@Bean`** 이다. S4(조각 3 이후) 에서
 * 소비자 쪽 nullable 배선을 null object(예: 빈 `DictionaryIndex`)로 바꿀 계획이며, 이 빈
 * 자체를 지금 바꾸지는 않는다(계획이 그 정리를 S4로 미룬다).
 *
 * **정리(2026-09-05, 조각 4).** [termCandidateSource] 가 그 null object 다 —
 * [dictionaryIndex] 가 `null` 이면 [NoTermCandidateSource] 를 골라, 컨트롤러가 nullable
 * 을 직접 다루지 않는다. [dictionaryIndex] 자체는 여전히 nullable 을 돌려준다(Spring 이
 * `null` `@Bean` 을 등록하지 않는 메커니즘을 그대로 쓴다) — 이 정리는 **소비자 쪽**의
 * null 처분만 흡수한다.
 */
@Configuration(proxyBeanMethods = false)
class DictionaryConfiguration {
    @Bean
    fun dictionaryIndex(properties: DictionaryLookupProperties): DictionaryIndex? =
        if (properties.enabled) DictionaryIndexJsonReader().readClasspathResource() else null

    /** [dictionaryIndex] 가 없으면(조회 기능이 꺼짐) [NoTermCandidateSource] 가 422 로 거절한다. */
    @Bean
    fun termCandidateSource(dictionaryIndex: DictionaryIndex?): TermCandidateSource =
        dictionaryIndex?.let(::IndexedTermCandidateSource) ?: NoTermCandidateSource

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
