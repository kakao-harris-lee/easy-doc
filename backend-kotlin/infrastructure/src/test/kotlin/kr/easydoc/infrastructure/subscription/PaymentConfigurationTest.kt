package kr.easydoc.infrastructure.subscription

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class PaymentConfigurationTest {
    private val configuration = SubscriptionConfiguration()

    @Test
    fun `live requires individual live keys with charging closed by default`() {
        val properties =
            PaymentProperties(
                provider = "toss_live",
                tossClientKey =
                    kr.easydoc.core.security
                        .Secret("live_ck_fixture"),
                tossSecretKey =
                    kr.easydoc.core.security
                        .Secret("live_sk_fixture"),
            )
        val environment = MockEnvironment().apply { setActiveProfiles("production") }
        configuration.paymentGateway(properties, environment)
        assertThat(properties.purchaseEnabled).isFalse()
        assertThat(properties.autoChargeEnabled).isFalse()
        listOf("test_sk_fixture", "live_gsk_fixture", "").forEach { key ->
            assertThatThrownBy {
                configuration.paymentGateway(
                    properties.copy(
                        tossSecretKey =
                            kr.easydoc.core.security
                                .Secret(key),
                    ),
                    environment,
                )
            }.isInstanceOf(IllegalStateException::class.java)
        }
        assertThatThrownBy {
            configuration.paymentGateway(properties.copy(mockEnabled = true), environment)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `mock enabled requires local or test and rejects production even with local`() {
        listOf(emptyArray(), arrayOf("production"), arrayOf("local", "prod")).forEach { profiles ->
            val environment = MockEnvironment().apply { setActiveProfiles(*profiles) }
            assertThatThrownBy { configuration.paymentGateway(PaymentProperties(mockEnabled = true), environment) }
                .isInstanceOf(IllegalStateException::class.java)
        }
        assertThat(
            configuration.paymentGateway(
                PaymentProperties(mockEnabled = true),
                MockEnvironment().apply {
                    setActiveProfiles("local")
                },
            ),
        ).isInstanceOf(StubPaymentGateway::class.java)
    }

    @Test
    fun `unimplemented toss configuration never silently falls back to fake charging`() {
        assertThatThrownBy { configuration.paymentGateway(PaymentProperties(provider = "toss"), MockEnvironment()) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `Toss test requires matching individual test key prefixes and rejects production`() {
        val local = MockEnvironment().apply { setActiveProfiles("local") }
        val test =
            PaymentProperties(
                provider = "toss_test",
                tossClientKey =
                    kr.easydoc.core.security
                        .Secret("test_ck_fixture"),
                tossSecretKey =
                    kr.easydoc.core.security
                        .Secret("test_sk_fixture"),
            )
        configuration.paymentGateway(test, local)
        listOf("live_sk_fixture", "test_gsk_fixture", "").forEach { secret ->
            assertThatThrownBy {
                configuration.paymentGateway(
                    test.copy(
                        tossSecretKey =
                            kr.easydoc.core.security
                                .Secret(secret),
                    ),
                    local,
                )
            }.isInstanceOf(IllegalStateException::class.java)
        }
        assertThatThrownBy {
            configuration.paymentGateway(test, MockEnvironment().apply { setActiveProfiles("local", "production") })
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
