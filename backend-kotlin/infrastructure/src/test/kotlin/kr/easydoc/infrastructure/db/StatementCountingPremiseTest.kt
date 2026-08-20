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
 * ## 분모에 **하한과 바닥 목록**을 둔다 (게이트 27 M-4 / codex C-6)
 *
 * 이전 판은 `isNotEmpty()` 와 「`JdbcClient` 를 받는 어댑터가 하나 이상」만 요구했다.
 * 그래서 **크롤이 10건에서 1건으로 줄어도 두 단언이 모두 통과**했고, 그 상태는 통과가
 * 아니라 **미검사**였다. 같은 저장소의 `SensitiveToStringReachTest` 는 하한 상수와 바닥
 * 목록을 둘 다 갖는데 이 자리만 그 규율이 빠져 있었다.
 *
 * [MIN_PORT_ADAPTERS] 가 하한이고 [KNOWN_PORT_ADAPTERS] 가 바닥 목록이다. **바닥 목록은
 * 천장이 아니다** — 새 어댑터를 여기 적을 필요는 없고, 기존 어댑터가 분모에서 빠지는 것만
 * 막는다. 정말로 지웠다면 이 목록에서도 지워라. 그 diff 가 신고다.
 *
 * ## 인터페이스 폐쇄를 **재귀로** 걷는다 (codex C-6)
 *
 * 이전 판은 각 클래스와 상위 클래스가 **직접 선언한** 인터페이스만 봤다. 그래서
 * `infrastructure` 쪽 중간 인터페이스가 포트를 상속하고 구상 어댑터가 그 중간 인터페이스를
 * 구현하면 **어댑터 전체가 분모 밖**이었다. 지금은 상위 인터페이스까지 재귀로 걷는다.
 * 금지 손잡이도 이름 정확 일치가 아니라 **할당 가능성**(`isAssignableFrom`)으로 본다 —
 * `HikariDataSource` 같은 하위 타입으로 빠져나갈 수 없다.
 *
 * ## 이 장치가 **막지 못하는** 것 (적어 두지 않으면 다음 사람이 과신한다)
 *
 * - 어댑터가 **다른 클래스에 위임**해 그쪽이 raw JDBC 를 쥐는 경우. 위임 대상이 포트를
 *   구현하지 않으면 분모 밖이다.
 * - 메서드 지역 변수로 `DataSource` 를 얻어 오는 경우(예: 정적 접근). 오늘 그런 경로가
 *   없지만 타입 검사로는 보이지 않는다.
 * - **분모가 `kr.easydoc.infrastructure` 로 한정된다.** `api`·`worker` 에 포트 구현이 생기면
 *   처음부터 밖이다. 오늘 그 두 모듈은 `runtimeOnly(project(":infrastructure"))` 라 어댑터를
 *   두지 않지만, 그 사실이 이 탐지기의 전제이지 이 탐지기가 재는 것이 아니다.
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
    @DisplayName("분모에 **하한과 바닥 목록**이 선다 — 크롤이 줄어도 초록이 되지 않는다")
    fun `분모가 하한과 바닥을 지킨다`() {
        val adapters = portAdapters()
        val names = adapters.map { it.name }

        assertThat(adapters)
            .withFailMessage {
                "포트 어댑터를 ${adapters.size} 개밖에 찾지 못했다(기대 $MIN_PORT_ADAPTERS 이상).\n" +
                    "  크롤이 좁아졌거나 클래스패스에서 제품 산출물이 빠졌다 — 그러면 이 게이트는 " +
                    "통과가 아니라 **미검사**다.\n" +
                    "  찾은 것: ${names.sorted()}"
            }.hasSizeGreaterThanOrEqualTo(MIN_PORT_ADAPTERS)

        assertThat(names)
            .withFailMessage {
                "아래 포트 어댑터가 분모에서 빠졌다: ${KNOWN_PORT_ADAPTERS - names.toSet()}\n" +
                    "  이 목록은 **바닥**이지 천장이 아니다 — 새 어댑터를 여기 적을 필요는 없고, " +
                    "기존 어댑터가 크롤 밖으로 빠지는 것만 막는다.\n" +
                    "  정말 지웠다면 이 목록에서도 지워라. 그 diff 가 '검사 대상을 뺐다'는 신고다."
            }.containsAll(KNOWN_PORT_ADAPTERS)

        val jdbcClientUsers = adapters.filter { JDBC_CLIENT in constructorParameterTypeNames(it) }

        assertThat(jdbcClientUsers.map { it.simpleName })
            .withFailMessage {
                "`JdbcClient` 를 받는 어댑터가 하나도 없다 — 계측 전제를 지킬 대상 자체가 사라졌다.\n" +
                    "  찾은 어댑터: ${adapters.map { it.simpleName }.sorted()}"
            }.isNotEmpty()
    }

    @Test
    @DisplayName("포트 판정이 **중간 인터페이스**를 건너뛰지 않는다 — 재귀 폐쇄를 실행으로 확인한다")
    fun `간접 포트 구현도 분모다`() {
        // 오늘 이 저장소에는 중간 인터페이스를 낀 어댑터가 없다. 그래서 「고쳤다」를 실제
        // 어댑터로는 잴 수 없고, **판정 함수 자체**를 합성 타입에 먹여 잰다.
        assertThat(implementsApplicationPort(IndirectAdapter::class.java))
            .withFailMessage("중간 인터페이스를 통해 포트를 구현한 어댑터가 분모 밖이다 — codex C-6 이 되살아났다.")
            .isTrue()
        assertThat(implementsApplicationPort(UnrelatedType::class.java))
            .withFailMessage("포트와 무관한 타입이 분모에 들어왔다 — 과잉 탐지다.")
            .isFalse()
    }

    @Test
    @DisplayName("금지 손잡이를 **하위 타입**으로도 잡는다 — 이름 정확 일치로 빠져나갈 수 없다")
    fun `금지 손잡이는 하위 타입도 잡는다`() {
        assertThat(rawJdbcHandlesOf(SubtypeHandleHolder::class.java))
            .withFailMessage("`DataSource` 하위 타입을 든 클래스를 놓쳤다 — codex C-6 의 둘째 절반이다.")
            .isNotEmpty()
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

    /**
     * 상위 클래스와 **상위 인터페이스까지 재귀로** 걷는다.
     *
     * 직접 선언한 인터페이스만 보면 중간 인터페이스를 낀 어댑터가 통째로 분모 밖이다
     * (codex C-6). 폐쇄를 걷는 비용은 클래스당 인터페이스 몇 개라 무시할 만하다.
     */
    private fun implementsApplicationPort(type: Class<*>): Boolean =
        generateSequence(type) { it.superclass }
            .flatMap { interfaceClosureOf(it) }
            .any { it.name.startsWith(APPLICATION_PACKAGE) }

    private fun interfaceClosureOf(type: Class<*>): Sequence<Class<*>> =
        sequence {
            val pending = ArrayDeque(type.interfaces.toList())
            val seen = mutableSetOf<Class<*>>()
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                if (!seen.add(next)) continue
                yield(next)
                pending.addAll(next.interfaces)
            }
        }

    /**
     * 이 어댑터가 들고 있는 금지 손잡이의 타입 이름. 없으면 빈 목록.
     *
     * **이름 정확 일치가 아니라 할당 가능성**으로 본다 — `HikariDataSource` 처럼 이름이 다른
     * 하위 타입으로 빠져나갈 수 없게(codex C-6). 상속받은 필드도 함께 본다.
     */
    private fun rawJdbcHandlesOf(type: Class<*>): List<String> {
        val fields =
            generateSequence(type) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .filterNot { it.isSynthetic }
                .map { it.type }
        val constructorParameters =
            type.declaredConstructors
                .filterNot { it.isSynthetic }
                .flatMap { it.parameterTypes.asList() }
        return (fields + constructorParameters.asSequence())
            .filter { candidate -> RAW_JDBC_HANDLES.any { it.isAssignableFrom(candidate) } }
            .map { it.name }
            .distinct()
            .toList()
    }

    // ------------------------------------------------------- 판정 함수용 합성 타입
    //
    // **제품 클래스가 아니다.** 판정 함수(재귀 폐쇄·할당 가능성)를 실행으로 재기 위한
    // probe 이고, 테스트 산출물이라 `portAdapters()` 의 크롤에서는 [TEST_OUTPUT_MARKERS] 가
    // 걸러 낸다 — 즉 실제 분모를 오염시키지 않는다.

    /** `application` 포트를 상속한 중간 인터페이스. */
    private interface IntermediatePort : kr.easydoc.application.auth.TransactionRunner

    private class IndirectAdapter : IntermediatePort {
        override fun <T> inTransaction(block: () -> T): T = block()
    }

    private class UnrelatedType

    /** `DataSource` **하위 타입**을 든 클래스. 이름은 금지 목록에 없다. */
    private class SubtypeHandleHolder(
        @Suppress("unused") private val handle: org.springframework.jdbc.datasource.SingleConnectionDataSource,
    )

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
         *
         * 이름은 **무엇인지**를 말한다(raw JDBC 손잡이). **왜 금지인지**는 위 KDoc 과 이
         * 목록을 소비하는 단언이 말한다 — 옛 이름 `FORBIDDEN_HANDLES` 는 "금지"만 말하고
         * 무엇이 왜 금지인지는 말하지 않았다. 개명은 개인정보 스캐너를 고친 **뒤**의
         * 선택이지 그것을 피한 수단이 아니다(게이트 28).
         */
        val RAW_JDBC_HANDLES: Set<Class<*>> =
            setOf(
                javax.sql.DataSource::class.java,
                java.sql.Connection::class.java,
                java.sql.Statement::class.java,
                org.springframework.jdbc.core.JdbcOperations::class.java,
                org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations::class.java,
            )

        /**
         * 오늘 실재하는 포트 어댑터. **바닥 목록**이지 천장이 아니다 —
         * `containsAll` 로만 쓰이므로 새 어댑터가 늘어도 여기를 고칠 필요는 없다.
         */
        val KNOWN_PORT_ADAPTERS =
            listOf(
                "kr.easydoc.infrastructure.auth.Argon2PasswordHasher",
                "kr.easydoc.infrastructure.auth.JdbcUserRepository",
                "kr.easydoc.infrastructure.auth.JdbcWorkspaceRepository",
                "kr.easydoc.infrastructure.auth.JwtAccessTokens",
                "kr.easydoc.infrastructure.crypto.AesGcmContentCipher",
                "kr.easydoc.infrastructure.db.SpringTransactionRunner",
                "kr.easydoc.infrastructure.document.JdbcConversionRepository",
                "kr.easydoc.infrastructure.document.JdbcDocumentRepository",
                "kr.easydoc.infrastructure.document.JdbcWorkspaceLookup",
                "kr.easydoc.infrastructure.ingest.ConcurrencyLimitedTextExtractor",
                "kr.easydoc.infrastructure.ingest.DocumentExtractors",
                "kr.easydoc.infrastructure.queue.JdbcConversionQueue",
            )

        /** 분모 하한. 오늘의 수와 같다 — 줄어들면 「정리」가 아니라 「축소」이므로 근거가 필요하다. */
        const val MIN_PORT_ADAPTERS = 12

        val TEST_OUTPUT_MARKERS =
            listOf(
                "/classes/kotlin/test/",
                "/classes/java/test/",
                "/classes/kotlin/testFixtures/",
                "/classes/java/testFixtures/",
            )
    }
}
