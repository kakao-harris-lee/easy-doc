package kr.easydoc.infrastructure.dictionary

import kr.easydoc.core.dictionary.DictionaryIndex
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
 */
@Configuration(proxyBeanMethods = false)
class DictionaryConfiguration {
    @Bean
    fun dictionaryIndex(properties: DictionaryLookupProperties): DictionaryIndex? =
        if (properties.enabled) DictionaryIndexJsonReader().readClasspathResource() else null
}
