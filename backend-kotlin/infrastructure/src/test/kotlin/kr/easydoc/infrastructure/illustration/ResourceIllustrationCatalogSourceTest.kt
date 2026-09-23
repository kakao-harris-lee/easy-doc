package kr.easydoc.infrastructure.illustration

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.illustration.IllustrationAssetId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ResourceIllustrationCatalogSourceTest {
    @Test
    fun `실제 카탈로그가 로드되고 10개 이하·전부 SVG 존재·전부 REVIEWED다`() {
        val source = ResourceIllustrationCatalogSource()

        val catalog = source.catalog()

        assertThat(catalog.size).isLessThanOrEqualTo(10)
        assertThat(catalog.selectable()).hasSize(catalog.size)
        catalog.selectable().forEach { illustration ->
            val image = source.image(illustration.assetId)
            assertThat(image).withFailMessage { "${illustration.assetId.value} 의 이미지가 없다" }.isNotNull()
            assertThat(String(image!!.svg, Charsets.UTF_8)).contains("<svg")
        }
    }

    @Test
    fun `SVG 없는 항목이 있으면 기동이 실패한다`() {
        assertThatThrownBy { ResourceIllustrationCatalogSource(basePath = "/illustrations-invalid/missing-svg") }
            .isInstanceOf(ConfigurationException::class.java)
    }

    @Test
    fun `UNREVIEWED 항목이 있는 카탈로그는 로드되지만 selectable에서 빠진다`() {
        val source = ResourceIllustrationCatalogSource(basePath = "/illustrations-invalid/unreviewed")

        val catalog = source.catalog()

        assertThat(catalog.size).isEqualTo(2)
        assertThat(catalog.selectable().map { it.assetId }).containsExactly(IllustrationAssetId.of("test-reviewed"))
    }

    @Test
    fun `image()를 두 번 부르면 값은 같지만 같은 배열 인스턴스가 아니다`() {
        val source = ResourceIllustrationCatalogSource()
        val assetId =
            source
                .catalog()
                .selectable()
                .first()
                .assetId

        val first = source.image(assetId)!!
        val second = source.image(assetId)!!

        assertThat(first.svg.contentEquals(second.svg)).isTrue()
        assertThat(first.svg).isNotSameAs(second.svg)
    }
}
