package kr.easydoc.api

import kr.easydoc.api.auth.AuthenticatedEndpoints
import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.ServedOperations
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/** 인증이 걸리는 경로 목록이 계약과 같은지 대조한다. */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class AuthenticationCoverageContractTest {
    @Autowired
    private lateinit var handlerMapping: RequestMappingHandlerMapping

    @Autowired
    private lateinit var environment: Environment

    @Test
    @DisplayName("서비스 중인 모든 (경로, 메서드) 오퍼레이션이 계약의 공개·보호 둘 중 하나로 분류된다")
    fun `모든 매핑이 계약으로 분류된다`() {
        val mapped = servedOperations()
        assertThat(mapped)
            .withFailMessage("프로덕션 매핑을 하나도 발견하지 못했다 — 이 테스트가 아무것도 검사하지 않는다")
            .isNotEmpty()

        val classified =
            mapped intersect (contractOperationsBySecurity(true) + contractOperationsBySecurity(false))

        assertThat(classified)
            .withFailMessage(
                "계약이 선언하지 않은 오퍼레이션을 서비스하고 있다: %s — 보호인지 공개인지 판정할 근거가 없다",
                labelled(mapped - classified),
            ).isEqualTo(mapped)
    }

    @Test
    @DisplayName("보호로 분류된 오퍼레이션이 전부 인증 목록에 있고, 목록에 계약 밖·공개 경로가 없다")
    fun `보호 목록이 계약과 같다`() {
        val declared = AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS.toSet()
        val contractProtected = contractOperationsBySecurity(requiresAuth = true)
        val mapped = servedOperations()

        assertThat(declared)
            .withFailMessage(
                "계약이 공개(security: [])로 선언한 경로를 잠갔다: %s",
                declared intersect pathsOf(contractOperationsBySecurity(requiresAuth = false)),
            ).isSubsetOf(pathsOf(contractProtected))

        val servedProtected = pathsOf(mapped intersect contractProtected)
        assertThat(declared)
            .withFailMessage(
                "계약이 보호로 선언했는데 인증이 걸리지 않은 경로: %s",
                servedProtected - declared,
            ).containsAll(servedProtected)
    }

    @Test
    @DisplayName("한 경로의 모든 오퍼레이션이 같은 security 를 선언한다 — 경로 단위 보호의 전제")
    fun `경로 단위 보호가 성립한다`() {
        val mixed =
            ContractSpec
                .operations()
                .groupBy({ it.first }, { requiresAuth(it.first, it.second) })
                .filterValues { it.distinct().size > 1 }
                .keys

        assertThat(mixed)
            .withFailMessage("한 경로 안에서 인증 요구가 갈린다 — 경로 패턴으로 거는 방식을 바꿔야 한다: %s", mixed)
            .isEmpty()
    }

    @Test
    @DisplayName("아직 구현되지 않은 보호 경로가 목록으로 드러난다")
    fun `남은 보호 경로가 드러난다`() {
        val remaining =
            (pathsOf(contractOperationsBySecurity(requiresAuth = true)) - pathsOf(servedOperations())).sorted()

        println("인증 대상인데 아직 구현되지 않은 경로: ${remaining.ifEmpty { listOf("없음") }}")
        assertThat(remaining).doesNotContain(ME_PATH)
    }

    @Test
    @DisplayName("서블릿 오류 디스패치 경로만 제외되며, 그 값은 설정에서 온다")
    fun `제외 경로가 설정에서 온다`() {
        val errorPath = servletErrorPath()

        assertThat(errorPath).isNotBlank()
        assertThat(ContractSpec.operations().map { it.first })
            .withFailMessage("계약이 %s 를 API 경로로 선언했다 — 더는 제외 대상이 아니다", errorPath)
            .doesNotContain(errorPath)
    }

    /** 실제로 서비스되는 프로덕션 오퍼레이션 — `(경로 패턴, 소문자 HTTP 메서드)`. */
    private fun servedOperations(): Set<Pair<String, String>> = ServedOperations.of(handlerMapping, environment)

    private fun servletErrorPath(): String = environment.getProperty("server.error.path") ?: DEFAULT_ERROR_PATH

    private fun contractOperationsBySecurity(requiresAuth: Boolean): Set<Pair<String, String>> =
        ContractSpec
            .operations()
            .filter { (path, method) -> requiresAuth(path, method) == requiresAuth }
            .toSet()

    private fun pathsOf(operations: Set<Pair<String, String>>): Set<String> = operations.map { it.first }.toSet()

    /** 실패 메시지용 표기. `POST /health` 처럼 읽혀야 어느 오퍼레이션인지 바로 보인다. */
    private fun labelled(operations: Set<Pair<String, String>>): List<String> =
        operations.map { (path, method) -> "${method.uppercase()} $path" }.sorted()

    /** `security: []` 는 인증 불필요다. 선언 자체가 없으면 계약의 결함이므로 [ContractSpec] 이 실패시킨다. */
    private fun requiresAuth(
        path: String,
        method: String,
    ): Boolean = ContractSpec.security(path, method).isNotEmpty()

    private companion object {
        const val ME_PATH = "/auth/me"

        /** `server.error.path` 가 비었을 때 서블릿 컨테이너가 쓰는 값. */
        const val DEFAULT_ERROR_PATH = "/error"
    }
}
