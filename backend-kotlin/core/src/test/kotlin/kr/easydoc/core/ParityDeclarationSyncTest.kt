package kr.easydoc.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 정본의 **성질 유무**와 Kotlin 의 **선언**이 같이 움직이는지 본다.
 *
 * ## 이 자리가 왜 비어 있었는가
 *
 * 이미 있는 두 장치는 다른 축을 본다.
 *
 * | 장치 | 보는 것 | 못 보는 것 |
 * |---|---|---|
 * | `parityManifestCheck` (Gradle) | 선언 ↔ **산출물** | 정본이 성질을 적었는지 |
 * | `.github/parity-canonical-floor.txt` | 정본에 도메인이 **있는지** | 그 도메인이 `ready` 인지 |
 * | `.github/parity-declared-floor.txt` | 선언이 **줄었는지** | 늘려야 할 때가 왔는지 |
 *
 * 남은 축이 하나다 — **정본이 `pending` → `ready` 로 바뀌었는데 아무도 선언하지 않는 경우.**
 * 그때 CI 는 조용히 초록이다. 그 도메인은 정본이 성질을 갖췄는데도 판정 범위 밖이고,
 * 로그에는 "부분 게이트 통과"만 찍힌다. 값이 갈려도 드러나지 않는다.
 *
 * ## 왜 export 한 건이 아니라 전 도메인인가
 *
 * 계기는 export 한 건이다(2026-08-14: 생산자를 다 만들고 나서야 정본이 `pending` 인 것을
 * 알았다). 그러나 빈자리의 **종류**가 재발형이다 — 정본 생성기에 도메인이 추가될 때마다
 * `pending` 으로 들어왔다가 성질이 적히면 `ready` 로 바뀌고, 그 전환을 알리는 것이 지금
 * 아무것도 없다. 남은 정본 도메인 수만큼 같은 일이 남아 있으므로 종류만큼 넓힌다.
 *
 * 넓혀도 오경보가 없다는 것을 **선언 전에 실측했다** — 오늘 기준 `ready` 7개는 선언 7개와
 * 정확히 같고 `pending` 1개(export)는 미선언이다. 즉 이 단언은 오늘 참이며, 깨지는 날은
 * 정확히 "정본이 바뀌었다"는 날이다.
 *
 * ## 방향이 둘 다 필요하다
 *
 * - `ready` 인데 미선언 → **조용한 미가동.** 위에서 설명한 그 자리다.
 * - `pending` 인데 선언 → 비교기가 종료 코드 2 를 낸다. CI 는 이미 잡지만 그때는 **CI 에서**
 *   안다. 여기서 잡으면 커밋 전에 안다.
 */
class ParityDeclarationSyncTest {
    private companion object {
        const val STATUS_READY = "ready"
        const val STATUS_PENDING = "pending"

        /** 선언 파일에서 주석과 빈 줄을 걷어낸다. `#` 뒤는 주석이다. */
        fun declaredDomains(file: File): Set<String> =
            file
                .readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .toSet()

        /**
         * 어긋난 자리를 계산한다. 파일 읽기와 갈라 둔 이유는 **음성 대조** 때문이다 —
         * 정본은 읽기 전용이라(소유자가 다르다) 실제 파일을 뒤집어 볼 수 없고, 뒤집어
         * 보지 않은 탐지기는 "오늘 참인 단언"일 뿐 탐지기라는 근거가 없다.
         */
        fun mismatches(
            status: Map<String, String>,
            declared: Set<String>,
        ): Pair<Set<String>, Set<String>> =
            (status.filterValues { it == STATUS_READY }.keys - declared) to
                declared.intersect(status.filterValues { it == STATUS_PENDING }.keys)
    }

    private val fixturesDir: File =
        File(
            requireNotNull(System.getProperty("parity.fixtures.dir")) {
                "parity.fixtures.dir 시스템 속성이 없다 — build.gradle.kts 의 Test 설정을 확인하라"
            },
        )

    private val declarationFile: File =
        File(
            requireNotNull(System.getProperty("easydoc.kotlin.source.root")) {
                "easydoc.kotlin.source.root 시스템 속성이 없다 — build.gradle.kts 의 Test 설정을 확인하라"
            },
            "parity-domains.txt",
        )

