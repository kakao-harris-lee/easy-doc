package kr.easydoc.api.support

import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.classreading.CachingMetadataReaderFactory
import java.io.File
import kotlin.reflect.KClass

/**
 * **제품 타입을 두 방향에서 센다** — 적재된 클래스(런타임)와 선언된 소스(디스크).
 *
 * ## 왜 두 방향인가
 *
 * 탐지기가 클래스패스만 보면 「제외한 이유」가 검사받지 않는다. 종전 판은 테스트·
 * testFixtures 산출물을 경로 표식으로 걸러 내면서 *"제품이 아니고 컨테이너 비밀번호 같은
 * 값이 섞여 있다"* 를 사유로 적었는데, **그 사유가 참인지 확인하는 장치가 없었다.**
 * 표식이 하나 어긋나거나 모듈이 클래스패스에 없으면 탐지 범위가 조용히 줄고, 줄어든 상태는
 * 「통과」로 보인다(게이트 24 privacy-gate A-3′ · Claude R-5).
 *
 * 그래서 **소스에 선언된 것을 세어 대조한다.** `모듈/src/main/kotlin` 아래에 `data class` 나
 * `value class` 로 적힌 타입은 전부 적재된 집합에 있어야 한다. 이 대조가 잡는 것은 열거가
 * 아니라 **종류**다 — 새 모듈이 생겼는데 클래스패스에 없거나(오늘 `worker` 가 그렇다),
 * 경로 표식이 제품 산출물까지 걸러 버리거나, 적재 필터가 넓어지면 전부 여기서 빨개진다.
 *
 * ## 대조 키는 **바이너리 이름**이다 — 단순 이름이 아니라
 *
 * 게이트 25 에서 고친 빈자리. 종전 판은 소스 선언 44 건을 **단순 이름**으로 적재 집합과
 * 맞췄고, 그래서 *다른 모듈에 같은 이름의 타입이 하나라도 있으면 미적재가 「발견됨」으로
 * 통과*했다. 실측: `worker` 에 `data class HealthResponse` 를 넣으면 `api` 의 동명 타입이
 * 대신 맞아 초록이 된다 — 클래스패스에 `worker` 가 통째로 없는데도.
 *
 * 그래서 소스 쪽에서 `package` 선언과 **중첩 사슬**을 읽어 바이너리 이름
 * (`kr.easydoc.…Outer$Inner`)을 만들고, 적재 쪽은 [Class.getName] 을 쓴다. 중첩 판정은
 * 중괄호를 세어서 하며(그래서 주석·문자열 리터럴을 먼저 지운다), `companion object` 는
 * 컴파일러가 넣는 `Companion` 한 칸을 그대로 반영한다.
 *
 * ## 소스 파서가 잡는 것 / 못 잡는 것
 *
 * 아래는 산문이 아니라 **실측이다** — [kr.easydoc.api.SourceScanFormsProbe] 가 같은 형태를
 * 합성 소스로 만들어 매 실행마다 확인한다. 목록을 KDoc 에만 두면 파서가 바뀌어도 산문은
 * 그대로 남고, 그때부터 이 문단이 거짓말을 시작한다.
 *
 * 잡는다 — `data class` · `value class` · `data object` · `internal`/`private` 등 [MODIFIERS] ·
 * **앞 줄**에 놓인 애너테이션(`@ConfigurationProperties(prefix = "easydoc")` 처럼 인자가 있어도) ·
 * **같은 줄** 애너테이션(`@JvmInline value class`, `@Suppress("x") data class`) ·
 * 클래스/인터페이스/`object`/`enum class`/`companion object` 안의 중첩(사슬을 그대로 이어 붙인다) ·
 * 주 생성자를 `@JsonCreator constructor(…)` 로 분리해 쓴 형태(`AuthDtos.kt`) ·
 * 주석·문자열 리터럴 **안**의 `data class` 는 잡지 않는다(과잉 탐지 0).
 *
 * 못 잡는다 —
 * ⑴ 애너테이션 인자가 **여러 줄**에 걸친 뒤 같은 줄에 선언이 오는 형태,
 * ⑵ 줄 머리가 아닌 선언(`val x = 1; data class Y` 처럼 한 줄에 둘),
 * ⑶ use-site target 이 붙은 **같은 줄** 애너테이션(`@field:Suppress("x") data class Y`),
 * ⑷ 함수 본문 안의 **지역** `data class` — 이름은 뽑아내지만 중첩 사슬을 함수 몸통까지
 *    따라가지 못해 바깥 타입만 이어 붙인다(실제 바이너리 이름은 `FileKt$f$Local`).
 *
 * ⑴~⑶ 은 **미탐지**라 조용하고(대조가 그 선언을 아예 모른다), ⑷ 는 틀린 이름을 내므로
 * 「적재 집합에 없다」로 시끄럽게 깨진다. 넷 다 오늘 main 소스에 0 건이며, 실제로 필요해지면
 * 파서를 늘리기보다 **선언 형태를 바꾸는 쪽**이 싸다.
 */
