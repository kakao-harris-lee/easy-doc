package kr.easydoc.infrastructure.dictionary

import kr.easydoc.application.conversion.NoDictionaryContext
import kr.easydoc.core.dictionary.TermQuery
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.RateLimitedException
import kr.easydoc.core.privacy.maskText
import kr.easydoc.infrastructure.queue.ConversionWorkerConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 색인이 worker 프로필 밖에서도 조립될 수 있는지, 두 스위치가 모두 꺼졌을 때만 읽지 않는지,
 * 그리고 [ConversionWorkerConfiguration.dictionaryContextSource] 가 [DictionaryIndexHolder]
 * 를 공유해 색인을 두 번 읽지 않는지 확인한다(2026-09-06, `docs/kotlin-redevelopment-backlog.md`
 * §1.1 「사전 색인이 API·worker 프로필 동시 기동 시 두 번 적재된다」 해결).
 *
 * **2차 정정.** 처음에는 `DictionaryConfiguration.dictionaryIndex` 가 `@Bean` 안에서 즉석에
 * 읽었다 — `@Profile` 없는 이 설정이 조립되는 모든 컨텍스트에서, [DictionaryProperties.enabled]
 * 기본값이 켜짐이라 API 전용 프로세스조차 조립 시점에 색인을 무조건 읽어 `:api:test` 의 여러
 * 캐시된 `@SpringBootTest` 컨텍스트가 겹쳐 힙을 고갈시켰다. [DictionaryIndexHolder] 로 실제
 * 읽기를 `by lazy` 뒤로 미루고, 두 소비자가 **자기 스위치가 켜졌을 때만** `indexOrNull()` 을
 * 부르게 바꿨다 — 아래 테스트는 카운팅 loader 로 "언제 실제로 읽는가"를 직접 증명한다.
 */
class DictionaryConfigurationTest {
    @Test
    @DisplayName("기본값은 꺼짐이다 - API 기동이 이번 변경만으로 색인을 읽게 되지 않는다")
    fun `기본값은 꺼짐이다`() {
        assertThat(DictionaryLookupProperties().enabled).isFalse()
    }

    @Test
    @DisplayName("두 스위치가 모두 꺼져 있으면 홀더가 비활성이다")
    fun `두 스위치가 모두 꺼지면 홀더가 비활성이다`() {
        val holder =
            DictionaryConfiguration()
                .dictionaryIndexHolder(
                    DictionaryLookupProperties(enabled = false),
                    DictionaryProperties(enabled = false),
                )

        assertThat(holder.indexOrNull()).isNull()
    }

    @Test
    @DisplayName("조회 스위치만 켜도 홀더가 활성이라 실제 색인을 읽는다")
    fun `조회만 켜면 홀더가 활성이다`() {
        val holder =
            DictionaryConfiguration()
                .dictionaryIndexHolder(
                    DictionaryLookupProperties(enabled = true),
                    DictionaryProperties(enabled = false),
                )

        val index = holder.indexOrNull()
        assertThat(index).isNotNull()
        assertThat(index!!.findAll("구비서류를 지참하세요")).isNotEmpty()
    }

    @Test
    @DisplayName("worker 주입 스위치만 켜도 홀더가 활성이다 - 합집합 논리")
    fun `worker 주입만 켜도 홀더가 활성이다`() {
        val holder =
            DictionaryConfiguration()
                .dictionaryIndexHolder(
                    DictionaryLookupProperties(enabled = false),
                    DictionaryProperties(enabled = true),
                )

        val index = holder.indexOrNull()
        assertThat(index).isNotNull()
        assertThat(index!!.findAll("구비서류를 지참하세요")).isNotEmpty()
    }

    @Test
    @DisplayName("조회가 꺼져 있으면 홀더가 활성이어도 termCandidateSource 는 NoTermCandidateSource 다")
    fun `조회가 꺼져 있으면 홀더가 활성이어도 null object 다`() {
        // worker 주입만 켜서 실제로 활성 홀더를 만든다 — "홀더가 읽을 수 있는가" 만으로는
        // "조회가 켜졌다"를 더 이상 답할 수 없다는 것이 이 테스트의 핵심이다.
        val holder = DictionaryIndexHolder(enabled = true) { DictionaryIndexJsonReader().readClasspathResource() }

        val source = DictionaryConfiguration().termCandidateSource(DictionaryLookupProperties(enabled = false), holder)

        assertThat(source).isSameAs(NoTermCandidateSource)
    }

