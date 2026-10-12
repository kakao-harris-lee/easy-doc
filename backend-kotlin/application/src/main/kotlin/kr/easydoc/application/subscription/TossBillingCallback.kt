package kr.easydoc.application.subscription

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.security.Secret
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/** The encrypted part of a callback row. Bearer values are never persisted in plaintext columns. */
data class TossBillingCallbackPayload(
    val authKey: Secret? = null,
    val resumeToken: String? = null,
    val failureCode: String? = null,
) {
    override fun toString(): String =
        "TossBillingCallbackPayload(authKey=[REDACTED], resumeToken=[REDACTED], failureCode=$failureCode)"
}

/** A callback row with the safe session binding kept outside the encrypted payload. */
data class TossBillingCallbackArtifact(
    val id: UUID,
    val workspaceId: UUID,
    val sessionId: UUID,
    val customerId: UUID,
    val environment: String,
    val purpose: String,
    val stateHash: String,
    val resumeHash: String?,
    val stateExpiresAt: Instant,
    val authExpiresAt: Instant?,
    val resumeExpiresAt: Instant?,
    val processingUntil: Instant?,
    val completedAt: Instant?,
    val payload: TossBillingCallbackPayload,
) {
    override fun toString(): String =
        "TossBillingCallbackArtifact(id=$id, workspaceId=$workspaceId, sessionId=$sessionId, " +
            "customerId=$customerId, environment=$environment, purpose=$purpose, " +
            "stateHash=[REDACTED], resumeHash=[REDACTED], stateExpiresAt=$stateExpiresAt, " +
            "authExpiresAt=$authExpiresAt, resumeExpiresAt=$resumeExpiresAt, completedAt=$completedAt, " +
            "payload=$payload)"
}

/**
 * Persistence boundary for the server-side Toss callback hand-off.
 *
 * Implementations lock rows returned by [findByState] and [findByResume]. That makes capture and
 * resume updates serialize with one another without adding a new payment lock or provider call.
 */
interface TossBillingCallbackStore {
    fun save(artifact: TossBillingCallbackArtifact)

    fun findByState(stateHash: String): TossBillingCallbackArtifact?

    fun findByResume(resumeHash: String): TossBillingCallbackArtifact?

    fun sessionMatches(artifact: TossBillingCallbackArtifact): Boolean

    fun purge(now: Instant): Int
}

data class TossBillingResumeResult(
    val workspaceId: UUID,
    val sessionId: UUID,
    val purpose: String,
)

/**
 * Server-side hand-off for the Toss billing-auth redirect.
 *
 * The public endpoint only stores a provider result. It never invokes Toss. The authenticated
 * resume endpoint owns the existing completion call, so a lost browser response remains safely
 * retryable and cannot create a second order.
 */
