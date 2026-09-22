package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.illustration.IllustrationResponse
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.application.illustration.IllustrationImage
import kr.easydoc.application.illustration.IllustrationsService
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.illustration.Illustration
import kr.easydoc.core.illustration.IllustrationAssetId
import kr.easydoc.core.illustration.IllustrationPurpose
import kr.easydoc.core.illustration.IllustrationReviewStatus
import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.reset
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDate
import java.util.UUID

@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class IllustrationsContractTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var users: InMemoryUserRepository

    @Autowired private lateinit var workspaces: InMemoryWorkspaceRepository

    @Autowired private lateinit var service: IllustrationsService

    /**
     * 이 mock 빈은 `@TestConfiguration` 이 평범한 `@Bean` 으로 만든 것이라(`@MockBean` 이
     * 아니다) Spring 이 테스트 사이에 자동으로 리셋하지 않는다 — 캐시된 컨텍스트를 여러
     * `@Test` 메서드가 공유하므로, `list()`처럼 인자가 없는 메서드는 앞선 테스트의 스텁이
     * 그대로 남아 다음 테스트의 `when(...)` 평가 시점에 다시 던져진다(구현 중 실측). 다른
     * 계약 테스트들이 이 문제를 안 겪는 이유는 매번 새 `UUID`를 인자로 써 스텁 매처가
     * 갈리기 때문이다 — 인자가 없는 이 서비스는 그 회피가 통하지 않아 명시적으로 리셋한다.
     */
    @BeforeEach
    fun resetServiceMock() {
        reset(service)
    }

    @Test
    fun `목록 200은 계약 필드와 private headers를 낸다`() {
        val owner = newOwner()
        `when`(service.list()).thenReturn(listOf(VISIT_OFFICE))

        val response =
            mockMvc
                .get("/illustrations") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        val body = response.contentAsString
        assertThat(body).contains("\"asset_id\":\"visit-office\"")
        assertThat(body).contains("\"purpose\":\"visit_office\"")
        assertThat(body).contains("\"image_url\":\"/illustrations/visit-office/image\"")
        assertThat(body).contains("\"mapping_examples\":[")
    }

    @Test
    fun `목록은 인증이 없으면 401이다`() {
        val response = mockMvc.get("/illustrations").andReturn().response

        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `목록은 기능이 꺼져 있으면 404다`() {
        val owner = newOwner()
        `when`(service.list()).thenThrow(NotFoundException("그림 목록을 찾을 수 없습니다"))

        val response =
            mockMvc
                .get("/illustrations") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(404)
    }

    @Test
    fun `이미지 200은 svg 미디어 타입과 4개 헤더를 낸다`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".toByteArray()
        `when`(service.image(IllustrationAssetId.of("visit-office"))).thenReturn(IllustrationImage(svg))

        val response = mockMvc.get("/illustrations/visit-office/image").andReturn().response

        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentType).startsWith("image/svg+xml")
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        // 이 바이트는 캐시해도 되는 공개 픽토그램이지만, 이 저장소는 Cache-Control 을 전역
        // 하나의 값(no-store)으로 못박는다(PrivateResponseHeadersConfig KDoc,
        // ContractSpec 의 헤더 일관성 검사) — 이 오퍼레이션만 다른 값을 쓰면 그 불변식이
        // 깨진다(IllustrationsController.image KDoc이 이유다).
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(response.getHeader("Content-Security-Policy"))
            .isEqualTo("default-src 'none'; style-src 'unsafe-inline'; sandbox")
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("inline")
        assertThat(response.contentAsString).startsWith("<svg")
    }

    @Test
    fun `이미지는 인증 없이도 200이다`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".toByteArray()
        `when`(service.image(IllustrationAssetId.of("visit-office"))).thenReturn(IllustrationImage(svg))

        // Authorization 헤더를 보내지 않는다 — 위 테스트와 동일 요청이며 그 자체가 단언이다.
        val response = mockMvc.get("/illustrations/visit-office/image").andReturn().response

        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `이미지는 서비스가 404면 404다`() {
        `when`(service.image(IllustrationAssetId.of("visit-office")))
            .thenThrow(NotFoundException("그림을 찾을 수 없습니다"))

        val response = mockMvc.get("/illustrations/visit-office/image").andReturn().response

        assertThat(response.status).isEqualTo(404)
    }

    @Test
    fun `asset_id 패턴 위반은 서비스를 부르지 않고 404다`() {
        val response = mockMvc.get("/illustrations/BAD%20ID/image").andReturn().response

        assertThat(response.status).isEqualTo(404)
        verifyNoInteractions(service)
    }

    @Test
    fun `IllustrationResponse toString은 caption과 alt_text를 찍지 않는다`() {
        val response = IllustrationResponse.of(VISIT_OFFICE)

        val text = response.toString()

        assertThat(text).doesNotContain(VISIT_OFFICE.caption)
        assertThat(text).doesNotContain(VISIT_OFFICE.altText)
        assertThat(text).contains("visit-office")
    }

    private fun newOwner(): UUID {
        val id = users.create("illustrations-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
        users.markEmailVerified(id)
        return id
    }

    private companion object {
        val STUB_HASH = PasswordHash("stub-hash")
        val VISIT_OFFICE =
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
    }
}
