package kr.easydoc.infrastructure.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.classreading.CachingMetadataReaderFactory
import java.lang.reflect.Modifier

/**
 * **`CountingDataSource` 의 전제를 장치로 바꾼다** — 원장 K-2.
 *
 * ## 무엇이 전제였나
 *
 * `CountingDataSource` 는 `Connection` 의 문장 생성 메서드만 세고 돌려준 `Statement` 는
 * 감싸지 않는다. 그래서 **`createStatement()` 로 얻은 문장 하나에 SQL 둘을 태우면 이
 * 계수기는 1 을 본다.** 그 우회가 성립하지 않는 근거는 그 KDoc 에 산문으로만 있었다 —
 * *"`JdbcClient` 는 SQL 문자열 하나당 `PreparedStatement` 하나를 만들고 한
 * `PreparedStatement` 에 다른 SQL 을 태울 수 없다. 저장소가 `JdbcClient` 를 벗어나는 순간
 * 이 계수기의 전제가 깨진다."*
 *
 * 원장은 그 마감을 **"Phase 4 (raw JDBC 하강 커밋)"** 으로 잡았다. 문서 저장소가
 * `JdbcClient` 를 쓰는 커밋이 여기이므로, 산문을 **단언**으로 옮긴다.
 *
 * ## 이름을 열거하지 않는다 — **종류로 훑는다**
 *
 * `Jdbc*Repository` 같은 접미사 목록으로 대상을 고르지 않는다. 그 방식은 다음에 생기는
 * 어댑터를 조용히 빠뜨리고, 이 저장소는 같은 형태의 실패를 이미 여러 번 겪었다
 * (`CLAUDE.md` 규칙 4 — 열거는 구조로 고친다).
 *
 * 분모는 **`kr.easydoc.infrastructure` 의 구상 클래스 중 `kr.easydoc.application` 의
 * 포트를 구현한 것 전부**다. 「유스케이스 뒤에서 바깥 세계를 만지는 것」이 정확히 그
 * 집합이고, 계측이 겨누는 것도 그 집합이다.
 *
 * ## 무엇을 금지하는가
 *
 * 그 어댑터들이 **생성자 파라미터나 필드로 raw JDBC 손잡이를 들지 않는다.** 손잡이가 없으면
 * `createStatement()` 를 부를 방법이 없고, 그러면 문장 하나에 SQL 둘을 태우는 우회도
 * 성립하지 않는다.
 *
 * ## 이 장치가 **막지 못하는** 것 (적어 두지 않으면 다음 사람이 과신한다)
 *
 * - 어댑터가 **다른 클래스에 위임**해 그쪽이 raw JDBC 를 쥐는 경우. 위임 대상이 포트를
 *   구현하지 않으면 분모 밖이다.
 * - 메서드 지역 변수로 `DataSource` 를 얻어 오는 경우(예: 정적 접근). 오늘 그런 경로가
 *   없지만 타입 검사로는 보이지 않는다.
 * - `db` 패키지의 인프라 코드(`SchemaFingerprint`·`FlywayBaselineGuard`)는 **일부러 분모
 *   밖이다.** 그 둘은 포트 구현이 아니고 요청 경로에서 돌지 않는다 — 스키마 지문과 기동
 *   가드다. 계측이 겨누는 「유스케이스 한 번 = 요청 한 번」에 들어가지 않는다.
 */
class StatementCountingPremiseTest {
    @Test
    @DisplayName("포트 어댑터가 raw JDBC 손잡이를 들지 않는다 — CountingDataSource 의 전제")
    fun `포트 어댑터가 raw JDBC 를 들지 않는다`() {
        val adapters = portAdapters()

        val offenders =
            adapters.mapNotNull { adapter ->
                val handles = rawJdbcHandlesOf(adapter)
                if (handles.isEmpty()) null else "${adapter.name} — ${handles.sorted()}"
            }

        assertThat(offenders)
            .withFailMessage {
                "아래 어댑터가 raw JDBC 손잡이를 들고 있다:\n" +
                    offenders.joinToString("\n") { "  - $it" } +
                    "\n  `CountingDataSource` 는 `Connection` 의 문장 **생성** 횟수만 센다. raw JDBC 로 내려가면\n" +
                    "  문장 하나에 SQL 둘을 태울 수 있고, 그 순간 소유권 은닉의 구조 축 계측이 조용히 무의미해진다.\n" +
                    "  `JdbcClient` 를 쓰거나, 정말 필요하면 `CountingDataSource` 의 계측 방식을 함께 고쳐라."
            }.isEmpty()
    }

