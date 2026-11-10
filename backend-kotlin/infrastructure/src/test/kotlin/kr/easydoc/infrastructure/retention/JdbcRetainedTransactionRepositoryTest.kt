package kr.easydoc.infrastructure.retention

import kr.easydoc.application.retention.ComplaintDecisionCommand
import kr.easydoc.application.retention.RefundDecisionCommand
import kr.easydoc.application.subscription.BillingOrder
import kr.easydoc.application.subscription.BillingSession
import kr.easydoc.application.subscription.LegalSnapshot
import kr.easydoc.application.subscription.TossPayment
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class JdbcRetainedTransactionRepositoryTest {
    private val now = Instant.parse("2026-10-11T00:00:00Z")

    @Test
    fun `November legal resources contain the published Start conditions`() {
        val terms = legalResource("legal/terms-${LegalSnapshot.VERSION}.md")
        assertThat(terms).contains("terms-${LegalSnapshot.VERSION}", "2026-10-26", "2026년 11월 1일")
        assertThat(terms).contains("월 99,000원", "50크레딧", "start-monthly-v1")

        val privacy = legalResource("legal/privacy-${LegalSnapshot.PRIVACY_VERSION}.md")
        assertThat(privacy).contains(
            "privacy-${LegalSnapshot.PRIVACY_VERSION}",
            "검토일: ${LegalSnapshot.PRIVACY_VERSION}",
            "2026년 11월 1일",
        )
        assertThat(privacy).contains("Images API는 이 옵션을 제공하지 않으므로")

        listOf(terms, privacy).forEach {
            assertThat(it).doesNotContain("출시 전 공고로 확정", "확정 전에는 신규 실결제를 받지 않습니다")
        }
    }

    @Test
    fun `retained legal resources are the published front-end wording`() {
        val published = publishedLegalDirectory()
        mapOf(
            "legal/terms-${LegalSnapshot.VERSION}.md" to "terms-of-service.md",
            "legal/privacy-${LegalSnapshot.PRIVACY_VERSION}.md" to "privacy-policy.md",
        ).forEach { (resource, fileName) ->
            assertThat(legalResource(resource))
                .describedAs("$resource 와 frontend/src/content/legal/$fileName 이 갈라졌다")
                .isEqualTo(Files.readString(published.resolve(fileName)))
        }
    }

    /**
     * 동의 증거는 동의 시점에 시행 중이던 판을 그대로 담으므로, 이력에 남은 옛 판의 본문도
     * 계속 classpath 에 있어야 한다 — 한 판이라도 빠지면 그 시점의 동의는 가리킬 본문을 잃는다.
     */
    @Test
    fun `every published legal version keeps its retained resource`() {
        LegalSnapshot.PRIVACY_VERSIONS.forEach { version ->
            assertThat(javaClass.classLoader.getResource("legal/privacy-$version.md"))
                .describedAs(version)
                .isNotNull()
        }
        assertThat(javaClass.classLoader.getResource("legal/terms-${LegalSnapshot.VERSION}.md"))
            .describedAs(LegalSnapshot.VERSION)
            .isNotNull()
    }

    @Test
    fun `consent at the November cutoff archives the policy in force at consent time`() {
        val db = database("retained_consent_at_cutoff")
        val (user, workspace, session) = owner(db)
        val repository = repository(db, 1)
        val fixture = paymentIds(db, workspace, "start", 1000, 0)
        val cutoff = Instant.parse("2026-10-31T15:00:00Z")
        val consent = sessionModel(workspace, session, user).copy(consentAt = cutoff)

        repository.archivePayment(fixture.order, fixture.payment, 0, null, consent)

        val archivedConsent = repository.find(UUID.nameUUIDFromBytes("consent:$session".toByteArray()))!!
        assertThat(archivedConsent.summary.occurredAt).isEqualTo(cutoff)
        assertThat(archivedConsent.summary.manualReview).isFalse()
        assertThat(archivedConsent.payloadJson).contains("terms-${LegalSnapshot.VERSION}", "privacy-2026-11-01")
        val payload = ObjectMapper().readTree(archivedConsent.payloadJson)
        assertThat(payload["privacy_version"].asString()).isEqualTo("privacy-2026-11-01")
        assertThat(payload["privacy_snapshot"].asString()).isEqualTo(legalResource("legal/privacy-2026-11-01.md"))
    }

    @Test
    fun `consent after the November tenth revision archives the revised policy`() {
        val db = database("retained_consent_after_revision")
        val (user, workspace, session) = owner(db)
        val repository = repository(db, 1)
        val fixture = paymentIds(db, workspace, "start", 1000, 0)
        val afterRevision = Instant.parse("2026-11-10T00:00:00+09:00")
        val consent = sessionModel(workspace, session, user).copy(consentAt = afterRevision)

        repository.archivePayment(fixture.order, fixture.payment, 0, null, consent)

        val archivedConsent = repository.find(UUID.nameUUIDFromBytes("consent:$session".toByteArray()))!!
        assertThat(archivedConsent.summary.manualReview).isFalse()
        val payload = ObjectMapper().readTree(archivedConsent.payloadJson)
        assertThat(payload["privacy_version"].asString()).isEqualTo("privacy-${LegalSnapshot.PRIVACY_VERSION}")
        assertThat(payload["privacy_snapshot"].asString())
            .isEqualTo(legalResource("legal/privacy-${LegalSnapshot.PRIVACY_VERSION}.md"))
    }

    @Test
    fun `consent one instant before the November cutoff is quarantined without snapshots`() {
        val db = database("retained_consent_before_cutoff")
        val (user, workspace, session) = owner(db)
        val repository = repository(db, 1)
        val fixture = paymentIds(db, workspace, "start", 1000, 0)
        val beforeCutoff = Instant.parse("2026-10-31T14:59:59Z")
        val consent = sessionModel(workspace, session, user).copy(consentAt = beforeCutoff)

        repository.archivePayment(fixture.order, fixture.payment, 0, null, consent)

        val archivedConsent = repository.find(UUID.nameUUIDFromBytes("consent:$session".toByteArray()))!!
        assertThat(archivedConsent.summary.occurredAt).isEqualTo(beforeCutoff)
        assertThat(archivedConsent.summary.manualReview).isTrue()
        assertThat(archivedConsent.payloadJson).contains(
            "\"terms_snapshot\":null",
            "\"privacy_snapshot\":null",
            "\"privacy_version\":null",
        )
    }

    @Test
    fun `full and partial live payments are archived before account rows disappear`() {
        val db = database("retained_delete")
        val (user, workspace, session) = owner(db)
        val first = paymentIds(db, workspace, "full", 1000, 1000)
        val second = paymentIds(db, workspace, "partial", 2000, 500)
        val repository = repository(db, 1)
        val consent = sessionModel(workspace, session, user)

        repository.archivePayment(first.order, first.payment, 0, "buyer@example.test", consent)
        repository.archivePayment(second.order, second.payment, 0, "buyer@example.test", consent)
        db.execute("UPDATE toss_billing_orders SET approved_at=NULL WHERE id='${first.order.id}'")
        repository.archiveForUser(user)
        repository.archiveForUser(user)

        assertThat(db.queryInt("SELECT count(*) FROM retained_transaction_records")).isEqualTo(7)
        val laterRefund =
            second.payment.copy(
                remainingAmount = 1000,
                canceledAt = Instant.parse("2026-10-09T00:00:00Z"),
            )
        repository.archivePayment(second.order, laterRefund, 500, "buyer@example.test", consent)
        repository.archivePayment(second.order, laterRefund, 1000, "buyer@example.test", consent)
        val refundOrder = UUID.randomUUID()
        db.execute(
            """
            INSERT INTO toss_billing_orders(id,workspace_id,plan_id,amount,created_at,cycle_ends_at,kind,original_id,
              previous_remaining,status,payload_encrypted,encryption_scheme,key_version,environment,cycle_id,canceled_at)
            VALUES ('$refundOrder','$workspace','partial',500,'2026-10-09T00:00:00Z','2026-11-01T00:00:00Z','refund',
              '${second.order.id}',1500,'paid',decode('00','hex'),'aes256gcm-v1',1,'toss_live','$refundOrder','2026-10-09T00:00:00Z')
            """.trimIndent(),
        )
        db.execute("UPDATE subscription_payments SET refunded_amount=1000 WHERE id='${second.order.id}'")
        repository.archiveForUser(user)
        assertThat(
            db.queryInt("SELECT count(*) FROM retained_transaction_records WHERE record_type='refund'"),
        ).isEqualTo(3)
        db.execute("DELETE FROM users WHERE id='$user'")
        assertThat(db.queryInt("SELECT count(*) FROM users WHERE id='$user'")).isZero()
        assertThat(db.queryInt("SELECT count(*) FROM retained_transaction_records")).isEqualTo(8)
        assertThat(db.queryInt("SELECT count(*) FROM retained_transaction_decisions")).isZero()
    }

    @Test
    fun `renewal extends consent retention without rewriting the original consent evidence`() {
        val db = database("retained_consent_renewal")
        val (user, workspace, session) = owner(db)
        val repository = repository(db, 1)
        val consent = sessionModel(workspace, session, user)
        val first = paymentIds(db, workspace, "start", 1000, 0)
        repository.archivePayment(first.order, first.payment, 0, null, consent)
        val later = paymentIds(db, workspace, "renewal", 1000, 0, approvedAt = Instant.parse("2027-10-07T00:00:00Z"))
        repository.archivePayment(later.order, later.payment, 0, null, consent)
        repository.archiveForUser(user)
        val archivedConsent = repository.find(UUID.nameUUIDFromBytes("consent:$session".toByteArray()))!!
        assertThat(archivedConsent.summary.occurredAt).isEqualTo(consent.consentAt)
        assertThat(archivedConsent.summary.retainedUntil).isEqualTo(Instant.parse("2032-10-07T00:00:00Z"))
        assertThat(archivedConsent.payloadJson).contains("terms_snapshot", "2026-11-01")
        assertThat(archivedConsent.payloadJson).contains("privacy_snapshot", "2026-11-01")
        assertThat(archivedConsent.payloadJson).contains("2026년 11월 1일", "99,000원", "50크레딧")
        assertThat(archivedConsent.summary.manualReview).isFalse()
    }

    @Test
    fun `initial checkout archives consent when the order uses the session id`() {
        val db = database("retained_initial_checkout")
        val (user, workspace, session) = owner(db)
        val repository = repository(db, 1)
        val initial = paymentIds(db, workspace, "start", 1000, 0, orderId = session)
        repository.archivePayment(initial.order, initial.payment, 0, null, sessionModel(workspace, session, user))
        repository.archiveForUser(user)
        assertThat(
            db.queryInt("SELECT count(*) FROM retained_transaction_records WHERE record_type='consent'"),
        ).isEqualTo(1)
        assertThat(
            repository
                .find(session)
                ?.summary
                ?.type
                ?.wireName,
        ).isEqualTo("payment")
    }

    @Test
    fun `failed card registration without a paid order does not create consent backfill`() {
        val db = database("retained_failed_registration")
        val (user, _, _) = owner(db)
        val repository = repository(db, 1)

        repository.archiveForUser(user)

        assertThat(
            db.queryInt("SELECT count(*) FROM retained_transaction_records WHERE record_type='consent'"),
        ).isZero()
    }

    @Test
    fun `card replacement reuses verified consent from the original paid checkout`() {
        val db = database("retained_card_replacement")
        val (user, workspace, session) = owner(db)
        val repository = repository(db, 1)
        val originalConsent = sessionModel(workspace, session, user)
        val payment = paymentIds(db, workspace, "start", 1000, 0)
        repository.archivePayment(payment.order, payment.payment, 0, null, originalConsent)

        val replacement = UUID.randomUUID()
        db.execute(
            """
            UPDATE toss_billing_sessions SET id='$replacement', purpose='replace_card'
            WHERE workspace_id='$workspace'
            """.trimIndent(),
        )

        repository.archiveForUser(user)

        assertThat(
            db.queryInt("SELECT count(*) FROM retained_transaction_records WHERE record_type='consent'"),
        ).isEqualTo(1)
        assertThat(
            db.queryInt(
                "SELECT count(*) FROM retained_transaction_records WHERE record_type='consent' AND manual_review=true",
            ),
        ).isZero()
        assertThat(
            db.queryInt(
                "SELECT count(*) FROM retained_transaction_records WHERE record_type='consent' " +
                    "AND source_id='$replacement'",
            ),
        ).isZero()
    }

    @Test
    fun `paid order without verified consent remains quarantined`() {
        val db = database("retained_missing_consent")
        val (user, workspace, _) = owner(db)
        paymentIds(db, workspace, "start", 1000, 0)
        val repository = repository(db, 1)

        assertThatThrownBy { repository.archiveForUser(user) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(
            db.queryInt(
                "SELECT count(*) FROM retained_transaction_records WHERE record_type='consent' AND manual_review=true",
            ),
        ).isEqualTo(1)
    }

    @Test
    @Suppress("LongMethod") // Exercise encrypted reason, replay and independent expiry on the same durable record.
    fun `decision reasons are encrypted and refund complaint retention periods are independent`() {
        val db = database("retained_decisions")
        val (user, workspace, session) = owner(db)
        val fixture = paymentIds(db, workspace, "decision", 1000, 0)
        val repository = repository(db, 1)
        repository.archivePayment(
            fixture.order,
            fixture.payment,
            0,
            "buyer@example.test",
            sessionModel(workspace, session, user),
        )
        val actor = UUID.randomUUID()
        db.execute("INSERT INTO users(id,email,password_hash) VALUES ('$actor','operator@example.test','test')")
        val refundReceived = Instant.parse("2020-01-01T00:00:00Z")
        val refund =
            repository.refundDecision(
                fixture.order.id,
                actor,
                RefundDecisionCommand(
                    UUID.randomUUID(),
                    "approved",
                    100,
                    java.math.BigDecimal("1.0"),
                    "refund reason",
                    true,
                    refundReceived,
                ),
            )
        val complaintOperation = UUID.randomUUID()
        val complaint =
            repository.complaintDecision(
                fixture.order.id,
                actor,
                ComplaintDecisionCommand(complaintOperation, "resolved", "complaint reason", refundReceived),
            )
        assertThat(refund.decisions).hasSize(1)
        assertThat(refund.decisions.single().reason).isEqualTo("refund reason")
        assertThat(complaint.decisions).hasSize(2)
        assertThat(
            db.queryInt(
                """
                SELECT count(*) FROM information_schema.columns WHERE
                table_name='retained_transaction_decisions' AND column_name='reason'
                """.trimIndent(),
            ),
        ).isZero()
        assertThat(
            db.queryInt(
                "SELECT count(*) FROM retained_transaction_decisions WHERE retained_until<'2026-10-11T00:00:00Z'",
            ),
        ).isEqualTo(1)
        assertThat(
            db.queryInt(
                "SELECT count(*) FROM retained_transaction_decisions WHERE operation_id='$complaintOperation' " +
                    "AND retained_until='2029-10-11T00:00:00Z'",
            ),
        ).isEqualTo(1)

        val same =
            repository.refundDecision(
                fixture.order.id,
                actor,
                RefundDecisionCommand(
                    refund.decisions.single().operationId,
                    "approved",
                    100,
                    java.math.BigDecimal("1"),
                    "refund reason",
                    true,
                    refundReceived,
                ),
            )
        assertThat(same.decisions).hasSize(2)
        assertThatThrownBy {
            repository.refundDecision(
                fixture.order.id,
                actor,
                RefundDecisionCommand(
                    refund.decisions.single().operationId,
                    "approved",
                    100,
                    java.math.BigDecimal("1"),
                    "different",
                    true,
                    refundReceived,
                ),
            )
        }.isInstanceOf(ConflictException::class.java)

        assertThat(repository.purge(now, 100, dryRun = true)).isEqualTo(1)
        assertThat(db.queryInt("SELECT count(*) FROM retained_transaction_records")).isEqualTo(3)
        assertThat(repository.purge(now, 100, dryRun = false)).isEqualTo(1)
        // The five-year payment record is retained even though its child decisions expired.
        assertThat(db.queryInt("SELECT count(*) FROM retained_transaction_records")).isEqualTo(3)
        assertThat(db.queryInt("SELECT count(*) FROM retained_transaction_decisions")).isEqualTo(1)
    }

    @Test
    fun `envelopes rotate with CAS and remain readable by the new key`() {
        val db = database("retained_rotation")
        val (user, workspace, session) = owner(db)
        val fixture = paymentIds(db, workspace, "rotate", 1000, 0)
        val old = repository(db, 1)
        old.archivePayment(
            fixture.order,
            fixture.payment,
            0,
            "buyer@example.test",
            sessionModel(workspace, session, user),
        )
        val actor = UUID.randomUUID()
        db.execute("INSERT INTO users(id,email,password_hash) VALUES ('$actor','operator-rotate@example.test','test')")
        old.refundDecision(
            fixture.order.id,
            actor,
            RefundDecisionCommand(
                UUID.randomUUID(),
                "approved",
                100,
                java.math.BigDecimal("1"),
                "rotate",
                false,
                now.minusSeconds(10),
            ),
        )

        val rotated = repository(db, 2)
        rotated.rotateSecrets()
        assertThat(db.queryInt("SELECT count(*) FROM retained_transaction_records WHERE key_version=2")).isEqualTo(3)
        assertThat(db.queryInt("SELECT count(*) FROM retained_transaction_decisions WHERE key_version=2")).isEqualTo(1)
        assertThat(rotated.find(fixture.order.id)?.payloadJson).contains("order_id")
    }

    @Test
    fun `unknown approval evidence is quarantined and blocks deletion`() {
        val db = database("retained_quarantine")
        val (user, workspace, session) = owner(db)
        val fixture = paymentIds(db, workspace, "unknown", 1000, 0, approvedAt = null)
        val repository = repository(db, 1)
        repository.archivePayment(
            fixture.order,
            fixture.payment,
            0,
            "buyer@example.test",
            sessionModel(workspace, session, user),
        )
        assertThatThrownBy { repository.archiveForUser(user) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(db.queryInt("SELECT count(*) FROM users WHERE id='$user'")).isEqualTo(1)
        assertThat(
            db.queryInt("SELECT count(*) FROM retained_transaction_records WHERE manual_review=true"),
        ).isGreaterThan(0)
    }

    private fun repository(
        db: DatabaseHandle,
        version: Int,
    ): JdbcRetainedTransactionRepository =
        JdbcRetainedTransactionRepository(
            JdbcClient.create(DriverManagerDataSource(db.jdbcUrl, db.username, db.password)),
            AesGcmContentCipher(mapOf(1 to key(1), 2 to key(2)), version),
            Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun legalResource(path: String): String =
        requireNotNull(javaClass.classLoader.getResource(path)) { "$path 리소스가 없다" }.readText()

    /**
     * 게시 문안이 있는 `frontend/src/content/legal` 디렉터리.
     *
     * Gradle 테스트의 작업 디렉터리는 모듈 디렉터리라 저장소 루트까지 거슬러 올라간다 —
     * 올라갈 단계 수를 적어 두면 모듈이 옮겨질 때 조용히 어긋난다.
     */
    private fun publishedLegalDirectory(): Path {
        var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (directory != null) {
            val candidate = directory.resolve("frontend/src/content/legal")
            if (Files.isDirectory(candidate)) return candidate
            directory = directory.parent
        }
        error(
            "frontend/src/content/legal 을 찾지 못했다 — 저장소 전체를 체크아웃한 상태에서 돌려야 한다" +
                " (작업 디렉터리: ${System.getProperty("user.dir")})",
        )
    }

    private fun database(prefix: String): DatabaseHandle =
        PostgresTestSupport.createEmptyDatabase(prefix).also { db ->
            Flyway
                .configure()
                .dataSource(
                    db.jdbcUrl,
                    db.username,
                    db.password,
                ).locations("classpath:db/migration")
                .load()
                .migrate()
        }

    private fun owner(db: DatabaseHandle): Triple<UUID, UUID, UUID> {
        val user = UUID.randomUUID()
        val workspace = UUID.randomUUID()
        val session = UUID.randomUUID()
        db.execute("INSERT INTO users(id,email,password_hash) VALUES ('$user','buyer@example.test','test')")
        db.execute("INSERT INTO workspaces(id,user_id,name) VALUES ('$workspace','$user','retained')")
        db.execute(
            """
            INSERT INTO toss_billing_sessions(workspace_id,id,customer,plan_id,state,expires_at,payload_encrypted,encryption_scheme,key_version,environment,purpose,consent_version,consent_at)
            VALUES ('$workspace','$session','$user','start','revoked','2030-01-01T00:00:00Z',decode('00','hex'),'aes256gcm-v1',1,'toss_live','purchase','start-monthly-v1','2026-11-01T00:00:00Z')
            """.trimIndent(),
        )
        return Triple(user, workspace, session)
    }

    private fun sessionModel(
        workspace: UUID,
        id: UUID,
        customer: UUID,
    ) = BillingSession(
        workspace,
        id,
        customer,
        "start",
        now.plusSeconds(3600),
        environment = "toss_live",
        purpose = "purchase",
        consentVersion = "start-monthly-v1",
        consentAt = Instant.parse("2026-11-01T00:00:00Z"),
    )

    @Suppress("LongParameterList", "LongMethod") // Seed the complete payment and refund history used by archive tests.
    private fun paymentIds(
        db: DatabaseHandle,
        workspace: UUID,
        plan: String,
        amount: Int,
        refunded: Int,
        approvedAt: Instant? = Instant.parse("2026-10-07T00:00:00Z"),
        orderId: UUID = UUID.randomUUID(),
    ): PaymentFixture {
        val refundId = UUID.randomUUID()
        val remaining = amount - refunded
        db.execute(
            """
            INSERT INTO toss_billing_orders(id,workspace_id,plan_id,amount,created_at,cycle_ends_at,kind,status,payload_encrypted,encryption_scheme,key_version,environment,cycle_id,approved_at)
            VALUES ('$orderId','$workspace','$plan',$amount,'2026-09-30T00:00:00Z','2026-11-01T00:00:00Z','charge','paid',decode('00','hex'),'aes256gcm-v1',1,'toss_live','$orderId',${approvedAt?.let {
                "'$it'"
            } ?: "NULL"})
            """.trimIndent(),
        )
        db.execute(
            """
            INSERT INTO subscription_payments(workspace_id,id,plan_id,amount,status,created_at,simulated_failure,provider,refunded_amount)
            VALUES ('$workspace','$orderId','$plan',$amount,'${if (refunded == amount) {
                "refunded"
            } else if (refunded > 0) {
                "partially_refunded"
            } else {
                "paid"
            }}','2026-09-30T00:00:00Z',false,'toss_live',$refunded)
            """.trimIndent(),
        )
        if (refunded > 0) {
            db.execute(
                """
                INSERT INTO toss_billing_orders(id,workspace_id,plan_id,amount,created_at,cycle_ends_at,kind,original_id,previous_remaining,status,payload_encrypted,encryption_scheme,key_version,environment,cycle_id,approved_at,canceled_at)
                VALUES ('$refundId','$workspace','$plan',$refunded,'2026-10-08T00:00:00Z','2026-11-01T00:00:00Z','refund','$orderId',$amount,'paid',decode('00','hex'),'aes256gcm-v1',1,'toss_live','$refundId','2026-10-07T00:00:00Z','2026-10-08T00:00:00Z')
                """.trimIndent(),
            )
        }
        val order =
            BillingOrder(
                orderId,
                workspace,
                plan,
                amount,
                Instant.parse("2026-09-30T00:00:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"),
                status = "paid",
                environment = "toss_live",
            )
        val payment =
            TossPayment(
                Secret("payment"),
                orderId,
                "DONE",
                amount,
                remaining,
                Secret("receipt"),
                approvedAt,
                canceledAt =
                    if (refunded >
                        0
                    ) {
                        Instant.parse("2026-10-08T00:00:00Z")
                    } else {
                        null
                    },
            )
        return PaymentFixture(order, payment)
    }

    private fun key(value: Byte): Secret = Secret(Base64.getEncoder().encodeToString(ByteArray(32) { value }))

    private data class PaymentFixture(
        val order: BillingOrder,
        val payment: TossPayment,
    )
}
