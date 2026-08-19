package kr.easydoc.infrastructure

import java.io.File

/**
 * **저장소에 실제로 있는 Flyway 마이그레이션 목록** — 기대값을 손으로 열거하지 않는다.
 *
 * ## 왜 상수를 세지 않고 디렉터리를 읽는가
 *
 * V3 를 추가하기 전, 기동 테스트들은 적용된 버전을 `containsExactly("1", "2")` 로 못박고
 * 있었다 — `FlywayBaselineGuardTest` 다섯 자리와 `ApiStartupWithDatabaseTest` 두 자리,
 * 그리고 baseline 후 유형 목록(`"BASELINE", "SQL"`) 두 자리다. 마이그레이션 하나를 더할
 * 때마다 **관계없는 아홉 자리를 함께 고쳐야** 하고, 그 손질은 기계적이라 다음 사람은
 * 「테스트가 빨개졌으니 숫자를 늘린다」로 지나간다. 그러면 이 단언들이 무엇을 지키는지가
 * 사라진다 — 원래 지키려던 것은 *"V1 은 재적용되지 않고 나머지가 그 위에 얹힌다"* 였지
 * "두 개"가 아니었다.
 *
 * 그래서 기대값을 **디스크에서 유도한다.** 마이그레이션을 더하면 이 목록이 저절로 늘고,
 * 파일을 지우거나 번호를 건너뛰면 그 순간 단언이 어긋난다.
 *
 * ## 왜 클래스패스가 아니라 소스 디렉터리인가
 *
 * 둘 다 되지만 소스 쪽이 **빠진 것을 잡는다**. 리소스 복사가 어긋나 V3 가 jar 에 들어가지
 * 않으면 클래스패스 기준 기대값도 함께 줄어 단언이 통과한다 — 기대와 실제가 같은 곳에서
 * 오면 그 대조는 아무것도 확인하지 않는다. 소스 디렉터리는 빌드 산출물과 독립이다.
 *
 * 경로 기준점은 빌드가 모든 테스트 태스크에 넣어 주는 `easydoc.kotlin.source.root` 다
 * (테스트 작업 디렉터리에서 상대 경로로 거슬러 오르면 모듈이 늘 때 조용히 어긋난다).
 */
object MigrationCatalog {
    private const val SOURCE_ROOT_PROPERTY = "easydoc.kotlin.source.root"

    private const val MIGRATION_PATH = "infrastructure/src/main/resources/db/migration"

    /** `V<번호>__<설명>.sql`. Flyway 의 파일명 규약 그대로다. */
    private val VERSIONED = Regex("""^V(\d+)__.+\.sql$""")

    /** 적용 순서대로의 버전 문자열(`"1"`, `"2"`, …). Flyway 이력 테이블의 `version` 값과 같은 표기다. */
    val versions: List<String> by lazy {
        val directory = File(sourceRoot(), MIGRATION_PATH)
        require(directory.isDirectory) { "마이그레이션 디렉터리가 없다: $directory — 기대값을 유도할 근거가 없다" }
        val numbers =
            (directory.listFiles() ?: emptyArray())
                .mapNotNull { file ->
                    VERSIONED
                        .find(file.name)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt()
                }.sorted()
        require(numbers.isNotEmpty()) { "$directory 에 버전 마이그레이션이 하나도 없다 — 0건을 훑고 통과한다" }
        require(numbers == (1..numbers.size).toList()) {
            "마이그레이션 번호가 1부터 연속이지 않다: $numbers — baseline 기대값을 유도할 수 없다"
        }
        numbers.map { it.toString() }
    }

    /** 마지막 버전. `spring.flyway.baseline-version` 이나 head 를 말할 때 쓴다. */
    val head: String get() = versions.last()

    /**
     * Python 스키마를 baseline 한 DB 의 이력 **유형** 목록.
     *
     * V1 자리는 `BASELINE`(적용하지 않고 「이미 적용됨」으로 기록), 나머지는 실제로 돌아간
     * `SQL` 이다. 이 구분이 곧 "V1 은 재적용되지 않았다"의 증거다.
     */
    val typesAfterPythonBaseline: List<String> get() = listOf("BASELINE") + List(versions.size - 1) { "SQL" }

    private fun sourceRoot(): File =
        File(
            System.getProperty(SOURCE_ROOT_PROPERTY)
                ?: error("시스템 속성 $SOURCE_ROOT_PROPERTY 이 없다 — 마이그레이션 디렉터리를 찾을 기준점이 없다"),
        )
}
