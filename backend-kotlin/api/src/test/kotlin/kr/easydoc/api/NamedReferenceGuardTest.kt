package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * **주석·KDoc·설정 파일이 이름으로 지목한 것이 저장소에 실재하는지 잰다** —
 * 게이트 28 리더 판정 **P-9**(L-③ 판정 1 의 재개봉 조건이 발동해 종류째 승격됐다).
 *
 * ## 무엇이 결함인가
 *
 * *"이것은 X 가 강제한다"* 는 문장에서 X 가 저장소에 없으면, 그 문장은 **잘못된 근거**다.
 * 읽는 사람은 그 자리가 지켜지고 있다고 믿고 넘어가는데 재는 것이 아무것도 없다.
 * 「전부 `ownerId` 를 받는다」 같은 거짓 전칭과 같은 형태이며(둘 다 저장소에 없는 것을
 * 근거로 든다), L-③ 판정 1 이 *"같은 형태가 다른 파일에서 한 번 더 나오면 종류째
 * 승격한다"* 는 조건을 걸어 두었다. **다섯 자리·세 커밋**이 실측되어 조건이 발동했다.
 *
 * ## 이름을 열거하지 않는다 — **참조 형태**에서 뽑는다
 *
 * 감시 대상 이름을 상수로 적으면 새 거짓 지목이 그 목록 밖에서 조용히 태어난다(형제 장치
 * `kr.easydoc.infrastructure.db.OwnershipPredicateGuardTest` KDoc 의 「핀은 면제 목록이
 * 아니라 인구조사다」와 같은 규율). 여기서 뽑는 것은 **형태**다 — 백틱 인용, `[대괄호]`
 * KDoc 링크, `@see`. 새 파일·새 주석이 자동으로 분모에 든다.
 *
 * 분모는 **주장이 사는 자리**다: `.kt` 의 주석·KDoc 과 `.yml` 의 주석 줄. 코드 본문은 보지
 * 않는다 — 거기서 이름이 틀리면 컴파일러가 먼저 잡고, 그것이 이 결함이 주석에서만 살아
 * 남는 이유다.
 *
 * ## 두 축 — 둘 다 **모양으로** 정의한다
 *
 * | 축 | 후보 모양 | 해소 집합 |
 * |---|---|---|
 * | **A** 테스트·프로브 지목 | [TEST_SUFFIXES] 로 끝나는 PascalCase | 저장소 Kotlin 선언 ∪ 파일 이름 |
 * | **B** 계약 확장 노드 지목 | `x-` + 소문자·숫자·붙임표 | [ContractSpec.extensionNodeNames] 전수 |
 *
 * 축 A 의 해소 집합에 **파일 이름**을 넣는 이유: `[…Test]` 가 파일을 가리키는 관용이 이
 * 저장소에 있다(한 파일이 클래스 둘을 담고 파일 이름이 그 묶음의 이름인 자리 — 기동 테스트).
 * 읽는 사람이 그 이름으로 파일을 찾을 수 있으면 포인터는 죽지 않았다.
 *
 * ## 왜 범위를 이만큼으로 좁혔나 — **실측이 근거다**
 *
 * 후보를 「참조 형태 안의 모든 PascalCase」로 넓히면 미해결이 **147개**가 되고 그중 실제
 * 결함은 **3개**다. 나머지 144는 전부 정당한 참조다 — 외부 라이브러리 타입, 계약 스키마·
 * 컴포넌트 이름, Python 원본 심볼 이름, detekt 규칙 id, HTTP 헤더 이름, Kotlin/JDK 기본형,
 * 백틱에 든 산문 조각. 오탐 98% 인 탐지기는 곧 **면제 목록**을 낳고, 그것이 규칙 4 ⑵ 가
 * 금지한 은폐형이다. 그래서 **목록을 좁히지 않고 모양을 좁혔다.**
 *
 * ## 이 장치가 **막지 못하는 것** (정직하게 적는다 — 적지 않으면 이것이 다음 거짓 전칭이 된다)
 *
 * - **계약 산문의 인용.** *"계약이 …라고 적었다"* 며 문장을 따온 자리가 계약에 실제로
 *   있는지는 재지 않는다. 측정: KDoc 인용 블록은 저장소 전체에 **9자리**이고 그중 8은
 *   자기 정의의 재기술이라 대조할 외부 앵커가 없다. 나쁜 자리 하나
 *   (`kr.easydoc.api.health.HealthController` — 폐기된 문면을 인용했다)는 게이트 28 P-8 에서
 *   손으로 걷어냈고, 그 KDoc 이 주장했던 성질은 이제 [HealthContractTest] 가 계약을 읽어
 *   잰다 — **문면이 아니라 성질**이 측정 대상이 됐다. 기계화하려면 인용에 앵커를 붙이는
 *   규약이 필요하고 그 신설은 이 단위 밖이다.
 * - **이름이 실재하지만 그 주장이 거짓인 경우.** `X 가 이것을 강제한다` 에서 X 가 존재하되
 *   그 성질을 재지 않는 상태는 여기서 보이지 않는다. 그 축은 변이 테스트의 몫이다
 *   (개선 백로그 B-19).
 * - **SCREAMING_CASE 상수 이름**(`MIN_TEST_CLASSES` 등)과 **함수·프로퍼티 이름**. 후보
 *   모양에서 뺐다 — 넓히면 위 147 의 잡음으로 되돌아간다.
 * - **`docs` 디렉터리의 산문.** 분모는 `backend-kotlin` 아래뿐이다. 문서는 그 시점의 판단
 *   기록이라 과거 이름을 인용하는 것이 정상이고, 거기에 이 검사를 걸면 이력을 고치게 만든다.
 *   (경로를 별표 두 개로 적지 않는다 — Kotlin 블록 주석은 중첩하므로 슬래시 뒤에 별표 둘이
 *   오는 순간 그 자리에서 새 주석이 열려 파일이 컴파일되지 않는다. 실측으로 밟았다.)
 * - **이 파일 자신의 삭제.** 최종 방어선은 `tests/test_kotlin_gate_reach.py` 의 선언 대조다.
 *
 * ## 폐기된 이름을 **이력으로** 적을 때
 *
 * 백틱·대괄호·`@see` 는 「이름으로 지목한다」의 형태다. 그래서 *"종전 문면은 `X` 를 가리켰다"*
 * 처럼 옛 이름을 그 형태로 다시 적으면 이 가드가 정당하게 잡는다 — 읽는 사람에게는 그것도
 * 「저장소에 없는 것을 가리키는 참조」이기 때문이다(실측: 이 가드를 세운 커밋의 정정 주석
 * 하나가 그 자리에서 빨개졌다). 이력은 **참조 형태 없이** 산문으로 적거나 계약
 * `x-changelog` 를 가리켜라.
 *
 * ## 빈 분모는 통과가 아니다
 *
 * 두 축의 후보가 0건이면 **빨강**이다(규칙 4 ⑶). 형제 가드와 같은 규율이고,
 * [`빈 분모는 통과가 아니다`] 가 그것을 합성 인자와 실제 훑기 양쪽으로 확인한다.
 */
