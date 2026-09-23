package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.illustration.IllustrationPlacementsResponse
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.application.document.CONTENT_REVISION_CONFLICT_MESSAGE
import kr.easydoc.application.document.CONVERSION_NOT_FOUND_MESSAGE
import kr.easydoc.application.illustration.IllustrationPlacementService
import kr.easydoc.application.illustration.IllustrationPlacementsView
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.illustration.IllustrationAssetId
import kr.easydoc.core.illustration.IllustrationPlacement
import kr.easydoc.core.illustration.IllustrationPlacements
import kr.easydoc.core.illustration.PLACEMENT_ASSET_NOT_SELECTABLE_MESSAGE
import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.reset
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.util.UUID

@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class IllustrationPlacementContractTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var users: InMemoryUserRepository

    @Autowired private lateinit var workspaces: InMemoryWorkspaceRepository

    @Autowired private lateinit var service: IllustrationPlacementService

    /** `AuthSliceBeans`의 평범한 `@Bean` mock 은 테스트 사이에 자동으로 리셋되지 않는다. */
    @BeforeEach
    fun resetServiceMock() {
        reset(service)
    }

    @Test
    fun `GET 200은 계약 필드와 private headers를 낸다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val view =
            IllustrationPlacementsView(
                conversionId = conversionId,
                currentContentRevision = 1,
                placementsContentRevision = 1,
                stale = false,
                placements = listOf(IllustrationPlacement(0, IllustrationAssetId.of("visit-office"))),
            )
        `when`(service.read(owner, conversionId)).thenReturn(view)

        val response =
            mockMvc
                .get("/conversions/$conversionId/illustration-placements") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        val body = response.contentAsString
        assertThat(body).contains("\"conversion_id\":\"$conversionId\"")
        assertThat(body).contains("\"current_content_revision\":1")
        assertThat(body).contains("\"placements_content_revision\":1")
        assertThat(body).contains("\"stale\":false")
        assertThat(body).contains("\"easy_unit_index\":0")
        assertThat(body).contains("\"asset_id\":\"visit-office\"")
    }

    @Test
    fun `GET은 본문이 바뀌면 stale=true를 그대로 낸다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val view =
            IllustrationPlacementsView(
                conversionId = conversionId,
                currentContentRevision = 2,
                placementsContentRevision = 1,
                stale = true,
                placements = listOf(IllustrationPlacement(0, IllustrationAssetId.of("visit-office"))),
            )
        `when`(service.read(owner, conversionId)).thenReturn(view)

        val response =
            mockMvc
                .get("/conversions/$conversionId/illustration-placements") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(200)
        val body = response.contentAsString
        assertThat(body).contains("\"stale\":true")
        assertThat(body).contains("\"current_content_revision\":2")
        assertThat(body).contains("\"placements_content_revision\":1")
    }

    @Test
    fun `GET은 인증이 없으면 401이다`() {
        val response =
            mockMvc.get("/conversions/${UUID.randomUUID()}/illustration-placements").andReturn().response

        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `GET은 서비스가 404면 404다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        `when`(service.read(owner, conversionId)).thenThrow(NotFoundException(CONVERSION_NOT_FOUND_MESSAGE))

        val response =
            mockMvc
                .get("/conversions/$conversionId/illustration-placements") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(404)
    }

    @Test
    fun `GET은 uuid가 아닌 conversion_id에 422다`() {
        val response =
            mockMvc
                .get("/conversions/not-a-uuid/illustration-placements") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:${newOwner()}")
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(422)
    }

    @Test
    fun `PUT 200은 저장된 배치를 그대로 낸다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val view =
            IllustrationPlacementsView(
                conversionId = conversionId,
                currentContentRevision = 1,
                placementsContentRevision = 1,
                stale = false,
                placements = listOf(IllustrationPlacement(0, IllustrationAssetId.of("visit-office"))),
            )
        val requested = IllustrationPlacements(listOf(IllustrationPlacement(0, IllustrationAssetId.of("visit-office"))))
        `when`(service.replace(owner, conversionId, 1, requested)).thenReturn(view)

        val response =
            mockMvc
                .put("/conversions/$conversionId/illustration-placements") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content = placementsBody(1, listOf(0 to "visit-office"))
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store")
        val body = response.contentAsString
        assertThat(body).contains("\"placements_content_revision\":1")
    }

    @Test
    fun `PUT은 본문 버전 충돌에 409다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        `when`(service.replace(owner, conversionId, 1, IllustrationPlacements(emptyList())))
            .thenThrow(ConflictException(CONTENT_REVISION_CONFLICT_MESSAGE))

        val response =
            mockMvc
                .put("/conversions/$conversionId/illustration-placements") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content = placementsBody(1, emptyList())
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(409)
    }

    @Test
    fun `PUT은 서비스의 InvalidInput을 422로 낸다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val requested = IllustrationPlacements(listOf(IllustrationPlacement(0, IllustrationAssetId.of("visit-office"))))
        `when`(service.replace(owner, conversionId, 1, requested))
            .thenThrow(InvalidInputException(PLACEMENT_ASSET_NOT_SELECTABLE_MESSAGE))

        val response =
            mockMvc
                .put("/conversions/$conversionId/illustration-placements") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content = placementsBody(1, listOf(0 to "visit-office"))
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(422)
    }

    @Test
    fun `PUT은 형식이 맞지 않는 asset_id를 서비스 호출 전 422로 낸다`() {
        val response =
            mockMvc
                .put("/conversions/${UUID.randomUUID()}/illustration-placements") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:${newOwner()}")
                    contentType = MediaType.APPLICATION_JSON
                    content = placementsBody(1, listOf(0 to "Bad Id"))
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(422)
    }

    @Test
    fun `PUT은 expected_content_revision이 없으면 422다`() {
        val response =
            mockMvc
                .put("/conversions/${UUID.randomUUID()}/illustration-placements") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:${newOwner()}")
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"placements":[]}"""
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(422)
    }

    @Test
    fun `PUT은 placements가 없으면 422다`() {
        val response =
            mockMvc
                .put("/conversions/${UUID.randomUUID()}/illustration-placements") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:${newOwner()}")
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"expected_content_revision":1}"""
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(422)
    }

    @Test
    fun `PUT은 인증이 없으면 401이다`() {
        val response =
            mockMvc
                .put("/conversions/${UUID.randomUUID()}/illustration-placements") {
                    contentType = MediaType.APPLICATION_JSON
                    content = placementsBody(1, emptyList())
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `PUT은 uuid가 아닌 conversion_id에 422다`() {
        val response =
            mockMvc
                .put("/conversions/not-a-uuid/illustration-placements") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:${newOwner()}")
                    contentType = MediaType.APPLICATION_JSON
                    content = placementsBody(1, emptyList())
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(422)
    }

    @Test
    fun `응답 DTO toString은 좌표와 asset을 찍지 않는다`() {
        val view =
            IllustrationPlacementsView(
                conversionId = UUID.randomUUID(),
                currentContentRevision = 3,
                placementsContentRevision = 3,
                stale = false,
                placements = listOf(IllustrationPlacement(7, IllustrationAssetId.of("payment"))),
            )
        val response = IllustrationPlacementsResponse.of(view)

        val text = response.toString()

        assertThat(text).doesNotContain("payment", "\"7\"")
        assertThat(text).contains("count=1")
    }

    private fun placementsBody(
        expectedContentRevision: Long,
        entries: List<Pair<Int, String>>,
    ): String {
        val items =
            entries.joinToString(",") { (index, assetId) ->
                """{"easy_unit_index":$index,"asset_id":"$assetId"}"""
            }
        return """{"expected_content_revision":$expectedContentRevision,"placements":[$items]}"""
    }

    private fun newOwner(): UUID {
        val id = users.create("illustration-placements-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
        users.markEmailVerified(id)
        return id
    }

    private companion object {
        val STUB_HASH = PasswordHash("stub-hash")
    }
}