    @Test
    @DisplayName("정본이 ready 인 도메인은 전부 선언돼 있고, pending 인 도메인은 선언돼 있지 않다")
    fun `정본 상태와 선언이 어긋나지 않는다`() {
        val status = fixtureStatuses()
        val declared = declaredDomains(declarationFile)

        assertThat(status).describedAs("정본 fixture 를 하나도 못 읽었다 — 경로가 비었는지 확인하라").isNotEmpty()

        val (readyButUndeclared, declaredButPending) = mismatches(status, declared)

        assertThat(readyButUndeclared)
            .describedAs(
                """
                정본이 성질(assert)을 갖췄는데 Kotlin 이 선언하지 않은 도메인이다.
                이 상태에서는 CI 가 그 도메인을 판정 범위에서 빼고도 초록으로 끝난다.
                선언하려면 세 곳을 같은 커밋에서 고친다:
                  1. 생산자 테스트에 @Tag("parity") 를 붙인다
                  2. backend-kotlin/parity-domains.txt 에 한 줄 추가
                  3. .github/parity-declared-floor.txt 에 한 줄 추가
                반대로 Kotlin 이 아직 그 도메인을 포팅하지 않았다면, 선언 대신 정본을
                pending 으로 되돌리는 것이 맞다 — 다만 그것은 정본 소유자(parity-verifier)의 결정이다.
                """.trimIndent(),
            ).isEmpty()

        assertThat(declaredButPending)
            .describedAs(
                """
                선언했는데 정본이 pending 이다. 비교기는 이 도메인을 '미검증'으로 세고
                종료 코드 2 를 낸다 — CI 는 이것을 통과로 읽지 않는다.
                정본에 assert 를 적어 ready 로 올리거나(parity-verifier), 선언을 되돌린다.
                """.trimIndent(),
            ).isEmpty()
    }

    @Test
    @DisplayName("정본이 ready 로 바뀌었는데 선언이 그대로면 검출한다")
    fun `음성 대조 - ready 전환을 놓치지 않는다`() {
        // export 가 ready 로 바뀐 날의 상태를 그대로 만든다.
        val afterFlip = mapOf("masking" to STATUS_READY, "export" to STATUS_READY)
        val (readyButUndeclared, declaredButPending) = mismatches(afterFlip, setOf("masking"))

        assertThat(readyButUndeclared).containsExactly("export")
        assertThat(declaredButPending).isEmpty()
    }

    @Test
    @DisplayName("정본이 pending 인데 선언하면 검출한다")
    fun `음성 대조 - 성급한 선언을 놓치지 않는다`() {
        // 오늘 export 를 선언했다면 나왔을 상태. 실측한 비교기 종료 코드 2 와 같은 자리다.
        val today = mapOf("masking" to STATUS_READY, "export" to STATUS_PENDING)
        val (readyButUndeclared, declaredButPending) = mismatches(today, setOf("masking", "export"))

        assertThat(declaredButPending).containsExactly("export")
        assertThat(readyButUndeclared).isEmpty()
    }

    @Test
    @DisplayName("어긋남이 없으면 아무것도 검출하지 않는다")
    fun `음성 대조 - 오경보가 없다`() {
        val aligned = mapOf("masking" to STATUS_READY, "export" to STATUS_PENDING)
        val (readyButUndeclared, declaredButPending) = mismatches(aligned, setOf("masking"))

        assertThat(readyButUndeclared).isEmpty()
        assertThat(declaredButPending).isEmpty()
    }

    /** `parity/fixtures/{도메인}/{도메인}.json` 의 `spec_status` 를 도메인별로 읽는다. */
    private fun fixtureStatuses(): Map<String, String> =
        fixturesDir
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull { dir ->
                val json = File(dir, "${dir.name}.json").takeIf { it.isFile } ?: return@mapNotNull null
                val parsed = Json.parseToJsonElement(json.readText()) as JsonObject
                val status =
                    parsed["spec_status"]?.jsonPrimitive?.content
                        ?: error("${dir.name} 정본에 spec_status 가 없다 — 정본 형식이 바뀌었는지 확인하라")
                check(status == STATUS_READY || status == STATUS_PENDING) {
                    "${dir.name} 정본의 spec_status 가 `$status` 다 — 아는 값은 ready·pending 둘뿐이다. " +
                        "값이 늘었다면 이 검사도 같이 늘려야 한다(모르는 값을 조용히 통과시키지 않는다)"
                }
                dir.name to status
            }.toMap()
}