class NamedReferenceGuardTest {
    @TempDir
    lateinit var temp: File

    @Test
    @DisplayName("축 A — 주석이 이름으로 지목한 테스트·프로브가 **전부 저장소에 있다**")
    fun `지목된 테스트 이름이 전부 실재한다`() {
        val references = Scanner.scan(sourceRoot())
        val declared = Scanner.declaredNames(sourceRoot())
        val dangling = Scanner.danglingTestNames(references, declared)

        assertThat(dangling)
            .withFailMessage { danglingNameFailure(dangling) }
            .isEmpty()
    }

    @Test
    @DisplayName("축 B — 주석이 이름으로 지목한 계약 확장 노드가 **전부 계약에 있다**")
    fun `지목된 계약 확장 노드가 전부 실재한다`() {
        val references = Scanner.scan(sourceRoot())
        val declared = ContractSpec.extensionNodeNames()
        val dangling = Scanner.danglingExtensionNodes(references, declared)

        assertThat(dangling)
            .withFailMessage { danglingNodeFailure(dangling) }
            .isEmpty()
    }

    @Test
    @DisplayName("**빈 분모는 통과가 아니다** — 두 축의 후보를 하나도 못 찾으면 빨강이다")
    fun `빈 분모는 통과가 아니다`() {
        val references = Scanner.scan(sourceRoot())

        // 축별로 따로 센다. 한쪽이 0 이면 그 축은 「위반 0건」으로 초록인데 실제로는 아무것도
        // 재지 않은 것이다 — 형제 가드가 같은 규율을 쓴다.
        assertThat(references.count { Scanner.isTestName(it.name) })
            .describedAs("축 A 후보(테스트·프로브 이름 지목)")
            .isPositive()
        assertThat(references.count { Scanner.isExtensionNode(it.name) })
            .describedAs("축 B 후보(계약 확장 노드 지목)")
            .isPositive()

        // 그리고 「0건이면 실패한다」는 판정 자체를 두 방향으로 확인한다.
        assertThatThrownBy { Scanner.requireNonEmpty(emptyList()) }
            .hasMessageContaining("한 건도 찾지 못했다")
        assertThatThrownBy { Scanner.requireNonEmpty(Scanner.scan(emptyRoot())) }
            .hasMessageContaining("한 건도 찾지 못했다")
    }

