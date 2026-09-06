package kr.easydoc.api

import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.dictionary.DictionaryIndexHolder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 회귀 고정판 — 사전 조회·worker 주입 스위치가 **둘 다 꺼진** API 컨텍스트는 사전 색인을
 * 적재하면 안 된다(2026-09-06, `docs/kotlin-redevelopment-backlog.md` §1.1 2차 정정). 이
 * 규칙이 깨지면 `DictionaryConfiguration.dictionaryIndex` 가 `@Bean` 조립 시점에 즉석으로
 * 색인을 읽던 옛 결함(worker 스위치 기본값이 켜짐이라 API 전용 컨텍스트조차 1.5MB 색인을
 * 무조건 읽었다)이 되돌아온 것이다 — 그 결함은 `:api:test` 의 여러 캐시된 `@SpringBootTest`
 * 컨텍스트가 겹쳐 힙을 실제로 고갈시켰다(`Java heap space`).
 *
 * ## 이 테스트가 규칙을 고정하는 방법
 *
 * [FailFastDictionaryIndexHolderConfiguration] 가 진짜 `DictionaryConfiguration.dictionaryIndexHolder`
 * 빈을 `@Primary` 로 밀어내 대신 주입된다 — `enabled = true`(항상 읽을 수 있는 상태)이지만
 * `loader` 가 불리면 즉시 실패한다. 이 컨텍스트의 어떤 소비자([DictionaryConfiguration
 * .termCandidateSource] 등)도 `indexOrNull()` 을 실제로 부르지 않아야 정상이므로(두 스위치
 * 모두 꺼짐), **컨텍스트 기동이 성공적으로 끝나는 것 자체가 이 테스트의 단언**이다 — 누군가
 * 스위치 확인 없이 `indexOrNull()` 을 불러 버리면 기동 자체가 그 자리에서 실패해 즉시 드러난다.
 * `worker` 프로필을 켜지 않아 `ConversionWorkerConfiguration` 은 아예 조립되지 않는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "easydoc.dictionary.lookup.enabled=false",
        "easydoc.dictionary.enabled=false",
    ],
)
class DictionaryIndexNotLoadedWhenDisabledTest {
    @Test
    @DisplayName("두 스위치가 모두 꺼지면 API 컨텍스트가 사전 색인을 적재하지 않고 정상 기동한다")
    fun `두 스위치가 꺼지면 기동에 성공한다`() {
        // 컨텍스트가 이미 성공적으로 떴다(그렇지 않았다면 이 테스트 메서드에 들어오지도
        // 못했을 것이다) — FailFastDictionaryIndexHolderConfiguration 의 loader 가 한 번도 안 불렸다는 뜻이다.
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FailFastDictionaryIndexHolderConfiguration {
        @Bean
        @Primary
        fun primaryDictionaryIndexHolder(): DictionaryIndexHolder =
            DictionaryIndexHolder(enabled = true) { error("API 컨텍스트가 사전 색인을 적재했다") }
    }

    companion object {
        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("dictionary_index_not_loaded")
        }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}
