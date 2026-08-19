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
 * **`migrate` 프로필은 본문 암호화 키 없이 뜬다** — 게이트 26 조치 2 (리더 판정 ④ ·
 * privacy-gate R-2 · cross 행 21).
 *
 * ## 왜 `CryptoProfileExemptionTest` 만으로 부족한가
 *
 * 그 파일은 `ApplicationContextRunner` 로 **조건 자체**를 잰다. 여기서 재는 것은 운영에서
 * 실제로 성립해야 하는 문장이다 — *"`java -jar easy-doc-api.jar
 * --spring.profiles.active=migrate` 를 `EASYDOC_ENCRYPTION_KEY_V1` 없이 돌리면 스키마가
 * 적용된다."* 그 문장은 실제 `application.yml`(placeholder 포함) · 실제 프로필 문서 ·
 * 실제 Flyway 배선을 전부 지나야 참이다.
 *
 * 키 두 개를 **빈 값으로 못박는다.** 그러지 않으면 testFixtures 의 `TestEncryptionKeys` 가
 * 넣어 준 키로 떠서, 「키가 없어도 뜬다」가 아니라 「키가 있어서 떴다」가 된다.
 *
 * ## 면제의 경계도 함께 잰다
 *
 * `migrate` 에는 [ContentCipher] 빈이 **아예 없다**. 「검사만 건너뛴다」가 아니라
 * 「조립하지 않는다」이므로, 이 실행은 키 재료를 메모리에 들지 않는다(최소 권한).
 * 그러면서 자기 전제(DB 도달·Flyway)에 대해서는 그대로 fail-fast 다 — 스키마가 적용되지
 * 않으면 이 컨텍스트는 뜨지 못한다.
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
