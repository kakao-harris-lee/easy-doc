package kr.easydoc.infrastructure

import java.io.File

/** **저장소에 실제로 있는 Flyway 마이그레이션 목록** — 기대값을 손으로 열거하지 않는다. */
object MigrationCatalog {
    private const val SOURCE_ROOT_PROPERTY = "easydoc.kotlin.source.root"

    private const val MIGRATION_PATH = "infrastructure/src/main/resources/db/migration"

    /** `V<번호>__<설명>.sql`. Flyway 의 파일명 규약 그대로다. */
    private val VERSIONED = Regex("""^V(\d+)__.+\.sql$""")

    /** 적용 순서대로의 버전 문자열(`"1"`, `"2"`, …). Flyway 이력 테이블의 `version` 값과 같은 표기다. */
    val versions: List<String> by lazy {
        val directory = directory()
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

    /** Python 스키마를 baseline 한 DB 의 이력 **유형** 목록. */
    val typesAfterPythonBaseline: List<String> get() = listOf("BASELINE") + List(versions.size - 1) { "SQL" }

    /** 마이그레이션 스크립트 **원문**. 스크립트 안의 리터럴을 코드 상수와 대조할 때 쓴다. */
    fun sourceOf(version: String): String {
        val prefix = "V${version}__"
        val file =
            (directory().listFiles() ?: emptyArray())
                .singleOrNull { it.name.startsWith(prefix) && it.name.endsWith(".sql") }
        requireNotNull(file) { "V$version 마이그레이션 파일을 찾지 못했다 — 원문을 대조할 근거가 없다" }
        return file.readText()
    }

    private fun directory(): File {
        val directory = File(sourceRoot(), MIGRATION_PATH)
        require(directory.isDirectory) { "마이그레이션 디렉터리가 없다: $directory — 기대값을 유도할 근거가 없다" }
        return directory
    }

    private fun sourceRoot(): File =
        File(
            System.getProperty(SOURCE_ROOT_PROPERTY)
                ?: error("시스템 속성 $SOURCE_ROOT_PROPERTY 이 없다 — 마이그레이션 디렉터리를 찾을 기준점이 없다"),
        )
}