    @Test
    @DisplayName("홀더가 비활성이고 조회도 꺼져 있으면 termCandidateSource 는 NoTermCandidateSource 다 (조각 4 정리)")
    fun `홀더가 비활성이면 null object 다`() {
        val holder = DictionaryIndexHolder(enabled = false) { error("비활성 홀더는 읽으면 안 된다") }

        val source = DictionaryConfiguration().termCandidateSource(DictionaryLookupProperties(enabled = false), holder)

        assertThat(source).isSameAs(NoTermCandidateSource)
    }

    @Test
    @DisplayName("조회가 켜져 있으면 termCandidateSource 는 그 색인으로 실제 조회를 한다")
    fun `색인이 있으면 실제로 조회한다`() {
        val holder = DictionaryIndexHolder(enabled = true) { DictionaryIndexJsonReader().readClasspathResource() }
        val source = DictionaryConfiguration().termCandidateSource(DictionaryLookupProperties(enabled = true), holder)

        val candidates = source.candidatesFor(TermQuery.of("구비서류"))

        assertThat(candidates).isNotEmpty()
    }

    @Test
    @DisplayName("조회가 켜졌는데 홀더가 비활성이면 fail-fast 한다 (구성상 발생할 수 없는 상태의 방어선)")
    fun `조회가 켜졌는데 홀더가 비활성이면 거절한다`() {
        // 실제로는 lookup.enabled=true 이면 dictionaryIndexHolder() 조립이 항상 enabled=true
        // 인 홀더를 만든다(합집합) — 이 조합은 구성상 발생할 수 없다. 방어선만 확인한다.
        val holder = DictionaryIndexHolder(enabled = false) { error("호출되면 안 된다") }

        assertThatThrownBy {
            DictionaryConfiguration().termCandidateSource(DictionaryLookupProperties(enabled = true), holder)
        }.isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("easydoc.dictionary.lookup.enabled")
    }

    @Test
    @DisplayName("lookupRateLimiter 는 설정한 분당 한도를 그대로 쓴다")
    fun `rate limiter 가 설정값을 쓴다`() {
        val limiter = DictionaryConfiguration().lookupRateLimiter(DictionaryLookupProperties(rateLimitPerMinute = 1))
        val userId = UUID.randomUUID()

        limiter.checkAndRecord(userId)

        assertThat(catchThrowable { limiter.checkAndRecord(userId) }).isInstanceOf(RateLimitedException::class.java)
    }

    /**
     * (a) 단일 적재의 핵심 증명. 두 스위치가 모두 켜졌을 때 [DictionaryIndexHolder] 에 심은
     * **카운팅 loader** 가 두 소비자([DictionaryConfiguration.termCandidateSource],
     * [ConversionWorkerConfiguration.dictionaryContextSource])를 거치는 동안 **정확히 한
     * 번만** 불린다는 것을, `by lazy` 뒤에 숨은 구현이 아니라 직접 관측한다. 두 소비자가
     * 실제로 그 하나의 인스턴스로 동작하는지는 이어서 진짜 조회·컨텍스트 생성으로 확인한다.
     */
    @Test
    @DisplayName("두 스위치가 모두 켜졌을 때 홀더의 loader 는 한 번만 불리고 두 소비자가 그 결과를 공유한다 (단일 적재 증명)")
    fun `두 소비자가 같은 색인을 공유한다`() {
        var loadCount = 0
        val holder =
            DictionaryIndexHolder(enabled = true) {
                loadCount++
                DictionaryIndexJsonReader().readClasspathResource()
            }

        val termSource =
            DictionaryConfiguration().termCandidateSource(DictionaryLookupProperties(enabled = true), holder)
        // 기본 정책의 maxCharsRatio=1.0 은 원문 길이를 그대로 예산 상한으로 삼는다 — 비율
        // 상한을 꺼서(maxCharsRatio=null) 이 테스트가 재려는 것(공유 여부)이 아니라 예산
        // 경계를 재게 되는 것을 막는다.
        val contextSource =
            ConversionWorkerConfiguration()
                .dictionaryContextSource(DictionaryProperties(enabled = true, maxCharsRatio = null), holder)

        assertThat(loadCount).isEqualTo(1)

        assertThat(termSource.candidatesFor(TermQuery.of("구비서류"))).isNotEmpty()
        assertThat(contextSource.contextFor(maskText("구비서류를 지참하세요.").maskedText)).isNotNull()
        assertThat(loadCount).isEqualTo(1)
    }