object ProductClasses {
    /** 빌드가 모든 테스트 태스크에 주입하는 Gradle 루트(= `backend-kotlin/`). */
    private const val SOURCE_ROOT_PROPERTY = "easydoc.kotlin.source.root"

    /** 제품 소스가 사는 자리. 모듈 이름을 열거하지 않는다 — 새 모듈이 저절로 들어온다. */
    private const val MAIN_SOURCES = "src/main/kotlin"

    /**
     * 테스트 런타임 클래스패스의 `kr.easydoc.**` **제품** 클래스.
     *
     * 적재는 초기화 없이 한다(`initialize = false`) — 이 탐지기가 클래스 초기화 부작용을
     * 일으킬 이유가 없다. 적재·연결 실패는 **건너뛰지 않고 끊는다**: 조용히 빼면 그 모듈이
     * 통째로 검사 밖에 남는다.
     *
     * 합성·익명·지역 클래스는 뺀다. **선언된 타입이 아니라 컴파일러 산출물**이고
     * (`$WhenMappings`·람다·`$serializer`), 소스 어디에도 `data class` 로 적혀 있지 않아
     * [declaredInMainSources] 대조가 이 제외를 확인해 준다 — 사유를 적어 두기만 한 것이
     * 아니라 검사받는 제외다.
     */
    fun onTestRuntimeClasspath(): List<KClass<*>> {
        val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)
        val metadata = CachingMetadataReaderFactory(resolver)
        return resolver
            .getResources("classpath*:kr/easydoc/**/*.class")
            .filter { resource -> TEST_OUTPUT_MARKERS.none { it in resource.url.toString() } }
            .map { resource -> load(metadata.getMetadataReader(resource).classMetadata.className) }
            .filter { !it.isSynthetic && !it.isAnonymousClass && !it.isLocalClass }
            .map { it.kotlin }
    }

    /**
     * `모듈/src/main/kotlin` 에 선언된 `data class`·`value class`.
     *
     * 소스 텍스트를 읽는 이유는 하나다 — **클래스패스에 없는 모듈도 세야 한다.** `api` 는
     * 다섯 모듈 중 넷만 런타임에 싣고 `worker` 를 싣지 않으므로, 클래스패스만 보면 `worker`
     * 에 생긴 첫 DTO 가 조용히 검사 밖에 남는다(privacy-gate 부수 실측).
     *
     * 산출물은 [SourceDeclaration.binaryName] 까지 채워진다. 단순 이름만으로 맞추면 다른
     * 모듈의 동명 타입이 대신 맞아 미적재가 통과한다(게이트 25).
     */
    fun declaredInMainSources(): List<SourceDeclaration> = sourceRoots().flatMap { root -> declarationsUnder(root) }

    /**
     * 파일 하나만 훑는다. [declaredInMainSources] 가 쓰는 이음매이며,
     * [kr.easydoc.api.SourceScanFormsProbe] 가 **잡는 것/못 잡는 것** 목록을 실측할 때도 이 문을 쓴다.
     * 목록을 KDoc 산문으로만 두면 파서가 바뀌어도 산문은 그대로 남는다.
     */
    fun declarationsIn(file: File): List<SourceDeclaration> = KotlinSourceFile(file).declarations()

    private fun sourceRoots(): List<File> {
        val root =
            File(
                System.getProperty(SOURCE_ROOT_PROPERTY)
                    ?: error("시스템 속성 $SOURCE_ROOT_PROPERTY 이 없다 — 제품 소스를 찾을 기준점이 없다"),
            )
        val roots =
            (root.listFiles()?.toList() ?: emptyList())
                .filter { it.isDirectory }
                .map { it.resolve(MAIN_SOURCES) }
                .filter { it.isDirectory }
        require(roots.isNotEmpty()) {
            "$root 아래에서 `*/$MAIN_SOURCES` 를 하나도 찾지 못했다 — 소스 대조가 0건을 훑고 통과한다"
        }
        return roots
    }

    private fun declarationsUnder(root: File): List<SourceDeclaration> =
        root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> declarationsIn(file).asSequence() }
            .toList()

    private fun load(name: String): Class<*> =
        try {
            Class.forName(name, false, javaClass.classLoader)
        } catch (failure: LinkageError) {
            error("제품 클래스 $name 을 적재하지 못했다(${failure::class.java.simpleName}) — 탐지 범위가 조용히 줄어든다")
        }

    /** 테스트·testFixtures 산출물을 가르는 표식. 제품이 아닌 것을 검사 대상에 넣지 않는다. */
    private val TEST_OUTPUT_MARKERS =
        listOf(
            "/classes/kotlin/test/",
            "/classes/java/test/",
            "/classes/kotlin/testFixtures/",
            "/classes/java/testFixtures/",
            "test-fixtures.jar",
        )
}

