package kr.easydoc.api

import kr.easydoc.api.support.ProductClasses
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * **`ProductClasses` 소스 파서가 무엇을 잡고 무엇을 놓치는지 실측한다.**
 *
 * `SensitiveToStringReachTest` 의 「소스에 선언된 타입이 전부 탐지 범위에 든다」는 소스 파서가
 * 선언을 실제로 세어야 의미가 있다. 파서가 조용히 놓치면 그 선언은 **대조 대상에서 빠지고**,
 * 빠진 상태는 초록으로 보인다 — 게이트 25 가 고친 것과 같은 형태의 공허한 통과다.
 *
 * 그래서 파서의 도달 범위를 산문이 아니라 **합성 소스**로 못박는다. 기대값에는 잡는 형태뿐
 * 아니라 **놓치는 형태**도 함께 적는다. 놓치는 쪽을 적어 두지 않으면 「어차피 다 잡겠지」로
 * 읽히고, 나중에 그 형태가 main 소스에 들어왔을 때 아무도 눈치채지 못한다.
 *
 * 여기 적힌 기대값은 **파서의 현재 능력**이지 요구가 아니다. 파서를 넓혀 놓친 형태가 잡히게
 * 되면 이 테스트가 빨개지고, 그때 KDoc 의 목록과 함께 고치면 된다 — 그것이 이 테스트의 값어치다.
 */
class SourceScanFormsProbe {
    @TempDir
    lateinit var temp: File

    @Test
    @DisplayName("파서가 잡는 선언 형태 — 중첩 사슬까지 바이너리 이름으로")
    fun `잡는 형태`() {
        assertForms(CAUGHT)
    }

    /**
     * **합성 소스가 아니라 실제 컴파일 산출물과 대조한다** (게이트 25 H-1).
     *
     * 위 [CAUGHT] 의 기대값은 손으로 적은 문자열이다. 그것만으로는 **파서가 내는 이름이
     * 컴파일러가 실제로 붙이는 이름과 같은지** 알 수 없다 — 둘이 갈리면
     * `SensitiveToStringReachTest` 의 「선언 ↔ 적재」 대조가 통째로 헛돈다.
     *
     * 그래서 **이 파일 자신**에 `fun interface` 안의 중첩 선언([NestingProbe.Spec])을 두고,
     * 파서가 이 파일을 훑어 낸 이름을 [Class.getName] 과 맞춘다. `fun` 이 [ProductClasses]
     * 의 수식어 목록에서 빠져 있으면 파서가 바깥 타입을 잃고 `kr.easydoc.api.Spec` 을 내므로
     * 여기가 빨개진다(음성 대조로 확인).
     *
     * 제품 실례는 `core/easyread/Prompts.kt` 의 `fun interface DocumentIdGenerator` 다 —
     * 오늘 그 안에 `data class` 가 없어 잠복이고, 하나 생기는 순간 도달한다.
     */
    @Test
    @DisplayName("`fun interface` 안의 중첩 이름이 컴파일러가 붙인 바이너리 이름과 같다 (H-1)")
    fun `fun interface 안의 중첩 이름이 적재와 일치한다`() {
        val declared = ProductClasses.declarationsIn(ownSourceFile()).map { it.binaryName }

        assertThat(declared)
            .withFailMessage {
                "파서가 이 파일의 `fun interface` 중첩 선언을 컴파일러와 다른 이름으로 냈다.\n" +
                    "  기대(적재): ${NestingProbe.Spec::class.java.name}\n" +
                    "  실제(파서): $declared\n" +
                    "  `ProductClasses.MODIFIERS` 에 `fun` 이 있는지 보라 — 없으면 바깥 타입을 잃는다."
            }.contains(NestingProbe.Spec::class.java.name)
    }

    @Test
    @DisplayName("파서가 놓치는 선언 형태 — 놓친다는 사실 자체를 못박는다")
    fun `놓치는 형태`() {
        assertForms(MISSED)
    }

    @Test
    @DisplayName("주석·문자열 리터럴 안의 선언은 세지 않는다 — 과잉 탐지 0")
    fun `주석과 문자열 안은 세지 않는다`() {
        assertForms(TRAPS)
    }

    @Test
    @DisplayName("소스를 읽어 낼 수 없으면 끊는다 — 조용히 0건으로 넘어가지 않는다")
    fun `읽어 낼 수 없는 소스는 끊는다`() {
        assertThatThrownBy { scan("package kr.easydoc.probe\n\nclass Broken {\n") }
            .hasMessageContaining("중괄호")

        assertThatThrownBy { scan("class NoPackage\n") }
            .hasMessageContaining("package")

        assertThatThrownBy { scan("package kr.easydoc.probe\n\nval s = \"안 닫힌 문자열\n") }
            .hasMessageContaining("닫히지 않았다")
    }

