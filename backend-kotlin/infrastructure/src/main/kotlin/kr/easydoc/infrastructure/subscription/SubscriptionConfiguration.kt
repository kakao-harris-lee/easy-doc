package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.subscription.PaymentEnvironments
import kr.easydoc.application.subscription.PaymentGateway
import kr.easydoc.application.subscription.SubscriptionService
import kr.easydoc.application.subscription.SubscriptionStore
import kr.easydoc.infrastructure.app.AppProperties
import kr.easydoc.infrastructure.usage.UsageProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Clock
import java.util.UUID

@ConfigurationProperties("easydoc.payment")
data class PaymentProperties(
    val provider: String = PaymentEnvironments.STUB,
    val mockEnabled: Boolean = false,
    val tossClientKey: kr.easydoc.core.security.Secret = kr.easydoc.core.security.Secret.EMPTY,
    val tossSecretKey: kr.easydoc.core.security.Secret = kr.easydoc.core.security.Secret.EMPTY,
    val purchaseEnabled: Boolean = false,
    val autoChargeEnabled: Boolean = false,
    val allowedWorkspaces: Set<UUID> = emptySet(),
)

/** Deterministic stub: the same input always has the same outcome; no network, keys or SDK. */
class StubPaymentGateway : PaymentGateway {
    override fun charge(
        orderId: UUID,
        amount: Int,
        fail: Boolean,
    ): Boolean = !fail
}

@Configuration(proxyBeanMethods = false)
class SubscriptionConfiguration {
    @Bean
    fun paymentGateway(
        properties: PaymentProperties,
        environment: Environment,
        app: AppProperties,
    ): PaymentGateway {
        validatePaymentEnvironment(properties, environment, app)
        check(properties.provider in PaymentEnvironments.ALLOWED) { "Unknown payment provider" }
        if (properties.provider == PaymentEnvironments.TOSS_TEST) {
            check(
                environment.activeProfiles.any { it in setOf("local", "test") } &&
                    environment.activeProfiles.none { it in setOf("prod", "production") },
            ) { "Toss test requires local/test" }
            check(
                properties.tossClientKey.reveal().startsWith("test_ck_") &&
                    properties.tossSecretKey.reveal().startsWith("test_sk_"),
            ) { "Toss individual test keys are required" }
        }
        if (properties.provider == PaymentEnvironments.TOSS_LIVE) {
            check(
                properties.tossClientKey.reveal().startsWith("live_ck_") &&
                    properties.tossSecretKey.reveal().startsWith("live_sk_"),
            ) { "Toss individual live keys are required" }
            check(!properties.mockEnabled) { "Mock payments are forbidden with live billing" }
        }
        check(!properties.mockEnabled || environment.activeProfiles.any { it in setOf("local", "test") }) {
            "Mock payments require the local or test profile"
        }
        check(!properties.mockEnabled || environment.activeProfiles.none { it in setOf("prod", "production") }) {
            "Mock payments are forbidden in production"
        }
        return StubPaymentGateway()
    }

    private fun validatePaymentEnvironment(
        properties: PaymentProperties,
        environment: Environment,
        app: AppProperties,
    ) {
        val profiles = environment.activeProfiles.toList()
        val production = profiles.any { it == "production" || it == "prod" }
        val development = profiles.any { it == "local" || it == "test" }
        check(properties.provider in PaymentEnvironments.ALLOWED) {
            invalid("easydoc.payment.provider", "unknown provider", profiles)
        }
        check(!(development && properties.provider == PaymentEnvironments.TOSS_LIVE)) {
            invalid("easydoc.payment.provider", "live billing is forbidden in local/test", profiles)
        }
        if (!production) return
        check(properties.provider == PaymentEnvironments.TOSS_LIVE) {
            invalid("easydoc.payment.provider", "production requires toss_live", profiles)
        }
        check(!properties.mockEnabled) {
            invalid("easydoc.payment.mock-enabled", "production forbids mock payments", profiles)
        }
        check(properties.tossClientKey.reveal().startsWith("live_ck_")) {
            invalid("easydoc.payment.toss-client-key", "live client key is required", profiles)
        }
        check(properties.tossSecretKey.reveal().startsWith("live_sk_")) {
            invalid("easydoc.payment.toss-secret-key", "live secret key is required", profiles)
        }
        val publicUrl = parsePublicUrl(app.publicBaseUrl, profiles)
        check(publicUrl.path.isNullOrEmpty() || publicUrl.path == "/") {
            invalid("easydoc.app.public-base-url", "public URL path must be root", profiles)
        }
        val callback = publicUrl.resolve("/api/payments/toss/billing/callback")
        check(
            isPublicHttps(callback) &&
                callback.scheme == publicUrl.scheme &&
                callback.host == publicUrl.host &&
                callback.port == publicUrl.port,
        ) {
            invalid("easydoc.app.public-base-url", "public and callback HTTPS origins must match", profiles)
        }
    }

