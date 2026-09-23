package kr.easydoc.application.illustration

import kr.easydoc.core.illustration.IllustrationAssetId
import kr.easydoc.core.illustration.IllustrationCatalog

/** 그림 한 장의 SVG 바이트. 사용자 콘텐츠가 아니지만 바이트 자체를 로그에 남기지 않는다. */
data class IllustrationImage(val svg: ByteArray) {
    override fun toString(): String = "IllustrationImage(${svg.size}B)"

    override fun equals(other: Any?): Boolean = other is IllustrationImage && svg.contentEquals(other.svg)

    override fun hashCode(): Int = svg.contentHashCode()
}

/**
 * 그림 카탈로그(ER-15) 를 읽는 포트. 구현은 `infrastructure` 가 진다
 * (`ResourceIllustrationCatalogSource` — 리소스에서 읽은 고정 카탈로그).
 */
interface IllustrationCatalogSource {
    fun catalog(): IllustrationCatalog

    fun image(assetId: IllustrationAssetId): IllustrationImage?
}