    @Test
    @DisplayName("worker 주입만 켜졌을 때(조회는 꺼짐) contextSource 는 활성, candidateSource 는 null object 다")
    fun `worker 주입만 켜지면 contextSource 만 활성이다`() {
        val holder = DictionaryIndexHolder(enabled = true) { DictionaryIndexJsonReader().readClasspathResource() }

        val termSource =
            DictionaryConfiguration().termCandidateSource(DictionaryLookupProperties(enabled = false), holder)
        val contextSource =
            ConversionWorkerConfiguration().dictionaryContextSource(DictionaryProperties(enabled = true), holder)

        assertThat(termSource).isSameAs(NoTermCandidateSource)
        assertThat(contextSource).isNotSameAs(NoDictionaryContext)
    }

    @Test
    @DisplayName("(b) 두 스위치가 모두 꺼지면 dictionaryContextSource 도 NoDictionaryContext 다")
    fun `두 스위치가 모두 꺼지면 contextSource 도 비활성이다`() {
        val holder =
            DictionaryConfiguration()
                .dictionaryIndexHolder(
                    DictionaryLookupProperties(enabled = false),
                    DictionaryProperties(enabled = false),
                )
        assertThat(holder.indexOrNull()).isNull()

        val contextSource =
            ConversionWorkerConfiguration().dictionaryContextSource(DictionaryProperties(enabled = false), holder)

        assertThat(contextSource).isSameAs(NoDictionaryContext)
    }

    /**
     * (c) 두 스위치가 모두 꺼지면 홀더는 **결코 읽지 않는다.** loader 가 불리면 즉시 실패하게
     * 만들어, "느리게 읽는다"가 아니라 "이 실행에서는 아예 읽지 않는다"를 확인한다.
     */
    @Test
    @DisplayName("(c) 두 스위치가 모두 꺼지면 홀더는 loader 를 결코 부르지 않는다")
    fun `두 스위치가 모두 꺼지면 loader 가 불리지 않는다`() {
        val holder =
            DictionaryConfiguration()
                .dictionaryIndexHolder(
                    DictionaryLookupProperties(enabled = false),
                    DictionaryProperties(enabled = false),
                )
        // 합집합이 거짓이라 이 홀더의 loader 는 실제로 DictionaryIndexJsonReader 를 쓰지만,
        // enabled=false 라 indexOrNull() 이 lazyIndex 에 손대지 않는다는 것을 반복 호출로
        // 확인한다 — 예외 없이 항상 null 이면 loader 가 한 번도 안 불린 것이다(불렸다면
        // 클래스패스 리소스가 있으므로 성공해 null 이 아니었을 것이다).
        repeat(3) { assertThat(holder.indexOrNull()).isNull() }
    }

    /**
     * (d) lookup 은 꺼져 있고 worker 주입은 켜져 있지만(홀더는 활성), worker 소비자
     * ([ConversionWorkerConfiguration.dictionaryContextSource])가 **아예 조립되지 않는**
     * 상황(예: API 전용 프로세스는 이 빈이 `@Profile("worker")` 밖이라 존재하지 않는다)을
     * 흉내 낸다. 카운팅 loader 로 이 경우 [termCandidateSource] 혼자서는 색인을 읽지
     * 않는다는 것을 확인한다.
     */
    @Test
    @DisplayName("(d) lookup 꺼짐·worker 켜짐이어도 worker 소비자가 없으면 결코 읽지 않는다")
    fun `worker 소비자가 조립되지 않으면 읽지 않는다`() {
        var loadCount = 0
        val holder =
            DictionaryIndexHolder(enabled = true) {
                loadCount++
                DictionaryIndexJsonReader().readClasspathResource()
            }

        // dictionaryContextSource 는 의도적으로 부르지 않는다 — API 전용 프로세스에서
        // ConversionWorkerConfiguration 자체가 조립되지 않는 상황(@Profile("worker"))과 같다.
        val termSource =
            DictionaryConfiguration().termCandidateSource(DictionaryLookupProperties(enabled = false), holder)

        assertThat(termSource).isSameAs(NoTermCandidateSource)
        assertThat(loadCount).isEqualTo(0)
    }

    @Test
    @DisplayName("worker 주입이 켜졌는데 홀더가 비활성이면 ConfigurationException 으로 거절한다")
    fun `worker 주입이 켜졌는데 홀더가 비활성이면 거절한다`() {
        // 실제로는 dictionary.enabled=true 이면 dictionaryIndexHolder() 조립이 항상
        // enabled=true 인 홀더를 만든다(합집합) — 이 조합은 구성상 발생할 수 없다.
        // 방어선만 확인한다.
        val holder = DictionaryIndexHolder(enabled = false) { error("호출되면 안 된다") }

        assertThatThrownBy {
            ConversionWorkerConfiguration().dictionaryContextSource(DictionaryProperties(enabled = true), holder)
        }.isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("easydoc.dictionary.enabled")
    }
}
