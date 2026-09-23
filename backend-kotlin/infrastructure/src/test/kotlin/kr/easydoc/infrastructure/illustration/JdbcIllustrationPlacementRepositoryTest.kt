package kr.easydoc.infrastructure.illustration

import kr.easydoc.application.illustration.IllustrationPlacementCodec
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.exceptions.DecryptionFailedException
import kr.easydoc.core.illustration.IllustrationAssetId
import kr.easydoc.core.illustration.IllustrationPlacement
import kr.easydoc.core.illustration.IllustrationPlacements
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.Base64
import java.util.UUID

/**
 * ER-16 `illustration_placements` 저장소 — **실제 PostgreSQL 에서만 잴 수 있는 것**.
 *
 * 특히 [JdbcIllustrationPlacementRepository.replaceOwned] 의 `ON CONFLICT (conversion_id)
 * DO UPDATE` 는 `id` 를 갱신하지 않는다. 봉인 AAD 가 그 행 `id` 에 결속되므로, 두 번째
 * 저장이 새 `id` 로 봉인한 암호문을 실으면 다음 조회의 복호화가 깨진다. 대역
 * (`IllustrationPlacementServiceTest.FakePlacementRepository`)은 이 규약을 흉내 낼 뿐이고,
 * SQL 자신이 그렇게 도는지는 여기서만 드러난다.
 */
class JdbcIllustrationPlacementRepositoryTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var repository: JdbcIllustrationPlacementRepository
    private val cipher =
        AesGcmContentCipher(
            mapOf(1 to Secret(Base64.getEncoder().encodeToString(ByteArray(32) { 9 }))),
            writeKeyVersion = 1,
        )

    @BeforeEach
    fun prepare() {
        val database = PostgresTestSupport.createEmptyDatabase("illustration_placements")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        jdbc = JdbcClient.create(DriverManagerDataSource(database.jdbcUrl, database.username, database.password))
        repository = JdbcIllustrationPlacementRepository(jdbc)
    }

    @Test
    @DisplayName("소유자만 읽고 쓰고 지운다 — 남의 변환에는 행이 생기지도 보이지도 않는다")
    fun `소유 술어가 SQL 자체에 걸려 있다`() {
        val fixture = seed()
        val stranger = insertUser()
        val rowId = UUID.randomUUID()

        assertThat(repository.findOwned(fixture.ownerId, fixture.conversionId)).isNull()
        assertThat(repository.replaceOwned(stranger, fixture.conversionId, rowId, 1, sealed(rowId, SAMPLE)))
            .withFailMessage("남의 소유자로 배치가 저장됐다 — INSERT ... WHERE EXISTS 의 소유 술어가 없다")
            .isFalse()
        assertThat(count()).isZero()

        assertThat(repository.replaceOwned(fixture.ownerId, fixture.conversionId, rowId, 1, sealed(rowId, SAMPLE)))
            .isTrue()

        assertThat(repository.findOwned(stranger, fixture.conversionId)).isNull()
        assertThat(repository.deleteOwned(stranger, fixture.conversionId)).isFalse()
        assertThat(count()).isEqualTo(1)

        val stored = checkNotNull(repository.findOwned(fixture.ownerId, fixture.conversionId))
        assertThat(stored.id).isEqualTo(rowId)
        assertThat(stored.conversionId).isEqualTo(fixture.conversionId)
        assertThat(stored.contentRevision).isEqualTo(1)
        assertThat(open(stored.payload, stored.id)).isEqualTo(SAMPLE)

        assertThat(repository.deleteOwned(fixture.ownerId, fixture.conversionId)).isTrue()
        assertThat(count()).isZero()
    }

    @Test
    @DisplayName("두 번 저장해도 행 id 는 처음 것이다 — ON CONFLICT 가 id 를 갱신하지 않는다")
    fun `두 번째 저장은 기존 행 id 를 유지한다`() {
        val fixture = seed()
        val firstId = UUID.randomUUID()
        val strayId = UUID.randomUUID()
        repository.replaceOwned(fixture.ownerId, fixture.conversionId, firstId, 1, sealed(firstId, SAMPLE))

        // 호출자가 기존 행을 읽지 않고 새 UUID 로 봉인한 경우 — 서비스가 막아야 하는 그 갈래다.
        assertThat(repository.replaceOwned(fixture.ownerId, fixture.conversionId, strayId, 2, sealed(strayId, OTHER)))
            .isTrue()

        val stored = checkNotNull(repository.findOwned(fixture.ownerId, fixture.conversionId))
        assertThat(count()).isEqualTo(1)
        assertThat(stored.id)
            .withFailMessage("두 번째 저장이 행 id 를 바꿨다 — 그러면 봉인 AAD 결속 축이 저장마다 흔들린다")
            .isEqualTo(firstId)
        assertThat(stored.contentRevision).isEqualTo(2)
        assertThatThrownBy { open(stored.payload, stored.id) }
            .withFailMessage("새 id 로 봉인한 암호문이 옛 id 로 열렸다 — AAD 가 행 id 에 결속돼 있지 않다")
            .isInstanceOf(DecryptionFailedException::class.java)
    }

    @Test
    @DisplayName("읽은 id 를 그대로 다시 넘기면 두 번째 저장 뒤에도 복호화된다 — 서비스가 지키는 규약")
    fun `기존 id 를 재사용하면 왕복한다`() {
        val fixture = seed()
        val firstId = UUID.randomUUID()
        repository.replaceOwned(fixture.ownerId, fixture.conversionId, firstId, 1, sealed(firstId, SAMPLE))

        val reused = checkNotNull(repository.findOwned(fixture.ownerId, fixture.conversionId)).id
        repository.replaceOwned(fixture.ownerId, fixture.conversionId, reused, 2, sealed(reused, OTHER))

        val stored = checkNotNull(repository.findOwned(fixture.ownerId, fixture.conversionId))
        assertThat(stored.id).isEqualTo(firstId)
        assertThat(open(stored.payload, stored.id)).isEqualTo(OTHER)
    }

    @Test
    @DisplayName("다른 행 id 로 옮긴 암호문은 열리지 않는다 — AAD 가 행 id 에 결속된다")
    fun `다른 행으로 옮긴 암호문은 복호화에 실패한다`() {
        val fixture = seed()
        val other = seed()
        val mineId = UUID.randomUUID()
        val theirsId = UUID.randomUUID()
        repository.replaceOwned(fixture.ownerId, fixture.conversionId, mineId, 1, sealed(mineId, SAMPLE))
        val mine = checkNotNull(repository.findOwned(fixture.ownerId, fixture.conversionId))
        // 내 변환의 암호문을 **남의 행 id 로** 저장한다 — 봉투 두 값은 그대로 따라가고 결속
        // 인자만 갈린다(암호문을 다른 행으로 옮겨 붙인 것과 같은 상태다).
        repository.replaceOwned(other.ownerId, other.conversionId, theirsId, 1, mine.payload)

        val moved = checkNotNull(repository.findOwned(other.ownerId, other.conversionId))
        assertThat(moved.id).isEqualTo(theirsId)
        assertThatThrownBy { open(moved.payload, moved.id) }
            .withFailMessage("남의 행 id 로 저장한 암호문이 그 행 id 로 열렸다 — AAD 가 행 id 에 결속돼 있지 않다")
            .isInstanceOf(DecryptionFailedException::class.java)
        assertThat(open(moved.payload, mineId))
            .withFailMessage("같은 암호문이 원래 행 id 로도 열리지 않는다 — 실패가 AAD 때문이 아니다")
            .isEqualTo(SAMPLE)
    }

    @Test
    @DisplayName("보존 기간이 지난 문서의 배치는 읽기·쓰기·삭제 모두 0행이다")
    fun `보존 기간이 조회와 쓰기 모두에 걸린다`() {
        val fixture = seed()
        val rowId = UUID.randomUUID()
        repository.replaceOwned(fixture.ownerId, fixture.conversionId, rowId, 1, sealed(rowId, SAMPLE))

        jdbc
            .sql("UPDATE documents SET retention_expires_at = now() - interval '1 second' WHERE id = :id")
            .param("id", fixture.documentId)
            .update()

        assertThat(repository.findOwned(fixture.ownerId, fixture.conversionId)).isNull()
        assertThat(repository.replaceOwned(fixture.ownerId, fixture.conversionId, rowId, 2, sealed(rowId, OTHER)))
            .isFalse()
        assertThat(repository.deleteOwned(fixture.ownerId, fixture.conversionId)).isFalse()
        assertThat(count()).isEqualTo(1)
    }

    @Test
    @DisplayName("문서를 지우면 배치도 CASCADE 로 사라진다 (V34)")
    fun `문서 삭제가 배치를 함께 지운다`() {
        val fixture = seed()
        val rowId = UUID.randomUUID()
        repository.replaceOwned(fixture.ownerId, fixture.conversionId, rowId, 1, sealed(rowId, SAMPLE))

        jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", fixture.documentId).update()

        assertThat(count()).isZero()
    }

    private fun sealed(
        rowId: UUID,
        placements: IllustrationPlacements,
    ): EncryptedContent =
        cipher.encryptBytes(
            PlainBytes(checkNotNull(IllustrationPlacementCodec.encode(placements))),
            rowId,
            EncryptedField.ILLUSTRATION_PLACEMENTS,
        )

    private fun open(
        payload: EncryptedContent,
        rowId: UUID,
    ): IllustrationPlacements =
        IllustrationPlacementCodec.decode(
            cipher.decryptBytes(payload, rowId, EncryptedField.ILLUSTRATION_PLACEMENTS).value,
        )

    private fun count(): Int =
        jdbc.sql("SELECT count(*) FROM illustration_placements").query { rs, _ -> rs.getInt(1) }.single()

    private fun insertUser(): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO users (id,email,password_hash) VALUES (:id,:email,:password)")
            .param("id", id)
            .param("email", "placement-$id@example.test")
            .param("password", DUMMY_PHC)
            .update()
        return id
    }

    private fun seed(): Fixture {
        val ownerId = insertUser()
        val workspaceId = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        val conversionId = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO workspaces (id,user_id,name) VALUES (:id,:owner,'공간')")
            .param("id", workspaceId)
            .param("owner", ownerId)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO documents
                    (id,user_id,workspace_id,title,source_format,source_text_encrypted,
                     encryption_scheme,key_version,char_count)
                VALUES (:id,:owner,:workspace,'제목','txt',:bytes,'aes256gcm-v1',1,10)
                """.trimIndent(),
            ).param("id", documentId)
            .param("owner", ownerId)
            .param("workspace", workspaceId)
            .param("bytes", byteArrayOf(1))
            .update()
        jdbc
            .sql(
                """
                INSERT INTO conversions
                    (id,document_id,status,easy_text_encrypted,encryption_scheme,key_version,content_revision)
                VALUES (:id,:document,'done',:bytes,'aes256gcm-v1',1,1)
                """.trimIndent(),
            ).param("id", conversionId)
            .param("document", documentId)
            .param("bytes", byteArrayOf(2))
            .update()
        return Fixture(ownerId, documentId, conversionId)
    }

    private class Fixture(
        val ownerId: UUID,
        val documentId: UUID,
        val conversionId: UUID,
    )

    private companion object {
        val SAMPLE =
            IllustrationPlacements(
                listOf(
                    IllustrationPlacement(0, IllustrationAssetId.of("visit-office")),
                    IllustrationPlacement(2, IllustrationAssetId.of("phone-call")),
                ),
            )
        val OTHER =
            IllustrationPlacements(
                listOf(IllustrationPlacement(1, IllustrationAssetId.of("visit-office"))),
            )
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}
