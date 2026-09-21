package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.application.document.ExplanationsService
import kr.easydoc.application.document.ExplanationsView
import kr.easydoc.core.dictionary.Explanation
import kr.easydoc.core.dictionary.ExplanationDefinitionSource
import kr.easydoc.core.easyread.SourceAnchor
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID

@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class ExplanationsContractTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var users: InMemoryUserRepository

    @Autowired private lateinit var workspaces: InMemoryWorkspaceRepository

    @Autowired private lateinit var service: ExplanationsService

    @Test
    fun `근거가 있는 설명은 200과 private headers, 계약 필드를 낸다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val explanationText = "행정심판은 행정청의 처분에 이의를 제기하는 절차입니다"
        `when`(service.read(owner, conversionId)).thenReturn(
            ExplanationsView(
                conversionId,
                4,
                listOf(
                    Explanation(
                        term = "행정심판",
                        definitionSource = ExplanationDefinitionSource.DICTIONARY_REVIEWED,
                        explanation = explanationText,
                        sourceAnchors =
                            listOf(
                                SourceAnchor(listOf(0, 2), "행정심판"),
                            ),
                    ),
                ),
            ),
        )

        val response =
            mockMvc
                .get("/conversions/$conversionId/explanations") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response
        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        val body = response.contentAsString
        assertThat(body).contains("\"conversion_id\":\"$conversionId\"")
        assertThat(body).contains("\"current_content_revision\":4")
        assertThat(body).contains("\"term\":\"행정심판\"")
        assertThat(body).contains("\"definition_source\":\"dictionary_reviewed\"")
        assertThat(body).contains("\"explanation\":\"$explanationText\"")
        assertThat(body).contains("\"source_unit_indexes\":[0,2]")
        assertThat(body).contains("\"quote\":\"행정심판\"")
        assertThat(response.toString()).doesNotContain(explanationText)
    }

    @Test
    fun `근거를 못 찾으면 source_anchors는 빈 배열이고 설명은 그대로 남는다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        `when`(service.read(owner, conversionId)).thenReturn(
            ExplanationsView(
                conversionId,
                1,
                listOf(
                    Explanation(
                        term = "행정심판",
                        definitionSource = ExplanationDefinitionSource.DICTIONARY_REVIEWED,
                        explanation = "행정심판 설명",
                        sourceAnchors = emptyList(),
                    ),
                ),
            ),
        )

        val response =
            mockMvc
                .get("/conversions/$conversionId/explanations") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response
        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentAsString).contains("\"source_anchors\":[]")
        assertThat(response.contentAsString).contains("\"term\":\"행정심판\"")
    }

    @Test
    fun `기능 OFF와 소유권 은닉은 404다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        `when`(service.read(owner, conversionId))
            .thenThrow(NotFoundException("변환을 찾을 수 없습니다"))

        val response =
            mockMvc
                .get("/conversions/$conversionId/explanations") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response
        assertThat(response.status).isEqualTo(404)
    }

    @Test
    fun `변환이 완료 전이면 409다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        `when`(service.read(owner, conversionId))
            .thenThrow(ConflictException("변환이 완료되지 않았습니다"))

        val response =
            mockMvc
                .get("/conversions/$conversionId/explanations") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response
        assertThat(response.status).isEqualTo(409)
    }

    @Test
    fun `Authorization 헤더가 없으면 401이다`() {
        val conversionId = UUID.randomUUID()

        val response =
            mockMvc
                .get("/conversions/$conversionId/explanations")
                .andReturn()
                .response
        assertThat(response.status).isEqualTo(401)
    }

    private fun newOwner(): UUID {
        val id = users.create("explanations-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
        users.markEmailVerified(id)
        return id
    }

    private companion object {
        val STUB_HASH = PasswordHash("stub-hash")
    }
}