/**
 * 소스에 적힌 선언 하나.
 *
 * [nesting] 은 바깥에서 안쪽 순서이며 마지막 칸이 자기 자신이다 — `Outcome.Body` 는
 * `["Outcome", "Body"]`, `Argon2Phc` 안의 `Costs` 는 `["Argon2Phc", "Costs"]`,
 * `companion object` 안에 있으면 그 자리에 `Companion` 이 들어간다(컴파일러가 넣는 칸이라
 * 바이너리 이름에도 그대로 나온다).
 */
data class SourceDeclaration(
    /** `data` 또는 `value`. */
    val kind: String,
    val packageName: String,
    val nesting: List<String>,
    val path: String,
) {
    val simpleName: String get() = nesting.last()

    /** JVM 바이너리 이름. 적재된 [Class.getName] 과 **이것으로** 맞춘다. */
    val binaryName: String get() = "$packageName.${nesting.joinToString(NESTED_SEPARATOR)}"

    /** 사람이 읽는 소스 표기(`Outer.Inner`). 실패 메시지에만 쓴다. */
    val sourceName: String get() = "$packageName.${nesting.joinToString(".")}"

    private companion object {
        /** 중첩 클래스의 바이너리 구분자. 문자열 템플릿과 섞이지 않게 상수로 둔다. */
        const val NESTED_SEPARATOR = "\$"
    }
}

/**
 * Kotlin 소스 한 파일에서 `data`/`value class` 선언을 **바이너리 이름까지** 읽어 낸다.
 *
 * 정규식만으로는 중첩을 알 수 없어 중괄호를 센다. 중괄호를 세려면 주석과 문자열 리터럴을
 * 먼저 지워야 한다 — `"{"` 한 글자나 `"http://…"` 안의 `//` 가 깊이를 어긋내면 사슬이
 * 통째로 틀리기 때문이다. 지운 결과가 깨졌는지(짝 안 맞는 중괄호, 안 닫힌 문자열)는
 * **끊어서** 알린다. 조용히 넘기면 그 파일의 선언이 전부 검사 밖으로 나간다.
 */
