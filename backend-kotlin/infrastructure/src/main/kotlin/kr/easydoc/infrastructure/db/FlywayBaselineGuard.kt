package kr.easydoc.infrastructure.db

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Flyway 기동 전략. 계획 §4.2-4의 "schema checksum이 일치할 때만 baseline version 1을
 * 기록한다"를 코드로 옮긴 것이다.
 *
 * ## 왜 `spring.flyway.baseline-on-migrate=true` 를 그냥 켜지 않는가
 *
 * 그 설정은 **Flyway 이력이 없는 모든 비어 있지 않은 스키마**를 아무 확인 없이 baseline
 * 한다. 즉 스키마가 Python 기준선과 다르더라도 "V1은 이미 적용된 것"으로 기록하고
 * V2부터 얹는다. 그러면 V1이 만들었어야 할 테이블·제약이 없는 채로 앱이 뜨고,
 * 문제는 첫 요청이 아니라 **그 테이블을 처음 건드리는 경로**에서 터진다.
 *
 * 그래서 baseline 을 두 조건으로 좁힌다.
 *
 * 1. Flyway 이력이 없고, 애플리케이션 테이블이 이미 있다 (= Alembic이 만든 DB로 보인다)
 * 2. 그 스키마의 지문이 `db/baseline/python-schema-fingerprint.txt` 와 **정확히 같다**
 *
 * 둘 다 맞으면 baseline version 1을 기록하고 V2부터 적용한다. 1은 맞는데 2가 틀리면
 * **기동을 실패시킨다** — 알 수 없는 스키마 위에 마이그레이션을 얹는 것보다 안 뜨는 편이
 * 복구가 쉽다.
 *
 * 빈 DB(테이블 0개)는 baseline 없이 V1부터 정상 적용된다.
 *
 * ## alembic_version 은 건드리지 않는다
 *
 * 계획 §4.2-7. 이 클래스는 그 테이블을 읽지도 쓰지도 않는다. 지문 질의도 명시적으로
 * 제외한다([SchemaFingerprint] 참고). "Alembic이 만든 DB로 보인다"는 판정도
 * `alembic_version` 의 존재가 아니라 **애플리케이션 테이블의 존재**로 한다.
 */
@Configuration(proxyBeanMethods = false)
class FlywayBaselineGuard {
    private val logger = LoggerFactory.getLogger(FlywayBaselineGuard::class.java)

    @Bean
    fun flywayMigrationStrategy(): FlywayMigrationStrategy =
        FlywayMigrationStrategy { flyway ->
            if (needsPythonBaseline(flyway)) {
                verifyMatchesPythonBaseline(flyway)
                logger.info(
                    "기존 Python 스키마를 확인했다. Flyway baseline version=1 을 기록하고 V2부터 적용한다 " +
                        "(alembic_version 은 읽지도 쓰지도 않는다).",
                )
                flyway.baseline()
            }
            val result = flyway.migrate()
            // 로그에 남기는 것은 개수와 버전뿐이다 — 본문·개인정보가 실릴 자리가 아니다.
            // privacy-allow: LOG-BODY @9b55d330 — 적용 개수와 스키마 버전만 보간한다.
            logger.info(
                "Flyway 마이그레이션 완료: applied={} targetSchemaVersion={}",
                result.migrationsExecuted,
                result.targetSchemaVersion ?: "(변경 없음)",
            )
        }

    /** Flyway 이력이 없는데 애플리케이션 테이블은 이미 있는 상태인가. */
    private fun needsPythonBaseline(flyway: Flyway): Boolean {
        val hasFlywayHistory = flyway.info().all().any { it.installedOn != null }
        if (hasFlywayHistory) return false
        return flyway.configuration.dataSource.connection.use { connection ->
            SchemaFingerprint.userTableCount(connection) > 0
        }
    }

    /** 지문이 기준선과 다르면 기동을 실패시킨다. */
    private fun verifyMatchesPythonBaseline(flyway: Flyway) {
        val expected = SchemaFingerprint.expectedPythonBaseline()
        val actual =
            flyway.configuration.dataSource.connection
                .use { SchemaFingerprint.of(it) }
        if (expected == actual) return

        throw IllegalStateException(
            buildString {
                appendLine("기존 스키마가 Python 기준선과 다르다. baseline 을 기록하지 않고 기동을 중단한다.")
                appendLine("(계획 §4.2-4: schema checksum이 일치할 때만 baseline version 1을 기록한다.)")
                appendLine()
                append(SchemaFingerprint.describeDifference(expected, actual))
                appendLine()
                appendLine("확인할 것:")
                appendLine("  - 대상 DB가 Alembic 0006까지 올라와 있는가")
                appendLine("  - 손으로 만든 테이블이 public 스키마에 섞여 있지 않은가")
                appendLine("  - Alembic 리비전이 0006 이후로 늘었다면 V1 과 기준선 지문을 함께 갱신했는가")
            },
        )
    }
}
