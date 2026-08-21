package kr.easydoc.infrastructure.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.classreading.CachingMetadataReaderFactory
import java.lang.reflect.Modifier

/** `CountingDataSource` 의 전제를 장치로 바꾼다 — 원장 K-2. */
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

    /** 상위 클래스와 상위 인터페이스까지 재귀로 걷는다. */
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

    /** 이 어댑터가 들고 있는 금지 손잡이의 타입 이름. 없으면 빈 목록. */
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

    /** `application` 포트를 상속한 중간 인터페이스. */
    private interface IntermediatePort : kr.easydoc.application.auth.TransactionRunner

    private class IndirectAdapter : IntermediatePort {
        override fun <T> inTransaction(block: () -> T): T = block()
    }

    private class UnrelatedType

    /** `DataSource` 하위 타입을 든 클래스. 이름은 금지 목록에 없다. */
    private class SubtypeHandleHolder(
        @Suppress("unused") private val handle: org.springframework.jdbc.datasource.SingleConnectionDataSource,
    )

    /** 생성자가 받는 타입 이름 전부. */
    private fun constructorParameterTypeNames(type: Class<*>): List<String> =
        type.declaredConstructors
            .filterNot { it.isSynthetic }
            .flatMap { constructor -> constructor.parameterTypes.map { it.name } }

    private fun load(className: String): Class<*> =
        runCatching { Class.forName(className, false, javaClass.classLoader) }
            .getOrElse { failure ->

                error("$className 을 적재하지 못했다: ${failure::class.java.simpleName}")
            }

    private companion object {
        const val APPLICATION_PACKAGE = "kr.easydoc.application."
        const val JDBC_CLIENT = "org.springframework.jdbc.core.simple.JdbcClient"

        /** raw JDBC 손잡이 — 이것을 들면 문장 하나에 SQL 둘을 태울 수 있다. */
        val RAW_JDBC_HANDLES: Set<Class<*>> =
            setOf(
                javax.sql.DataSource::class.java,
                java.sql.Connection::class.java,
                java.sql.Statement::class.java,
                org.springframework.jdbc.core.JdbcOperations::class.java,
                org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations::class.java,
            )

        /**
         * 오늘 실재하는 포트 어댑터. 바닥 목록이지 천장이 아니다 —
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
