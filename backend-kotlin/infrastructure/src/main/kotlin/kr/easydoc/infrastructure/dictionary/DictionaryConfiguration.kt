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

/**
 * 사전 색인을 worker 프로필 밖에서도 적재할 수 있게 하는 조립 지점 (P0-5 조각 3, S1).
 *
 * 이전에는 `ConversionWorkerConfiguration`(`@Profile("worker")`)만 색인을 읽어, API
 * 프로세스는 이 색인에 닿을 방법이 없었다(계획 §1 "API 프로세스는 색인을 읽지 않는다").
 * 이 설정은 **프로필에 묶이지 않는다** - 조회 엔드포인트(조각 4)를 포함해 API 프로세스에서
 * 도 같은 색인을 쓰려면 worker 프로필 밖에서도 조립 가능한 자리가 있어야 하기 때문이다.
 *
 * **단일 적재, 두 스위치의 합집합(2026-09-06 정정, `docs/kotlin-redevelopment-backlog.md`
 * §1.1 「사전 색인이 API·worker 프로필 동시 기동 시 두 번 적재된다」 해결).** [dictionaryIndexHolder]
 * 가 [DictionaryLookupProperties.enabled](조회)와 [DictionaryProperties.enabled](worker
 * 프롬프트 주입) **둘 중 하나라도** 켜져 있으면 색인을 읽을 수 있게 [DictionaryIndexHolder]
 * 를 만든다. **`ConversionWorkerConfiguration.dictionaryContextSource` 는 이제 이 빈을
 * 그대로 소비한다** — 더 이상 자기 색인을 따로 읽지 않는다. API·worker 두 프로필을 한
 * 프로세스에 동시에 켜도 색인은 [DictionaryIndexHolder] 가 한 번만 읽고, 두 소비자(worker
 * 의 컨텍스트 주입, 여기의 조회)가 같은 인스턴스를 공유한다.
 *
 * **`@Bean` 이 즉석에서 읽으면 안 되는 이유 (2026-09-06 2차 정정).** 처음에는 이 조립 지점이
 * `@Bean fun dictionaryIndex(): DictionaryIndex?` 형태로 두 스위치의 합집합을 계산해 **그
 * 자리에서** 색인을 읽어 돌려줬다. 그런데 이 클래스는 `@Profile` 이 없어 항상 조립되고,
 * [DictionaryProperties.enabled] 의 기본값은 **켜짐**이다 — Spring 이 `@Bean` 메서드를
 * 조립 시점(`preInstantiateSingletons`)에 즉시 호출하는 성질과 맞물려 **API 전용
 * 컨텍스트조차** worker 스위치 기본값 때문에 조립 시점에 1.5MB 색인을 무조건 읽었다. 여러
 * `@SpringBootTest` 컨텍스트가 캐시돼 함께 떠 있는 `:api:test` 스위트에서 이 무조건 로드가
 * 겹쳐 힙을 실제로 고갈시켰다(`Java heap space`). [DictionaryIndexHolder] 가 실제 읽기를
 * `by lazy` 로 미루고, 두 소비자가 **자기 스위치가 켜졌을 때만** `indexOrNull()` 을 부르게
 * 바꿔 고쳤다 — [DictionaryIndexHolder] KDoc 참고.
 *
 * **두 스위치의 의미는 분리된 채로 남는다.** 합쳐진 것은 "적재 여부"뿐이다 — 조회를 껐어도
 * worker 주입이 켜져 있으면(또는 그 반대) 홀더의 `enabled` 는 참이 되지만, 각 소비자는 자기
 * 스위치를 **따로** 확인해 `indexOrNull()` 을 부를지 말지, 그리고 그 결과로 기능을 켜고
 * 끈다. [termCandidateSource] 는 [DictionaryLookupProperties.enabled] 를 직접 보고
 * 판단한다 — 공유 홀더 이후로는 조회가 꺼져 있어도 홀더가 non-null 을 돌려줄 수 있으므로,
 * `indexOrNull()` 의 결과만으로는 "조회 기능이 켜져 있는가"를 답할 수 없다.
 * `ConversionWorkerConfiguration.dictionaryContextSource` 도 같은 원칙으로
 * [DictionaryProperties.enabled] 를 직접 본다.
 *
 * 기본값이 꺼짐인 이유는 [DictionaryLookupProperties] KDoc 을 본다.
 *
 * 2026-09-05 리뷰 - 이 저장소에서 **첫 nullable `@Bean`** 이었다(지금은 [DictionaryIndexHolder]
 * 로 대체돼 `DictionaryIndex` 자체는 더 이상 `@Bean` 이 아니다). 소비자 쪽 nullable 배선은
 * 여전히 null object 로 흡수한다.
 *
 * **정리(2026-09-05, 조각 4).** [termCandidateSource] 가 그 null object 다 —
 * 조회가 꺼져 있으면(위 참고) [NoTermCandidateSource] 를 골라, 컨트롤러가 nullable 을
 * 직접 다루지 않는다.
 */
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
     * 422 를 거절한다(worker 주입만 켜져 홀더가 읽을 수 있는 상태여도 이 소비자는 읽지
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
