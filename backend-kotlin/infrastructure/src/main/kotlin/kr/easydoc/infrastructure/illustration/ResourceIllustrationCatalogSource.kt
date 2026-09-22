package kr.easydoc.infrastructure.illustration

import kr.easydoc.application.illustration.IllustrationCatalogSource
import kr.easydoc.application.illustration.IllustrationImage
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.illustration.Illustration
import kr.easydoc.core.illustration.IllustrationAssetId
import kr.easydoc.core.illustration.IllustrationCatalog
import kr.easydoc.core.illustration.IllustrationPurpose
import kr.easydoc.core.illustration.IllustrationReviewStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

/**
 * 그림 카탈로그(ER-15)를 클래스패스 리소스(`$basePath/catalog.json` + `$basePath/<asset_id>.svg`)
 * 에서 읽는 [IllustrationCatalogSource].
 *
 * 생성자에서 **즉시** 읽는다 — lazy 로 미루지 않는다. 잘못된 카탈로그(형식 오류·SVG 누락)는
 * 부팅 실패로 드러나야 한다(`ResourceIllustrationCatalogSourceTest`).
 */
class ResourceIllustrationCatalogSource(
    private val basePath: String = DEFAULT_BASE_PATH,
    private val json: JsonMapper = JsonMapper.builder().build(),
) : IllustrationCatalogSource {
    private val catalog: IllustrationCatalog
    private val images: Map<IllustrationAssetId, ByteArray>

    init {
        val root = readCatalogJson()
        val illustrations = root.path(FIELD_ILLUSTRATIONS).toList().map(::illustrationOf)
        catalog = IllustrationCatalog(illustrations)
        images = illustrations.associate { it.assetId to readSvg(it.assetId) }
    }

    override fun catalog(): IllustrationCatalog = catalog

    // 캐시한 바이트를 그대로 내보내지 않는다 — 호출부가 반환받은 ByteArray를 변형하면
    // 공유 캐시(images)가 오염된다. 노출 시점에 복사한다(10개뿐이라 비용이 무시할 만하다).
    override fun image(assetId: IllustrationAssetId): IllustrationImage? =
        images[assetId]?.let { IllustrationImage(it.copyOf()) }

    private fun readCatalogJson(): JsonNode {
        val path = "$basePath/$CATALOG_FILE"
        val stream =
            javaClass.getResourceAsStream(path)
                ?: throw ConfigurationException("그림 카탈로그 리소스가 없다: $path")
        return stream.use(json::readTree)
    }

    @Suppress("ThrowsCount") // 형식 오류마다 무엇이 잘못됐는지 구분하는 기동 실패 진단이며 도메인 분기가 아니다.
    private fun illustrationOf(node: JsonNode): Illustration {
        val assetIdWire = required(node, FIELD_ASSET_ID)
        val assetId =
            IllustrationAssetId.parse(assetIdWire)
                ?: throw ConfigurationException("그림 카탈로그 asset_id 형식이 올바르지 않다: $assetIdWire")
        val purposeWire = required(node, FIELD_PURPOSE)
        val purpose =
            IllustrationPurpose.fromWire(purposeWire)
                ?: throw ConfigurationException("알 수 없는 그림 용도다: $purposeWire")
        val review = node.path(FIELD_REVIEW)
        val statusWire = required(review, FIELD_REVIEW_STATUS)
        val status =
            IllustrationReviewStatus.fromWire(statusWire)
                ?: throw ConfigurationException("알 수 없는 그림 검수 상태다: $statusWire")
        return Illustration(
            assetId = assetId,
            caption = required(node, FIELD_CAPTION),
            purpose = purpose,
            altText = required(node, FIELD_ALT_TEXT),
            license = required(node, FIELD_LICENSE),
            source = required(node, FIELD_SOURCE),
            reviewStatus = status,
            reviewedBy = optional(review, FIELD_REVIEWED_BY),
            reviewedAt = optional(review, FIELD_REVIEWED_AT)?.let(LocalDate::parse),
            version = node.path(FIELD_VERSION).asInt(),
            mappingExamples = node.path(FIELD_MAPPING_EXAMPLES).toList().map { it.stringValue("") },
        )
    }

    private fun readSvg(assetId: IllustrationAssetId): ByteArray {
        val path = "$basePath/${assetId.value}.svg"
        val stream =
            javaClass.getResourceAsStream(path)
                ?: throw ConfigurationException("그림 카탈로그 SVG 리소스가 없다: $path")
        return stream.use { it.readBytes() }
    }

    private fun required(
        node: JsonNode,
        field: String,
    ): String =
        node.path(field).stringValue("").ifEmpty {
            throw ConfigurationException("그림 카탈로그 항목에 '$field' 값이 없다")
        }

    private fun optional(
        node: JsonNode,
        field: String,
    ): String? = node.path(field).stringValue("").ifEmpty { null }

    companion object {
        const val DEFAULT_BASE_PATH: String = "/illustrations"

        private const val CATALOG_FILE = "catalog.json"
        private const val FIELD_ILLUSTRATIONS = "illustrations"
        private const val FIELD_ASSET_ID = "asset_id"
        private const val FIELD_CAPTION = "caption"
        private const val FIELD_PURPOSE = "purpose"
        private const val FIELD_ALT_TEXT = "alt_text"
        private const val FIELD_LICENSE = "license"
        private const val FIELD_SOURCE = "source"
        private const val FIELD_REVIEW = "review"
        private const val FIELD_REVIEW_STATUS = "status"
        private const val FIELD_REVIEWED_BY = "reviewed_by"
        private const val FIELD_REVIEWED_AT = "reviewed_at"
        private const val FIELD_VERSION = "version"
        private const val FIELD_MAPPING_EXAMPLES = "mapping_examples"
    }
}
