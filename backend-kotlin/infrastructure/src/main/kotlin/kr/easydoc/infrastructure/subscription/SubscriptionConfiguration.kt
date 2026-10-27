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
                environment.activeProfiles.any { it in DEVELOPMENT_PROFILES } &&
                    environment.activeProfiles.none { it in PRODUCTION_PROFILES },
            ) { "Toss test requires local/test" }
            check(
                properties.tossClientKey.reveal().startsWith(TEST_CLIENT_KEY_PREFIX) &&
                    properties.tossSecretKey.reveal().startsWith(TEST_SECRET_KEY_PREFIX),
            ) { "Toss individual test keys are required" }
        }
        check(!properties.mockEnabled || environment.activeProfiles.any { it in DEVELOPMENT_PROFILES }) {
            "Mock payments require the local or test profile"
        }
        check(!properties.mockEnabled || environment.activeProfiles.none { it in PRODUCTION_PROFILES }) {
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
        val production = profiles.any { it in PRODUCTION_PROFILES }
        val development = profiles.any { it in DEVELOPMENT_PROFILES }
        check(properties.provider in PaymentEnvironments.ALLOWED) {
            invalid("easydoc.payment.provider", "unknown provider", profiles)
        }
        // 이 조합을 먼저 거른다. worker 의 `worker,production` 에 `local` 이 섞이면 아래
        // 「local/test 금지」가 먼저 걸려 설정이 아니라 프로필이 문제라는 사실을 가린다.
        check(!(production && development)) {
            invalid(ACTIVE_PROFILES_PROPERTY, "production cannot be combined with local/test profiles", profiles)
        }
        check(!(development && properties.provider == PaymentEnvironments.TOSS_LIVE)) {
            invalid("easydoc.payment.provider", "live billing is forbidden in local/test", profiles)
        }
        check(!production || properties.provider == PaymentEnvironments.TOSS_LIVE) {
            invalid("easydoc.payment.provider", "production requires toss_live", profiles)
        }
        if (properties.provider != PaymentEnvironments.TOSS_LIVE) return
        validateLiveBilling(properties, app, profiles)
    }

    /**
     * 실결제 설정은 프로필과 무관하게 같은 조건을 요구한다. `production` 이 붙지 않은 기동도
     * 같은 라이브 상점을 호출하므로, 프로필 방향 검사(`production` → `toss_live`)와 별개로 둔다.
     */
    private fun validateLiveBilling(
        properties: PaymentProperties,
        app: AppProperties,
        profiles: List<String>,
    ) {
        check(!properties.mockEnabled) {
            invalid("easydoc.payment.mock-enabled", "live billing forbids mock payments", profiles)
        }
        // 실결제에서는 구매 가능 판정이 이 목록을 보지 않는다. 값이 남아 있으면 운영자는
        // 제한 출시로 읽는데 실제로는 전체 공개이므로, 조용히 열지 않고 기동을 막는다.
        check(properties.allowedWorkspaces.isEmpty()) {
            invalid(
                "easydoc.payment.allowed-workspaces",
                "live billing ignores the allowed workspace list",
                profiles,
            )
        }
        check(properties.tossClientKey.reveal().startsWith(LIVE_CLIENT_KEY_PREFIX)) {
            invalid("easydoc.payment.toss-client-key", "live client key is required", profiles)
        }
        check(properties.tossSecretKey.reveal().startsWith(LIVE_SECRET_KEY_PREFIX)) {
            invalid("easydoc.payment.toss-secret-key", "live secret key is required", profiles)
        }
        // API 는 이 기준 URL 뒤에 `TossCallbackUrl.PATH` 를 붙여 콜백을 만든다. 기준 URL 이 공개
        // HTTPS(위 parsePublicUrl)이고 경로가 루트이면 그 콜백도 같은 공개 출처에 남는다 —
        // 조립 결과를 다시 보는 검사는 같은 전제에서 유도되므로 항상 참이어서 두지 않는다.
        // 조립식 자체는 `TossCallbackUrlTest` 가 고정한다.
        val publicUrl = parsePublicUrl(app.publicBaseUrl, profiles)
        check(publicUrl.path.isNullOrEmpty() || publicUrl.path == "/") {
            invalid("easydoc.app.public-base-url", "public URL path must be root", profiles)
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

/** Spring 프로필 이름과 Toss 개별 키 접두사 — 코드와 함께 바뀌는 불변식이라 한곳에서만 적는다. */
private val PRODUCTION_PROFILES = setOf("production", "prod")
private val DEVELOPMENT_PROFILES = setOf("local", "test")
private const val ACTIVE_PROFILES_PROPERTY = "spring.profiles.active"
private const val TEST_CLIENT_KEY_PREFIX = "test_ck_"
private const val TEST_SECRET_KEY_PREFIX = "test_sk_"
private const val LIVE_CLIENT_KEY_PREFIX = "live_ck_"
private const val LIVE_SECRET_KEY_PREFIX = "live_sk_"
