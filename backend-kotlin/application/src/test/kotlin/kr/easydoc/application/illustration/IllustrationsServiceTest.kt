package kr.easydoc.application.illustration

import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.illustration.Illustration
import kr.easydoc.core.illustration.IllustrationAssetId
import kr.easydoc.core.illustration.IllustrationCatalog
import kr.easydoc.core.illustration.IllustrationPurpose
import kr.easydoc.core.illustration.IllustrationReviewStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class IllustrationsServiceTest {
    @Test
    fun `꺼져 있으면 목록 조회가 404다`() {
        val service = IllustrationsService(enabled = false, source = FakeSource(IllustrationCatalog(listOf(REVIEWED))))

        assertThatThrownBy { service.list() }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `켜져 있으면 검수된 항목만 돌려준다`() {
        val service =
            IllustrationsService(
                enabled = true,
                source = FakeSource(IllustrationCatalog(listOf(REVIEWED, UNREVIEWED))),
            )

        val result = service.list()

        assertThat(result).containsExactly(REVIEWED)
    }

    @Test
    fun `검수되지 않은 항목의 이미지는 404다`() {
        val source = FakeSource(IllustrationCatalog(listOf(UNREVIEWED)))
        source.images[UNREVIEWED.assetId] = IllustrationImage("<svg/>".toByteArray())
        val service = IllustrationsService(enabled = true, source = source)

        assertThatThrownBy { service.image(UNREVIEWED.assetId) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `미존재 항목의 이미지는 404다`() {
        val service = IllustrationsService(enabled = true, source = FakeSource(IllustrationCatalog(emptyList())))

        assertThatThrownBy { service.image(REVIEWED.assetId) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `기능이 꺼져 있으면 존재하는 이미지도 404다`() {
        val source = FakeSource(IllustrationCatalog(listOf(REVIEWED)))
        source.images[REVIEWED.assetId] = IllustrationImage("<svg/>".toByteArray())
        val service = IllustrationsService(enabled = false, source = source)

        assertThatThrownBy { service.image(REVIEWED.assetId) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `검수된 항목의 이미지 바이트를 돌려준다`() {
        val source = FakeSource(IllustrationCatalog(listOf(REVIEWED)))
        val bytes = "<svg/>".toByteArray()
        source.images[REVIEWED.assetId] = IllustrationImage(bytes)
        val service = IllustrationsService(enabled = true, source = source)

        val image = service.image(REVIEWED.assetId)

        assertThat(image.svg).isEqualTo(bytes)
    }

    private class FakeSource(private val catalog: IllustrationCatalog) : IllustrationCatalogSource {
        val images: MutableMap<IllustrationAssetId, IllustrationImage> = mutableMapOf()

        override fun catalog(): IllustrationCatalog = catalog

        override fun image(assetId: IllustrationAssetId): IllustrationImage? = images[assetId]
    }

    private companion object {
        val REVIEWED =
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
        val UNREVIEWED =
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