    private fun assertForms(cases: List<Form>) {
        val actual = cases.associate { it.label to scan(it.source).map { found -> found.binaryName }.sorted() }
        val expected = cases.associate { it.label to it.expected.sorted() }

        assertThat(actual)
            .withFailMessage {
                val drifted = cases.filter { actual[it.label] != expected[it.label] }
                "소스 파서의 도달 범위가 기록과 다르다:\n" +
                    drifted.joinToString("\n") {
                        "  - ${it.label}\n      기대 ${expected[it.label]}\n      실제 ${actual[it.label]}"
                    } +
                    "\n  파서를 의도적으로 넓혔다면 여기와 `ProductClasses` KDoc 「잡는 것/못 잡는 것」 을 " +
                    "함께 고쳐라. 의도한 변경이 아니라면 탐지 범위가 조용히 줄어든 것이다."
            }.isEqualTo(expected)
    }

    private fun scan(source: String) =
        ProductClasses.declarationsIn(File(temp, "Probe${source.hashCode()}.kt").apply { writeText(source) })

    /** 이 테스트 파일 자신. 파서 산출을 **같은 파일의 컴파일 결과**와 맞추기 위한 것이다. */
    private fun ownSourceFile(): File {
        val root =
            File(
                System.getProperty("easydoc.kotlin.source.root")
                    ?: error("시스템 속성 easydoc.kotlin.source.root 이 없다 — 이 파일을 찾을 기준점이 없다"),
            )
        val file = File(root, OWN_SOURCE_PATH)
        check(file.isFile) { "$file 이 없다 — 이 테스트가 옮겨졌다면 OWN_SOURCE_PATH 를 함께 고쳐라" }
        return file
    }

    private data class Form(
        val label: String,
        val source: String,
        val expected: List<String>,
    )

