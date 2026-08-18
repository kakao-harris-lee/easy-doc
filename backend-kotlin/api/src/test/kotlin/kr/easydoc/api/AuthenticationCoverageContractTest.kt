package kr.easydoc.api

import kr.easydoc.api.auth.AuthenticatedEndpoints
import kr.easydoc.api.support.ContractSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

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
 * ## 두 방향을 모두 본다
 *
 * - **계약은 보호라는데 목록에 없다** → 인증 없이 도는 엔드포인트가 생긴다.
 * - **계약은 공개라는데 목록에 있다** → 공개 화면이 통째로 401 이 된다. 계약 위반이면서
 *   사용자에게 즉시 보이는 고장이라 조용하지는 않지만, 여기서 먼저 잡는 편이 싸다.
 *
 * ## 아직 구현되지 않은 경로
 *
 * 계약의 14개 중 이번 작업 단위가 만든 것은 셋뿐이다. 그래서 대조는 **구현된 경로에
 * 한정**하되, 무엇이 남았는지를 결과에 드러낸다 — 남은 목록이 조용히 사라지면 "이미
 * 덮였다"로 읽히기 때문이다.
 */
class AuthenticationCoverageContractTest {
    @Test
    @DisplayName("보호 경로 목록이 계약의 security 선언과 정확히 같다 (구현된 경로 범위)")
    fun `보호 목록이 계약과 같다`() {
        val declared = AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS.toSet()
        val contractProtected = contractPathsBySecurity(requiresAuth = true)
        val contractPublic = contractPathsBySecurity(requiresAuth = false)

        // ① 목록에 있는 것은 전부 계약이 보호라고 선언한 경로여야 한다.
        assertThat(declared)
            .withFailMessage(
                "계약이 공개(security: [])로 선언한 경로를 잠갔다: %s",
                declared intersect contractPublic,
            ).isSubsetOf(contractProtected)

        // ② 구현된 경로 중 보호 대상은 전부 목록에 있어야 한다.
        val implementedProtected = contractProtected intersect implementedPaths()
        assertThat(declared)
            .withFailMessage(
                "계약이 보호로 선언했는데 인증이 걸리지 않은 경로: %s",
                implementedProtected - declared,
            ).containsAll(implementedProtected)
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
        val remaining = (contractPathsBySecurity(requiresAuth = true) - implementedPaths()).sorted()

        // 단언이 아니라 기록이다. 각 작업 단위가 자기 경로를 구현하는 커밋에서
        // AuthenticatedEndpoints 에 더하면 이 목록이 줄어든다.
        println("인증 대상인데 아직 구현되지 않은 경로: ${remaining.ifEmpty { listOf("없음") }}")
        assertThat(remaining).doesNotContain(ME_PATH)
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

    /**
     * 이번 시점에 Kotlin 이 실제로 서비스하는 경로.
     *
     * 손으로 적지만 **계약 대조의 기준이 아니라 대상 범위**다 — 여기 빠뜨리면 위 단언이
     * 느슨해지는 것이 아니라 세 번째 테스트의 「남은 경로」 목록에 그대로 나타난다.
     */
    private fun implementedPaths(): Set<String> = setOf("/auth/signup", "/auth/login", ME_PATH, "/health")

    private companion object {
        const val ME_PATH = "/auth/me"
    }
}
