package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * `POST /conversions/{conversion_id}/units/{source_unit_index}/reconvert` 실측 — 실제
 * PostgreSQL·실제 HTTP 를 태우고 LLM 만 `fake` provider(`easydoc.llm.provider=fake`,
 * `test` 프로필에서만 허용 — `LlmProviderConfiguration.requireFakeAllowed`)로 대신한다.
 * 유료 호출 없이 실제 배선(마스킹 → 예약 → 변환 → 정산 → 응답)이 끝까지 도는지 잰다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "easydoc.auth.jwt-secret=$RECONVERT_REACH_TEST_SECRET",
        "easydoc.llm.provider=fake",
        "spring.profiles.active=test",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconvertUnitReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("완료된 내 변환의 원본 단위를 다시 변환하면 200 · 후보 텍스트 · 예산이 실제로 줄어든다")
    fun `실제 배선을 끝까지 태운다`() {
        val token = newAccount()
        val (_, conversionId) = doneConversion(token, SOURCE_TEXT)

        val response = reconvert(token, conversionId, 0)

        assertThat(response.statusCode())
            .withFailMessage("응답 본문: %s", response.body())
            .isEqualTo(ContractSpec.successStatus(RECONVERT_PATH, POST))
        val body = bodyOf(response)
        assertThat(body[CANDIDATE_TEXT_PROPERTY]).isNotNull()
        assertThat(body[SOURCE_UNIT_INDEX_PROPERTY]).isEqualTo(0)
        assertThat(body[EASY_UNIT_INDEXES_PROPERTY]).isEqualTo(listOf(0))
        assertThat(body[FINGERPRINT_PROPERTY]).isEqualTo(FINGERPRINT)
        assertThat((body[LLM_CALLS_USED_PROPERTY] as Number).toInt()).isBetween(1, 2)

        val remaining = (body[REMAINING_BUDGET_PROPERTY] as Number).toInt()
        val used = (body[LLM_CALLS_USED_PROPERTY] as Number).toInt()
        assertThat(remaining).isEqualTo(DEFAULT_CALL_BUDGET - used)
    }

    // ================================================================ 요청 조립

    private fun newAccount(): String {
        val email = "reconvert-reach-${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(jsonRequest(SIGNUP_PATH, null).POST(bodyPublisher(credentials)))
        // 이메일 인증 게이트는 `POST /documents` 앞이다 — 실물 인증 흐름 대신 저장소를
        // 직접 인증 완료로 만든다(`ConversionReviewReachTest`와 같은 자리).
        database.execute("UPDATE users SET email_verified_at = now() WHERE email = '$email'")
        return bodyOf(send(jsonRequest(LOGIN_PATH, null).POST(bodyPublisher(credentials))))
            .getValue("access_token")
            .toString()
    }

    /** `(문서 id, 변환 id)` — 워커가 할 일을 SQL 로 대신한다. */
    private fun doneConversion(
        token: String,
        text: String,
    ): Pair<String, String> {
        val body = json.writeValueAsString(mapOf("text" to text))
        val response = send(jsonRequest(DOCUMENTS_PATH, token).POST(bodyPublisher(body)))
        check(response.statusCode() == ContractSpec.successStatus(DOCUMENTS_PATH, POST)) {
            "문서 접수가 실패했다: ${response.statusCode()} ${response.body()}"
        }
        val parsed = bodyOf(response)
        val documentId = parsed.getValue("document_id").toString()
        val conversionId = parsed.getValue("conversion_id").toString()

        database.execute(
            "UPDATE conversions SET status = 'done' WHERE id = '$conversionId'",
        )
        return documentId to conversionId
    }

    private fun reconvert(
        token: String,
        conversionId: String,
        sourceUnitIndex: Int,
    ): HttpResponse<String> {
        val path =
            RECONVERT_PATH
                .replace("{conversion_id}", conversionId)
                .replace("{source_unit_index}", sourceUnitIndex.toString())
        val requestBody =
            json.writeValueAsString(mapOf("easy_unit_indexes" to listOf(0), "easy_text_fingerprint" to FINGERPRINT))
        return send(jsonRequest(path, token).POST(bodyPublisher(requestBody)))
    }

    private fun jsonRequest(
        path: String,
        token: String?,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header(CONTENT_TYPE, JSON_MEDIA_TYPE)
        token?.let { builder.header(AUTHORIZATION, "Bearer $it") }
        return builder
    }

    private fun bodyPublisher(body: String): HttpRequest.BodyPublisher =
        HttpRequest.BodyPublishers.ofByteArray(body.toByteArray(Charsets.UTF_8))

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun Map<*, *>.getValue(key: String): Any = this[key] ?: error("응답에 $key 가 없다: $this")

    private companion object {
        const val SIGNUP_PATH = "/auth/signup"
        const val LOGIN_PATH = "/auth/login"
        const val DOCUMENTS_PATH = "/documents"
        const val RECONVERT_PATH = "/conversions/{conversion_id}/units/{source_unit_index}/reconvert"
        const val POST = "post"

        const val CANDIDATE_TEXT_PROPERTY = "candidate_text"
        const val SOURCE_UNIT_INDEX_PROPERTY = "source_unit_index"
        const val EASY_UNIT_INDEXES_PROPERTY = "easy_unit_indexes"
        const val FINGERPRINT_PROPERTY = "easy_text_fingerprint"
        const val LLM_CALLS_USED_PROPERTY = "llm_calls_used"
        const val REMAINING_BUDGET_PROPERTY = "remaining_call_budget"

        const val AUTHORIZATION = "Authorization"
        const val CONTENT_TYPE = "Content-Type"
        const val JSON_MEDIA_TYPE = "application/json"

        const val SOURCE_TEXT = "금일 서류를 제출하십시오."
        const val VALID_PASSWORD = "correct horse battery"
        val FINGERPRINT = "a".repeat(64)

        /** `ReconversionProperties.DEFAULT_CALL_BUDGET` 과 같은 값(구성을 덮어쓰지 않았다). */
        const val DEFAULT_CALL_BUDGET = 20

        var counter = 0

        /** 이 테스트만 쓰는 DB. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("reconvert_reach") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

/** 이 테스트가 쓰는 서명 키. */
const val RECONVERT_REACH_TEST_SECRET: String = "reconvert-reach-test-signing-key-0123456789"
