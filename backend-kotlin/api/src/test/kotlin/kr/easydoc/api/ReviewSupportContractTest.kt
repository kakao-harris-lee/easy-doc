package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.application.document.CONVERSION_NOT_FOUND_MESSAGE
import kr.easydoc.application.document.ReviewSupportService
import kr.easydoc.core.exceptions.NotFoundException
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
import org.springframework.test.web.servlet.put
import java.util.UUID

@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class ReviewSupportContractTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var users: InMemoryUserRepository

    @Autowired private lateinit var workspaces: InMemoryWorkspaceRepository

    @Autowired private lateinit var service: ReviewSupportService

    @Test
    fun `기능 OFF 서비스의 존재 은닉 오류는 API 404다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        `when`(service.get(owner, conversionId)).thenThrow(NotFoundException(CONVERSION_NOT_FOUND_MESSAGE))

        val response =
            mockMvc
                .get("/conversions/$conversionId/review-support") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(404)
    }

    @Test
    fun `분석 본문 revision 0은 서비스 호출 전 422다`() {
        val response =
            mockMvc
                .post("/conversions/${UUID.randomUUID()}/review-support") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:${newOwner()}")
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"expected_content_revision":0}"""
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(422)
    }

    @Test
    fun `항목 갱신의 content 0과 review 음수는 각각 422다`() {
        val owner = newOwner()
        val path = "/conversions/${UUID.randomUUID()}/review-support/items/${UUID.randomUUID()}"
        val assessmentId = UUID.randomUUID()

        val invalidContent =
            mockMvc
                .put(path) {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content = updateBody(assessmentId, 0, 0)
                }.andReturn()
                .response
        val invalidReview =
            mockMvc
                .put(path) {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content = updateBody(assessmentId, 1, -1)
                }.andReturn()
                .response

        assertThat(invalidContent.status).isEqualTo(422)
        assertThat(invalidReview.status).isEqualTo(422)
    }

    @Test
    fun `배치 갱신의 빈 item ids와 중복 item ids는 422다`() {
        val owner = newOwner()
        val path = "/conversions/${UUID.randomUUID()}/review-support/items"
        val assessmentId = UUID.randomUUID()
        val itemId = UUID.randomUUID()

        val empty =
            mockMvc
                .put(path) {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content = batchUpdateBody(assessmentId, emptyList())
                }.andReturn()
                .response
        val duplicate =
            mockMvc
                .put(path) {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content = batchUpdateBody(assessmentId, listOf(itemId, itemId))
                }.andReturn()
                .response

        assertThat(empty.status).isEqualTo(422)
        assertThat(duplicate.status).isEqualTo(422)
    }

    private fun updateBody(
        assessmentId: UUID,
        contentRevision: Long,
        reviewRevision: Long,
    ): String =
        """{"assessment_id":"$assessmentId","expected_content_revision":$contentRevision,""" +
            """"expected_review_revision":$reviewRevision,"state":"confirmed"}"""

    private fun batchUpdateBody(
        assessmentId: UUID,
        itemIds: List<UUID>,
    ): String =
        """{"assessment_id":"$assessmentId","expected_content_revision":1,"expected_review_revision":0,""" +
            """"item_ids":[${itemIds.joinToString(",") { "\"$it\"" }}],"state":"confirmed"}"""

    private fun newOwner(): UUID {
        val id = users.create("review-support-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
        users.markEmailVerified(id)
        return id
    }

    private companion object {
        val STUB_HASH = PasswordHash("stub-hash")
    }
}
