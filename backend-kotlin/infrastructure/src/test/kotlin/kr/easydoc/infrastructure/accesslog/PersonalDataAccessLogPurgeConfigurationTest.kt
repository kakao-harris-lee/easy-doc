package kr.easydoc.infrastructure.accesslog

import kr.easydoc.core.exceptions.ConfigurationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Period

/**
 * [PersonalDataAccessLogPurgeConfiguration]의 보관기간 하한 기동 자기점검 —
 * `SignupGrantRecordPurgeConfigurationTest`와 같은 자리: Spring 컨텍스트 없이 `@Bean`
 * 메서드를 평범한 함수처럼 직접 부른다.
 */
class PersonalDataAccessLogPurgeConfigurationTest {
    private val configuration = PersonalDataAccessLogPurgeConfiguration()

    @Test
    @DisplayName("보관기간이 법정 최소(1년) 미만이면 기동을 막는다")
    fun `보관기간 하한 위반은 ConfigurationException 이다`() {
        val properties = AccessLogProperties(retention = Period.ofMonths(11))

        assertThatThrownBy {
            configuration.personalDataAccessLogPurgePolicy(properties)
        }.isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("easydoc.access-log.retention")
            .hasMessageNotContaining("purge-batch-size")
    }

    @Test
    @DisplayName("배치 크기가 1보다 작으면 기동을 막고, retention 문제로 오진단하지 않는다")
    fun `배치 크기 위반은 ConfigurationException 이고 retention 을 언급하지 않는다`() {
        val properties = AccessLogProperties(purgeBatchSize = 0)

        assertThatThrownBy {
            configuration.personalDataAccessLogPurgePolicy(properties)
        }.isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("purge-batch-size")
            .hasMessageNotContaining("retention")
            .hasMessageNotContaining("P1Y 이상")
    }

    @Test
    @DisplayName("기본값(P1Y, enabled=true, batchSize=200)은 정상적으로 기동된다")
    fun `기본값은 기동된다`() {
        val properties = AccessLogProperties()

        assertThatCode {
            configuration.personalDataAccessLogPurgePolicy(properties)
        }.doesNotThrowAnyException()

        val policy = configuration.personalDataAccessLogPurgePolicy(properties)
        assertThat(policy.enabled).isTrue()
        assertThat(policy.batchSize).isEqualTo(DEFAULT_BATCH_SIZE)
    }

    @Test
    @DisplayName("P1Y 는 통과한다")
    fun `P1Y 는 통과한다`() {
        val properties = AccessLogProperties(retention = Period.ofYears(1))

        assertThatCode {
            configuration.personalDataAccessLogPurgePolicy(properties)
        }.doesNotThrowAnyException()
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE: Int = 200
    }
}