    @Test
    @DisplayName("분모가 비어 있지 않고 실제로 JdbcClient 를 쓰는 어댑터를 포함한다 — 0건을 훑고 통과하지 않는다")
    fun `분모가 비어 있지 않다`() {
        val adapters = portAdapters()

        assertThat(adapters)
            .withFailMessage("포트 어댑터를 하나도 찾지 못했다 — 이 탐지기는 아무것도 검사하지 않는다")
            .isNotEmpty()

        val jdbcClientUsers = adapters.filter { JDBC_CLIENT in constructorParameterTypeNames(it) }

        assertThat(jdbcClientUsers.map { it.simpleName })
            .withFailMessage {
                "`JdbcClient` 를 받는 어댑터가 하나도 없다 — 계측 전제를 지킬 대상 자체가 사라졌다.\n" +
                    "  찾은 어댑터: ${adapters.map { it.simpleName }.sorted()}"
            }.isNotEmpty()
    }

    // ---------------------------------------------------------------- 훑기

    /** `kr.easydoc.infrastructure` 의 구상 클래스 중 `kr.easydoc.application` 포트를 구현한 것. */
    private fun portAdapters(): List<Class<*>> {
        val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)
        val metadata = CachingMetadataReaderFactory(resolver)
        return resolver
            .getResources("classpath*:kr/easydoc/infrastructure/**/*.class")
            .filter { resource -> TEST_OUTPUT_MARKERS.none { it in resource.url.toString() } }
            .map { resource -> load(metadata.getMetadataReader(resource).classMetadata.className) }
            .filter { !it.isSynthetic && !it.isAnonymousClass && !it.isLocalClass }
            .filter { !it.isInterface && !Modifier.isAbstract(it.modifiers) && !it.isEnum }
            .filter { implementsApplicationPort(it) }
            .distinct()
            .sortedBy { it.name }
    }

    private fun implementsApplicationPort(type: Class<*>): Boolean =
        generateSequence(type) { it.superclass }
            .flatMap { it.interfaces.asSequence() }
            .any { it.name.startsWith(APPLICATION_PACKAGE) }

    /** 이 어댑터가 들고 있는 금지 손잡이의 타입 이름. 없으면 빈 목록. */
    private fun rawJdbcHandlesOf(type: Class<*>): List<String> {
        val fromFields = type.declaredFields.filterNot { it.isSynthetic }.map { it.type.name }
        val fromConstructor = constructorParameterTypeNames(type)
        return (fromFields + fromConstructor).filter { it in FORBIDDEN_HANDLES }.distinct()
    }

    /**
     * 생성자가 받는 타입 이름 전부.
     *
     * `kotlin-reflect` 를 쓰지 않고 자바 반사로 읽는다 — 이 모듈의 테스트 클래스패스에
     * `kotlin-reflect` 가 **컴파일 의존성으로 없고**(runtimeOnly 다), 그것을 더하는 것은
     * 이 탐지기 하나를 위해 모듈 의존성을 넓히는 일이다. 주 생성자만 고르는 대신
     * **모든 생성자**를 보므로 판정이 더 넓어질 뿐 좁아지지 않는다.
     */
    private fun constructorParameterTypeNames(type: Class<*>): List<String> =
        type.declaredConstructors
            .filterNot { it.isSynthetic }
            .flatMap { constructor -> constructor.parameterTypes.map { it.name } }

    private fun load(className: String): Class<*> =
        runCatching { Class.forName(className, false, javaClass.classLoader) }
            .getOrElse { failure ->
                // 조용히 건너뛰지 않는다 — 빠진 클래스는 검사받은 것과 구분되지 않는다.
                error("$className 을 적재하지 못했다: ${failure::class.java.simpleName}")
            }

    private companion object {
        const val APPLICATION_PACKAGE = "kr.easydoc.application."
        const val JDBC_CLIENT = "org.springframework.jdbc.core.simple.JdbcClient"

        /**
         * raw JDBC 손잡이 — **이것을 들면 문장 하나에 SQL 둘을 태울 수 있다.**
         *
         * `JdbcTemplate` 계열도 넣는다. 그쪽은 `execute(ConnectionCallback)` 로 `Connection`
         * 을 통째로 내주므로 `JdbcClient` 가 주는 「SQL 하나당 문장 하나」 성질이 없다.
         */
        val FORBIDDEN_HANDLES =
            setOf(
                "javax.sql.DataSource",
                "java.sql.Connection",
                "java.sql.Statement",
                "java.sql.PreparedStatement",
                "org.springframework.jdbc.core.JdbcTemplate",
                "org.springframework.jdbc.core.JdbcOperations",
                "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
                "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations",
            )

        val TEST_OUTPUT_MARKERS =
            listOf(
                "/classes/kotlin/test/",
                "/classes/java/test/",
                "/classes/kotlin/testFixtures/",
                "/classes/java/testFixtures/",
            )
    }
}