    private fun parsePublicUrl(
        value: String,
        profiles: List<String>,
    ): java.net.URI {
        val uri =
            try {
                java.net.URI.create(value)
            } catch (_: IllegalArgumentException) {
                throw IllegalStateException(
                    invalid("easydoc.app.public-base-url", "valid HTTPS public URL is required", profiles),
                )
            }
        check(isPublicHttps(uri) && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            invalid("easydoc.app.public-base-url", "valid credential-free HTTPS public URL is required", profiles)
        }
        return uri
    }

    private fun isPublicHttps(uri: java.net.URI): Boolean {
        val host = uri.host?.lowercase()?.removeSuffix(".")
        return uri.scheme.equals("https", ignoreCase = true) &&
            host != null &&
            host != "localhost" &&
            !host.endsWith(".localhost") &&
            host != "localhost.localdomain" &&
            isPublicIpLiteral(host)
    }

    private fun invalid(
        property: String,
        reason: String,
        profiles: List<String>,
    ): String =
        "Payment configuration invalid: profiles=${profiles.joinToString(",")}, " +
            "property=$property, reason=$reason"

    @Bean
    fun subscriptionStore(jdbc: JdbcClient): SubscriptionStore = JdbcSubscriptionStore(jdbc)

    @Bean
    @Suppress("LongParameterList")
    fun subscriptionService(
        store: SubscriptionStore,
        credits: CreditAccountService,
        transaction: TransactionRunner,
        gateway: PaymentGateway,
        properties: PaymentProperties,
        usage: UsageProperties,
        users: kr.easydoc.application.auth.UserRepository,
        toss: org.springframework.beans.factory.ObjectProvider<kr.easydoc.application.subscription.TossBillingService>,
    ): SubscriptionService =
        SubscriptionService(
            store,
            credits,
            transaction,
            gateway,
            properties.mockEnabled && properties.provider == PaymentEnvironments.STUB,
            Clock.systemUTC(),
            usage.zoneId(),
            users,
            toss.ifAvailable,
        )
}

private fun isPublicIpLiteral(host: String): Boolean {
    val literal = host.removeSurrounding("[", "]")
    val ipv4Candidate = literal.all { it in '0'..'9' || it == '.' }
    val ipv6Literal = literal.contains(":")
    return when {
        !ipv4Candidate && !ipv6Literal -> true
        else -> parseIpLiteral(literal, ipv4Candidate, ipv6Literal)?.let(::isPublicAddress) == true
    }
}

private fun parseIpLiteral(
    literal: String,
    ipv4Candidate: Boolean,
    ipv6Literal: Boolean,
): InetAddress? =
    if ((ipv4Candidate && isIpv4Literal(literal)) || ipv6Literal) {
        try {
            InetAddress.getByName(literal)
        } catch (_: UnknownHostException) {
            null
        }
    } else {
        null
    }

private fun isPublicAddress(address: InetAddress): Boolean =
    !address.isAnyLocalAddress &&
        !address.isLoopbackAddress &&
        !address.isSiteLocalAddress &&
        !address.isLinkLocalAddress &&
        !address.isMulticastAddress &&
        !isUniqueLocalIpv6(address)

private fun isIpv4Literal(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == IPV4_PART_COUNT &&
        parts.all { part ->
            part.length in 1..IPV4_PART_MAX_LENGTH &&
                part.all { it in '0'..'9' } &&
                part.toInt() <= IPV4_PART_MAX
        }
}

private fun isUniqueLocalIpv6(address: InetAddress): Boolean =
    address is Inet6Address &&
        (address.address[0].toInt() and UNIQUE_LOCAL_IPV6_MASK) == UNIQUE_LOCAL_IPV6_PREFIX

private const val IPV4_PART_COUNT = 4
private const val IPV4_PART_MAX_LENGTH = 3
private const val IPV4_PART_MAX = 255
private const val UNIQUE_LOCAL_IPV6_MASK = 0xfe
private const val UNIQUE_LOCAL_IPV6_PREFIX = 0xfc
