package kr.easydoc.api

import kr.easydoc.api.config.JsonRequestStrictnessConfig
import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.application.actionguide.ActionGuideAnalysisService
import kr.easydoc.application.actionguide.GuideAnalysisSnapshot
import kr.easydoc.application.actionguide.GuideAnalysisView
import kr.easydoc.core.actionguide.GuideActionPresence
import kr.easydoc.core.actionguide.GuideAnalysisResult
import kr.easydoc.core.actionguide.GuideCoverageStatus
import kr.easydoc.core.actionguide.GuideSourceUnit
import kr.easydoc.core.actionguide.GuideSuitability
import kr.easydoc.core.actionguide.GuideUnitAssessment
import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, JsonRequestStrictnessConfig::class, AuthSliceBeans::class)
class ActionGuideAnalysisContractTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var users: InMemoryUserRepository

    @Autowired private lateinit var workspaces: InMemoryWorkspaceRepository

    @Autowired private lateinit var service: ActionGuideAnalysisService

    @Test
    fun `fake analysis is explicit and generation is disabled with private response headers`() {
        val owner = owner()
        val conversion = UUID.randomUUID()
        val request = UUID.randomUUID()
        val result =
            GuideAnalysisResult(
                GuideSuitability.UNCERTAIN,
                GuideActionPresence.UNCERTAIN,
                "검증용",
                emptyList(),
                emptyList(),
                listOf(GuideUnitAssessment(0, GuideCoverageStatus.NEEDS_REVIEW, emptyList())),
                listOf("unreviewed"),
                false,
            )
        val snapshot =
            GuideAnalysisSnapshot(
                UUID.randomUUID(),
                1,
                1,
                listOf(GuideSourceUnit(0, "원문")),
                "쉬운 본문",
                "grade_3_4",
                result,
                Instant.parse("2026-09-26T00:00:00Z"),
            )
        `when`(service.create(owner, conversion, request, 1)).thenReturn(GuideAnalysisView(snapshot, "current"))
        val response =
            mvc
                .post("/conversions/$conversion/action-guide-analyses") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"request_id":"$request","expected_content_revision":1}"""
                }.andReturn()
                .response
        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeader("Cache-Control")).contains("no-store")
        val json = mapper.readTree(response.contentAsByteArray)
        assertThat(json["schema_version"].asInt()).isEqualTo(2)
        assertThat(json["generation_enabled"].asBoolean()).isFalse()
        assertThat(json["provenance"].asString()).isEqualTo("fake")
        assertThat(json["source_units"][0]["text"].asString()).isEqualTo("원문")
        assertThat(json["allowed_modes"].size()).isZero()
        assertThat(json.has("savedBody")).isFalse()
    }

    @Test
    fun `authentication precedes path parsing and revision is required positive`() {
        assertThat(
            mvc
                .get("/conversions/not-a-uuid/action-guide-analyses")
                .andReturn()
                .response.status,
        ).isEqualTo(401)
        val owner = owner()
        listOf("{}", """{"request_id":"${UUID.randomUUID()}","expected_content_revision":0}""").forEach { body ->
            val response =
                mvc
                    .post("/conversions/${UUID.randomUUID()}/action-guide-analyses") {
                        header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                        contentType = MediaType.APPLICATION_JSON
                        content = body
                    }.andReturn()
                    .response
            assertThat(response.status).isEqualTo(422)
        }
    }

    private fun owner(): UUID {
        val id = users.create("analysis-${UUID.randomUUID()}@example.test", PasswordHash("stub-hash")).id
        workspaces.createDefault(id)
        users.markEmailVerified(id)
        return id
    }
}
