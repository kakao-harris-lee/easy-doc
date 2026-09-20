package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.StoredActionGuide
import kr.easydoc.application.actionguide.StoredActionGuideCandidate
import kr.easydoc.application.actionguide.StoredActionGuideJob
import kr.easydoc.core.actionguide.ActionGuideJobStatus
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** ER-06의 소유권·보존기간·CAS·파기와 AEAD 결속을 실제 PostgreSQL에서 확인한다. */
class JdbcActionGuideContentRepositoryTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var repository: JdbcActionGuideContentRepository
    private lateinit var dataSource: DriverManagerDataSource
    private val cipher =
        AesGcmContentCipher(
            mapOf(1 to Secret(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))),
            writeKeyVersion = 1,
        )

    @BeforeEach
    fun prepare() {
        val database = PostgresTestSupport.createEmptyDatabase("action_guide_content")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        repository = JdbcActionGuideContentRepository(jdbc)
    }

    @Test
    fun `후보는 작업당 하나이며 소유권과 보존기간이 조회와 쓰기 모두에 적용된다`() {
        val fixture = seed()
        val job = fixture.job()
        insertJob(job)
        val candidate = fixture.candidate(job)
        assertThat(repository.insertCandidate(job, candidate)).isFalse() // queued 작업은 결과를 저장하지 않는다.
        jdbc
            .sql("UPDATE action_guide_jobs SET status='succeeded',settlement='consumed' WHERE id=:id")
            .param("id", job.jobId)
            .update()
        assertThat(repository.insertCandidate(job, candidate)).isTrue()
        assertThat(repository.insertCandidate(job, fixture.candidate(job))).isFalse()
        val stored = repository.findCandidateForJobOwned(fixture.ownerId, fixture.conversionId, job.jobId)!!
        assertThat(
            cipher.decrypt(stored.payload, stored.candidateId, EncryptedField.ACTION_GUIDE_CANDIDATE_PAYLOAD).value,
        ).isEqualTo("후보 본문")
        assertThat(
            repository.findCandidateOwned(UUID.randomUUID(), fixture.conversionId, candidate.candidateId),
        ).isNull()
        assertThat(repository.findCandidateOwned(fixture.ownerId, UUID.randomUUID(), candidate.candidateId)).isNull()

        jdbc
            .sql("UPDATE documents SET retention_expires_at=now()-interval '1 second' WHERE id=:id")
            .param("id", fixture.documentId)
            .update()
        assertThat(repository.findCandidateOwned(fixture.ownerId, fixture.conversionId, candidate.candidateId)).isNull()
        assertThat(repository.insertCandidate(job, fixture.candidate(job))).isFalse()
    }

    @Test
    fun `안내문은 CAS로 편집되고 본문 변경 뒤 새 후보로 교체할 수 있다`() {
        val fixture = seed()
        val guide = fixture.guide()
        assertThat(repository.saveGuide(fixture.ownerId, 1, null, guide)).isTrue()
        assertThat(repository.saveGuide(fixture.ownerId, 1, null, fixture.guide())).isFalse()
        assertThat(repository.findGuideOwned(UUID.randomUUID(), fixture.conversionId)).isNull()
        val revised = fixture.guide(guideId = guide.guideId, revision = 2)
        assertThat(repository.saveGuide(fixture.ownerId, 1, 1, revised)).isTrue()
        assertThat(
            JdbcActionGuideJobRepository(jdbc).lockOwnedContext(fixture.ownerId, fixture.conversionId)?.guideRevision,
        ).isEqualTo(2)
        assertThat(repository.saveGuide(fixture.ownerId, 1, 1, fixture.guide(guideId = guide.guideId, revision = 2)))
            .isFalse()
        assertThat(
            repository.saveGuide(
                fixture.ownerId,
                2,
                2,
                fixture.guide(guideId = guide.guideId, revision = 3, basedOnContentRevision = 2),
            ),
        ).isFalse()

        jdbc
            .sql("UPDATE conversions SET content_revision=2 WHERE id=:id")
            .param("id", fixture.conversionId)
            .update()
        val stale = repository.findGuideOwned(fixture.ownerId, fixture.conversionId)!!
        assertThat(stale.status).isEqualTo("stale")
        assertThat(stale.guideRevision).isEqualTo(3)
        assertThat(
            repository.saveGuide(
                fixture.ownerId,
                2,
                3,
                fixture.guide(guideId = guide.guideId, revision = 4, basedOnContentRevision = 2),
            ),
        ).isTrue()
        assertThat(repository.findGuideOwned(fixture.ownerId, fixture.conversionId)?.status).isEqualTo("draft")
    }

    @Test
    fun `문서 삭제는 후보와 안내문을 제거하고 작업 감사행은 보존한다`() {
        val fixture = seed()
        val job = fixture.job()
        insertJob(job)
        jdbc
            .sql("UPDATE action_guide_jobs SET status='succeeded',settlement='consumed' WHERE id=:id")
            .param("id", job.jobId)
            .update()
        assertThat(repository.insertCandidate(job, fixture.candidate(job))).isTrue()
        assertThat(repository.saveGuide(fixture.ownerId, 1, null, fixture.guide())).isTrue()
        jdbc.sql("DELETE FROM documents WHERE id=:id").param("id", fixture.documentId).update()
        assertThat(count("action_guide_candidates")).isZero()
        assertThat(count("action_guides")).isZero()
        assertThat(count("action_guide_jobs")).isEqualTo(1)
        jdbc.sql("DELETE FROM users WHERE id=:id").param("id", fixture.ownerId).update()
        assertThat(count("action_guide_jobs")).isEqualTo(1)
    }

    @Test
    fun `키 회전은 후보와 안내문을 각각의 AAD로 재봉인한다`() {
        val fixture = seed()
        val job = fixture.job()
        insertJob(job)
        jdbc
            .sql("UPDATE action_guide_jobs SET status='succeeded',settlement='consumed' WHERE id=:id")
            .param("id", job.jobId)
            .update()
        val candidate = fixture.candidate(job)
        val guide = fixture.guide()
        assertThat(repository.insertCandidate(job, candidate)).isTrue()
        assertThat(repository.saveGuide(fixture.ownerId, 1, null, guide)).isTrue()

        val rotatedCipher =
            AesGcmContentCipher(
                mapOf(
                    1 to Secret(Base64.getEncoder().encodeToString(ByteArray(32) { 7 })),
                    2 to Secret(Base64.getEncoder().encodeToString(ByteArray(32) { 8 })),
                ),
                writeKeyVersion = 2,
            )
        val rotation =
            ActionGuideContentKeyRotation(
                jdbc,
                rotatedCipher,
                TransactionTemplate(DataSourceTransactionManager(dataSource)),
                batchSize = 1,
            )
        assertThat(rotation.run()).isEqualTo(1 to 1)
        assertThat(rotation.run()).isEqualTo(0 to 0)
        val savedCandidate =
            repository.findCandidateOwned(
                fixture.ownerId,
                fixture.conversionId,
                candidate.candidateId,
            )!!
        val savedGuide = repository.findGuideOwned(fixture.ownerId, fixture.conversionId)!!
        assertThat(savedCandidate.payload.keyVersion).isEqualTo(2)
        assertThat(savedGuide.payload.keyVersion).isEqualTo(2)
        assertThat(
            rotatedCipher
                .decrypt(
                    savedCandidate.payload,
                    candidate.candidateId,
                    EncryptedField.ACTION_GUIDE_CANDIDATE_PAYLOAD,
                ).value,
        ).isEqualTo("후보 본문")
        assertThat(
            rotatedCipher
                .decrypt(
                    savedGuide.payload,
                    guide.guideId,
                    EncryptedField.ACTION_GUIDE_PAYLOAD,
                ).value,
        ).isEqualTo("안내 본문")
    }

    private fun seed(): Fixture {
        val fixture = Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        jdbc
            .sql("INSERT INTO users (id,email,password_hash) VALUES (:id,:email,:password)")
            .param("id", fixture.ownerId)
            .param("email", "u-${fixture.ownerId}@example.test")
            .param("password", DUMMY_PHC)
            .update()
        jdbc
            .sql("INSERT INTO workspaces (id,user_id,name) VALUES (:id,:owner,'공간')")
            .param("id", fixture.workspaceId)
            .param("owner", fixture.ownerId)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO documents
                    (id,user_id,workspace_id,title,source_format,source_text_encrypted,
                     encryption_scheme,key_version,char_count)
                VALUES (:id,:owner,:workspace,'제목','txt',:bytes,'aes256gcm-v1',1,10)
                """.trimIndent(),
            ).param("id", fixture.documentId)
            .param("owner", fixture.ownerId)
            .param("workspace", fixture.workspaceId)
            .param("bytes", byteArrayOf(1))
            .update()
        jdbc
            .sql(
                """
                INSERT INTO conversions
                    (id,document_id,status,easy_text_encrypted,encryption_scheme,key_version,content_revision)
                VALUES (:id,:document,'done',:bytes,'aes256gcm-v1',1,1)
                """.trimIndent(),
            ).param("id", fixture.conversionId)
            .param("document", fixture.documentId)
            .param("bytes", byteArrayOf(2))
            .update()
        return fixture
    }

    private fun insertJob(job: StoredActionGuideJob) {
        assertThat(JdbcActionGuideJobRepository(jdbc).insert(job))
            .isInstanceOf(kr.easydoc.application.actionguide.ActionGuideJobInsert.Inserted::class.java)
    }

    private fun count(table: String): Int =
        jdbc.sql("SELECT count(*) FROM $table").query { rs, _ -> rs.getInt(1) }.single()

    private inner class Fixture(
        val ownerId: UUID,
        val workspaceId: UUID,
        val documentId: UUID,
        val conversionId: UUID,
    ) {
        fun job(): StoredActionGuideJob =
            StoredActionGuideJob(
                UUID.randomUUID(),
                ownerId,
                workspaceId,
                documentId,
                conversionId,
                UUID.randomUUID(),
                null,
                1,
                BigDecimal.ONE,
                ActionGuideJobStatus.QUEUED,
                null,
                null,
                null,
                NOW,
                NOW,
            )

        fun candidate(job: StoredActionGuideJob): StoredActionGuideCandidate {
            val candidateId = UUID.randomUUID()
            return StoredActionGuideCandidate(
                candidateId,
                job.jobId,
                conversionId,
                1,
                cipher.encrypt(PlainBody("후보 본문"), candidateId, EncryptedField.ACTION_GUIDE_CANDIDATE_PAYLOAD),
                NOW,
            )
        }

        fun guide(
            guideId: UUID = UUID.randomUUID(),
            revision: Long = 1,
            basedOnContentRevision: Long = 1,
        ): StoredActionGuide =
            StoredActionGuide(
                guideId,
                conversionId,
                basedOnContentRevision,
                revision,
                "draft",
                cipher.encrypt(PlainBody("안내 본문"), guideId, EncryptedField.ACTION_GUIDE_PAYLOAD),
                null,
                null,
                NOW,
                NOW,
            )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-18T00:00:00Z")
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}
