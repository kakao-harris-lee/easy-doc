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
 * 주석·KDoc·설정 파일이 이름으로 지목한 것이 저장소에 실재하는지 잰다 —
 * 게이트 28 리더 판정 P-9(L-③ 판정 1 의 재개봉 조건이 발동해 종류째 승격됐다).
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
        val declared = ContractSpec.keyChains()
        val dangling = Scanner.danglingExtensionNodes(references, declared)

        assertThat(dangling)
            .withFailMessage { danglingNodeFailure(dangling) }
            .isEmpty()
    }

    @Test
    @DisplayName("**빈 분모는 통과가 아니다** — 두 축의 후보를 하나도 못 찾으면 빨강이다")
    fun `빈 분모는 통과가 아니다`() {
        val references = Scanner.scan(sourceRoot())

        assertThat(references.count { Scanner.isTestName(it.name) })
            .describedAs("축 A 후보(테스트·프로브 이름 지목)")
            .isPositive()
        assertThat(references.count { Scanner.isExtensionNode(it.name) })
            .describedAs("축 B 후보(계약 확장 노드 지목)")
            .isPositive()

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
    @DisplayName("축 B 는 **경로**를 묻는다 — 부모·자식이 각자 실재해도 그 부모 아래가 아니면 짚는다 (R-10-①)")
    fun `축 B 가 조각이 아니라 경로를 해소한다`() {
        val fake = fakeExtensionPath()
        val references = probe("axis-b-path", "/** 근거는 계약 `$fake` 다. */\nval x = 1\n")

        val names = ContractSpec.extensionNodeNames()
        fake.split('.').forEach { segment ->
            assertThat(names)
                .describedAs("가짜 경로의 조각 %s 가 계약에 없다 — 이 케이스가 경로 축을 재지 못한다", segment)
                .contains(segment)
        }
        assertThat(ContractSpec.keyChains())
            .describedAs("가짜 경로가 실제로 계약에 있으면 이 케이스가 성립하지 않는다")
            .doesNotContain(fake)

        assertThat(Scanner.danglingExtensionNodes(references, ContractSpec.keyChains()).map { it.chain })
            .contains(fake)
    }

    @Test
    @DisplayName("**점 참조 분모가 0이면 통과가 아니다** — 경로 해소가 실제로 도는 자리가 있어야 한다")
    fun `점으로 이어진 참조가 실재한다`() {
        val dotted = Scanner.scan(sourceRoot()).filter { Scanner.isExtensionNode(it.name) && it.hasTail }

        assertThat(dotted)
            .describedAs("점으로 이어진 계약 확장 노드 참조 — 경로 해소가 도는 분모다")
            .isNotEmpty()
    }

    @Test
    @DisplayName("멤버가 붙은 참조도 **머리를 검사한다** — 멤버를 안 보는 것이 머리를 안 보는 것은 아니다")
    fun `멤버가 붙은 참조도 머리를 검사한다`() {
        val absent = syntheticTestName()
        val references = probe("axis-a-member", "/** 사유는 `$absent.someMember` 에 있다. */\nval x = 1\n")

        assertThat(Scanner.danglingTestNames(references, Scanner.declaredNames(sourceRoot())).map { it.name })
            .containsExactly(absent)
    }

    @Test
    @DisplayName("배열 첨자는 키 층이 아니다 — `fields[0].limit` 형태를 오탐하지 않는다")
    fun `배열 첨자가 붙은 경로를 오탐하지 않는다`() {
        val chain =
            ContractSpec.keyChains().first { candidate ->
                candidate.startsWith(EXTENSION_PREFIX) && candidate.count { it == '.' } == 1
            }
        val (head, tail) = chain.split('.').let { it[0] to it[1] }
        val references = probe("axis-b-index", "/** 계약 `$head[0].$tail` 참고. */\nval x = 1\n")

        assertThat(Scanner.danglingExtensionNodes(references, ContractSpec.keyChains())).isEmpty()
    }

    @Test
    @DisplayName("음성 대조 — 실재하지 않는 계약 확장 노드를 심으면 축 B 가 짚고, 실재하는 노드는 통과시킨다")
    fun `축 B 가 실재 여부를 가른다`() {
        val absent = syntheticExtensionNode()
        val present = ContractSpec.extensionNodeNames().first()
        val references = probe("axis-b", "/** 계약 `$absent` · `$present` 참고. */\nval x = 1\n")

        assertThat(Scanner.danglingExtensionNodes(references, ContractSpec.keyChains()).map { it.name })
            .containsExactly(absent)
    }

    @Test
    @DisplayName("두 축이 **서로의 후보를 삼키지 않는다** — 한 축의 변이가 다른 축을 빨갛게 만들면 분리 판정이 무너진다")
    fun `두 축이 서로 독립이다`() {
        val references = probe("both", "/** `${syntheticTestName()}` · `${syntheticExtensionNode()}` */\nval x = 1\n")

        val axisA = Scanner.danglingTestNames(references, Scanner.declaredNames(sourceRoot()))
        val axisB = Scanner.danglingExtensionNodes(references, ContractSpec.keyChains())

        assertThat(axisA).hasSize(1)
        assertThat(axisB).hasSize(1)
        assertThat(axisA.map { it.name }).doesNotContainAnyElementsOf(axisB.map { it.name })
    }

    @Test
    @DisplayName("해소 집합에 **파일 이름**이 든다 — 파일이 클래스 둘을 담는 자리의 관용을 오탐하지 않는다")
    fun `파일 이름도 해소한다`() {
        val declared = Scanner.declaredNames(sourceRoot())

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

    /** 저장소에 없는 테스트 이름을 만든다. */
    private fun syntheticTestName(): String {
        val name = "Absent" + "Named" + "Reference" + TEST_SUFFIXES.first()
        check(name !in Scanner.declaredNames(sourceRoot())) { "합성 이름이 실재한다: $name" }
        return name
    }

    /** 조각은 전부 실재하지만 그 경로로는 없는 확장 노드 경로를 계약에서 찾아낸다. */
    private fun fakeExtensionPath(): String {
        val names = ContractSpec.extensionNodeNames().sorted()
        val chains = ContractSpec.keyChains()
        return names
            .asSequence()
            .flatMap { parent -> names.asSequence().map { child -> "$parent.$child" } }
            .firstOrNull { it.split('.').let { parts -> parts[0] != parts[1] } && it !in chains }
            ?: error("조각은 실재하나 경로로는 없는 조합을 찾지 못했다 — 이 케이스가 경로 축을 재지 못한다")
    }

    /** 계약에 없는 확장 노드 이름. 조립하는 이유는 [syntheticTestName] 과 같다. */
    private fun syntheticExtensionNode(): String {
        val name = EXTENSION_PREFIX + "absent-" + "named-reference"
        check(name !in ContractSpec.extensionNodeNames()) { "합성 노드 이름이 실재한다: $name" }
        return name
    }

    /** 합성 Kotlin 소스 하나를 스캐너에 먹인다. */
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

    /** 아무 소스도 없는 루트. 「분모 0」을 합성 인자가 아니라 실제 훑기로 만든다. */
    private fun emptyRoot(): Path {
        File(temp, "empty/src/main/kotlin").mkdirs()
        return File(temp, "empty").toPath()
    }

    private fun danglingNameFailure(dangling: List<Scanner.NamedReference>): String =
        "주석·KDoc·설정이 이름으로 지목한 테스트·프로브가 저장소에 없다.\n" +
            dangling.joinToString("\n") { "  - ${it.name}  ← ${it.file}" } +
            "\n  그 문장은 「이 자리가 지켜진다」는 잘못된 근거다 — 읽는 사람은 믿고 넘어가는데\n" +
            "  재는 것이 없다. 처분은 둘 중 하나다: ⑴ 실제로 재는 장치의 이름으로 고친다,\n" +
            "  ⑵ 재는 장치가 없으면 그 주장을 지우고 무엇이 미측정인지 적는다.\n" +
            "  이름을 이 파일의 예외 목록에 넣는 길은 없다 — 그것이 은폐형이다."

    /** 축 B 실패 메시지. 머리가 아니라 경로를 찍는다. */
    private fun danglingNodeFailure(dangling: List<Scanner.NamedReference>): String =
        "주석·KDoc·설정이 이름으로 지목한 계약 확장 노드 **경로**가 계약 파일에 없다.\n" +
            dangling.joinToString("\n") { "  - ${it.chain}  ← ${it.file}" } +
            "\n  둘 중 하나다: ⑴ 계약이 노드 이름을 바꾸거나 지웠는데 인용만 남았다(실측 선례:\n" +
            "  계약이 한 노드를 더 구체적인 이름으로 갈았고 옛 이름을 든 주석이 남았다), 또는\n" +
            "  ⑵ **자식이 다른 부모 아래로 옮겨 갔다** — 조각은 둘 다 실재하지만 그 경로로는 없다.\n" +
            "  ⑵ 가 이 축이 경로를 묻는 이유다(R-10-①). 현재 계약의 경로로 고쳐라.\n" +
            "  노드가 정말 없어졌다면 그 주장 자체를 지운다."

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

    /** 주석·KDoc·설정 주석에서 이름으로 지목된 참조를 뽑는다. */
    private object Scanner {
        /** 참조 하나. 실패 메시지가 자리를 가리켜야 하므로 파일을 함께 든다. */
        data class NamedReference(
            val file: String,
            val name: String,
            val chain: String,
        ) {
            /** 머리에 꼬리가 붙어 있는가. 「경로 해소가 실제로 도는가」를 재는 재료다. */
            val hasTail: Boolean get() = chain != name
        }

        /** Kotlin 블록 주석·KDoc. `.*?` 가 아니라 최단 일치로 잡되 줄바꿈을 포함한다. */
        private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        private val LINE_COMMENT = Regex("""//[^\n]*""")
        private val YAML_COMMENT = Regex("""#[^\n]*""")

        /** 참조 형태 셋. 어느 하나만 읽으면 나머지 형태가 통째로 분모 밖이다. */
        private val REFERENCE =
            Regex("""`([^`\n]{1,160})`|\[([^\]\n]{1,160})\]|@see\s+([\w.\[\]]{1,160})""")

        /** PascalCase — 대문자로 시작하고 소문자를 하나 이상 담는다. */
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
         * 한 건도 없으면 끊는다. 「위반 0건」과 「대상 0건」은 완전히 다른 상태이고,
         * 후자를 초록으로 두면 이 파일은 아무것도 재지 않으면서 재는 척한다.
         */
        fun requireNonEmpty(references: List<NamedReference>) {
            check(references.isNotEmpty()) {
                "주석·KDoc·설정 주석에서 이름으로 지목된 참조를 한 건도 찾지 못했다 — 검사 대상 0건은 " +
                    "통과가 아니라 실패다. 스캐너가 소스를 못 읽었거나(경로·확장자) 분모가 통째로 사라졌다."
            }
        }

        /** 저장소가 선언한 이름 — 클래스·객체·인터페이스 ∪ 파일 이름. */
        fun declaredNames(root: Path): Set<String> {
            val names = mutableSetOf<String>()
            kotlinFiles(root).forEach { file ->
                names += file.fileName.toString().removeSuffix(".$KOTLIN_EXTENSION")
                names += DECLARATION.findAll(file.readText()).map { it.groupValues[1] }
            }
            return names
        }

        /** 축 A — 머리 조각만 본다. `CanaryProbe.report` 에서 묻는 것은 `CanaryProbe` 다. */
        fun danglingTestNames(
            references: List<NamedReference>,
            declared: Set<String>,
        ): List<NamedReference> = references.filter { isTestName(it.name) && it.name !in declared }

        /** 축 B — 경로로 해소한다. [declared] 는 [ContractSpec.keyChains] 다. */
        fun danglingExtensionNodes(
            references: List<NamedReference>,
            declared: Set<String>,
        ): List<NamedReference> = references.filter { isExtensionNode(it.name) && it.chain !in declared }

        fun isTestName(name: String): Boolean =
            PASCAL.matches(name) && TEST_SUFFIXES.any { suffix -> name.length > suffix.length && name.endsWith(suffix) }

        fun isExtensionNode(name: String): Boolean = EXTENSION.matches(name)

        /** 그 파일에서 주장이 사는 부분만 남긴다. */
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
                    raw.substringBefore('(').split(' ', '/').flatMap { token -> referencesInToken(file, token) }
                }.toList()

        /** 점으로 이어진 한 토큰에서 참조를 뽑는다 — 머리와 꼬리를 함께 든다. */
        private fun referencesInToken(
            file: String,
            token: String,
        ): List<NamedReference> {
            val segments =
                token
                    .split('.')
                    .map { it.trim('`', '[', ']', '*', ',', ':', ';', '?', '!', ')').substringBefore('[') }
                    .filter { it.isNotEmpty() }
            return segments.indices.mapNotNull { index ->
                val head = segments[index]
                if (!isTestName(head) && !isExtensionNode(head)) {
                    null
                } else {
                    NamedReference(file, head, segments.subList(index, segments.size).joinToString("."))
                }
            }
        }

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

        /** 축 A 의 후보 접미사 — 이 저장소의 테스트 클래스 명명 관용이다. */
        val TEST_SUFFIXES = listOf("Test", "Probe")

        /** 축 B 의 접두. 리터럴로 적으면 이 파일이 자기 분모를 오염시키므로 상수로 접는다. */
        const val EXTENSION_PREFIX = "x-"

        /** 파일 하나가 클래스 둘을 담고 KDoc 이 파일 이름으로 그것을 가리키는 실제 자리. */
        const val STARTUP_FILE_NAME = "ApiStartupWithDatabaseTest"
    }
}
