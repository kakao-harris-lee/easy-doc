package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.subscription.PaymentEnvironments
import kr.easydoc.application.subscription.TossCallbackUrl
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.app.AppProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.util.UUID

class PaymentConfigurationTest {
    private val configuration = SubscriptionConfiguration()
    private val productionApp = AppProperties("https://easydoc.kr")
    private val live =
        PaymentProperties(
            provider = PaymentEnvironments.TOSS_LIVE,
            tossClientKey = Secret("live_ck_fixture"),
            tossSecretKey = Secret("live_sk_fixture"),
            purchaseEnabled = true,
            autoChargeEnabled = true,
        )

    private fun environment(vararg profiles: String) = MockEnvironment().apply { setActiveProfiles(*profiles) }

    @Test
    fun `production combined with local or test is rejected as a profile problem`() {
        listOf("local", "test").forEach { profile ->
            assertThatThrownBy {
                configuration.paymentGateway(live, environment("worker", "production", profile), productionApp)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("spring.profiles.active")
                .hasMessageContaining("production cannot be combined with local/test profiles")
                .hasMessageContaining("profiles=worker,production,$profile")
                .hasMessageNotContaining("easydoc.payment.provider")
                .hasMessageNotContaining("live_ck_fixture")
                .hasMessageNotContaining("live_sk_fixture")
        }
    }

    @Test
    fun `live settings are validated without the production profile too`() {
        assertThat(configuration.paymentGateway(live, environment("api"), productionApp))
            .isInstanceOf(StubPaymentGateway::class.java)

        listOf(
            live.copy(mockEnabled = true) to "easydoc.payment.mock-enabled",
            live.copy(tossClientKey = Secret("test_ck_fixture")) to "easydoc.payment.toss-client-key",
            live.copy(tossSecretKey = Secret("test_sk_fixture")) to "easydoc.payment.toss-secret-key",
        ).forEach { (properties, property) ->
            assertThatThrownBy {
                configuration.paymentGateway(properties, environment("api"), productionApp)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining(property)
                .hasMessageNotContaining("live_ck_fixture")
                .hasMessageNotContaining("live_sk_fixture")
                .hasMessageNotContaining("test_ck_fixture")
                .hasMessageNotContaining("test_sk_fixture")
        }

        assertThatThrownBy {
            configuration.paymentGateway(live, environment("api"), AppProperties("http://easydoc.kr"))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("easydoc.app.public-base-url")
            .hasMessageNotContaining("http://easydoc.kr")
    }

    @Test
    fun `live billing rejects a configured allowed workspace list without echoing the ids`() {
        val workspace = UUID.randomUUID()
        listOf(arrayOf("production"), arrayOf("api")).forEach { profiles ->
            assertThatThrownBy {
                configuration.paymentGateway(
                    live.copy(allowedWorkspaces = setOf(workspace)),
                    environment(*profiles),
                    productionApp,
                )
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("easydoc.payment.allowed-workspaces")
                .hasMessageNotContaining(workspace.toString())
        }

        assertThat(
            configuration.paymentGateway(
                PaymentProperties(
                    provider = PaymentEnvironments.TOSS_TEST,
                    tossClientKey = Secret("test_ck_fixture"),
                    tossSecretKey = Secret("test_sk_fixture"),
                    allowedWorkspaces = setOf(workspace),
                ),
                environment("local"),
                AppProperties(),
            ),
        ).isInstanceOf(StubPaymentGateway::class.java)
    }

    @Test
    fun `the guard reads the same callback URL the API hands out`() {
        assertThat(TossCallbackUrl.from(productionApp.publicBaseUrl))
            .isEqualTo("https://easydoc.kr" + TossCallbackUrl.PATH)
        assertThat(TossCallbackUrl.from("https://easydoc.kr/")).isEqualTo("https://easydoc.kr" + TossCallbackUrl.PATH)
        assertThat(configuration.paymentGateway(live, environment("production"), AppProperties("https://easydoc.kr/")))
            .isInstanceOf(StubPaymentGateway::class.java)
    }

    @Test
    fun `local and test reject live billing before gateway construction`() {
        listOf("local", "test").forEach { profile ->
            assertThatThrownBy {
                configuration.paymentGateway(live, environment(profile), AppProperties())
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining(profile)
                .hasMessageContaining("easydoc.payment.provider")
                .hasMessageNotContaining("live_ck_fixture")
                .hasMessageNotContaining("live_sk_fixture")
        }
    }

    @Test
    fun `production accepts only complete live HTTPS non mock configuration`() {
        assertThat(configuration.paymentGateway(live, environment("production"), productionApp))
            .isInstanceOf(StubPaymentGateway::class.java)

        val invalid =
            listOf(
                live.copy(provider = PaymentEnvironments.STUB),
                live.copy(provider = PaymentEnvironments.TOSS_TEST),
                live.copy(mockEnabled = true),
                live.copy(tossClientKey = Secret("test_ck_fixture")),
                live.copy(tossSecretKey = Secret.EMPTY),
            )
        invalid.forEach { properties ->
            assertThatThrownBy {
                configuration.paymentGateway(properties, environment("production"), productionApp)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("production")
                .hasMessageNotContaining("live_ck_fixture")
                .hasMessageNotContaining("live_sk_fixture")
                .hasMessageNotContaining("test_ck_fixture")
        }
    }

    @Test
    fun `production rejects insecure or credential bearing public and callback URLs without echoing them`() {
        listOf(
            "http://easydoc.kr",
            "https://localhost:5173",
            "https://127.0.0.1",
            "https://easydoc.kr/?resume=secret-callback-token",
        ).forEach { url ->
            assertThatThrownBy {
                configuration.paymentGateway(live, environment("production"), AppProperties(url))
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("easydoc.app.public-base-url")
                .hasMessageNotContaining(url)
                .hasMessageNotContaining("secret-callback-token")
        }
    }

    @Test
    fun `production rejects non root public URL paths without echoing them`() {
        val url = "https://easydoc.kr/app"
        assertThatThrownBy {
            configuration.paymentGateway(live, environment("production"), AppProperties(url))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("easydoc.app.public-base-url")
            .hasMessageNotContaining(url)

        configuration.paymentGateway(live, environment("production"), AppProperties("https://easydoc.kr/"))
    }

    @Test
    fun `production rejects localhost variants and non public IP literals without DNS or raw URL errors`() {
        listOf(
            "https://LOCALHOST.",
            "https://service.localhost",
            "https://localhost.localdomain",
            "https://127.0.0.2",
            "https://10.0.0.1",
            "https://172.16.0.1",
            "https://192.168.0.1",
            "https://169.254.0.1",
            "https://0.0.0.0",
            "https://224.0.0.1",
            "https://[::1]",
            "https://[::]",
            "https://[fc00::1]",
            "https://[fec0::1]",
            "https://[fe80::1]",
            "https://[ff02::1]",
        ).forEach { url ->
            assertThatThrownBy {
                configuration.paymentGateway(live, environment("production"), AppProperties(url))
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("easydoc.app.public-base-url")
                .hasMessageNotContaining(url)
        }
    }

    @Test
    fun `production accepts an ordinary public domain independent of case and trailing dot`() {
        assertThat(
            configuration.paymentGateway(live, environment("production"), AppProperties("HTTPS://EASYDOC.KR.")),
        ).isInstanceOf(StubPaymentGateway::class.java)
    }

    @Test
    fun `local stub and explicit local mock remain available`() {
        assertThat(configuration.paymentGateway(PaymentProperties(), environment("local"), AppProperties()))
            .isInstanceOf(StubPaymentGateway::class.java)
        assertThat(
            configuration.paymentGateway(
                PaymentProperties(mockEnabled = true),
                environment("local"),
                AppProperties(),
            ),
        ).isInstanceOf(StubPaymentGateway::class.java)
    }

    @Test
    fun `live requires individual live keys with charging closed by default`() {
        val properties = live.copy(purchaseEnabled = false, autoChargeEnabled = false)
        val environment = environment("production")
        configuration.paymentGateway(properties, environment, productionApp)
        assertThat(properties.purchaseEnabled).isFalse()
        assertThat(properties.autoChargeEnabled).isFalse()
        listOf("test_sk_fixture", "live_gsk_fixture", "").forEach { key ->
            assertThatThrownBy {
                configuration.paymentGateway(
                    properties.copy(tossSecretKey = Secret(key)),
                    environment,
                    productionApp,
                )
            }.isInstanceOf(IllegalStateException::class.java)
        }
        assertThatThrownBy {
            configuration.paymentGateway(properties.copy(mockEnabled = true), environment, productionApp)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `mock enabled requires local or test and rejects production even with local`() {
        listOf(emptyArray(), arrayOf("production"), arrayOf("local", "prod")).forEach { profiles ->
            val environment = environment(*profiles)
            assertThatThrownBy {
                configuration.paymentGateway(PaymentProperties(mockEnabled = true), environment, AppProperties())
            }.isInstanceOf(IllegalStateException::class.java)
        }
        assertThat(
            configuration.paymentGateway(
                PaymentProperties(mockEnabled = true),
                environment("local"),
                AppProperties(),
            ),
        ).isInstanceOf(StubPaymentGateway::class.java)
    }

    @Test
    fun `unimplemented toss configuration never silently falls back to fake charging`() {
        assertThatThrownBy {
            configuration.paymentGateway(PaymentProperties(provider = "toss"), environment(), AppProperties())
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `Toss test requires matching individual test key prefixes and rejects production`() {
        val local = environment("local")
        val test =
            PaymentProperties(
                provider = PaymentEnvironments.TOSS_TEST,
                tossClientKey = Secret("test_ck_fixture"),
                tossSecretKey = Secret("test_sk_fixture"),
            )
        configuration.paymentGateway(test, local, AppProperties())
        listOf("live_sk_fixture", "test_gsk_fixture", "").forEach { secret ->
            assertThatThrownBy {
                configuration.paymentGateway(
                    test.copy(tossSecretKey = Secret(secret)),
                    local,
                    AppProperties(),
                )
            }.isInstanceOf(IllegalStateException::class.java)
        }
        assertThatThrownBy {
            configuration.paymentGateway(test, environment("local", "production"), AppProperties())
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
