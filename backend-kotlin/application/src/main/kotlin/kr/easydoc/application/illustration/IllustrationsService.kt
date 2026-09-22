package kr.easydoc.application.illustration

import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.illustration.Illustration
import kr.easydoc.core.illustration.IllustrationAssetId

/**
 * 그림 카탈로그(ER-15) 조회 유스케이스. [enabled] 는 옵트인 토글이다
 * (`easydoc.illustrations.enabled`, 기본 false) — 꺼져 있으면 목록·이미지 모두 404다.
 */
class IllustrationsService(
    private val enabled: Boolean,
    private val source: IllustrationCatalogSource,
) {
    /** 검수된 항목만, 카탈로그에 실린 순서 그대로. */
    fun list(): List<Illustration> {
        if (!enabled) throw NotFoundException(ILLUSTRATIONS_NOT_FOUND_MESSAGE)
        return source.catalog().selectable()
    }

    /**
     * 그림 한 장의 SVG 바이트. 기능 OFF·미존재·미검수 셋을 **구분하지 않는 같은 404**다 —
     * 검수되지 않은 항목은 선택할 수 없으므로(AC-R7-a) 이미지도 내지 않는다.
     */
    @Suppress("ThrowsCount") // 셋 다 같은 404(존재 은닉)이며 갈래 수가 아니라 판정 지점의 수다.
    fun image(assetId: IllustrationAssetId): IllustrationImage {
        if (!enabled) throw NotFoundException(ILLUSTRATION_NOT_FOUND_MESSAGE)
        val illustration = source.catalog().find(assetId)?.takeIf { it.selectable }
        if (illustration == null) throw NotFoundException(ILLUSTRATION_NOT_FOUND_MESSAGE)
        return source.image(assetId) ?: throw NotFoundException(ILLUSTRATION_NOT_FOUND_MESSAGE)
    }
}
