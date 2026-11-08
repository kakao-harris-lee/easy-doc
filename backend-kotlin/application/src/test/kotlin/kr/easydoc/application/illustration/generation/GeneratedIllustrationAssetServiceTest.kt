package kr.easydoc.application.illustration.generation

import kr.easydoc.application.illustration.suggestion.PassThroughCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.illustration.generation.IllustrationGenerationStorageCodec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class GeneratedIllustrationAssetServiceTest {
    private val jobs = FakeGenerationJobs()
    private val assets = RecordingGenerationAssets()
    private val cipher = PassThroughCipher()

    init {
        val metadata =
            cipher.encrypt(
                PlainBody(IllustrationGenerationStorageCodec.encode(generationInput())),
                ASSET_ID,
                EncryptedField.ILLUSTRATION_GENERATION_ASSET_METADATA,
            )
        assets.inserted +=
            StoredGeneratedIllustrationAsset(
                assetId = ASSET_ID,
                jobId = GENERATION_JOB,
                conversionId = GENERATION_CONVERSION,
                basedOnContentRevision = 3,
                metadata = metadata,
                png = metadata,
                pngByteSize = 3,
                width = 1024,
                height = 1024,
                provenance = "fake",
                createdAt = GENERATION_NOW,
            )
    }

    @Test
    fun `대체텍스트 수정은 재조회에 유지되고 생성 provenance와 구성 revision은 바꾸지 않는다`() {
        val original = assets.inserted.single()
        val originalMetadata = decode(original).metadata
        val altText = "수정한 한국어 설명 " + "😀".repeat(150)

        val updated = service().updateReview(GENERATION_OWNER, GENERATION_CONVERSION, ASSET_ID, 3, 1, altText, null)

        assertThat(updated.assetRevision).isEqualTo(2)
        assertThat(updated.metadata.altText).isEqualTo(altText)
        assertThat(updated.metadata.configurationRevision).isEqualTo(originalMetadata.configurationRevision)
        assertThat(updated.metadata.suggestionId).isEqualTo(originalMetadata.suggestionId)
        assertThat(updated.provenance).isEqualTo(original.provenance)
        val listed = service().list(GENERATION_OWNER, GENERATION_CONVERSION).assets.single()
        assertThat(listed.metadata.altText).isEqualTo(altText)
    }

    @Test
    fun `대체텍스트는 공백과 301 코드 포인트를 거절하고 CAS와 stale을 검사한다`() {
        assertThatThrownBy {
            service().updateReview(GENERATION_OWNER, GENERATION_CONVERSION, ASSET_ID, 3, 1, " \t", null)
        }.isInstanceOf(InvalidInputException::class.java)
        assertThatThrownBy {
            service().updateReview(
                GENERATION_OWNER,
                GENERATION_CONVERSION,
                ASSET_ID,
                3,
                1,
                "😀".repeat(301),
                null,
            )
        }.isInstanceOf(InvalidInputException::class.java)
        assertThatThrownBy {
            service().updateReview(GENERATION_OWNER, GENERATION_CONVERSION, ASSET_ID, 3, 1, "수정", false)
        }.isInstanceOf(InvalidInputException::class.java)
        assertThatThrownBy {
            service().updateReview(GENERATION_OWNER, GENERATION_CONVERSION, ASSET_ID, 3, 1, null, null)
        }.isInstanceOf(InvalidInputException::class.java)
        assertThatThrownBy {
            service().updateReview(GENERATION_OWNER, GENERATION_CONVERSION, ASSET_ID, 3, 2, "수정", null)
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage(GENERATION_ASSET_REVISION_CONFLICT_MESSAGE)

        jobs.context = defaultGenerationContext().copy(contentRevision = 4)
        assertThatThrownBy {
            service().updateReview(GENERATION_OWNER, GENERATION_CONVERSION, ASSET_ID, 4, 1, "수정", null)
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage(GENERATION_CONTENT_REVISION_CONFLICT_MESSAGE)
        assertThat(assets.inserted.single().assetRevision).isEqualTo(1)
    }

    @Test
    fun `폐기는 일반 목록과 적용에서만 숨기고 소유자의 기존 이미지 URL은 유지한다`() {
        val discarded =
            service().updateReview(GENERATION_OWNER, GENERATION_CONVERSION, ASSET_ID, 3, 1, null, true)

        assertThat(discarded.discarded).isTrue()
        assertThat(discarded.assetRevision).isEqualTo(2)
        assertThat(service().list(GENERATION_OWNER, GENERATION_CONVERSION).assets).isEmpty()
        assertThat(service().image(GENERATION_OWNER, GENERATION_CONVERSION, ASSET_ID).bytes).isNotEmpty()
        assertThatThrownBy {
            service().updateApplication(GENERATION_OWNER, GENERATION_CONVERSION, ASSET_ID, 3, 2, true)
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage(GENERATED_ILLUSTRATION_DISCARDED_MESSAGE)
        assertThatThrownBy {
            service().updateReview(GENERATION_OWNER, GENERATION_CONVERSION, ASSET_ID, 3, 2, null, true)
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage(GENERATED_ILLUSTRATION_DISCARDED_MESSAGE)
    }

    @Test
    fun `적용된 자산은 먼저 적용을 해제해야 폐기할 수 있다`() {
        val index = assets.inserted.indexOfFirst { it.assetId == ASSET_ID }
        assets.inserted[index] = assets.inserted[index].copy(applied = true)

        assertThatThrownBy {
            service().updateReview(GENERATION_OWNER, GENERATION_CONVERSION, ASSET_ID, 3, 1, null, true)
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage(GENERATED_ILLUSTRATION_APPLIED_DISCARD_MESSAGE)
        assertThat(assets.inserted.single().assetRevision).isEqualTo(1)
    }

    @Test
    fun `자산 읽기가 꺼지면 목록 이미지 수정은 404다`() {
        val disabled = service(readEnabled = false)

        assertThatThrownBy { disabled.list(GENERATION_OWNER, GENERATION_CONVERSION) }
            .isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { disabled.image(GENERATION_OWNER, GENERATION_CONVERSION, ASSET_ID) }
            .isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy {
            disabled.updateReview(GENERATION_OWNER, GENERATION_CONVERSION, ASSET_ID, 3, 1, "수정", null)
        }.isInstanceOf(NotFoundException::class.java)
    }

    private fun service(readEnabled: Boolean = true) =
        GeneratedIllustrationAssetService(readEnabled, jobs, assets, cipher, DirectGenerationTransaction())

    private fun decode(asset: StoredGeneratedIllustrationAsset) =
        IllustrationGenerationStorageCodec.decode(
            cipher.decrypt(asset.metadata, asset.assetId, EncryptedField.ILLUSTRATION_GENERATION_ASSET_METADATA).value,
        )

    private companion object {
        val ASSET_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    }
}