    @Test
    @DisplayName("분모가 **주석·KDoc·설정 주석**이다 — 코드 본문의 같은 문자열은 세지 않는다")
    fun `코드 본문은 분모가 아니다`() {
        val name = syntheticTestName()
        val inComment = probe("in-comment", "// 이것은 `$name` 가 강제한다\nval x = 1\n")
        val inCode = probe("in-code", "val label = \"`$name`\"\n")

        assertThat(inComment.map { it.name }).contains(name)
        assertThat(inCode.map { it.name })
            .describedAs("코드 본문의 이름이 틀리면 컴파일러가 먼저 잡는다 — 이 가드의 대상이 아니다")
            .doesNotContain(name)
    }

    @Test
    @DisplayName("참조 형태 셋(백틱·대괄호·`@see`)을 **전부** 읽는다 — 하나만 읽으면 나머지 형태가 통째로 밖이다")
    fun `세 참조 형태를 모두 읽는다`() {
        val name = syntheticTestName()

        val backtick = probe("backtick", "/** 사유는 `$name` 에 있다. */\nval a = 1\n")
        val bracket = probe("bracket", "/** 사유는 [$name] 에 있다. */\nval b = 1\n")
        val see = probe("see", "/** @see $name */\nval c = 1\n")

        assertThat(backtick.map { it.name }).contains(name)
        assertThat(bracket.map { it.name }).contains(name)
        assertThat(see.map { it.name }).contains(name)
    }

    @Test
    @DisplayName("`.yml` 주석 줄도 분모다 — 설정 파일이 지목한 이름이 실측 다섯 자리 중 하나였다")
    fun `설정 파일 주석도 분모다`() {
        val name = syntheticTestName()
        val references = probeYaml("yaml", "# 두 값이 계약과 맞는지는 `$name` 가 잰다\nkey: 1\n")

        assertThat(references.map { it.name }).contains(name)
    }

    @Test
    @DisplayName("`.yml` 의 **값**은 분모가 아니다 — 주석 줄만 본다")
    fun `설정 파일 값은 분모가 아니다`() {
        val name = syntheticTestName()
        val references = probeYaml("yaml-value", "key: \"`$name`\"\n")

        assertThat(references.map { it.name }).doesNotContain(name)
    }

