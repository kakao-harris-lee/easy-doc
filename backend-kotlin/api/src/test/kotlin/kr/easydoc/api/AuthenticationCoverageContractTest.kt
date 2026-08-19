package kr.easydoc.api

import kr.easydoc.api.auth.AuthenticatedEndpoints
import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.io.File

/**
 * **인증이 걸리는 경로 목록이 계약과 같은지 대조한다.**
 *
 * ## 왜 이 대조가 필요한가
 *
 * `AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS` 는 손으로 적는 열거식 목록이고, 이
 * 저장소는 열거식 목록을 이미 두 번 놓쳤다(사적 응답 헤더 10곳 중 4곳). 새 엔드포인트를
 * 만들면서 목록에 넣는 것을 잊으면 **인증 없이 도는 API** 가 생기는데, 그 상태는
 * 조용하다 — 응답이 정상으로 나가기 때문이다.
 *
 * 그래서 판정을 사람의 기억이 아니라 **계약**에 맡긴다. 계약은 오퍼레이션마다
 * `security` 를 선언하고, 인증이 필요 없는 셋은 `security: []` 로 명시한다.
 *
 * ## 대상 범위를 손으로 적지 않는다 (게이트 20 · codex C3 ≡ Claude T-2)
 *
 * 종전 판은 「이번 시점에 구현된 경로」를 **수기 목록**(`implementedPaths()`)으로 들고
 * 계약의 보호 집합과 교집합해서 대조했다. 그러면 **새 컨트롤러를 만들면서 두 목록을 다
 * 빠뜨리는 실제 사고 형태**에서 단언이 공허하게 통과한다 — 교집합이 그 경로를 밀어내기
 * 때문이다(리뷰 실측 B-6: 그 시나리오에서 초록).
 *
 * 지금은 대상 범위를 [RequestMappingHandlerMapping] 에서 **런타임에 발견**한다. 손 목록이
 * 없으므로 빠뜨릴 자리가 없고, 어느 목록에도 없는 새 매핑은 그 자체로 실패다.
 *
 * ## 세 방향을 본다
 *
 * - **매핑이 계약 어디에도 없다** → 계약 밖 엔드포인트가 서비스되고 있다.
 * - **계약은 보호라는데 목록에 없다** → 인증 없이 도는 엔드포인트가 생긴다.
 * - **계약은 공개라는데 목록에 있다** → 공개 화면이 통째로 401 이 된다.
 *
 * ## 왜 `@WebMvcTest` 인가
 *
 * 매핑 표는 컨텍스트가 조립돼야 존재한다. DB 는 필요 없으므로 슬라이스로 충분하고,
 * 테스트 전용 컨트롤러(`ErrorProbeController` 등)는 **컴파일 산출물 위치**로 걸러 낸다 —
 * 프로덕션 소스에서 나온 클래스만 API 표면으로 센다.
 */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class AuthenticationCoverageContractTest {
    @Autowired
    private lateinit var handlerMapping: RequestMappingHandlerMapping

    @Autowired
    private lateinit var environment: Environment

    @Test
    @DisplayName("서비스 중인 모든 매핑이 계약의 공개·보호 둘 중 하나로 분류된다 (어느 쪽에도 없으면 실패)")
    fun `모든 매핑이 계약으로 분류된다`() {
        val mapped = servedPaths()
        assertThat(mapped)
            .withFailMessage("프로덕션 매핑을 하나도 발견하지 못했다 — 이 테스트가 아무것도 검사하지 않는다")
            .isNotEmpty()

        val classified = mapped intersect (contractPathsBySecurity(true) + contractPathsBySecurity(false))

        // 「공개 목록 ∪ 보호 목록 = 전체 매핑」의 정확 일치. 계약에 없는 경로를 서비스하면
        // 그 경로는 어느 쪽 목록에도 들지 못해 여기서 드러난다.
        assertThat(classified)
            .withFailMessage(
                "계약이 선언하지 않은 경로를 서비스하고 있다: %s — 보호인지 공개인지 판정할 근거가 없다",
                mapped - classified,
            ).isEqualTo(mapped)
    }

    @Test
    @DisplayName("보호로 분류된 매핑이 전부 인증 목록에 있고, 목록에 계약 밖·공개 경로가 없다")
    fun `보호 목록이 계약과 같다`() {
        val declared = AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS.toSet()
        val contractProtected = contractPathsBySecurity(requiresAuth = true)
        val mapped = servedPaths()

        // ① 목록에 있는 것은 전부 계약이 보호라고 선언한 경로여야 한다.
        assertThat(declared)
            .withFailMessage(
                "계약이 공개(security: [])로 선언한 경로를 잠갔다: %s",
                declared intersect contractPathsBySecurity(requiresAuth = false),
            ).isSubsetOf(contractProtected)

        // ② **발견된** 매핑 중 보호 대상은 전부 목록에 있어야 한다. 대상 범위가 수기
        //    목록이 아니라 매핑 표에서 오므로, 컨트롤러만 만들고 목록을 잊은 커밋이 여기서 깨진다.
        val servedProtected = mapped intersect contractProtected
        assertThat(declared)
            .withFailMessage(
                "계약이 보호로 선언했는데 인증이 걸리지 않은 경로: %s",
                servedProtected - declared,
            ).containsAll(servedProtected)
    }

    @Test
    @DisplayName("한 경로의 모든 오퍼레이션이 같은 security 를 선언한다 — 경로 단위 보호의 전제")
    fun `경로 단위 보호가 성립한다`() {
        // 인터셉터는 **경로 패턴**으로 건다. 한 경로에서 메서드마다 보호 여부가 갈리면
        // 그 전제가 깨지고, 목록 대조가 의미를 잃는다.
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
        val remaining = (contractPathsBySecurity(requiresAuth = true) - servedPaths()).sorted()

        // 단언이 아니라 기록이다. 각 작업 단위가 자기 경로를 구현하는 커밋에서
        // AuthenticatedEndpoints 에 더하면 이 목록이 줄어든다.
        println("인증 대상인데 아직 구현되지 않은 경로: ${remaining.ifEmpty { listOf("없음") }}")
        assertThat(remaining).doesNotContain(ME_PATH)
    }

    @Test
    @DisplayName("서블릿 오류 디스패치 경로만 제외되며, 그 값은 설정에서 온다")
    fun `제외 경로가 설정에서 온다`() {
        val errorPath = servletErrorPath()

        // 제외를 손으로 적으면 그 목록이 두 번째 수기 범위가 된다. 값은 Spring 설정에서
        // 읽고, 계약이 그 경로를 API 로 선언하지 않았음을 함께 확인한다 — 선언되는 날이
        // 오면 제외 자체를 다시 판단해야 한다.
        assertThat(errorPath).isNotBlank()
        assertThat(ContractSpec.operations().map { it.first })
            .withFailMessage("계약이 %s 를 API 경로로 선언했다 — 더는 제외 대상이 아니다", errorPath)
            .doesNotContain(errorPath)
    }

    /**
     * 실제로 서비스되는 **프로덕션** 경로 패턴.
     *
     * 걸러 내는 둘 —
     * ⑴ 테스트 전용 컨트롤러: 핸들러의 선언 클래스가 `api` 모듈의 **main 컴파일 산출물**에
     *    없으면 API 표면이 아니다. 클래스 이름 규칙이 아니라 산출물 위치로 가르는 이유는,
     *    규칙은 다음 사람이 어기고 위치는 빌드가 정하기 때문이다.
     * ⑵ 서블릿 오류 디스패치 경로: 컨테이너가 내부적으로 포워딩하는 자리이지 API 가 아니다.
     *    값은 설정에서 읽는다([servletErrorPath]).
     */
    private fun servedPaths(): Set<String> {
        val errorPath = servletErrorPath()
        return handlerMapping.handlerMethods
            .filterValues { isProductionClass(it.beanType) }
            .keys
            .flatMap { it.patternValues }
            .filterNot { it == errorPath }
            .toSet()
    }

    private fun servletErrorPath(): String = environment.getProperty("server.error.path") ?: DEFAULT_ERROR_PATH

    private fun isProductionClass(type: Class<*>): Boolean {
        val root =
            System.getProperty(SOURCE_ROOT_PROPERTY)
                ?: error("시스템 속성 $SOURCE_ROOT_PROPERTY 이 없다 — 프로덕션 클래스를 가려낼 기준점이 없다")
        val classesDir = File(root, MAIN_CLASSES_DIR)
        require(classesDir.isDirectory) { "api 컴파일 산출물이 없다: $classesDir" }
        return File(classesDir, type.name.replace('.', File.separatorChar) + ".class").isFile
    }

    private fun contractPathsBySecurity(requiresAuth: Boolean): Set<String> =
        ContractSpec
            .operations()
            .filter { (path, method) -> requiresAuth(path, method) == requiresAuth }
            .map { it.first }
            .toSet()

    /** `security: []` 는 인증 불필요다. 선언 자체가 없으면 계약의 결함이므로 [ContractSpec] 이 실패시킨다. */
    private fun requiresAuth(
        path: String,
        method: String,
    ): Boolean = ContractSpec.security(path, method).isNotEmpty()

    private companion object {
        const val ME_PATH = "/auth/me"

        /** 빌드가 주입하는 Gradle 루트(= `backend-kotlin/`). */
        const val SOURCE_ROOT_PROPERTY = "easydoc.kotlin.source.root"

        const val MAIN_CLASSES_DIR = "api/build/classes/kotlin/main"

        /** `server.error.path` 가 비었을 때 서블릿 컨테이너가 쓰는 값. */
        const val DEFAULT_ERROR_PATH = "/error"
    }
}
