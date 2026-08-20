package kr.easydoc.api

import jakarta.validation.Validator
import kr.easydoc.api.auth.AuthController
import kr.easydoc.api.auth.SignupRequest
import kr.easydoc.api.document.DocumentController
import kr.easydoc.api.document.DocumentTextRequest
import kr.easydoc.api.support.ConstraintMetadata
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.RequestFieldProbes
import kr.easydoc.api.support.RequestFieldProbes.Observed
import kr.easydoc.api.workspace.WorkspaceController
import kr.easydoc.api.workspace.WorkspaceNameRequest
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
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

/**
 * **F3 의 두 번째 강제자 (컨테이너 관측)** — 같은 판정([RequestFieldProbes])을 **실제 소켓**으로
 * 잰다. 재는 것은 「서버가 실제로 내보낸 바이트」다.
 *
 * ## 왜 슬라이스 축만으로는 부족한가 — 실측한 도달 경계 (2026-08-21)
 *
 * [RequestFieldRejectionLayerTest] 의 관측 지점은 `@WebMvcTest` 슬라이스다. 그래서 슬라이스에
 * 들어오지 않는 앞단 장치가 만든 응답은 **보이지 않는다.** 형태별로 길이 가드를 심어 재 봤다:
 *
 * | 앞단 장치 형태 | 슬라이스 축 | **이 축** |
 * |---|---|---|
 * | `@Component` 필터 | 본다 | 본다 |
 * | 임포트 안 된 `@Configuration` 의 `@Bean` 필터(= `CorsConfig` 형태) | **못 본다** | **본다** |
 * | 톰캣 Engine 밸브 | **못 본다**(톰캣이 없다) | **본다** |
 * | `WebMvcConfigurer.addInterceptors` 로 등록한 인터셉터 | 본다 | 본다 |
 * | `@Component` `HandlerInterceptor`(등록 없음) | 대상 아님 — **가드로 성립하지 않는다** | 같음 |
 * | `WebMvcConfigurer.addArgumentResolvers` 의 커스텀 리졸버 | 대상 아님 — **불리지 않는다** | 같음 |
 *
 * 가운데 두 줄이 이 파일의 존재 이유다. 이 저장소는 **요청 단계 장치를 바로 그 자리(Engine
 * 밸브)로 옮긴 전력이 있고**(사적 응답 헤더 — 필터 단층으로 6종이 비어 밸브를 더했다),
 * 그러므로 「앞단 가드가 MockMvc 가 못 보는 데 산다」는 것이 이 저장소의 가설이 아니라 선례다.
 *
 * 슬라이스 축을 지우지 않는 이유: 도커·DB 없이 돌고 DTO 가 생기는 즉시 돈다. 두 축은
 * **관측 지점만** 다르고 판정은 한 벌이다.
 *
 * ## 여전히 증명하지 못하는 것
 *
 * ⑴ 이 축도 **구현된 오퍼레이션만** 잰다 — `edited_text` 는 C7 까지 프로브가 없고 그 사실이
 *    슬라이스 축의 `PINNED_WITHOUT_DTO` 로 드러난다. ⑵ 프로브가 쓰는 「정규화가 걷어내는
 *    잡음」은 계약 `measured_on` **산문에서 손으로 옮긴 것**이라, 계약이 정규화 규칙을 바꾸면
 *    잡음 선택이 갈릴 수 있다. ⑶ **관측창이 경계 ±1 근처다** — 계약보다 느슨한 경계를 가진
 *    제약은 여기서도 발화하지 않는다. 그 자리는 [RequestFieldConstraintLayerTest] 가
 *    (전이적) `@Constraint` 보유로 덮는다(R-4). 세 잔여는 산출물에 적었다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$DOCUMENT_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestFieldRejectionReachTest {
    @LocalServerPort
    private var port: Int = 0

    /**
     * **스프링이 구성한** 검증기. [ConstraintMetadata.standalone] 과 다른 인스턴스다.
     *
     * 프로그램적 `ConstraintMapping` 은 이 빈에만 배선되므로 그 선언 형태는 **여기서만** 보인다
     * (실측 표는 [ConstraintMetadata] KDoc).
     */
    @Autowired
    private lateinit var springValidator: Validator

    private val json = ObjectMapper()

    @Test
    @DisplayName("실제 소켓으로도 길이·정규화·문구 갈래가 서비스 층에서 판정된다 — 앞단 장치가 만든 응답까지 본다 (F3)")
    fun `나간 바이트로 재도 스키마 층 거절이 없다`() {
        val probes = probes()
        // 도달 대조는 슬라이스 축이 정확 열거 핀으로 진다. 여기서는 **분모가 비지 않았는지**만
        // 본다 — 0건을 훑고 통과하는 상태를 막는 최소 단언이다.
        assertThat(probes.keys)
            .withFailMessage("프로브가 하나도 없다 — 이 축은 아무것도 재지 않는다")
            .isNotEmpty()
        assertThat(RequestFieldProbes.contractFields()).containsAll(probes.keys)

        val findings = probes.map { (field, probe) -> RequestFieldProbes.measure(field, probe) }

        val schemaLayer = findings.filter { it.arrayShaped.isNotEmpty() }
        assertThat(schemaLayer.map { "${it.field} ${it.arrayShaped}" })
            .withFailMessage(
                "아래 필드의 거절이 **배열** detail 로 나갔다 — 스키마·바인딩 층(또는 앞단 필터·밸브)이 " +
                    "판정했다는 뜻이고 계약 F3 위반이다.\n%s",
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
    @DisplayName("이 축도 배열 detail 을 볼 수 있다 — 대조 프로브(필수 필드 누락)로 확인한다")
    fun `판정 함수가 배열을 지목한다`() {
        val control = postJson(SIGNUP_PATH, "{}")

        assertThat(control.status).isEqualTo(RequestFieldProbes.UNPROCESSABLE)
        assertThat(control.arrayShaped)
            .withFailMessage("실제 소켓 응답에서 배열 detail 을 보지 못했다 — 위 케이스의 초록은 아무 뜻이 없다")
            .isTrue()
    }

    // ================================================================ R-5 — 엔진 질의(컨테이너 축)

    @Test
    @DisplayName("R-5 스프링이 구성한 엔진도 계약 다섯 필드의 DTO 에서 제약을 0 개 본다 — 프로그램적 매핑까지 덮는 층이다")
    fun `스프링 엔진 메타데이터에 DTO 제약이 없다`() {
        // 이 층이 따로 있는 이유는 하나다 — 프로그램적 `ConstraintMapping` 은 **스프링 빈에만**
        // 배선되므로 컨텍스트 없는 `standalone` 엔진이 모른다(실측: `ConstraintMetadata` 표).
        // 전제부터 단언한다: 두 엔진이 **같은 인스턴스가 아니어야** 이 층이 뜻을 갖는다.
        assertThat(springValidator)
            .withFailMessage("스프링 검증기가 standalone 과 같은 인스턴스다 — 이 층은 앞 층을 다시 재고 있을 뿐이다")
            .isNotSameAs(ConstraintMetadata.standalone)

        val targets = contractDtoClasses()
        assertThat(targets)
            .withFailMessage("계약 필드의 DTO 를 하나도 찾지 못했다 — 이 대조는 아무것도 재지 않는다")
            .isNotEmpty()

        val findings =
            targets.flatMap { ConstraintMetadata.constraintsOf(springValidator, it) } +
                targets.flatMap {
                    ConstraintMetadata.parameterConstraintsOn(springValidator, it, targets.toSet())
                } +
                CONTROLLERS.flatMap {
                    ConstraintMetadata.parameterConstraintsOn(springValidator, it, targets.toSet())
                }

        assertThat(findings.map { it.toString() })
            .withFailMessage(
                "스프링이 구성한 엔진이 계약 다섯 필드의 DTO 에서 제약을 발견했다 — F3 위반이다.\n" +
                    "  애너테이션·XML 매핑뿐 아니라 **프로그램적 ConstraintMapping** 도 이 층에서 보인다.\n%s",
                findings.joinToString("\n") { "  - $it" },
            ).isEmpty()
    }

    @Test
    @DisplayName("R-5 스프링 엔진 질의도 **실제로 제약을 본다** — 제품 코드의 파라미터 제약으로 확인한다")
    fun `스프링 엔진 질의가 제약을 지목한다`() {
        // `GET /documents` 의 `limit`·`offset` 은 계약이 요구한 스키마 층 제약이다.
        // 이 층이 그것을 본다는 확인이 없으면 위 케이스의 0건은 아무 뜻이 없다.
        val observed = ConstraintMetadata.constraintsOf(springValidator, DocumentController::class.java)

        assertThat(observed.map { it.toString() })
            .withFailMessage("스프링 엔진 질의가 DocumentController 의 파라미터 제약을 보지 못했다")
            .isNotEmpty()
    }

    /** 계약 다섯 필드가 사는 DTO 클래스 중 **실재하는 것**. 없는 것은 조용히 빠진다(핀은 슬라이스 축이 진다). */
    private fun contractDtoClasses(): List<Class<*>> =
        RequestFieldProbes
            .contractFields()
            .map { it.substringBefore('.') }
            .distinct()
            .mapNotNull { simpleName -> DTO_CLASSES.firstOrNull { it.simpleName == simpleName } }

    // ================================================================ 프로브

    private fun probes(): Map<String, (String) -> Observed> {
        // 계정을 프로브마다 새로 만들지 않는다 — 실제 Argon2 해시가 도는 축이라 비싸다.
        // 문서·작업 공간 프로브는 서로 다른 값을 보내므로 한 계정으로 충돌하지 않는다.
        val documentOwner = newAccount()
        val workspaceOwner = newAccount()
        return mapOf(
            SIGNUP_EMAIL_FIELD to { value -> signup(email = value, password = validPassword()) },
            SIGNUP_PASSWORD_FIELD to { value -> signup(email = RequestFieldProbes.uniqueEmail(), password = value) },
            TEXT_FIELD to { value ->
                postJson(DOCUMENTS_PATH, json.writeValueAsString(mapOf(TEXT_PROPERTY to value)), documentOwner)
            },
            NAME_FIELD to { value ->
                postJson(WORKSPACES_PATH, json.writeValueAsString(mapOf(NAME_PROPERTY to value)), workspaceOwner)
            },
        )
    }

    private fun newAccount(): String {
        val email = RequestFieldProbes.uniqueEmail()
        val credentials = json.writeValueAsString(mapOf("email" to email, "password" to validPassword()))
        send(post(null, SIGNUP_PATH, credentials))
        val login = send(post(null, "/auth/login", credentials))
        return json.readValue(login.body(), Map::class.java)["access_token"].toString()
    }

    private fun signup(
        email: String,
        password: String,
    ): Observed = postJson(SIGNUP_PATH, json.writeValueAsString(mapOf("email" to email, "password" to password)))

    private fun postJson(
        path: String,
        body: String,
        token: String? = null,
    ): Observed {
        val response = send(post(token, path, body))
        return Observed(response.statusCode(), detailOf(response))
    }

    private fun post(
        token: String?,
        path: String,
        body: String,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray(Charsets.UTF_8)))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))

    private fun validPassword(): String =
        RequestFieldProbes.FILLER_CHAR.repeat(ContractSpec.requestFieldConstraint(SIGNUP_PASSWORD_FIELD).limit)

    private fun detailOf(response: HttpResponse<String>): Any? {
        if (response.body().isEmpty()) return null
        return json.readValue(response.body(), Map::class.java)["detail"]
    }

    companion object {
        /**
         * 계약 다섯 필드가 사는 DTO 후보. **컴파일 시점 참조**라 이름이 바뀌면 컴파일이 먼저 깨진다.
         *
         * 문자열 목록으로 두지 않는 이유는 그것이 조용히 아무것도 겨누지 않게 되기 때문이다.
         * 아직 없는 `ConversionReviewRequest` 는 여기 없고, 그 사실의 강제자는 슬라이스 축의
         * 정확 열거 핀(`PINNED_WITHOUT_DTO`)이다.
         */
        private val DTO_CLASSES: List<Class<*>> =
            listOf(
                SignupRequest::class.java,
                DocumentTextRequest::class.java,
                WorkspaceNameRequest::class.java,
            )

        /** 그 DTO 를 파라미터로 받는 컨트롤러들. 파라미터 자리 제약 갈래를 재는 대상이다. */
        private val CONTROLLERS: List<Class<*>> =
            listOf(
                AuthController::class.java,
                DocumentController::class.java,
                WorkspaceController::class.java,
            )

        private const val SIGNUP_PATH = "/auth/signup"
        private const val DOCUMENTS_PATH = "/documents"
        private const val WORKSPACES_PATH = "/workspaces"

        private const val TEXT_PROPERTY = "text"
        private const val NAME_PROPERTY = "name"

        private const val SIGNUP_EMAIL_FIELD = "SignupRequest.email"
        private const val SIGNUP_PASSWORD_FIELD = "SignupRequest.password"
        private const val TEXT_FIELD = "DocumentTextRequest.text"
        private const val NAME_FIELD = "WorkspaceNameRequest.name"

        /** 이 테스트만 쓰는 DB. 계정·문서 행이 다른 테스트와 섞이지 않게 따로 만든다. */
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("field_rejection") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}
