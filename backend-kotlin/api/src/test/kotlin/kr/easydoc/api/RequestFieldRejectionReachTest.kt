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
 * F3 의 두 번째 강제자 (컨테이너 관측) — 같은 판정([RequestFieldProbes])을 실제 소켓으로
 * 잰다. 재는 것은 「서버가 실제로 내보낸 바이트」다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$DOCUMENT_REACH_TEST_SECRET"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestFieldRejectionReachTest {
    @LocalServerPort
    private var port: Int = 0

    /** 스프링이 구성한 검증기. [ConstraintMetadata.standalone] 과 다른 인스턴스다. */
    @Autowired
    private lateinit var springValidator: Validator

    private val json = ObjectMapper()

    @Test
    @DisplayName("실제 소켓으로도 길이·정규화·문구 갈래가 서비스 층에서 판정된다 — 앞단 장치가 만든 응답까지 본다 (F3)")
    fun `나간 바이트로 재도 스키마 층 거절이 없다`() {
        val probes = probes()

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

    /** 이 축의 판정을 [RequestFieldProbes] 밖에서 한 번 더 한다. */
    @Test
    @DisplayName("β-22 계약을 직접 읽는 **독립 판정**으로도 경계·방향·문구가 맞다 — 공유 판정 함수를 지나지 않는다")
    fun `계약 직독 판정이 같은 결론을 낸다`() {
        val probes = probes()
        assertThat(probes.keys)
            .withFailMessage("프로브가 하나도 없다 — 이 독립 축은 아무것도 재지 않는다")
            .isNotEmpty()

        val complaints = mutableListOf<String>()
        probes.forEach { (field, probe) ->
            val constraint = ContractSpec.requestFieldConstraint(field)
            val violating = if (constraint.upperBound) constraint.limit + 1 else constraint.limit - 1
            val compliant = if (constraint.upperBound) constraint.limit - 1 else constraint.limit + 1
            val declared = RequestFieldProbes.declaredDetails(field)

            listOf("경계" to constraint.limit, "준수 쪽" to compliant).forEach { (label, length) ->
                val observed = probe(RequestFieldProbes.valueOf(field, length))
                if (observed.status !in ACCEPTED_RANGE) {
                    complaints += "$field $label(길이 $length) 이 거절됐다: ${observed.status} ${observed.detail}"
                }
            }

            val rejected = probe(RequestFieldProbes.valueOf(field, violating))
            if (rejected.status != RequestFieldProbes.UNPROCESSABLE) {
                complaints += "$field 위반 쪽(길이 $violating) 이 ${rejected.status} 다 — 422 여야 한다"
            }
            val detail = rejected.detail
            if (detail !is String) {
                complaints += "$field 위반 쪽 detail 이 문자열이 아니다: $detail (스키마 층이 판정했다)"
            } else if (detail !in declared) {
                complaints += "$field 위반 쪽 문구가 계약 선언 밖이다: \"$detail\" (선언: $declared)"
            }
        }

        assertThat(complaints)
            .withFailMessage(
                "계약을 직접 읽은 판정이 관측과 어긋났다 — 공유 판정 함수(`RequestFieldProbes.measure`)가 " +
                    "초록이어도 이 축은 독립으로 판정한다.\n%s",
                complaints.joinToString("\n") { "  - $it" },
            ).isEmpty()
    }

    @Test
    @DisplayName("R-5 스프링이 구성한 엔진도 계약 다섯 필드의 DTO 에서 제약을 0 개 본다 — 프로그램적 매핑까지 덮는 층이다")
    fun `스프링 엔진 메타데이터에 DTO 제약이 없다`() {
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
        val observed = ConstraintMetadata.constraintsOf(springValidator, DocumentController::class.java)

        assertThat(observed.map { it.toString() })
            .withFailMessage("스프링 엔진 질의가 DocumentController 의 파라미터 제약을 보지 못했다")
            .isNotEmpty()
    }

    /** 계약 다섯 필드가 사는 DTO 클래스 중 실재하는 것. 없는 것은 조용히 빠진다(핀은 슬라이스 축이 진다). */
    private fun contractDtoClasses(): List<Class<*>> =
        RequestFieldProbes
            .contractFields()
            .map { it.substringBefore('.') }
            .distinct()
            .mapNotNull { simpleName -> DTO_CLASSES.firstOrNull { it.simpleName == simpleName } }

    private fun probes(): Map<String, (String) -> Observed> {
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
        // 이메일 인증 게이트는 `POST /documents` 앞이다 — 이 파일은 그 게이트를 재지 않으므로
        // 실물 인증 흐름 대신 저장소를 직접 인증 완료로 만든다.
        database.execute("UPDATE users SET email_verified_at = now() WHERE email = '$email'")
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
        /** 2xx. 독립 oracle 이 「통과」를 판정하는 창이다. */
        private val ACCEPTED_RANGE = 200..299

        /** 계약 다섯 필드가 사는 DTO 후보. 컴파일 시점 참조라 이름이 바뀌면 컴파일이 먼저 깨진다. */
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