    @Test
    @DisplayName("음성 대조 — 실재하지 않는 테스트 이름을 심으면 축 A 가 짚고, 실재하는 이름은 통과시킨다")
    fun `축 A 가 실재 여부를 가른다`() {
        val absent = syntheticTestName()
        val present = javaClass.simpleName
        val references = probe("axis-a", "/** `$absent` 와 `$present` 가 잰다. */\nval x = 1\n")
        val declared = Scanner.declaredNames(sourceRoot())

        assertThat(Scanner.danglingTestNames(references, declared).map { it.name }).containsExactly(absent)
    }

    @Test
    @DisplayName("음성 대조 — 실재하지 않는 계약 확장 노드를 심으면 축 B 가 짚고, 실재하는 노드는 통과시킨다")
    fun `축 B 가 실재 여부를 가른다`() {
        val absent = syntheticExtensionNode()
        val present = ContractSpec.extensionNodeNames().first()
        val references = probe("axis-b", "/** 계약 `$absent` · `$present` 참고. */\nval x = 1\n")

        assertThat(Scanner.danglingExtensionNodes(references, ContractSpec.extensionNodeNames()).map { it.name })
            .containsExactly(absent)
    }

    @Test
    @DisplayName("두 축이 **서로의 후보를 삼키지 않는다** — 한 축의 변이가 다른 축을 빨갛게 만들면 분리 판정이 무너진다")
    fun `두 축이 서로 독립이다`() {
        val references = probe("both", "/** `${syntheticTestName()}` · `${syntheticExtensionNode()}` */\nval x = 1\n")

        val axisA = Scanner.danglingTestNames(references, Scanner.declaredNames(sourceRoot()))
        val axisB = Scanner.danglingExtensionNodes(references, ContractSpec.extensionNodeNames())

        assertThat(axisA).hasSize(1)
        assertThat(axisB).hasSize(1)
        assertThat(axisA.map { it.name }).doesNotContainAnyElementsOf(axisB.map { it.name })
    }

    @Test
    @DisplayName("해소 집합에 **파일 이름**이 든다 — 파일이 클래스 둘을 담는 자리의 관용을 오탐하지 않는다")
    fun `파일 이름도 해소한다`() {
        val declared = Scanner.declaredNames(sourceRoot())

        // 이 저장소의 실제 자리: 기동 테스트 두 클래스가 한 파일에 살고 KDoc 이 파일 이름으로
        // 서로를 가리킨다. 파일 이름을 해소 집합에 넣지 않으면 그 관용이 전부 오탐이 된다.
        assertThat(declared)
            .describedAs("파일 이름이 해소 집합에 없다 — 클래스 선언만 모으고 있다")
            .contains(STARTUP_FILE_NAME)
    }

    @Test
    @DisplayName("SCREAMING_CASE 와 산문 조각은 후보가 아니다 — 좁힌 범위가 실제로 좁다")
    fun `좁힌 범위가 후보를 가른다`() {
        assertThat(Scanner.isTestName("MIN_TEST_CLASSES")).isFalse()
        assertThat(Scanner.isTestName("AND")).isFalse()
        assertThat(Scanner.isTestName("Test")).isFalse()
        assertThat(Scanner.isTestName("MediaType")).isFalse()
        assertThat(Scanner.isTestName("SomethingProbe")).isTrue()
        assertThat(Scanner.isExtensionNode("x-input-limits")).isTrue()
        assertThat(Scanner.isExtensionNode("X-Content-Type-Options")).isFalse()
    }

    // ================================================================ 합성 이름

    /**
     * 저장소에 **없는** 테스트 이름을 만든다.
     *
     * 리터럴로 적지 않고 조립하는 이유는 형제 가드의 probe 와 같다 — 리터럴로 적으면
     * **이 파일 자신의 주석·문자열이 실제 스캔의 분모를 오염시킨다.** 여기서는 그 오염이
     * 곧 「이 파일이 자기 자신을 위반으로 잡는다」가 된다(실제로 그 형태를 피해 조립한다).
     *
     * 조립한 이름이 정말 저장소에 없는지도 **확인한다** — 우연히 실재하면 음성 대조가
     * 조용히 무력해진다.
     */
    private fun syntheticTestName(): String {
        val name = "Absent" + "Named" + "Reference" + TEST_SUFFIXES.first()
        check(name !in Scanner.declaredNames(sourceRoot())) { "합성 이름이 실재한다: $name" }
        return name
    }

