package kr.easydoc.api

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.MigrationCatalog
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * `migrate` 프로필은 본문 암호화 키 없이 뜬다 — 게이트 26 조치 2 (리더 판정 ④ ·
 * privacy-gate R-2 · cross 행 21).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.profiles.active=migrate",
        "EASYDOC_ENCRYPTION_KEY_V1=",
        "EASYDOC_ENCRYPTION_KCV_V1=",
    ],
)
class MigrateProfileWithoutEncryptionKeyTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    @DisplayName("본문 암호화 키가 비어 있어도 migrate 는 뜨고 스키마를 전부 적용한다")
    fun `키 없이 뜨고 스키마를 적용한다`() {
        assertThat(context.environment.activeProfiles).contains("migrate")
        assertThat(context.environment.getProperty("EASYDOC_ENCRYPTION_KEY_V1"))
            .describedAs("키가 실려 있다 — 이 테스트는 「키 없이 뜬다」를 재지 못한다")
            .isEmpty()
        assertThat(
            database.queryFirstColumn("SELECT version FROM flyway_schema_history ORDER BY installed_rank"),
        ).containsExactlyElementsOf(MigrationCatalog.versions)
    }

    @Test
    @DisplayName("migrate 컨텍스트에는 ContentCipher 빈이 없다 — 키 재료를 들지 않는다")
    fun `cipher 빈이 없다`() {
        assertThat(context.getBeanNamesForType(ContentCipher::class.java))
            .describedAs("스키마만 옮기는 실행이 본문 암호화 키를 쥐고 있다 — 최소 권한에 어긋난다")
            .isEmpty()
    }

    companion object {
        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("migrate_without_key")
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