private class KotlinSourceFile(private val file: File) {
    fun declarations(): List<SourceDeclaration> {
        val code = NonCodeBlanker(file, file.readText()).blanked()
        val packageName =
            PACKAGE.find(code)?.groupValues?.get(1)
                ?: error("${file.path}: `package` 선언이 없다 — 바이너리 이름을 만들 기준이 없다")
        return scan(code, packageName)
    }

    /**
     * 한 번 훑으면서 ⑴ 중괄호 깊이와 ⑵ 열려 있는 **타입** 본문의 이름을 함께 들고 간다.
     *
     * 타입 선언을 만나면 「본문을 기다리는 상태」로 두고, 같은 깊이·괄호 밖에서 나오는
     * 첫 `{` 를 그 타입의 본문으로 본다. 본문이 없는 선언(`data class X(…) : Y`)은
     * 다음 선언이나 `fun`/`val` 같은 다른 멤버가 나오면 기다림을 접는다 — 접지 않으면
     * 뒤따르는 함수 몸통의 `{` 를 그 타입의 본문으로 착각한다.
     */
    private fun scan(
        code: String,
        packageName: String,
    ): List<SourceDeclaration> {
        val heads = HEAD.findAll(code).associateBy { it.range.first }
        val found = mutableListOf<SourceDeclaration>()
        val openTypes = mutableListOf<String?>()
        var parens = 0
        var pending: PendingType? = null
        var index = 0
        while (index < code.length) {
            // 괄호 안은 보지 않는다. 주 생성자 파라미터는 줄 머리에서 `val`/`var` 로 시작해
            // 멤버 머리와 똑같이 보이고, 그것을 멤버로 세면 **자기 본문을 기다리던 타입이
            // 파라미터 목록에서 지워진다**(`EasyDocProperties`·`Argon2Phc` 가 이 모양이라
            // 중첩 타입 셋이 최상위로 잘못 나왔다 — 실측 2026-08-19).
            val head = if (parens == 0) heads[index] else null
            if (head != null) {
                pending = onHead(head, openTypes, packageName, found)
                index = head.range.last + 1
                continue
            }
            when (code[index]) {
                '(' -> {
                    parens++
                }

                ')' -> {
                    parens--
                }

                '{' -> {
                    val opening = pending?.takeIf { parens == 0 && it.depth == openTypes.size }
                    openTypes += opening?.name
                    if (opening != null) pending = null
                }

                '}' -> {
                    require(openTypes.isNotEmpty()) { "${file.path}: 짝 없는 `}` — 소스 스캐너가 깨졌다" }
                    openTypes.removeAt(openTypes.lastIndex)
                    pending = pending?.takeIf { it.depth <= openTypes.size }
                }
            }
            index++
        }
        require(openTypes.isEmpty() && parens == 0) {
            "${file.path}: 훑기가 끝났는데 중괄호 ${openTypes.size} 개·괄호 $parens 개가 열려 있다 — 스캐너가 깨졌다"
        }
        return found
    }

    /** 선언 머리 하나를 처리하고, 본문을 기다릴 타입이 있으면 돌려준다. */
    private fun onHead(
        head: MatchResult,
        openTypes: List<String?>,
        packageName: String,
        found: MutableList<SourceDeclaration>,
    ): PendingType? {
        val name = typeName(head) ?: return null
        dataOrValue(head)?.let { kind ->
            found +=
                SourceDeclaration(
                    kind = kind,
                    packageName = packageName,
                    nesting = openTypes.filterNotNull() + name,
                    path = file.path,
                )
        }
        return PendingType(name, openTypes.size)
    }

    /** 타입 머리면 그 이름, `fun`/`val` 같은 다른 멤버 머리면 null. */
    private fun typeName(head: MatchResult): String? =
        when {
            head.groupValues[MEMBER_KEYWORD].isNotEmpty() -> null

            head.groupValues[TYPE_NAME].isNotEmpty() -> head.groupValues[TYPE_NAME]

            // `companion object` 는 이름을 생략할 수 있고, 그때 컴파일러가 `Companion` 을 넣는다.
            else -> head.groupValues[COMPANION_ALIAS].ifEmpty { "Companion" }
        }