    /** 계약에 **없는** 확장 노드 이름. 조립하는 이유는 [syntheticTestName] 과 같다. */
    private fun syntheticExtensionNode(): String {
        val name = EXTENSION_PREFIX + "absent-" + "named-reference"
        check(name !in ContractSpec.extensionNodeNames()) { "합성 노드 이름이 실재한다: $name" }
        return name
    }

    // ================================================================ probe

    /**
     * 합성 Kotlin 소스 하나를 스캐너에 먹인다.
     *
     * **probe 마다 독립 디렉터리**를 준다 — 같은 디렉터리에 쌓으면 뒤 probe 의 결과에 앞
     * probe 의 참조가 섞인다(형제 가드가 실측으로 밟은 자리다).
     */
    private fun probe(
        name: String,
        source: String,
    ): List<Scanner.NamedReference> {
        val directory = File(temp, "$name/src/main/kotlin").apply { mkdirs() }
        File(directory, "Probe.kt").writeText("package probe\n\n$source")
        return Scanner.scan(File(temp, name).toPath())
    }

    /** 합성 설정 파일. `.yml` 은 주석 줄만 분모다. */
    private fun probeYaml(
        name: String,
        source: String,
    ): List<Scanner.NamedReference> {
        val directory = File(temp, "$name/src/main/resources").apply { mkdirs() }
        File(directory, "application.yml").writeText(source)
        return Scanner.scan(File(temp, name).toPath())
    }

    /**
     * 아무 소스도 없는 루트. 「분모 0」을 합성 인자가 아니라 **실제 훑기**로 만든다.
     *
     * `src/main` 뼈대까지 만들어 둔다 — 디렉터리가 아예 없어서 0건인 것과, 훑을 자리는
     * 있는데 대상이 0건인 것은 다른 상태이고 재야 하는 것은 후자다.
     */
    private fun emptyRoot(): Path {
        File(temp, "empty/src/main/kotlin").mkdirs()
        return File(temp, "empty").toPath()
    }

    // ================================================================ 실패 메시지

    private fun danglingNameFailure(dangling: List<Scanner.NamedReference>): String =
        "주석·KDoc·설정이 이름으로 지목한 테스트·프로브가 저장소에 없다.\n" +
            dangling.joinToString("\n") { "  - ${it.name}  ← ${it.file}" } +
            "\n  그 문장은 「이 자리가 지켜진다」는 잘못된 근거다 — 읽는 사람은 믿고 넘어가는데\n" +
            "  재는 것이 없다. 처분은 둘 중 하나다: ⑴ 실제로 재는 장치의 이름으로 고친다,\n" +
            "  ⑵ 재는 장치가 없으면 그 주장을 지우고 무엇이 미측정인지 적는다.\n" +
            "  이름을 이 파일의 예외 목록에 넣는 길은 없다 — 그것이 은폐형이다."

    private fun danglingNodeFailure(dangling: List<Scanner.NamedReference>): String =
        "주석·KDoc·설정이 이름으로 지목한 계약 확장 노드가 계약 파일에 없다.\n" +
            dangling.joinToString("\n") { "  - ${it.name}  ← ${it.file}" } +
            "\n  계약이 노드 이름을 바꾸거나 지웠는데 인용만 남은 상태다(실측 선례: 계약이\n" +
            "  한 노드를 더 구체적인 이름으로 갈았고 옛 이름을 든 주석이 남았다).\n" +
            "  현재 계약의 노드 이름으로 고쳐라. 노드가 정말 없어졌다면 그 주장 자체를 지운다."

