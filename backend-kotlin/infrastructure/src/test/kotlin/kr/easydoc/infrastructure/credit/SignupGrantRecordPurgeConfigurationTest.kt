package kr.easydoc.infrastructure.credit

import kr.easydoc.core.exceptions.ConfigurationException
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [SignupGrantRecordPurgeConfiguration] 의 TTL 파싱 기동 자기점검 —
 * `CreditAccountConfigurationTest` 와 같은 자리: Spring 컨텍스트 없이 `@Bean` 메서드를
 * 평범한 함수처럼 직접 부른다.
 */
class SignupGrantRecordPurgeConfigurationTest {
    private val configuration = SignupGrantRecordPurgeConfiguration()

    @Test
    @DisplayName("잘못된 Period 형식은 기동을 막는다")
    fun `TTL 형식이 잘못되면 기동이 실패한다`() {
        val properties = CreditsProperties(signupGrantRecordTtl = "not-a-period")

        assertThatThrownBy {
            configuration.signupGrantRecordPurgePolicy(properties)
        }.isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("signup-grant-record-ttl")
    }

    @Test
    @DisplayName("기본값 P2Y 는 정상적으로 기동된다")
    fun `기본값은 기동된다`() {
        val properties = CreditsProperties()

        assertThatCode {
            configuration.signupGrantRecordPurgePolicy(properties)
        }.doesNotThrowAnyException()
    }
}
