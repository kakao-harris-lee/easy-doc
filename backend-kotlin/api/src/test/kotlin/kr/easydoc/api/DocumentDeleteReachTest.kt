package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.OwnershipConcealment
import kr.easydoc.api.support.TimingUniformity
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
import java.util.UUID

/** `DELETE /documents/{document_id}` 의 실측 계약 — 명세 §5 의 C-R·C-I 계층. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$DOCUMENT_DELETE_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentDeleteReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("DD-1 내 문서 삭제 → 204 · **본문 길이 0** · 사적 헤더 2종 **있음**(전역 부착으로 부호 반전, X-D2)")
    fun `삭제가 204 이고 본문이 없는데 헤더는 있다`() {
        val token = newAccount()
        val documentId = createDocument(token)

        val response = delete(token, documentId)

        assertDeclaredStatus(response, ContractSpec.successStatus(ITEM_PATH, DELETE))
        assertThat(response.body())
            .describedAs("지운 내용을 되돌려 주면 방금 파기한 문서를 다시 밖으로 내보내는 셈이다")
            .isEmpty()

        assertPrivateHeaders(response)
    }

    @Test
    @DisplayName("DD-1 204 응답에 `Content-Type` 이 없다 — 길이 0 의 JSON 이라는 없는 표현을 선언하지 않는다")
    fun `204 에 콘텐츠 타입이 없다`() {
        val token = newAccount()

        val response = delete(token, createDocument(token))

        assertThat(response.headers().firstValue(CONTENT_TYPE)).isEmpty
    }

    @Test
    @DisplayName("DD-5 삭제가 문서·**변환**·**작업** 행을 함께 없앤다 — FK CASCADE 연쇄(저장 상태로 관측)")
    fun `삭제가 변환과 작업 행까지 파기한다`() {
        val token = newAccount()
        val subject = subjectOf(token)
        val (documentId, conversionId) = createDocumentAndConversion(token)

        assertThat(documentRows(subject)).isEqualTo(1)
        assertThat(conversionRows(documentId)).isEqualTo(1)
        assertThat(jobRows(documentId)).isEqualTo(1)
        assertThat(orphanJobRows(conversionId)).isEqualTo(1)
        assertThat(sourceCiphertextBytes(documentId)).isPositive()

        delete(token, documentId).also { assertDeclaredStatus(it, ContractSpec.successStatus(ITEM_PATH, DELETE)) }

        assertThat(documentRows(subject)).describedAs("문서 행이 남았다 — 표시만 하고 지우지 않았다").isZero()
        assertThat(conversionRows(documentId)).describedAs("변환 행이 남았다 — CASCADE 가 끊겼다").isZero()

        assertThat(orphanJobRows(conversionId))
            .describedAs("작업 행이 남으면 워커가 매번 없는 변환을 읽으러 간다 — 연쇄 둘째 고리다")
            .isZero()
    }

    @Test
    @DisplayName("DD-5 **타인의** 문서·변환은 그대로다 — 연쇄가 소유 범위를 넘지 않는다")
    fun `연쇄가 남의 행까지 지우지 않는다`() {
        val mine = newAccount()
        val theirs = newAccount()
        val theirDocument = createDocument(theirs)
        val myDocument = createDocument(mine)

        delete(mine, myDocument)

        assertThat(documentRows(subjectOf(theirs))).isEqualTo(1)
        assertThat(conversionRows(theirDocument)).isEqualTo(1)
        assertThat(jobRows(theirDocument)).isEqualTo(1)
    }

    @Test
    @DisplayName("DD-2 타인 소유 문서 → **404 이고 403 이 아니다** · detail 이 계약 404 예시와 같다 (X-B1)")
    fun `타인 문서 삭제는 404 이고 403 이 아니다`() {
        val theirDocument = createDocument(newAccount())

        val response = delete(newAccount(), theirDocument)

        assertThat(response.statusCode()).isNotEqualTo(FORBIDDEN)
        assertDeclaredStatus(response, NOT_FOUND)
        assertThat(bodyOf(response)[DETAIL])
            .isEqualTo(ContractSpec.pathExampleDetail(ITEM_PATH, DELETE, NOT_FOUND, NOT_FOUND_EXAMPLE))
    }

    @Test
    @DisplayName("DD-2 404 를 받은 뒤에도 **타인 문서는 그대로다** — 거절이 파기를 동반하지 않는다")
    fun `타인 문서는 404 뒤에도 남아 있다`() {
        val theirs = newAccount()
        val theirDocument = createDocument(theirs)

        delete(newAccount(), theirDocument)

        assertThat(documentRows(subjectOf(theirs))).isEqualTo(1)
        assertThat(conversionRows(theirDocument)).isEqualTo(1)
    }

    /** 성질 P1 — 응답 구별 불가. 판정은 [OwnershipConcealment] 한 벌이 진다. */
    @Test
    @DisplayName("DD-3 없는 식별자와 타인 식별자의 **상태·본문 원시 바이트·헤더 이름 집합이 완전히 같다** (X-B2)")
    fun `없는 것과 남의 것이 구분되지 않는다`() {
        val mine = newAccount()
        val theirDocument = createDocument(newAccount())

        val absent = deleteBytes(mine, UUID.randomUUID().toString())
        val others = deleteBytes(mine, theirDocument)

        OwnershipConcealment.assertIndistinguishable("DELETE $ITEM_PATH", absent, others)
    }

    /**
     * 소유권 은닉의 셋째 축 — 응답 시간. 표본 설계와 이 축이 무엇의 보조인지는
     * [TimingUniformity] KDoc 에 있다. 여기 companion 상수가 그 설계의 값이다.
     */
    @Test
    @DisplayName("소유권 404 의 응답 시간이 「없음」과 「타인 것」 사이에서 갈리지 않는다")
    fun `소유권 404 의 응답 시간이 갈리지 않는다`() {
        val mine = newAccount()
        val theirDocument = createDocument(newAccount())

        val outcome = TimingUniformity.judge(TIMING_SPEC) { arm -> timeNotFoundRequest(mine, theirDocument, arm) }
        val description = TimingUniformity.describe(outcome)
        println(description)

        assertThat(outcome.ratio)
            .withFailMessage(
                "%s — 문턱 %.1f 를 넘었다. 일하는 양이 갈리면 존재 여부가 시간으로 샌다",
                description,
                MAX_TIMING_RATIO,
            ).isLessThan(MAX_TIMING_RATIO)
    }

    @Test
    @DisplayName("DD-4 삭제 성공 직후 같은 식별자로 재요청 → **404**(204 가 아니다) — 멱등 구현이 여기서 갈린다")
    fun `이미 지운 문서는 404 다`() {
        val token = newAccount()
        val documentId = createDocument(token)
        assertDeclaredStatus(delete(token, documentId), ContractSpec.successStatus(ITEM_PATH, DELETE))

        val again = delete(token, documentId)

        assertDeclaredStatus(again, NOT_FOUND)
        assertThat(bodyOf(again)[DETAIL])
            .isEqualTo(ContractSpec.pathExampleDetail(ITEM_PATH, DELETE, NOT_FOUND, NOT_FOUND_EXAMPLE))
    }

    @Test
    @DisplayName("DD-6 UUID 가 아닌 경로 변수 → 422 · detail **배열** · 항목 키 정확히 `ValidationErrorItem.required`")
    fun `UUID 가 아닌 경로 변수는 422 배열이다`() {
        val response = delete(newAccount(), NOT_A_UUID)

        assertDeclaredStatus(response, UNPROCESSABLE)
        assertValidationArray(response)
    }

    @Test
    @DisplayName("공백뿐인 경로 조각도 흡수되지 않고 계약이 선언한 상태로 거절된다 — 400 이 아니다")
    fun `공백 경로 조각이 흡수되지 않는다`() {
        val response = delete(newAccount(), BLANK_SEGMENT)

        assertDeclaredStatus(response, UNPROCESSABLE)
        assertValidationArray(response)
    }

    @Test
    @DisplayName("DD-7 Authorization 이 없으면 401 이고 `WWW-Authenticate` 가 붙는다 (X-A1)")
    fun `토큰이 없으면 401 이다`() {
        val response = delete(token = null, documentId = UUID.randomUUID().toString())

        assertDeclaredStatus(response, UNAUTHORIZED)
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE))
            .withFailMessage("401 에 WWW-Authenticate 가 없다 — 클라이언트가 재인증 방식을 알 수 없다")
            .hasValue(ContractSpec.headerConst(WWW_AUTHENTICATE_COMPONENT))
    }

    @Test
    @DisplayName("위조 토큰 + UUID 가 아닌 경로 변수 → **401**(422 가 아니다) — 인증이 변환보다 먼저다 (X-A3)")
    fun `인증이 경로 변수 변환보다 먼저다`() {
        val response = delete(FORGED_TOKEN, NOT_A_UUID)

        assertDeclaredStatus(response, UNAUTHORIZED)
        assertThat(bodyOf(response)[DETAIL])
            .withFailMessage("위조 토큰 응답의 detail 이 문자열이 아니다 — 검증 실패 배열이 새어 나왔다")
            .isInstanceOf(String::class.java)
    }

    @Test
    @DisplayName("토큰 없는 삭제가 **아무것도 지우지 않는다** — 401 이 파기를 동반하지 않는다")
    fun `토큰 없는 삭제는 파기하지 않는다`() {
        val token = newAccount()
        val documentId = createDocument(token)

        delete(token = null, documentId = documentId)

        assertThat(documentRows(subjectOf(token))).isEqualTo(1)
    }

    /**
     * 소유권 404 시간 판정 한 표본. [arm] 이 [ABSENT] 면 없는 식별자를, 아니면 남의 문서를 잰다.
     *
     * 요청 조립(계약에서 경로 변수 이름 읽기·URL 파싱·헤더 붙이기)은 괄호 **밖**이고, 잰 구간은
     * [client] 의 왕복 하나뿐이다. 종전에는 `HttpClient.newHttpClient()` 와 요청 조립이 모두 괄호
     * 안에 있어, 두 팔의 차이를 재는 자리에 두 팔과 무관한 비용(클라이언트 생성·연결 수립·계약
     * 파일 조회)이 표본마다 섞였다. 그 비용이 종전 중앙값을 지배했는지는 재지 않았다 — 공유로
     * 바꾼 뒤에도 중앙값은 5~6ms 대다. 고친 이유는 크기가 아니라 「재는 구간은 두 팔의 차이만
     * 담아야 한다」는 것이고, 그래서 클라이언트는 클래스 하나로 공유한다.
     */
    private fun timeNotFoundRequest(
        token: String,
        othersId: String,
        arm: TimingUniformity.Arm,
    ): Double {
        val target = if (arm == ABSENT) UUID.randomUUID().toString() else othersId
        val request = deleteRequest(token, target).build()

        val started = System.nanoTime()
        val status = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)).statusCode()
        val elapsed = (System.nanoTime() - started) / NANOS_PER_MILLI

        check(status == NOT_FOUND) { "404 가 아니면 잰 것이 다른 경로다: $status" }
        return elapsed
    }

    private fun newAccount(): String {
        val email = "documentdelete${counter++}@example.test"
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))
        send(post(null, credentials, "/auth/signup"))
        // 이메일 인증 게이트는 `POST /documents` 앞이다 — 이 파일은 그 게이트를 재지 않으므로
        // 실물 인증 흐름 대신 저장소를 직접 인증 완료로 만든다.
        database.execute("UPDATE users SET email_verified_at = now() WHERE email = '$email'")
        return bodyOf(send(post(null, credentials, "/auth/login"))).required("access_token").toString()
    }

    /** 붙여넣기 모드로 문서를 만들고 그 식별자를 돌려준다. */
    private fun createDocument(token: String): String = createDocumentAndConversion(token).first

    /** 문서를 만들고 `(문서 id, 변환 id)` 를 돌려준다. */
    private fun createDocumentAndConversion(token: String): Pair<String, String> {
        val body = json.writeValueAsString(mapOf("text" to "삭제 대상 안내문 본문"))
        val response = send(post(token, body, DOCUMENTS_PATH))
        check(response.statusCode() == ContractSpec.successStatus(DOCUMENTS_PATH, POST)) {
            "문서 접수가 실패했다: ${response.statusCode()} ${response.body()}"
        }
        val created = bodyOf(response)
        return created.required(DOCUMENT_ID_PROPERTY).toString() to created.required(CONVERSION_ID_PROPERTY).toString()
    }

    private fun delete(
        token: String?,
        documentId: String,
    ): HttpResponse<String> = send(deleteRequest(token, documentId))

    /** 같은 요청을 바이트로 받는다 — P1 만 디코딩을 지나지 않는 팔을 쓴다. */
    private fun deleteBytes(
        token: String?,
        documentId: String,
    ): HttpResponse<ByteArray> =
        client.send(
            deleteRequest(token, documentId).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    /** 두 팔이 같은 요청 조립을 쓰게 한다 — 조립이 갈리면 두 팔의 차이가 요청 차이가 된다. */
    private fun deleteRequest(
        token: String?,
        documentId: String,
    ): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port${itemPath(documentId)}")).DELETE()
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun post(
        token: String?,
        body: String,
        path: String,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header(CONTENT_TYPE, JSON_MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    /** P-21 — 경로 변수 이름을 계약에서 읽어 URL 을 조립한다. */
    private fun itemPath(documentId: String): String =
        ITEM_PATH.replace("{${ContractSpec.pathVariable(ITEM_PATH, DELETE).name}}", documentId)

    private fun subjectOf(token: String): String =
        kr.easydoc.api.support.TestJwt
            .payload(token)["sub"]
            .toString()

    private fun documentRows(subject: String): Int =
        database.queryInt("SELECT count(*) FROM documents WHERE user_id = '$subject'")

    private fun conversionRows(documentId: String): Int =
        database.queryInt("SELECT count(*) FROM conversions WHERE document_id = '$documentId'")

    /**
     * 그 문서의 변환을 가리키는 작업 행 수. 조인으로 센다 — 작업 테이블에는 문서 식별자가
     * 없고(작업 id 가 변환 id 다), 변환 행이 함께 사라지므로 삭제 후에는 조인 결과가 0 이다.
     */
    private fun jobRows(documentId: String): Int =
        database.queryInt(
            "SELECT count(*) FROM conversion_jobs j JOIN conversions c ON c.id = j.conversion_id " +
                "WHERE c.document_id = '$documentId'",
        )

    /** X4 보강 — 변환 식별자로 작업 행을 직접 센다. 조인이 없다. */
    private fun orphanJobRows(conversionId: String): Int =
        database.queryInt("SELECT count(*) FROM conversion_jobs WHERE conversion_id = '$conversionId'")

    /** 원문 암호문의 길이. 0 보다 커야 「지울 것이 실제로 있었다」가 성립한다. */
    private fun sourceCiphertextBytes(documentId: String): Int =
        database.queryInt("SELECT octet_length(source_text_encrypted) FROM documents WHERE id = '$documentId'")

    private fun assertPrivateHeaders(response: HttpResponse<String>) {
        ContractSpec.globalHeaderValues().forEach { (header, value) ->
            assertThat(response.headers().allValues(header))
                .withFailMessage(
                    "%s 가 %s 로 나갔다 — 값 또는 부착 개수가 계약과 다르다",
                    header,
                    response.headers().allValues(header),
                ).containsExactly(value)
        }
    }

    private fun assertDeclaredStatus(
        response: HttpResponse<String>,
        status: Int,
    ) {
        assertThat(response.statusCode())
            .withFailMessage("DELETE %s 가 %d 이 아니다: %s", ITEM_PATH, status, response.body())
            .isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(ITEM_PATH, DELETE))
            .withFailMessage("계약이 DELETE %s 에 %d 를 선언하지 않는다", ITEM_PATH, status)
            .contains(status.toString())
    }

    /** `detail` 이 배열이고 항목 키 집합이 정확히 `ValidationErrorItem.required` 다. */
    private fun assertValidationArray(response: HttpResponse<String>) {
        val items = bodyOf(response)[DETAIL]
        assertThat(items)
            .withFailMessage("detail 이 배열이 아니다 — 스키마 층 거절은 배열이어야 한다: %s", items)
            .isInstanceOf(List::class.java)

        val declared = ContractSpec.schemaRequired(VALIDATION_ITEM_SCHEMA)
        assertThat(items as List<*>).isNotEmpty()
        items.forEach { item ->
            assertThat((item as Map<*, *>).keys.map { it.toString() }.toSet())
                .withFailMessage("검증 항목의 키 집합이 계약 %s 와 다르다 — 제출값이 실리면 응답과 로그에 남는다", VALIDATION_ITEM_SCHEMA)
                .isEqualTo(declared)
        }
    }

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun Map<*, *>.required(key: String): Any = this[key] ?: error("응답에 $key 가 없다")

    companion object {
        private const val DOCUMENTS_PATH = "/documents"
        private const val ITEM_PATH = "/documents/{document_id}"
        private const val POST = "post"
        private const val DELETE = "delete"

        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val UNPROCESSABLE = 422

        private const val DETAIL = "detail"
        private const val DOCUMENT_ID_PROPERTY = "document_id"

        /** X4 보강이 쓰는 응답 필드. 작업 행을 조인 없이 세려면 이 값이 필요하다. */
        private const val CONVERSION_ID_PROPERTY = "conversion_id"
        private const val VALIDATION_ITEM_SCHEMA = "ValidationErrorItem"

        /** 계약이 이 경로 404 의 인라인 예시에 붙인 이름. 값이 아니라 이름이라 여기 적는다. */
        private const val NOT_FOUND_EXAMPLE = "not_found"

        private const val CONTENT_TYPE = "Content-Type"
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val WWW_AUTHENTICATE = "WWW-Authenticate"
        private const val WWW_AUTHENTICATE_COMPONENT = "WWWAuthenticateBearer"

        private const val NOT_A_UUID = "not-a-uuid"

        /** 퍼센트 인코딩된 공백 하나. 널화 흡수 경로를 재려면 인코딩된 채로 실어야 한다. */
        private const val BLANK_SEGMENT = "%20"

        private const val FORGED_TOKEN = "forged.token.value"
        private const val VALID_PASSWORD = "correct horse battery"

        private val ABSENT = TimingUniformity.Arm("absent", "없음")
        private val OTHERS = TimingUniformity.Arm("others", "타인")

        /** 1차 회차의 경로당 표본 수. 홀수라 확인 없이 끝날 때 중앙값이 표본 하나로 정해진다. */
        private const val TIMING_SAMPLES = 21

        /** 1차가 문턱 이상일 때만 더 재는 표본 수 — 합산이 홀수가 되게 짝수다. 근거는 [TimingUniformity] KDoc. */
        private const val CONFIRMATION_SAMPLES = TIMING_SAMPLES * 2

        /** 두 경로를 섞는 순서. 고정 시드라 실패가 재현된다. */
        private const val TIMING_SEED = 20260821L

        /** 확인 회차의 시드. 1차와 다른 배치를 써 확인이 1차의 자리 배열을 되풀이하지 않게 한다. */
        private const val CONFIRMATION_SEED = TIMING_SEED + 1

        /**
         * 1차와 확인 사이에 재우는 시간(ms). `AesGcmContentCipherTest.TIMING_ATTEMPT_GAP_MILLIS`
         * 와 같은 값·같은 이유다 — 두 회차가 하나의 스케줄러·GC 잡음 구간에 붙어 있지 않게 한다.
         */
        private const val CONFIRMATION_GAP_MILLIS = 50L

        /**
         * 명세는 [TimingUniformity] KDoc 에 있다. 문턱 값 자체는 `WorkspaceEndpointReachTest` ·
         * `AuthEndpointReachTest` 의 로그인 게이트와 같지만, 판정은 auth 와 다르다 —
         * auth 는 한 회차로 끝내고 여기는 1차·확인을 합산한다.
         */
        private const val MAX_TIMING_RATIO = 1.5

        /** 0 으로 나누지 않기 위한 바닥. 이보다 짧은 응답은 측정 분해능 밖이다. */
        private const val MIN_MEASURABLE_MILLIS = 0.05

        /** 위 상수들이 곧 이 판정의 표본 설계다. */
        private val TIMING_SPEC =
            TimingUniformity.TimingSpec(
                arms = listOf(ABSENT, OTHERS),
                samples = TIMING_SAMPLES,
                confirmationSamples = CONFIRMATION_SAMPLES,
                seed = TIMING_SEED,
                confirmationSeed = CONFIRMATION_SEED,
                gapMillis = CONFIRMATION_GAP_MILLIS,
                threshold = MAX_TIMING_RATIO,
                minMeasurableMillis = MIN_MEASURABLE_MILLIS,
            )

        private const val NANOS_PER_MILLI = 1_000_000.0

        private var counter = 0

        /** 모든 요청이 쓰는 클라이언트 하나. 요청마다 새로 만들면 그 생성 비용이 시간 판정에 얹힌다. */
        private val client: HttpClient = HttpClient.newHttpClient()

        /** 이 테스트만 쓰는 DB. 다른 테스트의 행과 섞이면 행 수 단언이 무너진다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("document_delete") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

/** 이 테스트가 쓰는 서명 키. 계약 `x-auth.min_secret_bytes` 이상이어야 한다. */
const val DOCUMENT_DELETE_TEST_SECRET: String = "document-delete-test-signing-key-0123456789"
