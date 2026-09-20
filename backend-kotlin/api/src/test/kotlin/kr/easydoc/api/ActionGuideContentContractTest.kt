package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.application.actionguide.ActionGuideContentService
import kr.easydoc.application.actionguide.ActionGuideResourceView
import kr.easydoc.application.actionguide.ActionGuideStatus
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
import org.springframework.test.web.servlet.put
import java.util.UUID

@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class ActionGuideContentContractTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var users: InMemoryUserRepository

    @Autowired private lateinit var workspaces: InMemoryWorkspaceRepository

    @Autowired private lateinit var service: ActionGuideContentService

    @Test
    fun `안내문 조회는 상태와 작업 식별자를 반환하고 저장을 막는다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val jobId = UUID.randomUUID()
        `when`(service.get(owner, conversionId))
            .thenReturn(ActionGuideResourceView(ActionGuideStatus.NOT_GENERATED, null, jobId, jobId))

        val response =
            mockMvc
                .get("/conversions/$conversionId/action-guide") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentAsString).contains("\"status\":\"not_generated\"")
        assertThat(response.contentAsString).contains("\"active_job_id\":\"$jobId\"")
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store")
    }

    @Test
    fun `수동 저장 본문의 알 수 없는 필드는 422다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val sections =
            listOf("eligibility", "benefits", "documents", "steps", "exceptions", "contact")
                .joinToString(",") { """{"kind":"$it","status":"not_in_source","items":[]}""" }
        val body =
            """{"candidate_id":null,"expected_content_revision":1,"expected_guide_revision":null,""" +
                """"mark_reviewed":false,"content":{"schema_version":1,"sections":[$sections],"unexpected":true}}"""

        val response =
            mockMvc
                .put("/conversions/$conversionId/action-guide") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(422)
    }

    @Test
    fun `내보내기는 UTF-8 첨부 파일과 private 헤더를 반환한다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val bytes = "행동 안내문\n".toByteArray(Charsets.UTF_8)
        `when`(service.export(owner, conversionId, 3)).thenReturn(bytes)

        val response =
            mockMvc
                .get("/conversions/$conversionId/action-guide/export?guide_revision=3") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentAsByteArray).isEqualTo(bytes)
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment")
        assertThat(response.getHeader(HttpHeaders.CONTENT_TYPE)).contains("charset=UTF-8")
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store")
    }

    private fun newOwner(): UUID {
        val id = users.create("action-guide-content-${UUID.randomUUID()}@example.test", PasswordHash("stub-hash")).id
        workspaces.createDefault(id)
        users.markEmailVerified(id)
        return id
    }
}
