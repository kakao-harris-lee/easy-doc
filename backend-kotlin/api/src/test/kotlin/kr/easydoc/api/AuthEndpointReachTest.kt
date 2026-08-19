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

/**
 * `/auth` 의 **실측** 계약 — 명세 §5 의 C-R 계층.
 *
 * ## 왜 MockMvc 가 아닌가
 *
 * 인증 실패 401(M-2~M-7)은 **필터·인터셉터·컨테이너가 얽힌 자리**에서 만들어진다. MockMvc 는
 * 서블릿 컨테이너를 띄우지 않고 필터를 손으로 체인에 끼워 부르므로, 인증 401 을
 * `sendError` 로 내는 구현(가장 흔한 형태)이 `/error` 재디스패치를 지나며 Spring 기본
 * 본문을 내보내는 것을 **재현하지 못한다.** 계약이 그 자리를 E-2 로 따로 지목했고,
 * 헤더 쪽에서 이미 "측정한 것처럼 보이는 통과"를 겪었다.
 *
 * 함께 여기 두는 것들 — **실물 설정에서만 잴 수 있는 값**이다.
 * `expires_in`(설정에서 온다)·`token_type`·발급 토큰의 클레임 집합, 그리고 가입이 기본
 * 작업 공간을 같은 트랜잭션에서 만드는지. 이것들을 슬라이스에서 재면 테스트 배선이 정한
 * 값을 테스트가 다시 단언하는 꼴이 된다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$AUTH_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthEndpointReachTest {
    @LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    // ================================================================ 성공 경로

    @Test
    @DisplayName("S-1 가입이 계정과 기본 작업 공간을 함께 만든다 (같은 트랜잭션)")
    fun `가입은 기본 작업 공간까지 만든다`() {
        val email = uniqueEmail()

        val response = post("/auth/signup", credentials(email, VALID_PASSWORD))

        assertThat(response.statusCode()).isEqualTo(ContractSpec.successStatus("/auth/signup", "post"))
        val userId = UUID.fromString(bodyOf(response)["id"].toString())
        // 계정만 있고 작업 공간이 없는 사용자가 생기면 첫 업로드가 갈 곳을 잃는다.
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
        // X-E1/X-E2 — snake_case 회귀와 키 누락·추가를 「정확히」 하나로 잡는다.
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

    /**
     * **L-3b — 자격증명 실패의 세 번째 축: 응답 시간.**
     *
     * 계약 `x-auth.failure_uniformity` 는 축이 셋이다 — 상태 코드·본문·**응답 시간**.
     * `AuthContractTest` L-3 이 앞의 둘을 재고, 셋째는 실물 Argon2 가 도는 이 계층에서만
     * 잴 수 있다(슬라이스의 가짜 해시는 비용이 0 이라 격차가 나타나지 않는다).
     *
     * ## 절대값이 아니라 **비**로 판정한다
     *
     * 기계·부하에 따라 Argon2 1회 비용이 달라지므로 "몇 밀리초 이하"는 재현되지 않는다.
     * 두 경로의 중앙값 비는 그 변동을 함께 타므로 남는다 — 수정 전 실측은 **약 42배**였고
     * (privacy-gate B-1: 2.3ms vs 97ms) 더미 검증을 넣으면 1 에 가까워진다.
     * 워밍업 1건은 버린다(첫 요청은 클래스 적재·JIT 을 함께 문다).
     */
    @Test
    @DisplayName("L-3b 없는 이메일과 있는 이메일의 로그인 응답 시간이 갈리지 않는다 (계약 x-auth 3번째 축)")
    fun `자격증명 실패의 응답 시간이 갈리지 않는다`() {
        // 조항이 시간 축을 요구한다는 사실 자체를 계약에서 읽는다 — 그 문장이 지워지면
        // 이 케이스가 무엇을 지키는지부터 다시 판단해야 한다.
        assertThat(ContractSpec.authText("failure_uniformity"))
            .withFailMessage("계약의 failure_uniformity 가 응답 시간 축을 더는 요구하지 않는다 — 이 케이스를 재판정하라")
            .contains(RESPONSE_TIME_CLAUSE)

        val known = uniqueEmail()
        post("/auth/signup", credentials(known, VALID_PASSWORD))

        val absent = medianLoginMillis { uniqueEmail() }
        val wrongPassword = medianLoginMillis { known }

        val ratio = maxOf(absent, wrongPassword) / minOf(absent, wrongPassword).coerceAtLeast(1.0)
        assertThat(ratio)
            .withFailMessage(
                "로그인 응답 시간이 계정 존재 여부로 갈린다 — 없는 이메일 %.1fms / 틀린 비밀번호 %.1fms (비 %.1f배). " +
                    "계정이 없을 때도 더미 PHC 로 같은 검증 비용을 치러야 한다",
                absent,
                wrongPassword,
                ratio,
            ).isLessThan(MAX_TIMING_RATIO)
    }

    /** 워밍업 1건을 버리고 [TIMING_SAMPLES] 건의 중앙값(밀리초)을 낸다. */
    private fun medianLoginMillis(email: () -> String): Double {
        post("/auth/login", credentials(email(), WRONG_PASSWORD))
        val samples =
            (1..TIMING_SAMPLES).map {
                val started = System.nanoTime()
                val response = post("/auth/login", credentials(email(), WRONG_PASSWORD))
                val elapsed = (System.nanoTime() - started) / NANOS_PER_MILLI
                assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED)
                elapsed
            }
        return samples.sorted()[TIMING_SAMPLES / 2]
    }

    /**
     * **S-9b — 타입 불일치가 계약이 정한 모양으로 거절된다** (게이트 20 codex C4).
     *
     * Jackson 기본 설정은 숫자·불리언을 문자열 필드에 **말없이 변환한다.** 실측(수정 전):
     * `password: 12345678` 은 **201** 이었고, `email: true` 는 형식 오류 문자열 detail 로
     * 둔갑했다. 계약 `ValidationFailed` 는 타입 불일치를 **배열 detail** 로 정한다.
     *
     * 슬라이스가 아니라 이 계층에서 재는 이유: 강제 변환을 끄는 것은 `JsonMapperBuilderCustomizer`
     * 빈이고 `@WebMvcTest` 는 평범한 `@Configuration` 을 슬라이스에 넣지 않는다. 슬라이스에서
     * 재면 **테스트가 직접 들여온 배선**을 재게 되고, 실물 컨텍스트가 그 빈을 줍는지는 재지 못한다.
     */
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
            // TST-3 — 키 집합만 보면 「언제나 `["body"]`」를 내는 회귀가 초록이다. 값까지 건다.
            val item = singleValidationItem(response, label)
            assertThat(item["loc"])
                .withFailMessage("%s 의 loc 이 문제 필드를 가리키지 않는다: %s", label, item["loc"])
                .isEqualTo(listOf("body", field))
            assertThat(item["type"]).isEqualTo("string_type")
            assertThat(item["msg"]).isEqualTo("Input should be a valid string")
            // 거절된 값이 응답으로 되돌아오지 않는다 — 비밀번호가 그 자리에 있을 수 있다.
            assertThat(response.body()).doesNotContain("12345678")
        }
    }

    /**
     * **S-9c — 필드 누락·명시적 `null` 이 깨진 JSON 과 구분되고 어느 필드인지 가리킨다**
     * (게이트 21 codex C-2 (i), contract-keeper §2-3).
     *
     * 종전에는 셋이 **바이트 동일**했다(`loc:["body"]`·`"JSON decode error"`·`json_invalid`).
     * 필드를 빠뜨린 사용자가 화면에서 "JSON decode error" 를 봤고, 계약 예시
     * `field_missing`(`type: "missing"`)은 **어떤 요청에서도 나오지 않았다.**
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

        // 깨진 JSON 은 여전히 파싱 실패 갈래다 — 세 경우가 다시 한 모양으로 뭉치면 여기서 깨진다.
        val broken = singleValidationItem(post("/auth/signup", """{"email":"""), "깨진 JSON")
        assertThat(broken["type"]).isEqualTo("json_invalid")
        assertThat(broken["loc"]).isEqualTo(listOf("body"))
    }

    /**
     * **S-9d — 루트에 배열·스칼라를 보내도 내부 클래스 이름이 새지 않는다**
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

    // ================================================================ 인증 실패 (C-R 의 요점)

    @Test
    @DisplayName("M-2 Authorization 헤더 없음 → 401 · WWW-Authenticate · 본문 최상위 키가 정확히 계약의 required")
    fun `헤더가 없으면 401 이고 본문이 계약 형태다`() {
        val response = get("/auth/me", token = null)

        assertDeclaredStatus(response, UNAUTHORIZED, ME_PATH, GET)
        // X-D2 — 컨테이너·인터셉터가 만드는 401 에도 전역 헤더가 붙는가. 「실행해 봐야만
        // 잡힌다」고 계약이 적은 성질이고(x-failure-mode-shift), 그 자리를 재는 케이스가 여기다.
        assertPrivateHeaders(response)
        assertThat(response.headers().firstValue(WWW_AUTHENTICATE).orElse(null))
            .isEqualTo(ContractSpec.headerConst("WWWAuthenticateBearer"))
        // `sendError` → `/error` 로 나가면 timestamp·status·error·path 가 함께 실린다.
        // 금지 키를 열거하지 않고 「정확히 같다」로 잡는다 — 그 목록은 계약 산문에만 있다.
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
        // 허용 오차가 0 이면 exp 가 1초 전인 토큰이다. 오차가 바뀌면 이 값도 따라 바뀐다.
        val expired = forgedToken(expiresAt = Instant.now().minusSeconds(skew + 1))

        assertThat(get("/auth/me", expired).statusCode()).isEqualTo(UNAUTHORIZED)
    }

    /**
     * **허용 오차 그 자체를 재는 자리.**
     *
     * M-6 은 `skew + 1` 만 보므로 계약의 오차가 0에서 120으로 바뀌어도 여전히 만료라
     * **통과해 버린다**(음성 대조 N4 실측). 오차 경계 **안쪽**을 함께 재야 값이 코드에
     * 결속된다 — `exp` 를 정확히 `skew` 만큼 지난 토큰은 오차가 0이면 거절, 0보다 크면
     * 수용이어야 한다. 기대값을 계약에서 **유도**하므로 오차를 바꾸면 이 케이스가 뒤집힌다.
     */
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
        // 한쪽만 걸면 「전부 거절」 구현이 M-6 을 통과한다.
        val valid = forgedToken(expiresAt = Instant.now().plusSeconds(NOT_YET_EXPIRED_SECONDS))

        assertThat(get("/auth/me", valid).statusCode()).isEqualTo(ContractSpec.successStatus("/auth/me", "get"))
    }

    // ================================================================ 도구

    /** 실제로 가입·로그인해 얻은 토큰. 위조 케이스의 `sub` 도 여기서 온다. */
    private fun issueToken(): String {
        val email = uniqueEmail()
        post("/auth/signup", credentials(email, VALID_PASSWORD))
        return bodyOf(post("/auth/login", credentials(email, VALID_PASSWORD)))["access_token"].toString()
    }

    /**
     * 위조 케이스가 공유하는 **실재하는** 사용자.
     *
     * `sub` 를 아무 UUID 로 두면 M-7(만료 전 토큰은 통과한다)이 "계정 없음"으로 401 이 되어
     * 그 케이스가 재려던 것을 재지 못한다 — 통과 쪽 단언은 다른 이유로 막히면 안 된다.
     */
    private val registeredUserId: String by lazy {
        bodyOf(post("/auth/signup", credentials(uniqueEmail(), VALID_PASSWORD)))["id"].toString()
    }

    /**
     * 계약의 클레임 이름으로 토큰을 조립한다. 이름은 계약에서 읽고 값만 여기서 정한다.
     *
     * [omit] 은 그 클레임을 아예 빼고, [subject]·[typ] 은 값을 바꾼다.
     */
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

    /**
     * X-D1 하한선 + X-D2b 중복 부착 부재. 값과 개수를 모두 계약에서 읽어 본다.
     *
     * 값은 **헤더 컴포넌트의 `const`** 에서 읽는다(P-3). 전역 절
     * (`x-global-response-headers.headers`)의 값만 읽으면 컴포넌트 `const` 가 바뀌어도
     * 이 테스트가 반응하지 않는다 — 음성 대조 N3 에서 실측으로 드러난 자리다.
     * 두 곳이 갈리지 않는지도 함께 본다.
     */
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

    /**
     * 상태 코드를 **응답과 계약 양쪽에** 건다 (C-1).
     *
     * `/auth/me` 의 401 은 계약 대조 없이 상수만 썼다 — 계약에서 그 선언을 지워도 빨강이
     * 0 이었다(실측 `'401'`→`'403'`).
     */
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

        /** 워밍업 뒤 표본 수. 홀수라 중앙값이 표본 하나로 정해진다. */
        private const val TIMING_SAMPLES = 5

        /**
         * 두 경로 중앙값의 허용 비. 수정 전 실측이 **42배**였고 수정 후 기대는 1 근처다 —
         * 이 값은 기계 지터를 넉넉히 덮으면서 그 사이 어디에도 닿지 않는 자리에 둔다.
         */
        private const val MAX_TIMING_RATIO = 4.0

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

/**
 * 이 테스트가 쓰는 서명 키.
 *
 * 32바이트 이상이어야 한다 — 계약 `x-auth.min_secret_bytes` 미만이면 인증 API 전체가
 * 503 이 되고, 그 상태는 [AuthUnavailableContractTest] 가 따로 잰다. 값이
 * `@SpringBootTest(properties=...)` 안에 들어가야 해서 컴파일 상수여야 한다.
 */
const val AUTH_REACH_TEST_SECRET: String = "test-only-signing-key-0123456789-abcdef"
