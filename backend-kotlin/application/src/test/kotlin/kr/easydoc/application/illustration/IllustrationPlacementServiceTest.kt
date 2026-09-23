package kr.easydoc.application.illustration

import kr.easydoc.application.document.CONTENT_REVISION_CONFLICT_MESSAGE
import kr.easydoc.application.document.CONTENT_REVISION_INVALID_MESSAGE
import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.application.document.ConversionEnvelope
import kr.easydoc.application.document.FakeContentCipher
import kr.easydoc.application.document.FakeConversionRepository
import kr.easydoc.application.document.FakeDocumentOriginalRepository
import kr.easydoc.application.document.LockedConversion
import kr.easydoc.application.document.RecordingTransactionRunner
import kr.easydoc.application.document.StoredConversion
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.illustration.Illustration
import kr.easydoc.core.illustration.IllustrationAssetId
import kr.easydoc.core.illustration.IllustrationCatalog
import kr.easydoc.core.illustration.IllustrationPlacement
import kr.easydoc.core.illustration.IllustrationPlacements
import kr.easydoc.core.illustration.IllustrationPurpose
import kr.easydoc.core.illustration.IllustrationReviewStatus
import kr.easydoc.core.illustration.PLACEMENT_ASSET_NOT_SELECTABLE_MESSAGE
import kr.easydoc.core.illustration.PLACEMENT_UNIT_OUT_OF_RANGE_MESSAGE
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class IllustrationPlacementServiceTest {
    @Test
    fun `꺼져 있으면 조회와 저장 모두 404다`() {
        val world = World(enabled = false)
        val conversionId = world.seedDone(easyText = "첫째 줄\n둘째 줄")

        assertThatThrownBy { world.service.read(OWNER, conversionId) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy {
            world.service.replace(OWNER, conversionId, 1, IllustrationPlacements(emptyList()))
        }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `내 것이 아니거나 없으면 404다`() {
        val world = World()

        assertThatThrownBy { world.service.read(OWNER, UUID.randomUUID()) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `DONE이 아니면 409다`() {
        val world = World()
        val conversionId = world.seedNotDone()

        assertThatThrownBy { world.service.read(OWNER, conversionId) }.isInstanceOf(ConflictException::class.java)
        assertThatThrownBy {
            world.service.replace(OWNER, conversionId, 1, IllustrationPlacements(emptyList()))
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `본문 버전이 다르면 409다`() {
        val world = World()
        val conversionId = world.seedDone(easyText = "첫째 줄\n둘째 줄")

        assertThatThrownBy {
            world.service.replace(OWNER, conversionId, 2, IllustrationPlacements(emptyList()))
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage(CONTENT_REVISION_CONFLICT_MESSAGE)
    }

    @Test
    fun `본문 버전 하한을 422로 검증한다`() {
        val world = World()
        val conversionId = world.seedDone(easyText = "첫째 줄")

        assertThatThrownBy {
            world.service.replace(OWNER, conversionId, 0, IllustrationPlacements(emptyList()))
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(CONTENT_REVISION_INVALID_MESSAGE)
    }

    @Test
    fun `본문 줄 수 밖의 easyUnitIndex는 422다`() {
        val world = World()
        val conversionId = world.seedDone(easyText = "첫째 줄\n둘째 줄")
        val requested = IllustrationPlacements(listOf(IllustrationPlacement(5, ASSET_VISIT_OFFICE.assetId)))

        assertThatThrownBy { world.service.replace(OWNER, conversionId, 1, requested) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(PLACEMENT_UNIT_OUT_OF_RANGE_MESSAGE)
    }

    @Test
    fun `미검수 asset은 422다`() {
        val world = World()
        val conversionId = world.seedDone(easyText = "첫째 줄\n둘째 줄")
        val requested = IllustrationPlacements(listOf(IllustrationPlacement(0, ASSET_PHONE_CALL.assetId)))

        assertThatThrownBy { world.service.replace(OWNER, conversionId, 1, requested) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(PLACEMENT_ASSET_NOT_SELECTABLE_MESSAGE)
    }

    @Test
    fun `정상 저장은 왕복하고 stale이 아니다`() {
        val world = World()
        val conversionId = world.seedDone(easyText = "첫째 줄\n둘째 줄\n셋째 줄")
        val requested =
            IllustrationPlacements(
                listOf(
                    IllustrationPlacement(0, ASSET_VISIT_OFFICE.assetId),
                    IllustrationPlacement(2, ASSET_VISIT_OFFICE.assetId),
                ),
            )

        val saved = world.service.replace(OWNER, conversionId, 1, requested)

        assertThat(saved.stale).isFalse()
        assertThat(saved.placements).isEqualTo(requested.entries)
        assertThat(saved.placementsContentRevision).isEqualTo(1)

        val reopened = world.service.read(OWNER, conversionId)
        assertThat(reopened.stale).isFalse()
        assertThat(reopened.placements).isEqualTo(requested.entries)
        assertThat(reopened.placementsContentRevision).isEqualTo(1)
    }

    @Test
    fun `두 번째 저장도 처음 행 id 로 봉인한다`() {
        val world = World()
        val conversionId = world.seedDone(easyText = "첫째 줄\n둘째 줄")
        val first = IllustrationPlacements(listOf(IllustrationPlacement(0, ASSET_VISIT_OFFICE.assetId)))
        val second = IllustrationPlacements(listOf(IllustrationPlacement(1, ASSET_VISIT_OFFICE.assetId)))
        world.service.replace(OWNER, conversionId, 1, first)
        val rows = world.placements.rows
        val cipher = world.cipher
        val firstRowId = rows.getValue(conversionId).id

        world.service.replace(OWNER, conversionId, 1, second)

        val sealedRecord = cipher.sealed.last().second
        assertThat(rows.getValue(conversionId).id)
            .withFailMessage("대역이 행 id 를 갈아 끼웠다 — 실물 ON CONFLICT 는 기존 id 를 유지한다")
            .isEqualTo(firstRowId)
        assertThat(sealedRecord)
            .withFailMessage("두 번째 저장이 새 행 id 로 봉인했다 — 저장된 행 id 는 그대로라 다음 조회가 깨진다")
            .isEqualTo(firstRowId)

        val reopened = world.service.read(OWNER, conversionId)

        val openedRecord = cipher.decryptions.last().first
        assertThat(reopened.placements).isEqualTo(second.entries)
        assertThat(openedRecord)
            .withFailMessage("조회가 저장 때와 다른 결속 인자로 열었다 — 실물이라면 태그 검증이 실패한다")
            .isEqualTo(firstRowId)
    }

    @Test
    fun `빈 목록으로 저장하면 지운다`() {
        val world = World()
        val conversionId = world.seedDone(easyText = "첫째 줄\n둘째 줄")
        val seeded = IllustrationPlacements(listOf(IllustrationPlacement(0, ASSET_VISIT_OFFICE.assetId)))
        world.service.replace(OWNER, conversionId, 1, seeded)

        val cleared = world.service.replace(OWNER, conversionId, 1, IllustrationPlacements(emptyList()))

        assertThat(cleared.placements).isEmpty()
        assertThat(cleared.placementsContentRevision).isNull()
        assertThat(cleared.stale).isFalse()
        assertThat(world.placements.rows).isEmpty()
    }

    @Test
    fun `본문이 바뀌면 저장된 배치를 stale로 낸다`() {
        val world = World()
        val conversionId = world.seedDone(easyText = "첫째 줄\n둘째 줄")
        val seeded = IllustrationPlacements(listOf(IllustrationPlacement(0, ASSET_VISIT_OFFICE.assetId)))
        world.service.replace(OWNER, conversionId, 1, seeded)
        world.bumpContentRevision(conversionId)

        val reopened = world.service.read(OWNER, conversionId)

        assertThat(reopened.stale).isTrue()
        assertThat(reopened.placements).hasSize(1)
        assertThat(reopened.currentContentRevision).isEqualTo(2)
        assertThat(reopened.placementsContentRevision).isEqualTo(1)
    }

    @Test
    fun `배치가 없으면 조회는 빈 목록에 stale=false다`() {
        val world = World()
        val conversionId = world.seedDone(easyText = "첫째 줄")

        val view = world.service.read(OWNER, conversionId)

        assertThat(view.placements).isEmpty()
        assertThat(view.placementsContentRevision).isNull()
        assertThat(view.stale).isFalse()
    }

    private class World(enabled: Boolean = true) {
        val transaction = RecordingTransactionRunner()
        val cipher = FakeContentCipher(writeKeyVersion = 1, transaction = transaction)
        private val originals = FakeDocumentOriginalRepository(transaction)
        val conversions = FakeConversionRepository(transaction, originals)
        val placements = FakePlacementRepository()
        private val catalog =
            FakeCatalogSource(IllustrationCatalog(listOf(ASSET_VISIT_OFFICE, ASSET_PHONE_CALL)))
        val service =
            IllustrationPlacementService(
                enabled = enabled,
                conversions = conversions,
                catalog = catalog,
                placements = placements,
                cipher = cipher,
                transaction = transaction,
            )

        fun seedDone(easyText: String): UUID {
            val conversionId = UUID.randomUUID()
            val documentId = UUID.randomUUID()
            val sealed = cipher.encrypt(PlainBody(easyText), conversionId, EncryptedField.CONVERSION_EASY_TEXT)
            val envelope =
                ConversionEnvelope(
                    conversionId,
                    cipher.writeScheme,
                    cipher.writeKeyVersion,
                    ConversionCiphertexts(sealed, null),
                )
            conversions.lockedForReview[OWNER to conversionId] = LockedConversion(ConversionStatus.DONE, envelope, 1)
            conversions.owned[OWNER to conversionId] =
                StoredConversion(
                    conversionId,
                    documentId,
                    ConversionStatus.DONE,
                    SourceFormat.TEXT,
                    false,
                    envelope.ciphertexts,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                )
            return conversionId
        }

        fun seedNotDone(): UUID {
            val conversionId = UUID.randomUUID()
            val documentId = UUID.randomUUID()
            val envelope =
                ConversionEnvelope(
                    conversionId,
                    cipher.writeScheme,
                    cipher.writeKeyVersion,
                    ConversionCiphertexts(null, null),
                )
            conversions.lockedForReview[OWNER to conversionId] =
                LockedConversion(ConversionStatus.PROCESSING, envelope, 0)
            conversions.owned[OWNER to conversionId] =
                StoredConversion(
                    conversionId,
                    documentId,
                    ConversionStatus.PROCESSING,
                    SourceFormat.TEXT,
                    false,
                    envelope.ciphertexts,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                )
            return conversionId
        }

        /** 검수 저장처럼 본문 버전을 1 올린다 — 저장된 배치가 stale이 되는 갈래를 흉내 낸다. */
        fun bumpContentRevision(conversionId: UUID) {
            val key = OWNER to conversionId
            val locked = conversions.lockedForReview.getValue(key)
            val bumped = locked.contentRevision + 1
            conversions.lockedForReview[key] = LockedConversion(locked.status, locked.envelope, bumped)
            conversions.owned[key] = conversions.owned.getValue(key).copy(contentRevision = bumped)
        }
    }

    private class FakePlacementRepository : IllustrationPlacementRepository {
        val rows = mutableMapOf<UUID, StoredIllustrationPlacements>()
        private val owners = mutableMapOf<UUID, UUID>()

        override fun findOwned(
            ownerId: UUID,
            conversionId: UUID,
        ): StoredIllustrationPlacements? = rows[conversionId]?.takeIf { owners[conversionId] == ownerId }

        /**
         * 실물 SQL 과 같은 upsert 규약 — `ON CONFLICT (conversion_id) DO UPDATE` 는 `id` 를
         * 갱신하지 않으므로, 행이 이미 있으면 **기존 id 가 그대로 유지된다**([id] 는 행이 아직
         * 없을 때만 쓰인다). `JdbcIllustrationPlacementRepositoryTest` 가 이 규약을 SQL 로 잰다.
         */
        override fun replaceOwned(
            ownerId: UUID,
            conversionId: UUID,
            id: UUID,
            contentRevision: Long,
            payload: EncryptedContent,
        ): Boolean {
            owners[conversionId] = ownerId
            val keptId = rows[conversionId]?.id ?: id
            rows[conversionId] =
                StoredIllustrationPlacements(keptId, conversionId, contentRevision, payload, Instant.EPOCH)
            return true
        }

        override fun deleteOwned(
            ownerId: UUID,
            conversionId: UUID,
        ): Boolean {
            if (owners[conversionId] != ownerId) return false
            owners.remove(conversionId)
            return rows.remove(conversionId) != null
        }

        override fun lockEnvelope(id: UUID): StoredIllustrationPlacements? = rows.values.firstOrNull { it.id == id }

        override fun rewriteEnvelope(
            expected: StoredIllustrationPlacements,
            payload: EncryptedContent,
        ): Boolean {
            if (rows[expected.conversionId] != expected) return false
            rows[expected.conversionId] = expected.copy(payload = payload)
            return true
        }

        override fun idsOlderThan(
            keyVersion: Int,
            after: UUID,
            limit: Int,
        ): List<UUID> = emptyList()
    }

    private class FakeCatalogSource(private val catalog: IllustrationCatalog) : IllustrationCatalogSource {
        override fun catalog(): IllustrationCatalog = catalog

        override fun image(assetId: IllustrationAssetId): IllustrationImage? = null
    }

    private companion object {
        val OWNER: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000c2")

        val ASSET_VISIT_OFFICE =
            Illustration(
                assetId = IllustrationAssetId.of("visit-office"),
                caption = "기관 방문",
                purpose = IllustrationPurpose.VISIT_OFFICE,
                altText = "사람이 건물 입구로 걸어 들어가는 그림",
                license = "CC0-1.0",
                source = "easy-doc",
                reviewStatus = IllustrationReviewStatus.REVIEWED,
                reviewedBy = "harris.lee",
                reviewedAt = LocalDate.of(2026, 9, 23),
                version = 1,
                mappingExamples = listOf("주민센터에 직접 가서 신청하세요."),
            )
        val ASSET_PHONE_CALL =
            Illustration(
                assetId = IllustrationAssetId.of("phone-call"),
                caption = "전화 문의",
                purpose = IllustrationPurpose.PHONE_CALL,
                altText = "수화기를 든 손 그림",
                license = "CC0-1.0",
                source = "easy-doc",
                reviewStatus = IllustrationReviewStatus.UNREVIEWED,
                reviewedBy = null,
                reviewedAt = null,
                version = 1,
                mappingExamples = listOf("전화로 물어보세요."),
            )
    }
}
