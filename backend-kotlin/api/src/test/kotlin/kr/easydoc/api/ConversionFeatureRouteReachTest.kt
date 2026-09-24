package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.OwnershipConcealment
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * R1~R7·ER-16·ER-17 이 더한 변환 하위 라우트의 **소유권 은닉** 실측 — 남의 변환이 없는 변환과
 * 구별되지 않는가(X-B1·X-B2, [DocumentDeleteReachTest]·[WorkspaceEndpointReachTest] 와
 * 같은 판정).
 *
 * 각 라우트의 계약 테스트는 서비스를 mock 으로 세운 슬라이스라 「서비스가 404 를 던지면
 * 404 로 낸다」까지만 잰다 — **서비스가 실제로 남의 자원에 404 를 던지는가**는 실제 스택에서만
 * 드러난다. 여기가 그 자리다.
 *
 * 기능 토글은 전부 켠다 — 꺼져 있으면 모든 팔이 같은 404 라서 소유권 판정이 공회전한다.
 * 응답 시간 균일성은 재지 않는다(그 판정은 `ConversionReadReachTest` 계열의 몫이다).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "easydoc.auth.jwt-secret=$CONVERSION_FEATURE_ROUTE_TEST_SECRET",
        "easydoc.review-support.enabled=true",
        "easydoc.review-history.enabled=true",
        "easydoc.explanations.enabled=true",
        "easydoc.illustrations.enabled=true",
        "easydoc.action-guide.enabled=true",
        "easydoc.illustration-suggestions.enabled=true",
        // 단가가 없으면 접수가 503이라 기능이 노출되지 않는다 — 소유권 판정이 공회전한다.
        "easydoc.illustration-suggestions.credits-per-100-chars=0.1",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversionFeatureRouteReachTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var cipher: ContentCipher

    private val json = ObjectMapper()

    @ParameterizedTest(name = "GET {0}")
    @MethodSource("ownedGetRoutes")
    fun `타인 변환의 기능 라우트 조회는 404 이고 없는 것과 구별되지 않는다`(template: String) {
        val mine = newAccount()
        val myConversion = doneConversion(mine).conversionId
        val theirs = doneConversion(newAccount()).conversionId

        // 대조군 — 토글이 꺼져 있으면 모든 팔이 404 라 아래 판정이 공회전한다.
        assertThat(getBytes(mine, template.forConversion(myConversion)).statusCode())
            .withFailMessage("내 변환 조회가 200 이 아니다 — 기능 토글이 꺼져 있어 소유권 판정이 아무것도 재지 못한다")
            .isEqualTo(OK)

        val others = getBytes(mine, template.forConversion(theirs))
        val absent = getBytes(mine, template.forConversion(UUID.randomUUID()))

        assertThat(others.statusCode())
            .withFailMessage("타인 변환 조회가 부재 응답이 아니다 — 거절을 가르면 남의 자원 존재를 확인해 준다")
            .isEqualTo(NOT_FOUND)
        assertThat(others.statusCode())
            .withFailMessage("타인 자원 거절이 부재와 다른 코드다 — 그 차이가 곧 존재 확인 수단이다")
            .isNotEqualTo(FORBIDDEN)
        OwnershipConcealment.assertIndistinguishable("GET $template", absent, others)
    }

    @Test
    @DisplayName("타인 변환에 그림 배치 저장(PUT) → 404 · 없는 변환과 응답이 구별되지 않는다")
    fun `타인 변환의 그림 배치 저장은 404 다`() {
        val mine = newAccount()
        val theirs = doneConversion(newAccount()).conversionId
        val body = json.writeValueAsString(mapOf("expected_content_revision" to 1, "placements" to emptyList<Any>()))

        assertThat(putBytes(mine, PLACEMENTS_PATH.forConversion(doneConversion(mine).conversionId), body).statusCode())
            .withFailMessage("내 변환 저장이 200 이 아니다 — 토글이 꺼져 있어 소유권 판정이 공회전한다")
            .isEqualTo(OK)

        val others = putBytes(mine, PLACEMENTS_PATH.forConversion(theirs), body)
        val absent = putBytes(mine, PLACEMENTS_PATH.forConversion(UUID.randomUUID()), body)

        assertThat(others.statusCode()).isEqualTo(NOT_FOUND)
        assertThat(others.statusCode()).isNotEqualTo(FORBIDDEN)
        OwnershipConcealment.assertIndistinguishable("PUT $PLACEMENTS_PATH", absent, others)
    }

    @Test
    @DisplayName("타인 행동 안내 작업 조회 → 404 · 남의 변환으로도 내 변환 위의 남의 작업으로도 존재가 새지 않는다")
    fun `타인 행동 안내 작업 조회는 404 다`() {
        val mine = newAccount()
        val theirs = doneConversion(newAccount())
        val theirJob = seedActionGuideJob(theirs)
        val myDone = doneConversion(mine)
        val myConversion = myDone.conversionId
        val myJob = seedActionGuideJob(myDone)

        assertThat(getBytes(mine, ACTION_GUIDE_JOBS_PATH.forConversion(myConversion)).statusCode())
            .withFailMessage("내 변환의 작업 목록이 200 이 아니다 — 토글이 꺼져 있어 소유권 판정이 공회전한다")
            .isEqualTo(OK)
        // 단건 조회에도 대조군이 있어야 한다 — 아래 두 팔이 404 대 404 뿐이면 언제나
        // 404 를 내는 구현도 통과한다.
        assertThat(getBytes(mine, jobPath(myConversion, myJob)).statusCode())
            .withFailMessage("내 변환의 내 작업 단건 조회가 200 이 아니다 — 모든 팔이 404 라 판정이 공회전한다")
            .isEqualTo(OK)

        val foreignConversion = getBytes(mine, jobPath(theirs.conversionId, theirJob))
        val absentConversion = getBytes(mine, jobPath(UUID.randomUUID(), UUID.randomUUID()))
        assertThat(foreignConversion.statusCode()).isEqualTo(NOT_FOUND)
        assertThat(foreignConversion.statusCode()).isNotEqualTo(FORBIDDEN)
        OwnershipConcealment.assertIndistinguishable("GET $JOB_PATH (남의 변환)", absentConversion, foreignConversion)

        // 변환은 내 것인데 작업 id 만 남의 것 — 이 팔이 갈리면 job id 로 남의 작업 존재를 확인할 수 있다.
        val foreignJob = getBytes(mine, jobPath(myConversion, theirJob))
        val absentJob = getBytes(mine, jobPath(myConversion, UUID.randomUUID()))
        assertThat(foreignJob.statusCode()).isEqualTo(NOT_FOUND)
        OwnershipConcealment.assertIndistinguishable("GET $JOB_PATH (내 변환·남의 작업)", absentJob, foreignJob)
    }

    @Test
    @DisplayName("타인 그림 제안 작업 조회 → 404 · 남의 변환으로도 내 변환 위의 남의 작업으로도 존재가 새지 않는다")
    fun `타인 그림 제안 작업 조회는 404 다`() {
        val mine = newAccount()
        val theirs = doneConversion(newAccount())
        val theirJob = seedSuggestionJob(theirs)
        val myDone = doneConversion(mine)
        val myConversion = myDone.conversionId
        seedSuggestionJob(myDone)

        assertThat(getBytes(mine, SUGGESTION_JOBS_PATH.forConversion(myConversion)).statusCode())
            .withFailMessage("내 변환의 작업 목록이 200 이 아니다 — 토글이나 이용량 단가가 없어 판정이 공회전한다")
            .isEqualTo(OK)

        val foreignConversion = getBytes(mine, suggestionJobPath(theirs.conversionId, theirJob))
        val absentConversion = getBytes(mine, suggestionJobPath(UUID.randomUUID(), UUID.randomUUID()))
        assertThat(foreignConversion.statusCode()).isEqualTo(NOT_FOUND)
        assertThat(foreignConversion.statusCode()).isNotEqualTo(FORBIDDEN)
        OwnershipConcealment.assertIndistinguishable(
            "GET $SUGGESTION_JOB_PATH (남의 변환)",
            absentConversion,
            foreignConversion,
        )

        // 변환은 내 것인데 작업 id 만 남의 것 — 이 팔이 갈리면 job id 로 남의 작업 존재를 확인할 수 있다.
        val foreignJob = getBytes(mine, suggestionJobPath(myConversion, theirJob))
        val absentJob = getBytes(mine, suggestionJobPath(myConversion, UUID.randomUUID()))
        assertThat(foreignJob.statusCode()).isEqualTo(NOT_FOUND)
        OwnershipConcealment.assertIndistinguishable(
            "GET $SUGGESTION_JOB_PATH (내 변환·남의 작업)",
            absentJob,
            foreignJob,
        )
    }

    /** 남의 문서에서 소유자·작업 공간을 그대로 읽어 제안 작업 행 하나를 심는다. */
    private fun seedSuggestionJob(seeded: Seeded): UUID {
        val jobId = UUID.randomUUID()
        database.execute(
            """
            INSERT INTO illustration_suggestion_jobs
                (id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
                 expected_content_revision, based_on_content_revision, input_fingerprint,
                 status, reserved_credits, settlement)
            SELECT '$jobId', gen_random_uuid(), d.user_id, d.workspace_id, d.id, '${seeded.conversionId}',
                   1, 1, '$FINGERPRINT', 'succeeded', 1.0, 'consumed'
            FROM documents d WHERE d.id = '${seeded.documentId}'
            """.trimIndent(),
        )
        return jobId
    }

    private fun suggestionJobPath(
        conversionId: UUID,
        jobId: UUID,
    ): String = SUGGESTION_JOB_PATH.forConversion(conversionId).replace("{$JOB_ID_VARIABLE}", jobId.toString())

    /** 남의 문서에서 소유자·작업 공간을 그대로 읽어 작업 행 하나를 심는다. */
    private fun seedActionGuideJob(seeded: Seeded): UUID {
        val jobId = UUID.randomUUID()
        database.execute(
            """
            INSERT INTO action_guide_jobs
                (id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
                 expected_content_revision, based_on_content_revision, input_fingerprint,
                 status, reserved_credits, settlement)
            SELECT '$jobId', gen_random_uuid(), d.user_id, d.workspace_id, d.id, '${seeded.conversionId}',
                   1, 1, '$FINGERPRINT', 'succeeded', 1.0, 'consumed'
            FROM documents d WHERE d.id = '${seeded.documentId}'
            """.trimIndent(),
        )
        return jobId
    }

    private fun newAccount(): String {
        val email = "featureroute${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(jsonRequest(SIGNUP_PATH, null).POST(bodyPublisher(credentials)))
        // 이메일 인증 게이트는 `POST /documents` 앞이다 — 이 파일은 그 게이트를 재지 않는다.
        database.execute("UPDATE users SET email_verified_at = now() WHERE email = '$email'")
        return bodyOf(send(jsonRequest(LOGIN_PATH, null).POST(bodyPublisher(credentials))))
            .getValue("access_token")
            .toString()
    }

    /** 완료 상태의 변환 하나 — 워커가 할 일을 SQL 로 대신한다. */
    private fun doneConversion(token: String): Seeded {
        val body = json.writeValueAsString(mapOf("text" to SOURCE_BODY))
        val response = send(jsonRequest(DOCUMENTS_PATH, token).POST(bodyPublisher(body)))
        check(response.statusCode() == ContractSpec.successStatus(DOCUMENTS_PATH, POST)) {
            "문서 접수가 실패했다: ${response.statusCode()} ${response.body()}"
        }
        val parsed = bodyOf(response)
        val seeded =
            Seeded(
                UUID.fromString(parsed.getValue("document_id").toString()),
                UUID.fromString(parsed.getValue("conversion_id").toString()),
            )
        val sealed = cipher.encrypt(PlainBody(STORED_DRAFT), seeded.conversionId, EncryptedField.CONVERSION_EASY_TEXT)
        val hex = sealed.bytes.joinToString("") { "%02x".format(it) }
        // 봉투 두 값을 암호문과 **같은 문장에서** SET 한다(`EnvelopeColumnWriteGuardTest` 규약).
        database.execute(
            """
            UPDATE conversions
            SET status = 'done',
                content_revision = 1,
                easy_text_encrypted = decode('$hex', 'hex'),
                encryption_scheme = '${sealed.scheme}',
                key_version = ${sealed.keyVersion}
            WHERE id = '${seeded.conversionId}'
            """.trimIndent(),
        )
        return seeded
    }

    private fun jobPath(
        conversionId: UUID,
        jobId: UUID,
    ): String = JOB_PATH.forConversion(conversionId).replace("{$JOB_ID_VARIABLE}", jobId.toString())

    private fun String.forConversion(conversionId: UUID): String =
        replace("{$CONVERSION_ID_VARIABLE}", conversionId.toString())

    private fun getBytes(
        token: String,
        path: String,
    ): HttpResponse<ByteArray> = sendBytes(jsonRequest(path, token).GET())

    private fun putBytes(
        token: String,
        path: String,
        body: String,
    ): HttpResponse<ByteArray> = sendBytes(jsonRequest(path, token).PUT(bodyPublisher(body)))

    private fun bodyPublisher(body: String): HttpRequest.BodyPublisher =
        HttpRequest.BodyPublishers.ofByteArray(body.toByteArray(Charsets.UTF_8))

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

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    private fun sendBytes(builder: HttpRequest.Builder): HttpResponse<ByteArray> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun Map<*, *>.getValue(key: String): Any = this[key] ?: error("응답에 $key 가 없다: $this")

    private class Seeded(
        val documentId: UUID,
        val conversionId: UUID,
    )

    companion object {
        private const val SIGNUP_PATH = "/auth/signup"
        private const val LOGIN_PATH = "/auth/login"
        private const val DOCUMENTS_PATH = "/documents"
        private const val POST = "post"

        private const val CONVERSION_ID_VARIABLE = "conversion_id"
        private const val JOB_ID_VARIABLE = "job_id"

        private const val REVIEW_SUPPORT_PATH = "/conversions/{conversion_id}/review-support"
        private const val REVIEW_HISTORY_PATH = "/conversions/{conversion_id}/review-history"
        private const val ACTION_GUIDE_JOBS_PATH = "/conversions/{conversion_id}/action-guide-jobs"
        private const val ACTION_GUIDE_PATH = "/conversions/{conversion_id}/action-guide"
        private const val EXPLANATIONS_PATH = "/conversions/{conversion_id}/explanations"
        private const val PLACEMENTS_PATH = "/conversions/{conversion_id}/illustration-placements"
        private const val SUGGESTION_JOBS_PATH = "/conversions/{conversion_id}/illustration-suggestion-jobs"
        private const val SUGGESTIONS_PATH = "/conversions/{conversion_id}/illustration-suggestions"
        private const val JOB_PATH = "$ACTION_GUIDE_JOBS_PATH/{job_id}"
        private const val SUGGESTION_JOB_PATH = "$SUGGESTION_JOBS_PATH/{job_id}"

        /** `ck_action_guide_jobs_fingerprint_length` 이 정확히 64자를 요구한다. */
        private const val FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        private const val OK = 200
        private const val NOT_FOUND = 404
        private const val FORBIDDEN = 403

        private const val AUTHORIZATION = "Authorization"
        private const val CONTENT_TYPE = "Content-Type"
        private const val JSON_MEDIA_TYPE = "application/json"

        private const val SOURCE_BODY = "소유권 은닉 판정 대상 안내문 본문"
        private const val STORED_DRAFT = "쉬운 글 초안입니다.\n둘째 줄입니다."
        private const val VALID_PASSWORD = "correct horse battery"

        private var counter = 0

        @JvmStatic
        fun ownedGetRoutes(): List<String> =
            listOf(
                REVIEW_SUPPORT_PATH,
                REVIEW_HISTORY_PATH,
                ACTION_GUIDE_JOBS_PATH,
                ACTION_GUIDE_PATH,
                EXPLANATIONS_PATH,
                PLACEMENTS_PATH,
                SUGGESTION_JOBS_PATH,
                SUGGESTIONS_PATH,
            )

        /** 기능 토글이 다른 Spring 컨텍스트라 다른 테스트와 DB 를 공유하지 않는다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("conversion_feature_routes") }

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
const val CONVERSION_FEATURE_ROUTE_TEST_SECRET: String = "conversion-feature-route-test-signing-key-0123456789"