    private fun sourceRoot(): Path {
        val configured =
            System.getProperty(SOURCE_ROOT_PROPERTY)
                ?: error(
                    "시스템 프로퍼티 $SOURCE_ROOT_PROPERTY 가 없다. 이 가드는 소스 전수를 훑어야 " +
                        "의미가 있는데, 경로를 못 찾으면 0개 파일을 훑고 통과한다 — 그것은 통과가 아니라 미검사다.",
                )
        val root = Paths.get(configured)
        check(Files.isDirectory(root)) { "소스 루트가 디렉터리가 아니다: $root" }
        return root
    }

    /**
     * 주석·KDoc·설정 주석에서 **이름으로 지목된 참조**를 뽑는다.
     *
     * 파서가 아니라 훑개다 — 형제 가드와 같은 판단이고 사유도 같다(파싱 실패가 새 무성
     * 표면이 된다).
     */
    private object Scanner {
        /** 참조 하나. 실패 메시지가 자리를 가리켜야 하므로 파일을 함께 든다. */
        data class NamedReference(
            val file: String,
            val name: String,
        )

        /** Kotlin 블록 주석·KDoc. `.*?` 가 아니라 최단 일치로 잡되 줄바꿈을 포함한다. */
        private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        private val LINE_COMMENT = Regex("""//[^\n]*""")
        private val YAML_COMMENT = Regex("""#[^\n]*""")

        /** 참조 **형태** 셋. 어느 하나만 읽으면 나머지 형태가 통째로 분모 밖이다. */
        private val REFERENCE =
            Regex("""`([^`\n]{1,160})`|\[([^\]\n]{1,160})\]|@see\s+([\w.\[\]]{1,160})""")

        /**
         * PascalCase — **대문자로 시작하고 소문자를 하나 이상 담는다.**
         *
         * 소문자를 요구하는 것이 SCREAMING_CASE 와 SQL 키워드(`AND`·`ANY`)를 가르는 조건이다.
         */
        private val PASCAL = Regex("""^[A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*$""")

        /** 계약 확장 노드 이름의 모양. 대문자 헤더 이름(`X-…`)과 가른다. */
        private val EXTENSION = Regex("""^x-[a-z0-9]+(?:-[a-z0-9]+)*$""")

        /** Kotlin 선언 머리. 클래스·객체·인터페이스만 본다 — 함수·프로퍼티는 후보 모양 밖이다. */
        private val DECLARATION = Regex("""\b(?:class|object|interface)\s+([A-Za-z_]\w*)""")

        fun scan(root: Path): List<NamedReference> =
            sources(root).flatMap { file ->
                val relative = root.relativize(file).joinToString("/")
                referencesIn(relative, claimsOf(file))
            }

        /**
         * **한 건도 없으면 끊는다.** 「위반 0건」과 「대상 0건」은 완전히 다른 상태이고,
         * 후자를 초록으로 두면 이 파일은 아무것도 재지 않으면서 재는 척한다.
         */
        fun requireNonEmpty(references: List<NamedReference>) {
            check(references.isNotEmpty()) {
                "주석·KDoc·설정 주석에서 이름으로 지목된 참조를 한 건도 찾지 못했다 — 검사 대상 0건은 " +
                    "통과가 아니라 실패다. 스캐너가 소스를 못 읽었거나(경로·확장자) 분모가 통째로 사라졌다."
            }
        }

        /** 저장소가 선언한 이름 — 클래스·객체·인터페이스 ∪ **파일 이름**. */
        fun declaredNames(root: Path): Set<String> {
            val names = mutableSetOf<String>()
            kotlinFiles(root).forEach { file ->
                names += file.fileName.toString().removeSuffix(".$KOTLIN_EXTENSION")
                names += DECLARATION.findAll(file.readText()).map { it.groupValues[1] }
            }
            return names
        }

        fun danglingTestNames(
            references: List<NamedReference>,
            declared: Set<String>,
        ): List<NamedReference> = references.filter { isTestName(it.name) && it.name !in declared }

