package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.api.support.ProductClasses
import kr.easydoc.api.support.RequestFieldProbes
import kr.easydoc.api.support.RequestFieldProbes.Observed
import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * **F3 의 두 번째 강제자 (슬라이스 관측)** — 「그 다섯 필드의 길이·형식 판정이 어느 층에서
 * 일어나는가」를 응답 `detail` 의 모양으로 잰다. 판정과 프로브 조립은 [RequestFieldProbes] 다.
 *
 * ## 왜 이것이 필요해졌는가
 *
 * 계약은 다섯 요청 필드에 길이·형식 Bean Validation 을 금지했다(F3). C4 이전까지 그 금지를
 * 지킨 것은 둘이었다 — [RequestFieldConstraintLayerTest] 의 **애너테이션 부재 스캔**과,
 * 「`spring-boot-starter-validation` 이 클래스패스에 없어 **달 수조차 없다**」는 사실.
 * C4 가 그 의존성을 들여 **두 번째를 영구히 없앴고**, 그 순간 금지 애너테이션 **열거**가
 * 처음으로 실제 방벽이 되었다. 열거가 곧 약점이다.
 *
 * ## 왜 열거를 넓히지 않았나 (`CLAUDE.md` 규칙 4 분류)
 *
 * 금지 애너테이션 목록은 **범위 선언형**이고 규칙 ⑶ 이 걸린다. 그리고 그 목록은 닫히지 않는다 —
 * `@CodePointLength`·`@Range`·`@DecimalMin`·직접 만든 `ConstraintValidator` 가 모두 같은 일을
 * 한다. 열거를 넓히는 것은 다음 항목이 생길 때까지만 참인 조치다. 그래서 **탐지형으로**
 * 갈아탔다: 재는 것은 애너테이션이 아니라 **나간 바이트**다.
 *
 * ## 이 축만으로는 부족하다 — **관측창이 경계 ±1 근처다** (R-4)
 *
 * 계약보다 **느슨한** 경계를 가진 제약(계약 상한 50 인 필드에 `@CodePointLength(max = 100)`)은
 * 이 축의 어느 프로브에서도 발화하지 않는다 — 49·50·51·정규화·빈 값 전부 100 아래다.
 * 프로브를 멀리 쏘는 것으로 메우지 않았다(창이 넓어질 뿐 여전히 창이고, `text` 는 전송
 * 상한에 걸려 413 이 나와 축이 오염된다). 그 자리는 [RequestFieldConstraintLayerTest] 가
 * **(전이적) `@Constraint` 보유**라는 정의적 성질로 덮는다 — 그쪽도 열거를 버렸다.
 *
 * **두 강제자는 서로의 구멍을 덮는다**: 이 축은 「제약이 없어도 앞단이 거절하는」 경로를,
 * 저 축은 「경계가 느슨해 발화하지 않는 제약」을 잡는다.
 *
 * ## 이 축의 도달 경계 — **측정했다** (2026-08-21)
 *
 * 종전 KDoc 은 *"이 축은 무엇이 앞단에서 거절했는지를 묻지 않는다"* 고 적었다. **그것은
 * 재지 않은 전칭이었다.** 이 테스트의 관측 지점은 `@WebMvcTest` **슬라이스**이므로
 * 슬라이스에 들어오지 않는 장치가 만든 응답은 보이지 않는다. 형태별로 길이 가드를 심어
 * 실측한 결과는 아래와 같고, 표의 정본은 산출물
 * `docs/migration/_workspace/04_kotlin-implementer_documents.md` 의 「C4-R1」 절이다.
 *
 * | 앞단 장치 형태 | 이 슬라이스 축 | 컨테이너 축([RequestFieldRejectionReachTest]) |
 * |---|---|---|
 * | `@Component` 필터 | **본다** | 본다 |
 * | 임포트 안 된 `@Configuration` 의 `@Bean` 필터(= `CorsConfig` 형태) | **못 본다** | 본다 |
 * | 톰캣 Engine 밸브 | **못 본다**(톰캣이 없다) | 본다 |
 * | `WebMvcConfigurer.addInterceptors` 로 등록한 인터셉터 | **본다** | 본다 |
 * | `@Component` `HandlerInterceptor`(등록 없음) | 대상 아님 — 체인에 등록되지 않아 **가드로 성립하지 않는다** | 같음 |
 * | `WebMvcConfigurer.addArgumentResolvers` 의 커스텀 리졸버 | 대상 아님 — 내장 `@RequestBody` 처리가 선점해 **불리지 않는다** | 같음 |
 *
 * 마지막 두 줄은 「탐지기가 놓쳤다」가 아니다 — **심은 가드가 애초에 돌지 않았다.** 앞엣것은
 * 같은 인터셉터를 `addInterceptors` 로 등록하면 두 축이 모두 빨개지는 것으로, 뒤엣것은
 * `supportsParameter` 호출이 **0회**인 것으로 확인했다.
 *
 * **그래서 컨테이너 축을 함께 세웠다**([RequestFieldRejectionReachTest]). 이 파일이 재는
 * 것은 「슬라이스 안에서 일어난 거절」이고, 「서버가 실제로 내보낸 바이트」는 그쪽이 잰다.
 * 이 파일을 남기는 이유는 도커·DB 없이 돌고 클래스가 생기는 즉시 도는 축이라서다.
 *
 * ## 「위반 0건」이 공허하지 않다는 것도 함께 잰다
 *
 * 판정 함수가 배열을 볼 수 있는지를 **대조 프로브**로 확인한다 — 필수 필드를 뺀 요청은 배열
 * `detail` 이 나가야 하고, 같은 판정이 그것을 지목해야 한다.
 */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class RequestFieldRejectionLayerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var users: InMemoryUserRepository

    @Autowired
    private lateinit var workspaces: InMemoryWorkspaceRepository

    private val json = ObjectMapper()

    // ================================================================ 도달 범위

    @Test
    @DisplayName("계약의 요청 필드 전부가 프로브를 갖거나 **DTO 가 아직 없다고 드러난다** — 조용히 건너뛰지 않는다")
    fun `계약 필드 전부가 다뤄진다`() {
        val fields = RequestFieldProbes.contractFields()
        assertThat(fields)
            .withFailMessage("계약 x-request-field-constraints.fields 가 비었다 — 아래 대조가 전부 0건 검사가 된다")
            .isNotEmpty()

        val apiSimpleNames =
            ProductClasses
                .onTestRuntimeClasspath()
                .filter { it.qualifiedName?.startsWith(API_PACKAGE) == true }
                .mapNotNull { it.simpleName }
                .toSet()
        val absent = fields.filterNot { it.substringBefore('.') in apiSimpleNames }.toSet()

        // **정확 열거 핀**이다. 계약이 필드를 더하면 프로브가 없어 빨강, DTO 가 생기면
        // 이 집합이 줄어 빨강 — 어느 쪽이든 diff 가 리뷰에 올라온다.
        assertThat(absent)
            .withFailMessage(
                "「api 모듈에 DTO 가 없는 계약 필드」 집합이 핀과 다르다.\n" +
                    "  줄었다면 그 DTO 가 생긴 것이다 — **프로브를 배선하고 핀에서 지워라**(그 커밋이 F3 마감이다).\n" +
                    "  늘었다면 DTO 가 사라졌거나 계약에 필드가 늘었다.\n  실제: %s / 핀: %s",
                absent,
                PINNED_WITHOUT_DTO,
            ).isEqualTo(PINNED_WITHOUT_DTO)

        val expected = fields.toSet() - absent
        assertThat(probes().keys)
            .withFailMessage("프로브 목록이 「계약 필드 − DTO 없는 필드」와 다르다 — 프로브 없는 필드는 이 축에서 **검사받지 않는다**")
            .isEqualTo(expected)
        // 값 조립 규칙도 같은 집합을 덮어야 한다. 안 덮으면 `valueOf` 가 실행에서 끊기지만,
        // 그 실패는 「프로브가 없다」와 구분되지 않는다 — 여기서 먼저 가른다.
        assertThat(RequestFieldProbes.FIELD_SHAPES.keys)
            .withFailMessage("RequestFieldProbes.FIELD_SHAPES 가 계약 필드 집합을 덮지 않는다")
            .isEqualTo(expected)
    }

    @Test
    @DisplayName("정규화 축도 계약 필드 전부에 걸린다 — measured_on 이 NORMALIZED 인 필드가 실재한다")
    fun `정규화 축의 대상이 실재한다`() {
        // 계약이 F3 의 결정적 근거로 든 것이 이 축이다(`x-why-this-section-exists`).
        // 대상이 0 이면 `measure()` 의 정규화 갈래는 아무 필드에서도 돌지 않으면서 초록이다.
        val normalized =
            probes().keys.filter { ContractSpec.requestFieldConstraint(it).measuresNormalized }

        assertThat(normalized)
            .withFailMessage("정규화 후를 재는 계약 필드가 하나도 없다 — 이 축은 아무것도 재지 않는다")
            .isNotEmpty()
        println("F3 정규화 축 대상: $normalized / 원시 축 대상: ${probes().keys - normalized.toSet()}")
    }

    // ================================================================ 본 축

    @Test
    @DisplayName("길이·정규화·문구 갈래가 **서비스 층**에서 판정된다 — 422 · detail 문자열 · 계약 선언 문구 (F3)")
    fun `길이 판정이 스키마 층에서 일어나지 않는다`() {
        val findings = probes().map { (field, probe) -> RequestFieldProbes.measure(field, probe) }

        assertThat(findings).isNotEmpty()

        val schemaLayer = findings.filter { it.arrayShaped.isNotEmpty() }
        assertThat(schemaLayer.map { "${it.field} ${it.arrayShaped}" })
            .withFailMessage(
                "아래 필드의 거절이 **배열** detail 로 나갔다 — 스키마·바인딩 층이 판정했다는 뜻이고 계약 F3 위반이다.\n" +
                    "  금지 애너테이션 목록에 없는 가드(커스텀 제약·중첩 @Valid)도 여기서 잡힌다.\n%s",
                schemaLayer.joinToString("\n") { "  - ${it.field}: ${it.arrayShaped}" },
            ).isEmpty()

        val misjudged = findings.filter { it.problems.isNotEmpty() }
        assertThat(misjudged.map { it.field })
            .withFailMessage(
                "아래 필드의 판정이 계약과 다르다:\n%s",
                misjudged.joinToString("\n") { "  - ${it.field}\n      ${it.problems.joinToString("\n      ")}" },
            ).isEmpty()
    }

    @Test
    @DisplayName("형식 위반도 서비스 층이다 — 이메일이 형식 축의 유일한 대상이다 (@Email·@Pattern 금지의 관측면)")
    fun `형식 판정이 스키마 층에서 일어나지 않는다`() {
        val declared = RequestFieldProbes.declaredDetails(SIGNUP_EMAIL_FIELD)

        val observed = signup(email = RequestFieldProbes.MALFORMED_EMAIL, password = validPassword())

        assertThat(observed.status).isEqualTo(RequestFieldProbes.UNPROCESSABLE)
        assertThat(observed.detail)
            .withFailMessage("형식 위반이 배열 detail 로 나갔다 — @Email·@Pattern 이 붙으면 이렇게 된다")
            .isInstanceOf(String::class.java)
        assertThat(observed.detail.toString()).isIn(declared)
    }

    // ================================================================ 판정 함수가 실제로 지목한다

    @Test
    @DisplayName("판정 함수가 배열 detail 을 지목한다 — 대조 프로브로 확인한다(위 초록이 공허하지 않다)")
    fun `판정 함수가 배열을 지목한다`() {
        // 필수 필드를 뺀 요청은 계약이 **배열**로 정한 갈래다(`ValidationFailed.field_missing`).
        val control = postJson(SIGNUP_PATH, "{}")

        assertThat(control.status).isEqualTo(RequestFieldProbes.UNPROCESSABLE)
        assertThat(control.arrayShaped)
            .withFailMessage("판정 함수가 명백한 배열 detail 을 배열로 보지 못했다 — 위 케이스의 초록은 아무 뜻이 없다")
            .isTrue()

        // 과잉 탐지 0 — 문자열 갈래를 배열로 오인하지 않는다.
        assertThat(signup(RequestFieldProbes.MALFORMED_EMAIL, validPassword()).arrayShaped).isFalse()
    }

    // ================================================================ 프로브

    /**
     * 계약 필드 → 「그 필드에 이 값을 넣은 요청을 보낸다」.
     *
     * 이 매핑은 열거지만 **도달을 보증하는 것은 열거가 아니다** — 위
     * [`계약 필드 전부가 다뤄진다`] 가 계약에서 읽은 집합과 정확 일치로 대조한다.
     */
    private fun probes(): Map<String, (String) -> Observed> =
        mapOf(
            SIGNUP_EMAIL_FIELD to { value -> signup(email = value, password = validPassword()) },
            SIGNUP_PASSWORD_FIELD to { value -> signup(email = RequestFieldProbes.uniqueEmail(), password = value) },
            TEXT_FIELD to ::probeText,
            NAME_FIELD to ::probeName,
        )

    private fun probeText(value: String): Observed =
        postJson(DOCUMENTS_PATH, json.writeValueAsString(mapOf(TEXT_PROPERTY to value)), newOwner())

    private fun probeName(value: String): Observed =
        postJson(WORKSPACES_PATH, json.writeValueAsString(mapOf(NAME_PROPERTY to value)), newOwner())

    private fun signup(
        email: String,
        password: String,
    ): Observed = postJson(SIGNUP_PATH, json.writeValueAsString(mapOf("email" to email, "password" to password)))

    private fun postJson(
        path: String,
        body: String,
        owner: UUID? = null,
    ): Observed {
        val response =
            mockMvc
                .post(path) {
                    if (owner != null) header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andReturn()
                .response
        return Observed(response.status, detailOf(response))
    }

    /** 인증이 필요한 프로브가 쓸 계정. 기본 작업 공간까지 만든다(`DocumentContractTest` 와 같은 규칙). */
    private fun newOwner(): UUID {
        val id = users.create("probe-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
        return id
    }

    /** 다른 필드의 프로브가 비밀번호 규칙에 걸리지 않게 하는 값. 길이는 계약 하한에서 온다. */
    private fun validPassword(): String =
        RequestFieldProbes.FILLER_CHAR.repeat(ContractSpec.requestFieldConstraint(SIGNUP_PASSWORD_FIELD).limit)

    private fun detailOf(response: MockHttpServletResponse): Any? {
        val body = response.getContentAsString(StandardCharsets.UTF_8)
        if (body.isEmpty()) return null
        return json.readValue(body, Map::class.java)["detail"]
    }

    private companion object {
        val STUB_HASH = PasswordHash("stub-hash")

        const val API_PACKAGE = "kr.easydoc.api."

        const val SIGNUP_PATH = "/auth/signup"
        const val DOCUMENTS_PATH = "/documents"
        const val WORKSPACES_PATH = "/workspaces"

        const val TEXT_PROPERTY = "text"
        const val NAME_PROPERTY = "name"

        /** 계약이 필드를 지목하는 **경로 문자열**이다. 값이 아니라 이름이다. */
        const val SIGNUP_EMAIL_FIELD = "SignupRequest.email"
        const val SIGNUP_PASSWORD_FIELD = "SignupRequest.password"
        const val TEXT_FIELD = "DocumentTextRequest.text"
        const val NAME_FIELD = "WorkspaceNameRequest.name"

        /**
         * **계약 필드 중 api 모듈에 DTO 가 아직 없는 것** — 정확 열거 핀이다.
         *
         * `ConversionReviewRequest` 는 `PUT /conversions/{id}` 를 만드는 커밋(C7)이 만든다.
         * 그 커밋이 이 핀을 비우고 프로브를 배선하면 F3 다섯 필드가 이 축에서 마감된다 —
         * **길이 축과 정규화 축이 함께** 강제된다(그 필드의 `measured_on` 은 「정규화 후」다).
         *
         * **비우는 방향으로만 고쳐라.**
         */
        val PINNED_WITHOUT_DTO = setOf("ConversionReviewRequest.edited_text")
    }
}
