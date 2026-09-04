package kr.easydoc.api

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.MigrationCatalog
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.document.KeyRotationRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * `rotate-keys` profile 의 실 배선 — backlog §1.1 「키 회전에 운영 진입점이 없음」의
 * 프로필 층 회귀 고정판. 회전 로직 자체(가족 넷 순회·재실행 no-op)는
 * `infrastructure` 의 `KeyRotationBatchTest` 가 잰다 — 이 테스트는 **그 로직이 실제로
 * `ApiApplication` 으로 뜨는가**를 본다: `migrate` 와 달리 `ContentCipher` 가 조립되고,
 * `KeyRotationRunner` 가 `ExitCodeGenerator` 로 배선돼 있으며, 회전할 것이 없으면
 * 종료 코드가 0 이다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.profiles.active=rotate-keys"],
)
class RotateKeysProfileTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    @DisplayName("빈 DB — 회전할 것이 없으면 종료 코드 0 이다(no-op)")
    fun `빈 DB 에서 no-op 으로 뜬다`() {
        assertThat(context.environment.activeProfiles).contains("rotate-keys")

        // migrate 와 갈리는 자리 — 회전은 본문 암호화 키를 쥐어야 한다(`KeyRotationConfiguration` KDoc).
        assertThat(context.getBeanNamesForType(ContentCipher::class.java))
            .describedAs("rotate-keys 컨텍스트에 ContentCipher 빈이 없다 — 회전이 키를 쥐지 못한다")
            .isNotEmpty()

        val runner = context.getBean(KeyRotationRunner::class.java)
        assertThat(runner.exitCode)
            .describedAs("빈 DB 에 회전할 행이 없는데 종료 코드가 0 이 아니다")
            .isZero()

        assertThat(
            database.queryFirstColumn("SELECT version FROM flyway_schema_history ORDER BY installed_rank"),
        ).containsExactlyElementsOf(MigrationCatalog.versions)
    }

    companion object {
        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("rotate_keys_profile")
        }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}
