package kr.easydoc.api.illustration

import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.api.MIGRATE_PROFILE
import kr.easydoc.application.illustration.ILLUSTRATION_NOT_FOUND_MESSAGE
import kr.easydoc.application.illustration.IllustrationsService
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.illustration.Illustration
import kr.easydoc.core.illustration.IllustrationAssetId
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@Profile("!$MIGRATE_PROFILE")
@RestController
class IllustrationsController(private val service: IllustrationsService) {
    @GetMapping(ILLUSTRATIONS_PATH)
    fun list(): ResponseEntity<IllustrationCatalogResponse> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .body(IllustrationCatalogResponse.of(service.list()))

    /**
     * **인증이 없다** (계약 `security: []`) — `<img src>`는 `Authorization` 헤더를 보낼 수
     * 없고, 이 바이트는 사용자 콘텐츠가 아니라 공공기관이 배포하는 고정 픽토그램이다.
     * `AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS`에 `/illustrations`만 있고 이 하위
     * 경로가 없는 것은 **의도적이다** — 인터셉터 패턴이 하위 경로를 덮지 않는 이 저장소의
     * 함정(`AuthenticatedEndpoints` 주석)이 여기서는 오히려 원하는 성질이다.
     *
     * `asset_id`가 [IllustrationAssetId.parse]에 실패하면 서비스를 부르지 않고 서비스가
     * 내는 404와 **같은 문구**를 낸다 — 존재를 숨긴다(형식 오류와 미존재를 구분하지 않는다).
     * **다만 `asset_id`가 공백뿐이면 이 메서드가 아니라 전역
     * `TypedValueSlotInterceptor`가 먼저 끊어 422로 답한다** — 경로·쿼리의 모든 값
     * 자리에 걸린 이 저장소의 기존 강제이며 이 오퍼레이션도 예외가 아니다(계약
     * `GET /illustrations/{asset_id}/image` 422). 공백이 아닌 형식 위반(대문자·구분자
     * 등)만 이 메서드가 404로 판정한다.
     *
     * **`Cache-Control` 은 `no-store` 다** — 이 바이트가 사실은 캐시해도 되는 공개
     * 픽토그램이라도, 이 저장소는 `Cache-Control` 을 전역에서 하나의 값으로 못박아
     * 서블릿 필터·Tomcat 밸브 2층이 **모든** 응답에 강제로 싣고(`PrivateResponseHeadersConfig`
     * KDoc), `ContractSpec.collectHeaders` 가 계약 안의 모든 `Cache-Control` 선언이 같은
     * 컴포넌트를 참조하는지 검사한다(`api`의 여러 `*ContractSpec` 계열 테스트가 그 위에서
     * 돈다). 이 오퍼레이션 하나만 다른 값을 쓰면 그 전역 불변식이 깨진다 — 공개 캐싱을
     * 원한다면 이 무조건 강제와 계약 검사를 함께 다시 설계해야 하는 별도 결정이라
     * (오케스트레이터 지침의 `public, max-age=86400` 대신) 기존 불변식을 따른다.
     */
    @GetMapping(ILLUSTRATION_IMAGE_PATH)
    fun image(
        @PathVariable(ASSET_ID) rawAssetId: String,
    ): ResponseEntity<ByteArray> {
        val assetId = IllustrationAssetId.parse(rawAssetId) ?: throw NotFoundException(ILLUSTRATION_NOT_FOUND_MESSAGE)
        val image = service.image(assetId)
        return ResponseEntity
            .ok()
            .contentType(IMAGE_SVG_XML)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(CONTENT_SECURITY_POLICY, CSP_VALUE)
            .header(HttpHeaders.CONTENT_DISPOSITION, INLINE)
            .body(image.svg)
    }

    private companion object {
        const val ILLUSTRATIONS_PATH = "/illustrations"
        const val ASSET_ID = "asset_id"
        const val ILLUSTRATION_IMAGE_PATH = "$ILLUSTRATIONS_PATH/{$ASSET_ID}/image"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val CONTENT_SECURITY_POLICY = "Content-Security-Policy"
        const val NO_STORE = "no-store"
        const val NOSNIFF = "nosniff"
        const val CSP_VALUE = "default-src 'none'; style-src 'unsafe-inline'; sandbox"
        const val INLINE = "inline"
        val IMAGE_SVG_XML: MediaType = MediaType("image", "svg+xml", Charsets.UTF_8)
    }
}

/** `GET /illustrations` 응답. 계약 `IllustrationCatalogResponse`. */
data class IllustrationCatalogResponse(
    @get:JsonProperty("illustrations") val illustrations: List<IllustrationResponse>,
) {
    companion object {
        fun of(illustrations: List<Illustration>): IllustrationCatalogResponse =
            IllustrationCatalogResponse(illustrations.map(IllustrationResponse::of))
    }
}

/**
 * 그림 카탈로그 항목 한 건. 계약 `Illustration` 스키마.
 *
 * **[toString]은 [assetId]·[purpose]·[version]만 낸다** — 이 타입은 사용자 콘텐츠가 아니지만
 * `SensitiveToStringReachTest`가 필드 이름 토큰(`altText`의 `text`)으로 민감 후보로 잡으므로
 * 값을 찍지 않는 게이트 규약을 그대로 따른다.
 */
data class IllustrationResponse(
    @get:JsonProperty("asset_id") val assetId: String,
    @get:JsonProperty("caption") val caption: String,
    @get:JsonProperty("purpose") val purpose: String,
    @get:JsonProperty("alt_text") val altText: String,
    @get:JsonProperty("license") val license: String,
    @get:JsonProperty("source") val source: String,
    @get:JsonProperty("reviewed_by") val reviewedBy: String,
    @get:JsonProperty("reviewed_at") val reviewedAt: String,
    @get:JsonProperty("version") val version: Int,
    @get:JsonProperty("mapping_examples") val mappingExamples: List<String>,
    @get:JsonProperty("image_url") val imageUrl: String,
) {
    override fun toString(): String = "IllustrationResponse(assetId=$assetId, purpose=$purpose, version=$version)"

    companion object {
        fun of(illustration: Illustration): IllustrationResponse =
            IllustrationResponse(
                assetId = illustration.assetId.value,
                caption = illustration.caption,
                purpose = illustration.purpose.wire,
                altText = illustration.altText,
                license = illustration.license,
                source = illustration.source,
                // selectable() 이 REVIEWED만 돌려주므로 도메인 불변식(Illustration.init)에
                // 따라 항상 non-null이다 — 계약이 이 둘을 required로 두는 이유가 같다.
                reviewedBy = requireNotNull(illustration.reviewedBy) { "선택 가능한 그림은 reviewedBy 가 있어야 한다" },
                reviewedAt =
                    requireNotNull(illustration.reviewedAt) { "선택 가능한 그림은 reviewedAt 이 있어야 한다" }.toString(),
                version = illustration.version,
                mappingExamples = illustration.mappingExamples,
                imageUrl = "/illustrations/${illustration.assetId.value}/image",
            )
    }
}