    private fun dataOrValue(head: MatchResult): String? = head.groupValues[DATA_OR_VALUE].ifEmpty { null }

    /** 본문 `{` 를 기다리는 타입. [depth] 는 선언을 만난 시점의 중괄호 깊이. */
    private data class PendingType(
        val name: String,
        val depth: Int,
    )

    private companion object {
        private val PACKAGE = Regex("""^package[ \t]+([\w.]+)""", RegexOption.MULTILINE)

        /**
         * `data`/`value` 앞에 올 수 있는 수식어. 이 목록 밖의 수식어가 앞서면 그 선언은
         * 세어지지 않는다(클래스 KDoc 「못 잡는 것」 ⑶).
         */
        private const val MODIFIERS =
            "public|private|internal|protected|open|final|abstract|sealed|inner|enum|annotation|" +
                "expect|actual|external|override|lateinit|const|suspend|operator|infix|inline|tailrec"

        /** 같은 줄에 붙은 애너테이션(`@JvmInline`, `@Suppress("x")`). 인자는 한 줄 안에서만 읽는다. */
        private const val ANNOTATION = """@[A-Za-z_][\w.]*(?:\([^\n]*?\))?"""

        private const val PREFIX = """(?:(?:$ANNOTATION|$MODIFIERS)[ \t]+)*"""

        /**
         * 줄 머리에서 시작하는 선언 머리 — 타입 셋(`companion object` / 이름 있는 타입)과,
         * 「기다림을 접게 하는」 멤버 키워드.
         *
         * `constructor` 를 멤버 목록에 넣지 않는 이유: `data class X` 다음 줄에
         * `@JsonCreator constructor(…)` 가 오는 형태(`AuthDtos.kt`)에서 X 의 본문을 놓치게 된다.
         */
        private val HEAD =
            Regex(
                "^[ \t]*$PREFIX(?:" +
                    """companion[ \t]+object(?:[ \t]+([A-Za-z_]\w*))?""" +
                    """|(?:(data|value)[ \t]+)?(?:class|interface|object)[ \t]+([A-Za-z_]\w*)""" +
                    """|(fun|val|var|init|typealias)\b""" +
                    ")",
                RegexOption.MULTILINE,
            )

        private const val COMPANION_ALIAS = 1
        private const val DATA_OR_VALUE = 2
        private const val TYPE_NAME = 3
        private const val MEMBER_KEYWORD = 4
    }
}

/**
 * 주석·문자열 리터럴을 **같은 길이의 공백으로** 지운다(줄바꿈은 남긴다).
 *
 * 길이를 보존하는 이유는 뒤에서 정규식 위치로 원문을 다시 짚기 때문이고, 줄바꿈을 남기는
 * 이유는 「줄 머리」 판정이 살아 있어야 하기 때문이다. Kotlin 의 중첩 블록 주석, 문자열
 * 템플릿 `${…}` 안의 코드(그 안의 또 다른 문자열 포함), `"""` 원시 문자열, 문자 리터럴을
 * 모두 다룬다 — 이 중 하나라도 놓치면 중괄호 깊이가 어긋나 중첩 사슬이 통째로 틀린다.
 */
