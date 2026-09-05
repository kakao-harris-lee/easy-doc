package kr.easydoc.infrastructure.dictionary

import kr.easydoc.core.dictionary.TermQuery
import kr.easydoc.core.exceptions.RateLimitedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/** 색인이 worker 프로필 밖에서도 조립될 수 있는지, 꺼져 있으면 읽지 않는지 확인한다. */
class DictionaryConfigurationTest {
    @Test
    @DisplayName("기본값은 꺼짐이다 - API 기동이 이번 변경만으로 색인을 읽게 되지 않는다")
    fun `기본값은 꺼짐이다`() {
        assertThat(DictionaryLookupProperties().enabled).isFalse()
    }

    @Test
    @DisplayName("꺼져 있으면 null 이다 - 빈이 등록되지 않아 색인을 읽지 않는다")
    fun `꺼져 있으면 적재하지 않는다`() {
        val index = DictionaryConfiguration().dictionaryIndex(DictionaryLookupProperties(enabled = false))

        assertThat(index).isNull()
    }

    @Test
    @DisplayName("켜면 실제 색인을 적재한다")
    fun `켜면 적재한다`() {
        val index = DictionaryConfiguration().dictionaryIndex(DictionaryLookupProperties(enabled = true))

        assertThat(index).isNotNull()
        assertThat(index!!.findAll("구비서류를 지참하세요")).isNotEmpty()
    }

    @Test
    @DisplayName("색인이 없으면 termCandidateSource 는 NoTermCandidateSource 다 (조각 4 정리)")
    fun `색인이 없으면 null object 다`() {
        val source = DictionaryConfiguration().termCandidateSource(dictionaryIndex = null)

        assertThat(source).isSameAs(NoTermCandidateSource)
    }

    @Test
    @DisplayName("색인이 있으면 termCandidateSource 는 그 색인으로 실제 조회를 한다")
    fun `색인이 있으면 실제로 조회한다`() {
        val index = DictionaryConfiguration().dictionaryIndex(DictionaryLookupProperties(enabled = true))
        val source = DictionaryConfiguration().termCandidateSource(index)

        val candidates = source.candidatesFor(TermQuery.of("구비서류"))

        assertThat(candidates).isNotEmpty()
    }

    @Test
    @DisplayName("lookupRateLimiter 는 설정한 분당 한도를 그대로 쓴다")
    fun `rate limiter 가 설정값을 쓴다`() {
        val limiter = DictionaryConfiguration().lookupRateLimiter(DictionaryLookupProperties(rateLimitPerMinute = 1))
        val userId = UUID.randomUUID()

        limiter.checkAndRecord(userId)

        assertThat(catchThrowable { limiter.checkAndRecord(userId) }).isInstanceOf(RateLimitedException::class.java)
    }
}