class TossBillingCallbackService(
    private val store: TossBillingCallbackStore,
    private val toss: TossBillingService,
    private val accounts: CreditAccountRepository,
    private val transaction: TransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
    private val environment: String = toss.environment,
) {
    /** Create a 256-bit state bearer and bind it to the just-created billing session. */
    fun register(session: BillingSession): String {
        val state = OpaqueToken.generate()
        val now = clock.instant()
        transaction.inTransaction {
            store.save(
                TossBillingCallbackArtifact(
                    id = UUID.randomUUID(),
                    workspaceId = session.workspaceId,
                    sessionId = session.id,
                    customerId = session.customer,
                    environment = session.environment,
                    purpose = session.purpose,
                    stateHash = OpaqueToken.hash(state),
                    resumeHash = null,
                    stateExpiresAt = session.expiresAt,
                    authExpiresAt = null,
                    resumeExpiresAt = null,
                    processingUntil = null,
                    completedAt = null,
                    payload = TossBillingCallbackPayload(),
                ),
            )
        }
        return state
    }

    /**
     * Store the provider callback and return an opaque resume bearer. No provider operation is
     * reached from this method.
     */
    fun capture(
        state: String,
        authKey: String?,
        customerKey: String?,
        failureCode: String?,
    ): String {
        val stateToken = OpaqueToken.require(state)
        val customer = customerKey?.let(::parseCustomer)
        val code = failureCode?.takeIf { it.isNotBlank() }
        validateCaptureInput(authKey, customer, code)
        val now = clock.instant()
        return transaction.inTransaction {
            val current = store.findByState(OpaqueToken.hash(stateToken)) ?: notFound()
            if (current.environment != environment || !store.sessionMatches(current)) notFound()
            if (customer != null && customer != current.customerId) notFound()

            // A provider/browser retry may arrive after the original session deadline. Once a
            // resume bearer exists, returning that same bearer is safe and preserves convergence;
            // a first callback still has to arrive before the session deadline below.
            val existingResume = current.payload.resumeToken
            if (existingResume != null && current.resumeExpiresAt?.isAfter(now) == true) {
                return@inTransaction existingResume
            }

            if (!now.isBefore(current.stateExpiresAt)) notFound()
            val resume = OpaqueToken.generate()
            store.save(
                current.copy(
                    resumeHash = OpaqueToken.hash(resume),
                    authExpiresAt = authKey?.let { now.plus(AUTH_TTL) },
                    resumeExpiresAt = now.plus(RESUME_TTL),
                    completedAt = null,
                    payload =
                        TossBillingCallbackPayload(
                            authKey = authKey?.let(::Secret),
                            resumeToken = resume,
                            failureCode = code,
                        ),
                ),
            )
            resume
        }
    }

    /**
     * Resolve a resume bearer for the authenticated owner and run the existing completion path.
     * A short transaction claims the callback row before completion. The existing completion call
     * then runs outside that transaction, preserving its durable pre-provider state commit. The
     * lease prevents concurrent browser retries and can be reclaimed after a process crash.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught")
    fun resume(
        owner: UUID,
        resumeId: String,
    ): TossBillingResumeResult {
        val resumeToken = OpaqueToken.require(resumeId)
        val prepared =
            transaction.inTransaction {
                val current = store.findByResume(OpaqueToken.hash(resumeToken)) ?: notFound()
                if (accounts.ownerOf(current.workspaceId) != owner) notFound()
                if (current.resumeExpiresAt == null || !clock.instant().isBefore(current.resumeExpiresAt)) notFound()
                if (current.environment != environment || !store.sessionMatches(current)) notFound()
                if (current.completedAt != null) {
                    return@inTransaction ResumePreparation.Completed(
                        TossBillingResumeResult(current.workspaceId, current.sessionId, current.purpose),
                    )
                }
                current.payload.failureCode?.let { throw failureConflict(it) }

                val now = clock.instant()
                var effective = current
                if (current.authExpiresAt?.let { !now.isBefore(it) } == true && current.payload.authKey != null) {
                    // Expiry is enforced on reads as well as by the minute-level scrubber. The
                    // encrypted provider credential therefore leaves storage before any retry.
                    effective = current.copy(authExpiresAt = null, payload = current.payload.copy(authKey = null))
                    store.save(effective)
                } else if (current.authExpiresAt?.let { !now.isBefore(it) } == true) {
                    effective = current.copy(authExpiresAt = null)
                    store.save(effective)
                }
                if (effective.processingUntil?.isAfter(now) == true) {
                    return@inTransaction ResumePreparation.Busy
                }
                val auth = effective.payload.authKey
                val sessionIsActive = toss.state(current.workspaceId) == "active"
                if (auth == null || effective.authExpiresAt == null || !now.isBefore(effective.authExpiresAt)) {
                    if (!sessionIsActive) return@inTransaction ResumePreparation.Expired
                }
                val leaseUntil = now.plus(PROCESSING_LEASE).truncatedTo(ChronoUnit.MICROS)
                store.save(effective.copy(processingUntil = leaseUntil))
                ResumePreparation.Pending(effective, auth ?: Secret.EMPTY, leaseUntil)
            }

        val pending =
            when (prepared) {
                is ResumePreparation.Completed -> return prepared.result
                ResumePreparation.Busy -> throw ConflictException(PROCESSING_MESSAGE)
                ResumePreparation.Expired -> throw ConflictException(EXPIRED_AUTH_MESSAGE)
                is ResumePreparation.Pending -> prepared
            }
        try {
            // An active session already issued its billing key. Passing an empty secret is safe
            // here because complete() validates active state before provider reconciliation.
            toss.complete(
                owner,
                pending.artifact.workspaceId,
                pending.artifact.sessionId,
                pending.artifact.customerId,
                pending.auth,
                fail = false,
            )
            markCompleted(resumeToken, pending.artifact, pending.leaseUntil)
            return TossBillingResumeResult(
                pending.artifact.workspaceId,
                pending.artifact.sessionId,
                pending.artifact.purpose,
            )
        } catch (failure: RuntimeException) {
            // TossUncertain, TossDeclined, validation conflicts and storage failures all leave
            // the staged auth intact; only the short-lived concurrency lease is released.
            clearProcessing(resumeToken, pending.artifact.id, pending.leaseUntil)
            throw failure
        }
    }

    fun purge(now: Instant): Int = store.purge(now)

    private fun markCompleted(
        resumeToken: String,
        claimed: TossBillingCallbackArtifact,
        leaseUntil: Instant,
    ) {
        transaction.inTransaction {
            val current = store.findByResume(OpaqueToken.hash(resumeToken)) ?: return@inTransaction
            if (current.id != claimed.id || current.processingUntil != leaseUntil || current.completedAt != null) {
                return@inTransaction
            }
            val completedAt = clock.instant()
            store.save(
                current.copy(
                    authExpiresAt = null,
                    processingUntil = null,
                    completedAt = completedAt,
                    resumeExpiresAt = current.resumeExpiresAt ?: completedAt.plus(RESUME_TTL),
                    payload = current.payload.copy(authKey = null, failureCode = null),
                ),
            )
        }
    }

    private fun clearProcessing(
        resumeToken: String,
        id: UUID,
        leaseUntil: Instant,
    ) {
        runCatching {
            transaction.inTransaction {
                val current = store.findByResume(OpaqueToken.hash(resumeToken)) ?: return@inTransaction
                if (current.id == id && current.processingUntil == leaseUntil && current.completedAt == null) {
                    store.save(current.copy(processingUntil = null))
                }
            }
        }
    }

    @Suppress("ThrowsCount") // Each malformed provider field fails closed before storing credentials.
    private fun validateCaptureInput(
        authKey: String?,
        customer: UUID?,
        failureCode: String?,
    ) {
        if (failureCode != null && failureCode.length > FAILURE_CODE_MAX_LENGTH) {
            throw InvalidInputException("카드 등록 실패 코드가 너무 깁니다")
        }
        if (failureCode != null && failureCode !in ALLOWED_FAILURE_CODES) {
            throw InvalidInputException("카드 등록 실패 코드가 올바르지 않습니다")
        }
        if (failureCode != null && authKey != null) {
            throw InvalidInputException("카드 등록 결과 형식이 올바르지 않습니다")
        }
        if (failureCode == null) {
            if (authKey.isNullOrBlank() || authKey.length > AUTH_KEY_MAX_LENGTH || customer == null) {
                throw InvalidInputException("카드 등록 결과가 올바르지 않습니다")
            }
        }
    }

    private fun parseCustomer(value: String): UUID =
        runCatching { UUID.fromString(value) }
            .getOrElse { throw InvalidInputException("카드 등록 고객 식별자가 올바르지 않습니다") }

    private fun failureConflict(code: String): ConflictException =
        when (code) {
            "PAY_PROCESS_CANCELED" -> {
                ConflictException("카드 등록을 취소했습니다. 다시 시도하세요")
            }

            "NOT_SUPPORTED_CARD_TYPE" -> {
                ConflictException("지원하지 않는 카드 종류입니다. 다른 카드를 사용하세요")
            }

            else -> {
                ConflictException("카드 등록이 완료되지 않았습니다. 다시 시도하세요")
            }
        }

    private fun notFound(): Nothing = throw NotFoundException(NOT_FOUND_MESSAGE)

    private sealed interface ResumePreparation {
        data class Completed(val result: TossBillingResumeResult) : ResumePreparation

        data object Busy : ResumePreparation

        data object Expired : ResumePreparation

        data class Pending(
            val artifact: TossBillingCallbackArtifact,
            val auth: Secret,
            val leaseUntil: Instant,
        ) : ResumePreparation
    }

    private object OpaqueToken {
        private const val BYTE_MASK = 0xff
        private val random = SecureRandom()
        private val encoder = Base64.getUrlEncoder().withoutPadding()

        fun generate(): String = ByteArray(TOKEN_BYTES).also(random::nextBytes).let(encoder::encodeToString)

        fun hash(token: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }

        fun require(token: String): String {
            if (token.length != TOKEN_LENGTH || !TOKEN_PATTERN.matches(token)) notFoundToken()
            return token
        }

        private fun notFoundToken(): Nothing = throw NotFoundException(NOT_FOUND_MESSAGE)

        private const val TOKEN_BYTES = 32
        private const val TOKEN_LENGTH = 43
        private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]+")
    }

    private companion object {
        val AUTH_TTL: Duration = Duration.ofMinutes(10)
        val RESUME_TTL: Duration = Duration.ofHours(24)
        val ALLOWED_FAILURE_CODES: Set<String> =
            setOf("PAY_PROCESS_CANCELED", "NOT_SUPPORTED_CARD_TYPE")
        const val AUTH_KEY_MAX_LENGTH = 300
        const val FAILURE_CODE_MAX_LENGTH = 100
        const val EXPIRED_AUTH_MESSAGE = "카드 등록 결과가 만료되었습니다. 다시 결제를 시작하세요"
        const val PROCESSING_MESSAGE = "카드 등록 결과를 확인 중입니다. 잠시 후 다시 시도하세요"
        const val NOT_FOUND_MESSAGE = "카드 등록 복구 자료를 찾을 수 없습니다"
        val PROCESSING_LEASE: Duration = Duration.ofMinutes(2)
    }
}
