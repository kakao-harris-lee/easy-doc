package kr.easydoc.infrastructure.subscription

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.convert.support.DefaultConversionService
import java.time.Duration

class TossBillingPropertiesTest {
    @Test
    fun `defaults preserve the existing billing timings`() {
        val properties = TossBillingProperties()

        assertThat(properties.connectTimeout).isEqualTo(Duration.ofSeconds(10))
        assertThat(properties.requestTimeout).isEqualTo(Duration.ofSeconds(70))
        assertThat(properties.sessionTtl).isEqualTo(Duration.ofSeconds(600))
        assertThat(properties.retryInterval).isEqualTo(Duration.ofSeconds(30))
        assertThat(properties.retryWindow).isEqualTo(Duration.ofDays(14))
    }

    @Test
    fun `duration fields bind as typed values`() {
        val properties =
            bind(
                mapOf(
                    "easydoc.payment.toss.connect-timeout" to "11s",
                    "easydoc.payment.toss.request-timeout" to "71s",
                    "easydoc.payment.toss.session-ttl" to "601s",
                    "easydoc.payment.toss.retry-interval" to "31s",
                    "easydoc.payment.toss.retry-window" to "15d",
                ),
            )

        assertThat(properties.connectTimeout).isEqualTo(Duration.ofSeconds(11))
        assertThat(properties.requestTimeout).isEqualTo(Duration.ofSeconds(71))
        assertThat(properties.sessionTtl).isEqualTo(Duration.ofSeconds(601))
        assertThat(properties.retryInterval).isEqualTo(Duration.ofSeconds(31))
        assertThat(properties.retryWindow).isEqualTo(Duration.ofDays(15))
    }

    @Test
    fun `non-positive timings are rejected`() {
        assertThatThrownBy { TossBillingProperties(connectTimeout = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TossBillingProperties(requestTimeout = Duration.ofSeconds(-1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TossBillingProperties(sessionTtl = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TossBillingProperties(retryInterval = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TossBillingProperties(retryWindow = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun bind(values: Map<String, String>): TossBillingProperties {
        val sources: List<ConfigurationPropertySource> = listOf(MapConfigurationPropertySource(values))
        val binder = Binder(sources, null, DefaultConversionService())
        return binder
            .bind("easydoc.payment.toss", Bindable.of(TossBillingProperties::class.java))
            .get()
    }
}