        fun danglingExtensionNodes(
            references: List<NamedReference>,
            declared: Set<String>,
        ): List<NamedReference> = references.filter { isExtensionNode(it.name) && it.name !in declared }

        fun isTestName(name: String): Boolean =
            PASCAL.matches(name) && TEST_SUFFIXES.any { suffix -> name.length > suffix.length && name.endsWith(suffix) }

        fun isExtensionNode(name: String): Boolean = EXTENSION.matches(name)

        /** 그 파일에서 **주장이 사는 부분**만 남긴다. */
        private fun claimsOf(file: Path): String {
            val text = file.readText()
            return if (file.extension == KOTLIN_EXTENSION) {
                (BLOCK_COMMENT.findAll(text) + LINE_COMMENT.findAll(text)).joinToString("\n") { it.value }
            } else {
                YAML_COMMENT.findAll(text).joinToString("\n") { it.value }
            }
        }

        private fun referencesIn(
            file: String,
            claims: String,
        ): List<NamedReference> =
            REFERENCE
                .findAll(claims)
                .flatMap { match ->
                    val raw = (1..3).firstNotNullOfOrNull { match.groupValues[it].ifEmpty { null } } ?: ""
                    // `Foo.bar` · `kr.easydoc.Foo` · `x-cors.x-note` 처럼 점으로 이어진 참조를
                    // 조각으로 나눈다. 한 조각만 보면 정본을 가리키는 긴 참조가 통째로 밖이다.
                    raw
                        .substringBefore('(')
                        .split('.', ' ', '/')
                        .map { it.trim('`', '[', ']', '*', ',', ':', ';', '?', '!', ')') }
                        .filter { candidate -> isTestName(candidate) || isExtensionNode(candidate) }
                        .map { candidate -> NamedReference(file, candidate) }
                }.toList()

        private fun sources(root: Path): List<Path> =
            walk(root) { path -> path.extension == KOTLIN_EXTENSION || path.extension in CONFIG_EXTENSIONS }

        private fun kotlinFiles(root: Path): List<Path> = walk(root) { path -> path.extension == KOTLIN_EXTENSION }

        /** Gradle 산출물은 소스가 아니다 — 넣으면 같은 파일을 두 번 센다. */
        private fun walk(
            root: Path,
            accept: (Path) -> Boolean,
        ): List<Path> =
            Files.walk(root).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && accept(it) }
                    .filter { root.relativize(it).none { part -> part.toString() == "build" } }
                    .sorted()
                    .toList()
            }

        private const val KOTLIN_EXTENSION = "kt"
        private val CONFIG_EXTENSIONS = setOf("yml", "yaml")
    }

    private companion object {
        const val SOURCE_ROOT_PROPERTY = "easydoc.kotlin.source.root"

        /**
         * 축 A 의 후보 접미사 — **이 저장소의 테스트 클래스 명명 관용**이다.
         *
         * 열거처럼 보이지만 이것은 이름 목록이 아니라 **모양**이다: 새 테스트 클래스는 이름을
         * 여기 적지 않아도 자동으로 후보에 든다. 관용이 늘면(새 접미사) 여기 한 줄이 늘고,
         * 그 diff 는 「탐지 범위를 넓혔다」로 리뷰에 올라온다.
         */
        val TEST_SUFFIXES = listOf("Test", "Probe")

        /** 축 B 의 접두. 리터럴로 적으면 이 파일이 자기 분모를 오염시키므로 상수로 접는다. */
        const val EXTENSION_PREFIX = "x-"

        /**
         * 파일 하나가 클래스 둘을 담고 KDoc 이 **파일 이름**으로 그것을 가리키는 실제 자리.
         *
         * 이름을 조립하지 않고 그대로 적는다 — 이 값은 해소 **집합에 있어야** 하는 이름이라,
         * 오염이 아니라 그 반대다(이 파일의 이 문장 자신도 축 A 후보가 되고 통과한다).
         */
        const val STARTUP_FILE_NAME = "ApiStartupWithDatabaseTest"
    }
}
