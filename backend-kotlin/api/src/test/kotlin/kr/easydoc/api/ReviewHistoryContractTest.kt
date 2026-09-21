package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.application.document.CONVERSION_NOT_FOUND_MESSAGE
import kr.easydoc.application.document.ReviewHistoryEventType
import kr.easydoc.application.document.ReviewHistoryEventView
import kr.easydoc.application.document.ReviewHistoryPageView
import kr.easydoc.application.document.ReviewHistoryService
import kr.easydoc.application.document.ReviewHistorySnapshotView
import kr.easydoc.core.exceptions.InvalidInputException
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
import java.time.Instant
import java.util.UUID

@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class ReviewHistoryContractTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var users: InMemoryUserRepository

    @Autowired private lateinit var workspaces: InMemoryWorkspaceRepository

    @Autowired private lateinit var service: ReviewHistoryService

    @Test
    fun `기능 OFF와 소유권 은닉은 404다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        `when`(service.page(owner, conversionId, null, 20))
            .thenThrow(NotFoundException(CONVERSION_NOT_FOUND_MESSAGE))

        val response =
            mockMvc
                .get("/conversions/$conversionId/review-history") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response
        assertThat(response.status).isEqualTo(404)
    }

    @Test
    fun `목록과 TXT는 private headers와 필수 null 키를 포함한다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        `when`(service.page(owner, conversionId, null, 20))
            .thenReturn(ReviewHistoryPageView(conversionId, 2, emptyList(), null))
        `when`(service.export(owner, conversionId)).thenReturn("검수 기록\n".toByteArray())

        val page =
            mockMvc
                .get("/conversions/$conversionId/review-history") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response
        assertThat(page.status).isEqualTo(200)
        assertThat(page.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(page.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(page.contentAsString).contains("\"next_cursor\":null")

        val export =
            mockMvc
                .get("/conversions/$conversionId/review-history/export") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response
        assertThat(export.status).isEqualTo(200)
        assertThat(export.getHeader(HttpHeaders.CONTENT_DISPOSITION))
            .isEqualTo("attachment; filename=review-history.txt")
        assertThat(export.getHeader("Cache-Control")).isEqualTo("no-store")
    }

    @Test
    fun `nullable로 선언된 키는 값이 없어도 JSON에 null로 남는다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val event =
            ReviewHistoryEventView(
                eventId = UUID.randomUUID(),
                eventType = ReviewHistoryEventType.INVALIDATED_BY_EDIT,
                createdAt = Instant.parse("2026-09-01T00:00:00Z"),
                actorUserId = owner,
                contentRevision = 3,
                artifactRevision = null,
                itemId = null,
                assessmentId = null,
                guideId = null,
                snapshot =
                    ReviewHistorySnapshotView(
                        status = "invalidated",
                        kind = null,
                        contentText = null,
                        artifactJson = null,
                    ),
            )
        `when`(service.page(owner, conversionId, null, 20))
            .thenReturn(ReviewHistoryPageView(conversionId, 3, listOf(event), null))

        val page =
            mockMvc
                .get("/conversions/$conversionId/review-history") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response
        assertThat(page.status).isEqualTo(200)
        // @get:JsonInclude(Include.ALWAYS)는 값이 null이어도 키 자체를 남기라는 계약(required)이다.
        // 원문 JSON을 그대로 검사해 키가 생략되지 않고 명시적 null로 남는지 실측한다.
        assertThat(page.contentAsString)
            .contains("\"artifact_revision\":null")
            .contains("\"item_id\":null")
            .contains("\"assessment_id\":null")
            .contains("\"guide_id\":null")
            .contains("\"kind\":null")
            .contains("\"content_text\":null")
            .contains("\"artifact_json\":null")
    }

    @Test
    fun `잘못된 cursor는 서비스의 422를 그대로 경계에 낸다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        `when`(service.page(owner, conversionId, "bad", 20))
            .thenThrow(InvalidInputException("검수 기록 커서가 올바르지 않습니다"))

        val response =
            mockMvc
                .get("/conversions/$conversionId/review-history?cursor=bad") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response
        assertThat(response.status).isEqualTo(422)
    }

    @Test
    fun `페이지 크기 상한은 서비스의 422를 그대로 경계에 낸다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        `when`(service.page(owner, conversionId, null, 51))
            .thenThrow(InvalidInputException("검수 기록 페이지 크기가 올바르지 않습니다"))

        val response =
            mockMvc
                .get("/conversions/$conversionId/review-history?limit=51") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response
        assertThat(response.status).isEqualTo(422)
    }

    private fun newOwner(): UUID {
        val id = users.create("review-history-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
        users.markEmailVerified(id)
        return id
    }

    private companion object {
        val STUB_HASH = PasswordHash("stub-hash")
    }
}
