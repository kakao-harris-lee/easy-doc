package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.TestJwt
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
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/** `/auth` 의 실측 계약 — 명세 §5 의 C-R 계층. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$AUTH_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthEndpointReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    @DisplayName("S-1 가입이 계정과 기본 작업 공간을 함께 만든다 (같은 트랜잭션)")
    fun `가입은 기본 작업 공간까지 만든다`() {
        val email = uniqueEmail()

        val response = post("/auth/signup", credentials(email, VALID_PASSWORD))

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus("/auth/signup", "post"))
        val userId = UUID.fromString(bodyOf(response)["id"].toString())

        assertThat(database.queryInt("SELECT count(*) FROM workspaces WHERE user_id = '$userId'")).isEqualTo(1)
    }

    @Test
    @DisplayName("L-1 로그인 응답의 키 집합·token_type·expires_in 이 계약과 같다")
    fun `토큰 응답이 계약과 같다`() {
        val email = uniqueEmail()
        post("/auth/signup", credentials(email, VALID_PASSWORD))

        val response = post("/auth/login", credentials(email, VALID_PASSWORD))

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus("/auth/login", "post"))
        val body = bodyOf(response)

        assertThat(body.keys.map { it.toString() }.toSet()).isEqualTo(ContractSpec.schemaRequired("TokenResponse"))
        assertThat(body["token_type"]).isEqualTo(ContractSpec.schemaPropertyConst("TokenResponse", "token_type"))
        assertThat((body["expires_in"] as Number).toLong())
            .isEqualTo(ContractSpec.authNumber("default_lifetime_seconds").toLong())
        assertPrivateHeaders(response)
    }

    @Test
    @DisplayName("L-1b 발급 토큰의 alg 와 클레임 키 집합이 계약과 정확히 같다 (개인정보 클레임 0)")
    fun `발급 토큰이 계약의 클레임만 담는다`() {
        val token = issueToken()

        assertThat(TestJwt.header(token)["alg"]).isEqualTo(ContractSpec.authText("algorithm"))
        assertThat(
            TestJwt
                .payload(token)
                .keys
                .map { it.toString() }
                .toSet(),
        ).isEqualTo(ContractSpec.authStrings("claims").toSet())
    }

    @Test
    @DisplayName("M-1 유효한 토큰 → 200 · 사적 헤더 2종(개수까지) · 본문 키 집합이 계약의 required")
    fun `내 정보가 계약과 같다`() {
        val response = get("/auth/me", issueToken())

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus("/auth/me", "get"))
        assertPrivateHeaders(response)
        assertThat(bodyOf(response).keys.map { it.toString() }.toSet())
            .isEqualTo(ContractSpec.schemaRequired("UserResponse"))
    }

    /** L-3b — 자격증명 실패의 세 번째 축: 응답 시간. */
    @Test
    @DisplayName("L-3b 없는 이메일과 있는 이메일의 로그인 응답 시간이 갈리지 않는다 (계약 x-auth 3번째 축)")
    fun `자격증명 실패의 응답 시간이 갈리지 않는다`() {
        assertThat(ContractSpec.authText("failure_uniformity"))
            .withFailMessage("계약의 failure_uniformity 가 응답 시간 축을 더는 요구하지 않는다 — 이 케이스를 재판정하라")
            .contains(RESPONSE_TIME_CLAUSE)

        val known = uniqueEmail()
        post("/auth/signup", credentials(known, VALID_PASSWORD))

        val (absent, wrongPassword) = interleavedLoginMedians(known)
        val ratio = maxOf(absent, wrongPassword) / minOf(absent, wrongPassword).coerceAtLeast(1.0)

        println(
            "L-3b 없는 이메일 %.1fms / 틀린 비밀번호 %.1fms → 비 %.3f (문턱 %.1f)"
                .format(absent, wrongPassword, ratio, MAX_TIMING_RATIO),
        )

        assertThat(minOf(absent, wrongPassword))
            .withFailMessage(
                "로그인 한 건이 %.1fms 다 — 실물 Argon2 가 도는 비용이 아니다(스텁·초소형 파라미터). " +
                    "그 상태에서는 두 경로가 함께 싸져 비 판정이 공허해진다",
                minOf(absent, wrongPassword),
            ).isGreaterThan(MIN_REAL_HASH_MILLIS)

        assertThat(ratio)
            .withFailMessage(
                "로그인 응답 시간이 계정 존재 여부로 갈린다 — 없는 이메일 %.1fms / 틀린 비밀번호 %.1fms (비 %.2f배). " +
                    "계정이 없을 때도 더미 PHC 로 같은 검증 비용을 치러야 한다",
                absent,
                wrongPassword,
                ratio,
            ).isLessThan(MAX_TIMING_RATIO)
    }

    /** 두 경로를 섞어 재고 각각의 중앙값(밀리초)을 낸다. 반환은 (없는 이메일, 틀린 비밀번호). */
    private fun interleavedLoginMedians(known: String): Pair<Double, Double> {
        val plan =
            (1..TIMING_SAMPLES)
                .flatMap { listOf(ABSENT_ACCOUNT, KNOWN_ACCOUNT) }
                .shuffled(Random(TIMING_SEED))

        listOf(ABSENT_ACCOUNT, KNOWN_ACCOUNT).forEach { failedLoginMillis(it, known) }

        val samples = plan.map { it to failedLoginMillis(it, known) }
        return medianOf(samples, ABSENT_ACCOUNT) to medianOf(samples, KNOWN_ACCOUNT)
    }

    /**
     * 그 그룹 표본의 중앙값. index 를 그룹 크기에서 유도한다 — 표본 수를 상수로 박아 두면
     * 표본 수가 다른 측정을 여기 붙일 때 조용히 중앙이 아닌 값을 읽는다(홀수라 표본 하나로 정해진다).
     */
    private fun medianOf(
        samples: List<Pair<String, Double>>,
        group: String,
    ): Double {
        val ordered = samples.filter { it.first == group }.map { it.second }.sorted()
        check(ordered.size % 2 == 1) { "$group 표본이 ${ordered.size} 개다 — 짝수면 중앙값이 표본 하나로 정해지지 않는다" }
        return ordered[ordered.size / 2]
    }

    /** 실패하는 로그인 한 건의 왕복 시간. 401 이 아니면 잰 것이 다른 경로다. */
    private fun failedLoginMillis(
        group: String,
        known: String,
    ): Double {
        val email = if (group == ABSENT_ACCOUNT) uniqueEmail() else known
        val started = System.nanoTime()
        val response = post("/auth/login", credentials(email, WRONG_PASSWORD))
        val elapsed = (System.nanoTime() - started) / NANOS_PER_MILLI
        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED)
        return elapsed
    }

    /** S-9b — 타입 불일치가 계약이 정한 모양으로 거절된다 (게이트 20 codex C4). */
    @Test
    @DisplayName("S-9b 숫자·불리언을 문자열 필드에 넣으면 422 배열 — 강제 변환으로 통과하지 않는다")
    fun `타입 불일치는 422 배열이다`() {
        listOf(
            Triple("숫자 비밀번호", """{"email":"coerce1@example.test","password":12345678}""", "password"),
            Triple("불리언 이메일", """{"email":true,"password":"$VALID_PASSWORD"}""", "email"),
            Triple("불리언 비밀번호", """{"email":"coerce3@example.test","password":true}""", "password"),
        ).forEach { (label, payload, field) ->
            val response = post("/auth/signup", payload)

            assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, SIGNUP_PATH, POST, label)

            val item = singleValidationItem(response, label)
            assertThat(item["loc"])
                .withFailMessage("%s 의 loc 이 문제 필드를 가리키지 않는다: %s", label, item["loc"])
                .isEqualTo(listOf("body", field))
            assertThat(item["type"]).isEqualTo("string_type")
            assertThat(item["msg"]).isEqualTo("Input should be a valid string")

            assertThat(response.body()).doesNotContain("12345678")
        }
    }

    /**
     * S-9c — 필드 누락·명시적 `null` 이 깨진 JSON 과 구분되고 어느 필드인지 가리킨다
     * (게이트 21 codex C-2 (i), contract-keeper §2-3).
     */
    @Test
    @DisplayName("S-9c 필드 누락·명시적 null 은 그 필드를 지목하는 missing 항목이고, 깨진 JSON 과 구분된다")
    fun `누락과 null 이 필드를 지목한다`() {
        listOf(
            Triple("비밀번호 누락", """{"email":"missing1@example.test"}""", "password"),
            Triple("이메일 누락", """{"password":"$VALID_PASSWORD"}""", "email"),
            Triple("명시적 null", """{"email":"missing3@example.test","password":null}""", "password"),
        ).forEach { (label, payload, field) ->
            val response = post("/auth/signup", payload)

            assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, SIGNUP_PATH, POST, label)
            val item = singleValidationItem(response, label)
            assertThat(item["loc"])
                .withFailMessage("%s 이 어느 필드인지 지목하지 않는다: %s", label, item["loc"])
                .isEqualTo(listOf("body", field))
            assertThat(item["type"])
                .withFailMessage("%s 의 type 이 계약 예시(field_missing)와 다르다: %s", label, item["type"])
                .isEqualTo("missing")
            assertThat(item["msg"]).isEqualTo("Field required")
        }

        val broken = singleValidationItem(post("/auth/signup", """{"email":"""), "깨진 JSON")
        assertThat(broken["type"]).isEqualTo("json_invalid")
        assertThat(broken["loc"]).isEqualTo(listOf("body"))
    }

    /**
     * S-9d — 루트에 배열·스칼라를 보내도 내부 클래스 이름이 새지 않는다
     * (게이트 21 codex C-2 (ii) — HTTP 경계 실측이 0관점이던 자리).
     */
    @Test
    @DisplayName("S-9d 루트 배열·스칼라 본문의 msg 에 내부 DTO 클래스 이름이 실리지 않는다")
    fun `루트 타입 불일치가 클래스 이름을 노출하지 않는다`() {
        listOf("루트 배열" to "[]", "루트 스칼라" to "5").forEach { (label, payload) ->
            val response = post("/auth/signup", payload)

            assertDeclaredStatus(response, UNPROCESSABLE_CONTENT, SIGNUP_PATH, POST, label)
            assertThat(response.body())
                .withFailMessage("%s 응답에 내부 DTO 이름이 실렸다: %s", label, response.body())
                .doesNotContain("SignupRequest", "kr.easydoc")
            val item = singleValidationItem(response, label)
            assertThat(item["loc"]).isEqualTo(listOf("body"))
            assertThat(item["type"]).isEqualTo("value_type")
        }
    }

    /** 422 배열 detail 에서 항목 하나를 꺼낸다. 배열 여부와 키 집합(계약 required)을 함께 건다. */
    private fun singleValidationItem(
        response: HttpResponse<String>,
        label: String,
    ): Map<*, *> {
        val detail = bodyOf(response)["detail"]
        assertThat(detail)
            .withFailMessage("%s 의 detail 이 배열이 아니다: %s", label, detail)
            .isInstanceOf(List::class.java)
        val items = detail as List<*>
        assertThat(items).withFailMessage("%s 의 detail 항목이 하나가 아니다: %s", label, items).hasSize(1)
        val item = items.first() as Map<*, *>
        assertThat(item.keys.map { it.toString() }.toSet())
            .isEqualTo(ContractSpec.schemaRequired("ValidationErrorItem"))
        return item
    }

    @Test
    @DisplayName("M-2 Authorization 헤더 없음 → 401 · WWW-Authenticate · 본문 최상위 키가 정확히 계약의 required")
    fun `헤더가 없으면 401 이고 본문이 계약 형태다`() {
        val response = get("/auth/me", token = null)

        assertDeclaredStatus(response, UNAUTHORIZED, ME_PATH, GET)

        assertPrivateHeaders(response)
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE).orElse(null))
            .isEqualTo(ContractSpec.headerConst("WWWAuthenticateBearer"))

        assertThat(ContractSpec.schemaAllowsAdditionalProperties("ErrorResponse")).isFalse()
        assertThat(bodyOf(response).keys.map { it.toString() }.toSet())
            .isEqualTo(ContractSpec.schemaRequired("ErrorResponse"))
        assertThat(response.headers().firstValue("content-type").orElse(""))
            .startsWith("application/json")
    }

    @Test
    @DisplayName("M-3 위조 서명·만료·typ 오값·sub 부재·삭제된 계정 → 다섯 응답이 서로 동일한 401")
    fun `토큰 무효 갈래가 서로 구분되지 않는다`() {
        val deletedUserToken = issueToken().also { deleteUserOf(it) }
        val responses =
            listOf(
                "위조 서명" to get("/auth/me", TestJwt.withBrokenSignature(issueToken())),
                "만료" to get("/auth/me", forgedToken(expiresAt = Instant.now().minusSeconds(1))),
                "typ 오값" to get("/auth/me", forgedToken(typ = "refresh")),
                "sub 부재" to get("/auth/me", forgedToken(subject = null)),
                "삭제된 계정" to get("/auth/me", deletedUserToken),
            )

        responses.forEach { (label, response) ->
            assertDeclaredStatus(response, UNAUTHORIZED, ME_PATH, GET, label)
        }
        val distinct =
            responses.map { (_, response) ->
                response.body() to
                    response.headers().firstValue(WWW_AUTHENTICATE)
            }
        assertThat(distinct.distinct())
            .withFailMessage("무효 갈래의 응답이 서로 다르다 — 어디서 실패했는지가 새어 나간다: %s", distinct)
            .hasSize(1)
    }

    /** M-3b — 토큰이 든 세 갈래의 401 응답 시간이 갈리지 않는다 (게이트 24 잔여 X24-2). */
    @Test
    @DisplayName("M-3b 삭제 계정·위조·만료 401 의 응답 시간이 갈리지 않는다 (X24-2)")
    fun `토큰 무효 세 갈래의 응답 시간이 갈리지 않는다`() {
        assertThat(ContractSpec.authText("failure_uniformity"))
            .withFailMessage("계약의 failure_uniformity 가 응답 시간 축을 더는 요구하지 않는다 — 이 케이스를 재판정하라")
            .contains(RESPONSE_TIME_CLAUSE)

        val tokens =
            mapOf(
                DELETED_ACCOUNT to issueToken().also { deleteUserOf(it) },
                FORGED_SIGNATURE to TestJwt.withBrokenSignature(issueToken()),
                EXPIRED_TOKEN to forgedToken(expiresAt = Instant.now().minusSeconds(1)),
            )

        val medians = unauthorizedMedians(tokens)
        val ratio = medians.values.max() / medians.values.min().coerceAtLeast(MIN_MEASURABLE_MILLIS)

        println(
            "M-3b %s → 비 %.3f (문턱 %.1f · 표본 각 %d · 워밍업 %d라운드)".format(
                medians.entries.joinToString(" / ") { "%s %.2fms".format(it.key, it.value) },
                ratio,
                MAX_TIMING_RATIO,
                UNIFORMITY_SAMPLES,
                UNIFORMITY_WARMUP_ROUNDS,
            ),
        )

        assertThat(ratio)
            .withFailMessage(
                "토큰 무효 갈래의 응답 시간이 갈린다 (비 %.2f배 · 문턱 %.1f): %s.\n" +
                    "  본문·헤더가 같아도 시간이 갈리면 「어디서 실패했는지」가 그대로 새어 나간다.\n" +
                    "  균일화(AuthService.authenticate 의 ABSENT_USER_PROBE_ID 왕복)가 빠졌는지 먼저 보라.",
                ratio,
                MAX_TIMING_RATIO,
                medians,
            ).isLessThanOrEqualTo(MAX_TIMING_RATIO)
    }

    /** 세 갈래를 섞어 재고 각각의 중앙값(밀리초)을 낸다. */
    private fun unauthorizedMedians(tokens: Map<String, String>): Map<String, Double> {
        val plan =
            (1..UNIFORMITY_SAMPLES)
                .flatMap { tokens.keys }
                .shuffled(Random(TIMING_SEED))

        repeat(UNIFORMITY_WARMUP_ROUNDS) { tokens.forEach { (_, token) -> unauthorizedMillis(token) } }

        val samples = plan.map { branch -> branch to unauthorizedMillis(tokens.getValue(branch)) }
        return tokens.keys.associateWith { medianOf(samples, it) }
    }

    /** 401 이 나오는 `/auth/me` 한 건의 왕복 시간. 401 이 아니면 잰 것이 다른 경로다. */
    private fun unauthorizedMillis(token: String): Double {
        val started = System.nanoTime()
        val response = get("/auth/me", token)
        val elapsed = (System.nanoTime() - started) / NANOS_PER_MILLI
        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED)
        return elapsed
    }

    @Test
    @DisplayName("M-4 계약의 required_claims 를 하나씩 뺀 토큰은 전부 401 (케이스를 계약에서 유도한다)")
    fun `필수 클레임이 빠지면 거부한다`() {
        val required = ContractSpec.authStrings("required_claims")
        assertThat(required).isNotEmpty()

        required.forEach { claim ->
            val token = forgedToken(omit = claim)
            assertThat(get("/auth/me", token).statusCode())
                .withFailMessage("클레임 %s 이 빠진 토큰이 거부되지 않았다", claim)
                .isEqualTo(UNAUTHORIZED)
        }
    }

    @Test
    @DisplayName("M-5 typ 가 계약의 claim_typ 와 다르면 401")
    fun `용도가 다른 토큰을 거부한다`() {
        val other = ContractSpec.authText("claim_typ") + "-아님"

        assertThat(get("/auth/me", forgedToken(typ = other)).statusCode()).isEqualTo(UNAUTHORIZED)
    }

    @Test
    @DisplayName("M-6 exp 를 계약의 허용 오차 + 1초 지난 토큰은 401 (skew 를 계약에서 읽어 유도한다)")
    fun `만료 직후 토큰을 거부한다`() {
        val skew = ContractSpec.authNumber("clock_skew_seconds").toLong()

        val expired = forgedToken(expiresAt = Instant.now().minusSeconds(skew + 1))

        assertThat(get("/auth/me", expired).statusCode()).isEqualTo(UNAUTHORIZED)
    }

    /** 허용 오차 그 자체를 재는 자리. */
    @Test
    @DisplayName("M-6b exp 를 계약의 허용 오차만큼 지난 토큰의 판정이 그 오차와 맞물린다")
    fun `허용 오차 경계 안쪽을 재다`() {
        val skew = ContractSpec.authNumber("clock_skew_seconds").toLong()
        val atTolerance = forgedToken(expiresAt = Instant.now().minusSeconds(skew))
        val expected =
            if (skew > 0) ContractSpec.successStatus("/auth/me", "get") else UNAUTHORIZED

        assertThat(get("/auth/me", atTolerance).statusCode())
            .withFailMessage("허용 오차 %d 초에서 기대한 상태(%d)가 나오지 않았다", skew, expected)
            .isEqualTo(expected)
    }

    @Test
    @DisplayName("M-7 exp 가 아직 지나지 않은 토큰은 통과한다 — M-6 의 반대쪽")
    fun `만료 전 토큰은 통과한다`() {
        val valid = forgedToken(expiresAt = Instant.now().plusSeconds(NOT_YET_EXPIRED_SECONDS))

        assertThat(get("/auth/me", valid).statusCode()).isEqualTo(ContractSpec.successStatus("/auth/me", "get"))
    }

    /** 실제로 가입·로그인해 얻은 토큰. 위조 케이스의 `sub` 도 여기서 온다. */
    private fun issueToken(): String {
        val email = uniqueEmail()
        post("/auth/signup", credentials(email, VALID_PASSWORD))
        return bodyOf(post("/auth/login", credentials(email, VALID_PASSWORD)))["access_token"].toString()
    }

    /** 위조 케이스가 공유하는 실재하는 사용자. */
    private val registeredUserId: String by lazy {
        bodyOf(post("/auth/signup", credentials(uniqueEmail(), VALID_PASSWORD)))["id"].toString()
    }

    /** 계약의 클레임 이름으로 토큰을 조립한다. 이름은 계약에서 읽고 값만 여기서 정한다. */
    private fun forgedToken(
        subject: String? = registeredUserId,
        typ: String = ContractSpec.authText("claim_typ"),
        expiresAt: Instant = Instant.now().plusSeconds(NOT_YET_EXPIRED_SECONDS),
        omit: String? = null,
    ): String {
        val claims =
            buildMap<String, Any?> {
                subject?.let { put("sub", it) }
                put("typ", typ)
                put("exp", expiresAt.epochSecond)
            }.filterKeys { it != omit }
        return TestJwt.signHs256(
            AUTH_REACH_TEST_SECRET,
            mapOf("alg" to ContractSpec.authText("algorithm")),
            claims,
        )
    }

    /** M-3 의 「삭제된 계정」 — 토큰은 유효한데 행이 없는 상태를 만든다. */
    private fun deleteUserOf(token: String) {
        val subject = TestJwt.payload(token)["sub"].toString()
        database.execute("DELETE FROM users WHERE id = '$subject'")
    }

    private fun credentials(
        email: String,
        password: String,
    ): String = json.writeValueAsString(mapOf("email" to email, "password" to password))

    private fun post(
        path: String,
        payload: String,
    ): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8)),
        )

    private fun get(
        path: String,
        token: String?,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(uri(path)).GET()
        token?.let { builder.header("Authorization", "Bearer $it") }
        return send(builder)
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    private fun uri(path: String): URI = URI.create("http://localhost:$port$path")

    private fun bodyOf(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    /** 값·부착 개수만 잰다(X-D2b). 하한선(X-D1)은 `PrivateHeaderFloorCensusTest` 가 진다. */
    private fun assertPrivateHeaders(response: HttpResponse<String>) {
        val byComponent = ContractSpec.globalHeaderValues()
        ContractSpec.globalResponseHeaders().forEach { (header, globalValue) ->
            val declared = byComponent.getValue(header)
            assertThat(declared)
                .withFailMessage("계약 안에서 %s 의 값이 두 절에 다르게 적혀 있다", header)
                .isEqualTo(globalValue)
            assertThat(response.headers().allValues(header))
                .withFailMessage("%s 가 %s 로 나갔다 — 값 또는 부착 개수가 계약과 다르다", header, response.headers().allValues(header))
                .containsExactly(declared)
        }
    }

    /** 상태 코드를 응답과 계약 양쪽에 건다 (C-1). */
    private fun assertDeclaredStatus(
        response: HttpResponse<String>,
        status: Int,
        path: String,
        method: String,
        label: String = path,
    ) {
        assertThat(response.statusCode()).withFailMessage("%s 가 %d 이 아니다", label, status).isEqualTo(status)
        assertThat(ContractSpec.responseStatuses(path, method))
            .withFailMessage("계약이 %s %s 에 %d 를 선언하지 않는다", method, path, status)
            .contains(status.toString())
    }

    private fun uniqueEmail(): String = "reach${counter++}@example.test"

    companion object {
        private const val UNAUTHORIZED = 401
        private const val UNPROCESSABLE_CONTENT = 422
        private const val SIGNUP_PATH = "/auth/signup"
        private const val POST = "post"
        private const val ME_PATH = "/auth/me"
        private const val GET = "get"
        private const val WWW_AUTHENTICATE = "WWW-Authenticate"
        private const val VALID_PASSWORD = "correct horse battery"
        private const val WRONG_PASSWORD = "correct horse batteryX"
        private const val NOT_YET_EXPIRED_SECONDS = 600L

        /**
         * 계약 조항이 시간 축을 요구한다는 표식. 문구 전문을 옮겨 적지 않는다 — 그러면
         * 계약을 코드에 복제하는 것이고, 조항이 조금만 다듬어져도 무관한 실패가 난다.
         */
        private const val RESPONSE_TIME_CLAUSE = "응답 시간"

        /** 경로당 표본 수. 홀수라 중앙값이 표본 하나로 정해진다. */
        private const val TIMING_SAMPLES = 11

        /** 두 경로를 섞는 순서. 고정 시드라 실패가 재현된다. */
        private const val TIMING_SEED = 20260819L

        private const val ABSENT_ACCOUNT = "absent"
        private const val KNOWN_ACCOUNT = "known"

        /**
         * M-3b 의 세 갈래 이름. 계약 `x-auth.failure_uniformity` 가 한 줄에 묶은 것과 같은 셋이고,
         * 무헤더는 여기 없다(사유는 그 케이스 KDoc).
         */
        private const val DELETED_ACCOUNT = "삭제 계정"
        private const val FORGED_SIGNATURE = "위조 서명"
        private const val EXPIRED_TOKEN = "만료"

        /** M-3b 의 경로당 표본 수와 폐기할 워밍업 라운드. */
        private const val UNIFORMITY_SAMPLES = 101
        private const val UNIFORMITY_WARMUP_ROUNDS = 20

        /**
         * 비를 낼 때 분모의 하한(밀리초). 0 으로 나누는 것을 막을 뿐 판정을 바꾸지 않는다 —
         * L-3b 가 `coerceAtLeast(1.0)` 을 쓰는 것과 달리 401 경로는 해시가 없어 1ms 미만이
         * 정상이므로, 1.0 을 쓰면 빠른 기계에서 비가 인위적으로 눌린다.
         */
        private const val MIN_MEASURABLE_MILLIS = 0.001

        /** 두 경로 중앙값의 허용 비. */
        private const val MAX_TIMING_RATIO = 1.5

        /** 로그인 한 건이 이보다 빠르면 실물 Argon2 가 도는 것이 아니다. */
        private const val MIN_REAL_HASH_MILLIS = 15.0

        private const val NANOS_PER_MILLI = 1_000_000.0

        private var counter = 0

        /** 이 테스트만 쓰는 DB. 다른 기동 테스트의 행과 섞이지 않게 따로 만든다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("auth_reach") }

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
const val AUTH_REACH_TEST_SECRET: String = "test-only-signing-key-0123456789-abcdef"