    private companion object {
        private const val PKG = "kr.easydoc.probe"

        /** 이 파일의 저장소 상대 경로. 클래스를 옮기면 여기가 먼저 끊긴다(조용히 통과하지 않는다). */
        private const val OWN_SOURCE_PATH = "api/src/test/kotlin/kr/easydoc/api/SourceScanFormsProbe.kt"

        private val CAUGHT =
            listOf(
                Form(
                    "맨 선언과 수식어",
                    """
                    package $PKG

                    data class Plain(val a: String)

                    internal data class Internal(val a: String)

                    private data class Private(val a: String)

                    data object Singleton
                    """.trimIndent(),
                    listOf("$PKG.Plain", "$PKG.Internal", "$PKG.Private", "$PKG.Singleton"),
                ),
                Form(
                    "애너테이션 — 앞 줄(인자 있음)과 같은 줄",
                    """
                    package $PKG

                    @JvmInline
                    value class Wrapped(val v: String)

                    @JvmInline value class SameLine(val v: String)

                    @Suppress("unused") data class Annotated(val a: String)

                    @ConfigurationProperties(prefix = "easydoc")
                    data class Bound(val a: String)
                    """.trimIndent(),
                    listOf("$PKG.Wrapped", "$PKG.SameLine", "$PKG.Annotated", "$PKG.Bound"),
                ),
                Form(
                    "중첩 — 클래스·sealed interface·object·enum class",
                    """
                    package $PKG

                    class Outer {
                        data class Inner(val a: String)
                    }

                    private sealed interface Face {
                        data class Impl(val a: String) : Face
                    }

                    object Reg {
                        data class Entry(val a: String)
                    }

                    enum class E {
                        A,
                        ;

                        data class Row(val a: String)
                    }
                    """.trimIndent(),
                    listOf(
                        "$PKG.Outer${'$'}Inner",
                        "$PKG.Face${'$'}Impl",
                        "$PKG.Reg${'$'}Entry",
                        "$PKG.E${'$'}Row",
                    ),
                ),
                Form(
                    // 게이트 25 H-1. `fun` 이 수식어 목록에 없던 종전 판은 `fun interface` 를
                    // **멤버 머리**로 읽어 바깥 타입을 잃었고, `kr.easydoc.probe.Spec` 이라는
                    // 있지도 않은 이름을 냈다. 위 「적재와 일치한다」 케이스가 같은 축을
                    // 이 파일 자신의 컴파일 결과로 확인한다.
                    "fun interface 안의 중첩 — 수식어 fun",
                    """
                    package $PKG

                    fun interface Gen {
                        fun make(spec: Spec): String

                        data class Spec(val a: String)
                    }

                    private fun interface Quiet {
                        fun go()
                    }

                    data class After(val a: String)
                    """.trimIndent(),
                    listOf("$PKG.Gen${'$'}Spec", "$PKG.After"),
                ),
                Form(
                    "companion object 는 컴파일러가 넣는 Companion 칸을 그대로 반영한다",
                    """
                    package $PKG

                    class Holder {
                        companion object {
                            data class Deep(val a: String)
                        }
                    }

                    class Named {
                        companion object Factory {
                            data class Made(val a: String)
                        }
                    }
                    """.trimIndent(),
                    listOf("$PKG.Holder${'$'}Companion${'$'}Deep", "$PKG.Named${'$'}Factory${'$'}Made"),
                ),
                Form(
                    // 파라미터가 여러 줄로 펴지면 `val` 이 줄 머리에 온다. 그것을 멤버 선언으로
                    // 세면 바깥 타입이 파라미터 목록에서 지워지고 중첩 타입이 최상위로 나온다 —
                    // `EasyDocProperties`·`Argon2Phc` 가 실제로 이 모양이었다.
                    "주 생성자 파라미터가 여러 줄이어도 바깥 타입을 잃지 않는다",
                    """
                    package $PKG

                    data class Spread(
                        val a: String,
                        val b: String = "x",
                    ) {
                        data class Nested(val c: String)
                    }
                    """.trimIndent(),
                    listOf("$PKG.Spread", "$PKG.Spread${'$'}Nested"),
                ),
                Form(
                    "주 생성자를 constructor 로 분리한 형태 — AuthDtos.kt 가 이 모양이다",
                    """
                    package $PKG

                    data class Split
                        @JsonCreator
                        constructor(val a: String) {
                            data class Kid(val b: String)
                        }
                    """.trimIndent(),
                    listOf("$PKG.Split", "$PKG.Split${'$'}Kid"),
                ),
                Form(
                    "본문 없는 선언이 뒤따르는 함수 몸통을 자기 본문으로 삼지 않는다",
                    """
                    package $PKG

                    data class Head(val a: String)

                    class Later {
                        fun go() {
                            report("x")
                        }

                        data class Tail(val b: String)
                    }
                    """.trimIndent(),
                    listOf("$PKG.Head", "$PKG.Later${'$'}Tail"),
                ),
            )

        private val MISSED =
            listOf(
                Form(
                    "애너테이션 인자가 여러 줄 — 못 잡는다 ⑴",
                    """
                    package $PKG

                    @Suppress(
                        "unused",
                    ) data class MultiLineAnn(val a: String)
                    """.trimIndent(),
                    emptyList(),
                ),
                Form(
                    "줄 머리가 아닌 선언 — 못 잡는다 ⑵",
                    """
                    package $PKG

                    val x = 1; data class Semi(val a: String)
                    """.trimIndent(),
                    emptyList(),
                ),
                Form(
                    "use-site target 이 붙은 같은 줄 애너테이션 — 못 잡는다 ⑶",
                    """
                    package $PKG

                    @field:Suppress("x") data class Targeted(val a: String)
                    """.trimIndent(),
                    emptyList(),
                ),
                Form(
                    // 실제 바이너리 이름은 `ProbeKt$make$Local` 이라 적재 집합에 없고,
                    // 그래서 대조가 「미발견」으로 빨개진다 — 조용히 빠지지는 않는다.
                    "함수 본문 안의 지역 선언 — 이름을 틀리게 낸다 ⑷",
                    """
                    package $PKG

                    fun make() {
                        data class Local(val a: String)
                    }
                    """.trimIndent(),
                    listOf("$PKG.Local"),
                ),
            )

        private val TRAPS =
            listOf(
                Form(
                    "주석과 문자열 리터럴 안의 선언",
                    "package $PKG\n" +
                        "// data class LineGhost(val a: String)\n" +
                        "/* data class BlockGhost(val a: String) { */\n" +
                        "/** data class DocGhost(val a: String) */\n" +
                        "val trap = \"data class StringGhost(val a: String) { //\"\n" +
                        "val raw = \"\"\"\n" +
                        "data class RawGhost(val a: String) {\n" +
                        "\"\"\"\n" +
                        "data class After(val a: String)\n",
                    listOf("$PKG.After"),
                ),
                Form(
                    "문자열 템플릿 안의 코드 — 중괄호도 따옴표도 깊이를 어긋내지 않는다",
                    "package $PKG\n" +
                        "val t = \"\${listOf(\"a\").joinToString { it }}\"\n" +
                        "val c = '\"'\n" +
                        "data class AfterTemplate(val a: String)\n",
                    listOf("$PKG.AfterTemplate"),
                ),
            )
    }
}

/**
 * `fun interface` 안에 중첩된 `data class` — **이 파일이 스스로 갖는 실례**다.
 *
 * 합성 소스로만 재면 「파서가 무엇을 내는가」만 알 수 있고 「그 이름이 맞는가」는 모른다.
 * 이 선언이 있어야 파서 산출을 컴파일러의 [Class.getName] 과 직접 맞출 수 있다
 * (`SourceScanFormsProbe.fun interface 안의 중첩 이름이 적재와 일치한다`).
 *
 * 제품에서 같은 형태를 쓰는 자리는 `core/easyread/Prompts.kt` 의 `DocumentIdGenerator` 다.
 */
fun interface NestingProbe {
    fun make(spec: Spec): String

    /** 중첩된 `data class`. 바이너리 이름은 `kr.easydoc.api.NestingProbe$Spec` 이다. */
    data class Spec(val label: String)
}
