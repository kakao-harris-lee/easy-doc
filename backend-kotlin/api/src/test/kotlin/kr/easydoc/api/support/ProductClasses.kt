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
 * `value class` 로 적힌 이름은 전부 적재된 집합에 있어야 한다. 이 대조가 잡는 것은 열거가
 * 아니라 **종류**다 — 새 모듈이 생겼는데 클래스패스에 없거나(오늘 `worker` 가 그렇다),
 * 경로 표식이 제품 산출물까지 걸러 버리거나, 적재 필터가 넓어지면 전부 여기서 빨개진다.
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
     * `모듈/src/main/kotlin` 에 선언된 `data class`·`value class` 이름.
     *
     * 소스 텍스트를 읽는 이유는 하나다 — **클래스패스에 없는 모듈도 세야 한다.** `api` 는
     * 다섯 모듈 중 넷만 런타임에 싣고 `worker` 를 싣지 않으므로, 클래스패스만 보면 `worker`
     * 에 생긴 첫 DTO 가 조용히 검사 밖에 남는다(privacy-gate 부수 실측).
     *
     * 판정은 **줄 머리**로 한다. 주석을 걷어낸 뒤 수식어(`private`·`internal`·`@JvmInline`
     * 등)만 앞설 수 있게 해서, KDoc 산문에 등장하는 「`data class` 의 기본 `toString()`」
     * 같은 문장을 선언으로 오독하지 않는다.
     */
    fun declaredInMainSources(): List<SourceDeclaration> = sourceRoots().flatMap { root -> declarationsUnder(root) }

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
            .flatMap { file ->
                val stripped = LINE_COMMENT.replace(BLOCK_COMMENT.replace(file.readText(), ""), "")
                DECLARATION.findAll(stripped).map { match ->
                    SourceDeclaration(
                        kind = match.groupValues[1],
                        simpleName = match.groupValues[2],
                        path = file.path,
                    )
                }
            }.toList()

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

    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

    private val LINE_COMMENT = Regex("//[^\n]*")

    /** 선언 앞에 올 수 있는 수식어. 이 목록 밖의 수식어가 생기면 그 선언은 세어지지 않는다. */
    private const val MODIFIERS = "private|internal|public|protected|sealed|abstract|open|inner|annotation|@JvmInline"

    /** 줄 머리 + 수식어 + `data`/`value` + `class` + 이름. 산문 속 언급과 선언을 가른다. */
    private val DECLARATION =
        Regex(
            """^[ \t]*(?:(?:$MODIFIERS)[ \t]+)*(data|value)[ \t]+class[ \t]+([A-Za-z_]\w*)""",
            RegexOption.MULTILINE,
        )
}

/** 소스에 적힌 선언 하나. [kind] 는 `data` 또는 `value`. */
data class SourceDeclaration(
    val kind: String,
    val simpleName: String,
    val path: String,
)
