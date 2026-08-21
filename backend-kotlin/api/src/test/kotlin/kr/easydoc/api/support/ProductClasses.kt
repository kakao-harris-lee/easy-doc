package kr.easydoc.api.support

import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.classreading.CachingMetadataReaderFactory
import java.io.File
import kotlin.reflect.KClass

/** 제품 타입을 두 방향에서 센다 — 적재된 클래스(런타임)와 선언된 소스(디스크). */
object ProductClasses {
    /** 빌드가 모든 테스트 태스크에 주입하는 Gradle 루트(= `backend-kotlin/`). */
    private const val SOURCE_ROOT_PROPERTY = "easydoc.kotlin.source.root"

    /** 제품 소스가 사는 자리. 모듈 이름을 열거하지 않는다 — 새 모듈이 저절로 들어온다. */
    private const val MAIN_SOURCES = "src/main/kotlin"

    /** 테스트 런타임 클래스패스의 `kr.easydoc.` 제품 클래스. */
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

    /** `모듈/src/main/kotlin` 에 선언된 `data class`·`value class`. */
    fun declaredInMainSources(): List<SourceDeclaration> = sourceRoots().flatMap { root -> declarationsUnder(root) }

    /**
     * 파일 하나만 훑는다. [declaredInMainSources] 가 쓰는 이음매이며,
     * [kr.easydoc.api.SourceScanFormsProbe] 가 잡는 것/못 잡는 것 목록을 실측할 때도 이 문을 쓴다.
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

/** 소스에 적힌 선언 하나. */
data class SourceDeclaration(
    /** `data` 또는 `value`. */
    val kind: String,
    val packageName: String,
    val nesting: List<String>,
    val path: String,
) {
    val simpleName: String get() = nesting.last()

    /** JVM 바이너리 이름. 적재된 [Class.getName] 과 이것으로 맞춘다. */
    val binaryName: String get() = "$packageName.${nesting.joinToString(NESTED_SEPARATOR)}"

    /** 사람이 읽는 소스 표기(`Outer.Inner`). 실패 메시지에만 쓴다. */
    val sourceName: String get() = "$packageName.${nesting.joinToString(".")}"

    private companion object {
        /** 중첩 클래스의 바이너리 구분자. 문자열 템플릿과 섞이지 않게 상수로 둔다. */
        const val NESTED_SEPARATOR = "\$"
    }
}

/** Kotlin 소스 한 파일에서 `data`/`value class` 선언을 바이너리 이름까지 읽어 낸다. */
private class KotlinSourceFile(private val file: File) {
    fun declarations(): List<SourceDeclaration> {
        val code = NonCodeBlanker(file, file.readText()).blanked()
        val packageName =
            PACKAGE.find(code)?.groupValues?.get(1)
                ?: error("${file.path}: `package` 선언이 없다 — 바이너리 이름을 만들 기준이 없다")
        return scan(code, packageName)
    }

    /** 한 번 훑으면서 ⑴ 중괄호 깊이와 ⑵ 열려 있는 타입 본문의 이름을 함께 들고 간다. */
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
                "expect|actual|external|override|lateinit|const|suspend|operator|infix|inline|tailrec|fun"

        /** 같은 줄에 붙은 애너테이션(`@JvmInline`, `@Suppress("x")`). 인자는 한 줄 안에서만 읽는다. */
        private const val ANNOTATION = """@[A-Za-z_][\w.]*(?:\([^\n]*?\))?"""

        private const val PREFIX = """(?:(?:$ANNOTATION|$MODIFIERS)[ \t]+)*"""

        /**
         * 줄 머리에서 시작하는 선언 머리 — 타입 셋(`companion object` / 이름 있는 타입)과,
         * 「기다림을 접게 하는」 멤버 키워드.
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

/** 주석·문자열 리터럴을 같은 길이의 공백으로 지운다(줄바꿈은 남긴다). */
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
