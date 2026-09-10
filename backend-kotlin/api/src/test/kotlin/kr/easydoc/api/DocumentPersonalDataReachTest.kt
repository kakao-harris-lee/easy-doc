package kr.easydoc.api

import kr.easydoc.api.support.MultipartBody
import kr.easydoc.api.support.TestJwt
import kr.easydoc.api.support.UploadFixtures
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
 * `POST /documents` 개인정보 경고용 검출 — 계획
 * `docs/plans/2026-09-10-personal-data-warning.md` §4 수용 기준 1~4·6·7·8을 실 Postgres로 잰다.
 *
 * 오탐 억제(기준 5·6)의 1차 증거는 `kr.easydoc.core.privacy.PersonalDataDetectionTest`
 * (순수 함수 단위 테스트)다 — 이 파일은 그 판정이 HTTP 경계·DB 행 수까지 그대로
 * 이어지는지를 잰다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$DOCUMENT_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentPersonalDataReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("A1 — 검증식을 통과하는 주민등록번호는 확인 없이 422·X-Personal-Data-Kinds: rrn·행 0건")
    fun `주민등록번호 검출은 확인 없이 422 다`() {
        val token = newAccount()

        val response = createFromText(token, textBody("주민등록번호는 $VALID_RRN 입니다"))

        assertThat(response.statusCode()).isEqualTo(UNPROCESSABLE)
        assertThat(response.headers().firstValue(PERSONAL_DATA_KINDS_HEADER)).contains(RRN_KIND)
        assertThat(bodyOf(response).keys.map { it.toString() }.toSet()).isEqualTo(setOf(DETAIL))
        assertThat(bodyOf(response)[DETAIL]).isEqualTo(PERSONAL_DATA_DETECTED_MESSAGE)
        assertThat(documentCount(token)).isEqualTo(0)
    }

    @Test
    @DisplayName("A2 — Luhn 을 통과하는 카드번호는 확인 없이 422·X-Personal-Data-Kinds: card·행 0건")
    fun `카드번호 검출은 확인 없이 422 다`() {
        val token = newAccount()

        val response = createFromText(token, textBody("카드번호는 $VALID_CARD 입니다"))

        assertThat(response.statusCode()).isEqualTo(UNPROCESSABLE)
        assertThat(response.headers().firstValue(PERSONAL_DATA_KINDS_HEADER)).contains(CARD_KIND)
        assertThat(documentCount(token)).isEqualTo(0)
    }

    @Test
    @DisplayName("A3 — 둘 다 있으면 헤더가 정렬된 `card,rrn` 이다")
    fun `둘 다 검출되면 헤더가 정렬된다`() {
        val token = newAccount()

        val response =
            createFromText(token, textBody("주민등록번호 $VALID_RRN, 카드번호 $VALID_CARD 안내드립니다"))

        assertThat(response.statusCode()).isEqualTo(UNPROCESSABLE)
        assertThat(response.headers().firstValue(PERSONAL_DATA_KINDS_HEADER)).contains("$CARD_KIND,$RRN_KIND")
        assertThat(documentCount(token)).isEqualTo(0)
    }

    @Test
    @DisplayName("A4 — 확인 플래그를 참으로 보내면 정상 등록된다(202) — 붙여넣기 경로")
    fun `확인하면 붙여넣기도 정상 등록된다`() {
        val token = newAccount()

        val response = createFromText(token, textBody("주민등록번호는 $VALID_RRN 입니다", acknowledged = true))

        assertThat(response.statusCode()).isEqualTo(ACCEPTED)
        assertThat(response.headers().firstValue(PERSONAL_DATA_KINDS_HEADER)).isEmpty()
        assertThat(documentCount(token)).isEqualTo(1)
    }

    @Test
    @DisplayName("A6 — 검증식을 통과하지 못하는 13자리 숫자는 통과한다(오탐 억제)")
    fun `검증식을 통과하지 못하는 13자리 숫자는 통과한다`() {
        val token = newAccount()

        val response = createFromText(token, textBody("사업자 정보 $INVALID_13_DIGITS 입니다"))

        assertThat(response.statusCode()).isEqualTo(ACCEPTED)
        assertThat(response.headers().firstValue(PERSONAL_DATA_KINDS_HEADER)).isEmpty()
    }

    @Test
    @DisplayName("A7 — 파일 업로드 경로도 A1~A4 와 같게 동작한다")
    fun `파일 업로드 경로도 같은 규칙을 탄다`() {
        val token = newAccount()

        val rejected =
            upload(token, MultipartBody().file(FILE_PART, "안내문.docx", UploadFixtures.docxWithBody(VALID_CARD)))
        assertThat(rejected.statusCode()).isEqualTo(UNPROCESSABLE)
        assertThat(rejected.headers().firstValue(PERSONAL_DATA_KINDS_HEADER)).contains(CARD_KIND)
        assertThat(documentCount(token)).isEqualTo(0)

        val accepted =
            upload(
                token,
                MultipartBody()
                    .file(FILE_PART, "안내문.docx", UploadFixtures.docxWithBody(VALID_CARD))
                    .value(PERSONAL_DATA_ACK_PART, "true"),
            )
        assertThat(accepted.statusCode()).isEqualTo(ACCEPTED)
        assertThat(documentCount(token)).isEqualTo(1)
    }

    private fun newAccount(): String {
        val email = "personal-data${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(post(null, JSON_MEDIA_TYPE, credentials.toByteArray(Charsets.UTF_8), "/auth/signup"))
        database.execute("UPDATE users SET email_verified_at = now() WHERE email = '$email'")
        val login = send(post(null, JSON_MEDIA_TYPE, credentials.toByteArray(Charsets.UTF_8), "/auth/login"))
        return bodyOf(login).required("access_token").toString()
    }

    private fun createFromText(
        token: String,
        body: String,
    ): HttpResponse<String> = send(post(token, JSON_MEDIA_TYPE, body.toByteArray(Charsets.UTF_8)))

    private fun upload(
        token: String,
        body: MultipartBody,
    ): HttpResponse<String> = send(post(token, body.contentType(), body.build()))

    private fun textBody(
        text: String,
        acknowledged: Boolean = false,
    ): String =
        json.writeValueAsString(
            buildMap {
                put("text", text)
                if (acknowledged) put("personal_data_acknowledged", true)
            },
        )

    private fun post(
        token: String?,
        contentType: String,
        body: ByteArray,
        path: String = DOCUMENTS_PATH,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    private fun documentCount(token: String): Int =
        database.queryInt(
            "SELECT count(*) FROM documents WHERE user_id = '${TestJwt.payload(token)["sub"]}'",
        )

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun Map<*, *>.required(key: String): Any = this[key] ?: error("응답에 $key 가 없다")

    private companion object {
        const val DOCUMENTS_PATH = "/documents"
        const val JSON_MEDIA_TYPE = "application/json"
        const val FILE_PART = "file"
        const val PERSONAL_DATA_ACK_PART = "personal_data_acknowledged"
        const val PERSONAL_DATA_KINDS_HEADER = "X-Personal-Data-Kinds"
        const val DETAIL = "detail"

        const val UNPROCESSABLE = 422
        const val ACCEPTED = 202

        const val RRN_KIND = "rrn"
        const val CARD_KIND = "card"

        const val VALID_PASSWORD = "correct horse battery"

        /** 계약 `POST /documents` 422 예시 `personal_data_detected`. */
        const val PERSONAL_DATA_DETECTED_MESSAGE = "개인정보로 보이는 내용이 있습니다. 확인 후 다시 시도하세요."

        /** 검증식(모듈러스 11)을 통과하는 합성 주민등록번호 — 실존 인물과 무관하다. */
        const val VALID_RRN = "900101-1234568"

        /** Luhn 을 통과하는 표준 테스트 카드번호(Visa 공개 테스트 번호). */
        const val VALID_CARD = "4111-1111-1111-1111"

        /** 두 검증식 모두 실패하는 13자리 숫자 — 사업자등록번호를 이어 붙인 문자열의 대역이다. */
        const val INVALID_13_DIGITS = "1234567890000"

        private var counter = 0

        /** 이 테스트만 쓰는 DB. 다른 기동 테스트의 행과 섞이지 않게 따로 만든다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("document_personal_data") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}