private class NonCodeBlanker(
    private val file: File,
    private val text: String,
) {
    private val out = CharArray(text.length) { ' ' }

    /** 문자열 템플릿 안의 코드 구역. 비어 있지 않으면 「지금은 문자열 속」이라 전부 지운다. */
    private val templates = ArrayDeque<TemplateFrame>()
    private var mode = Mode.CODE
    private var blockDepth = 0
    private var index = 0

    fun blanked(): String {
        while (index < text.length) {
            when (mode) {
                Mode.CODE -> code()
                Mode.LINE_COMMENT -> lineComment()
                Mode.BLOCK_COMMENT -> blockComment()
                Mode.STRING -> string()
                Mode.RAW_STRING -> rawString()
            }
        }
        require(templates.isEmpty() && (mode == Mode.CODE || mode == Mode.LINE_COMMENT)) {
            "${file.path}: 파일이 끝났는데 주석·문자열이 닫히지 않았다($mode) — 스캐너가 깨졌다"
        }
        return String(out)
    }

    private fun code() {
        val c = text[index]
        when {
            c == '/' && peek(1) == '/' -> {
                enter(Mode.LINE_COMMENT, 2)
            }

            c == '/' && peek(1) == '*' -> {
                blockDepth = 1
                enter(Mode.BLOCK_COMMENT, 2)
            }

            text.startsWith(TRIPLE_QUOTE, index) -> {
                enter(Mode.RAW_STRING, TRIPLE_QUOTE.length)
            }

            c == '"' -> {
                enter(Mode.STRING, 1)
            }

            c == '\'' -> {
                charLiteral()
            }

            c == '{' && templates.isNotEmpty() -> {
                templates.last().braces++
                index++
            }

            c == '}' && templates.isNotEmpty() -> {
                closeBraceInsideTemplate()
            }

            else -> {
                keep(c)
                index++
            }
        }
    }

    private fun closeBraceInsideTemplate() {
        val frame = templates.last()
        if (frame.braces == 0) {
            templates.removeLast()
            mode = frame.owner
        } else {
            frame.braces--
        }
        index++
    }

    private fun string() {
        val c = text[index]
        when {
            c == '\\' -> {
                index += 2
            }

            c == '$' && peek(1) == '{' -> {
                openTemplate(Mode.STRING)
            }

            c == '"' -> {
                enter(Mode.CODE, 1)
            }

            else -> {
                keepNewline(c)
                index++
            }
        }
    }

    private fun rawString() {
        val c = text[index]
        when {
            c == '$' && peek(1) == '{' -> {
                openTemplate(Mode.RAW_STRING)
            }

            // 따옴표가 넷 이상이면 마지막 셋이 닫는 자리다.
            text.startsWith(TRIPLE_QUOTE, index) && peek(TRIPLE_QUOTE.length) != '"' -> {
                enter(Mode.CODE, TRIPLE_QUOTE.length)
            }

            else -> {
                keepNewline(c)
                index++
            }
        }
    }

    private fun lineComment() {
        val c = text[index]
        if (c == '\n') {
            keepNewline(c)
            mode = Mode.CODE
        }
        index++
    }

    private fun blockComment() {
        when {
            text.startsWith("/*", index) -> {
                blockDepth++
                index += 2
            }

            text.startsWith("*/", index) -> {
                blockDepth--
                index += 2
                if (blockDepth == 0) mode = Mode.CODE
            }

            else -> {
                keepNewline(text[index])
                index++
            }
        }
    }

    /** 문자 리터럴은 짧고 중첩이 없어 그 자리에서 건너뛴다. `'\''` 같은 탈출도 함께 처리한다. */
    private fun charLiteral() {
        index++
        while (index < text.length && text[index] != '\'') {
            if (text[index] == '\\') index++
            index++
        }
        if (index < text.length) index++
    }

    private fun openTemplate(owner: Mode) {
        templates.addLast(TemplateFrame(owner))
        mode = Mode.CODE
        index += 2
    }

    private fun enter(
        next: Mode,
        width: Int,
    ) {
        mode = next
        index += width
    }

    /** 코드 글자를 남긴다. 문자열 템플릿 안이면 그것도 문자열의 일부라 지운다. */
    private fun keep(c: Char) {
        if (templates.isEmpty() || c == '\n') out[index] = c
    }

    private fun keepNewline(c: Char) {
        if (c == '\n') out[index] = c
    }

    private fun peek(offset: Int): Char? = text.getOrNull(index + offset)

    private enum class Mode { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, RAW_STRING }

    /** 문자열 안 `${…}` 코드 구역 하나. [braces] 는 그 안에서 더 열린 중괄호 수. */
    private class TemplateFrame(val owner: Mode) {
        var braces: Int = 0
    }

    private companion object {
        private const val TRIPLE_QUOTE = "\"\"\""
    }
}
