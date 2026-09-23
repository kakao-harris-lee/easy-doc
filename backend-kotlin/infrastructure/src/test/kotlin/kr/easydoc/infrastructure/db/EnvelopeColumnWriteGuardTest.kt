package kr.easydoc.infrastructure.db

import kr.easydoc.core.crypto.EncryptedField
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readText

/** 암호문 열을 쓰는 UPDATE 는 봉투 두 값도 같은 문장에서 써야 한다 — 게이트 27 지적 ②. */
class EnvelopeColumnWriteGuardTest {
    @TempDir
    lateinit var temp: File

    @Test
    @DisplayName("암호문 열을 SET 하는 모든 UPDATE 가 `encryption_scheme`·`key_version` 도 함께 SET 한다")
    fun `암호문 쓰기는 봉투를 함께 쓴다`() {
        val writes = Scanner.scan(sourceRoot())
        Scanner.requireNonEmpty(writes)

        val offenders = writes.filterNot { it.setsEnvelope }

        assertThat(offenders)
            .withFailMessage {
                "암호문 열을 쓰면서 봉투 두 값을 함께 쓰지 않는 UPDATE 가 있다:\n" +
                    offenders.joinToString("\n") { "  - ${it.file}\n      ${it.setClause.trim()}" } +
                    "\n  행당 키 세대가 하나라, 암호문만 바꾸면 「세대는 v1 인데 암호문은 v2」인 행이 남고\n" +
                    "  그 행은 영원히 열리지 않는다(AAD 에 세대가 실린다). 같은 문장에서\n" +
                    "  encryption_scheme 과 key_version 도 SET 하라."
            }.isEmpty()
    }

    @Test
    @DisplayName("**빈 분모는 통과가 아니다** — 대상 SQL 을 하나도 못 찾으면 빨강이다")
    fun `빈 분모는 통과가 아니다`() {
        val writes = Scanner.scan(sourceRoot())

        assertThat(writes.map { it.file }.distinct())
            .describedAs("암호문 열을 쓰는 SQL 이 사는 파일")
            .isEqualTo(EXPECTED_FILES)
        assertThat(writes)
            .describedAs("암호문 쓰기 문장 수 — 늘거나 줄면 그 diff 가 리뷰에 올라가야 한다")
            .hasSize(EXPECTED_STATEMENTS)

        assertThatThrownBy { Scanner.requireNonEmpty(emptyList()) }
            .hasMessageContaining("한 건도 찾지 못했다")
    }

    @Test
    @DisplayName("스캐너 음성 대조 — 봉투를 빠뜨린 SQL 을 심으면 잡고, 지키는 SQL 은 통과시킨다")
    fun `스캐너가 위반과 준수를 가른다`() {
        val violating = probe("violating", "UPDATE $target SET $column = :value WHERE id = :id")
        val compliant =
            probe(
                "compliant",
                "UPDATE $target SET $column = :value, encryption_scheme = :s, key_version = :v WHERE id = :id",
            )

        assertThat(violating.single().setsEnvelope).isFalse()
        assertThat(compliant.single().setsEnvelope).isTrue()
    }

    @Test
    @DisplayName("암호문 열이 `WHERE` 절에만 나오는 UPDATE 는 암호문 쓰기가 아니다 — 과잉 탐지 0")
    fun `조건절의 암호문 열은 쓰기가 아니다`() {
        assertThat(probe("where-only", "UPDATE $target SET status = :status WHERE $column IS NULL")).isEmpty()
    }

    @Test
    @DisplayName("소문자 SQL 과 대상 아닌 테이블을 가른다 — 대소문자로 빠져나갈 수 없다")
    fun `대소문자와 대상 테이블을 가른다`() {
        val lower = probe("lower", "update $target set $column = :v where id = :id")
        val other = probe("other", "UPDATE workspaces SET name = :name WHERE id = :id")

        assertThat(lower.single().setsEnvelope).isFalse()
        assertThat(other).isEmpty()
    }

    @Test
    @DisplayName("**주석에 든 봉투 열은 쓴 것이 아니다** — `--` 로 죽은 대입을 준수로 세지 않는다")
    fun `줄 주석에 든 봉투 대입은 준수가 아니다`() {
        val commented =
            probe(
                "line-commented",
                "UPDATE $target SET $column = :value -- , encryption_scheme = :s, key_version = :v\n" +
                    "WHERE id = :id",
            )

        assertThat(commented.single().setsEnvelope)
            .describedAs(
                "`--` 뒤는 PostgreSQL 이 무시한다. 실제 UPDATE 는 암호문만 바꾸고 세대는 그대로라 " +
                    "그 행은 영원히 열리지 않는데, 가드가 준수로 읽으면 그것을 승인한다 — fail-open 이다",
            ).isFalse()
    }

    @Test
    @DisplayName("**블록 주석에 든 봉투 열도 쓴 것이 아니다** — 중첩까지 끝까지 걷어낸다")
    fun `블록 주석에 든 봉투 대입은 준수가 아니다`() {
        val commented =
            probe(
                "block-commented",
                "UPDATE $target SET $column = :value, /* 보류 /* 사유 */ encryption_scheme = :s, " +
                    "key_version = :v */ status = :status WHERE id = :id",
            )

        assertThat(commented.single().setsEnvelope)
            .describedAs("블록 주석 안도 PostgreSQL 이 무시한다 — 중첩이라 첫 닫힘에서 끊으면 잔여가 남는다")
            .isFalse()
    }

    @Test
    @DisplayName("주석 제거가 **참인 대입을 깨뜨리지 않는다** — 대입 뒤에 붙은 설명 주석은 무해하다")
    fun `살아 있는 봉투 대입 뒤의 주석은 대입을 죽이지 않는다`() {
        val compliant =
            probe(
                "trailing-comment",
                "UPDATE $target SET $column = :value, encryption_scheme = :s, key_version = :v " +
                    "-- 세대를 함께 올린다\nWHERE id = :id",
            )

        assertThat(compliant.single().setsEnvelope)
            .describedAs("주석 제거는 죽은 대입만 지워야 한다 — 살아 있는 대입까지 지우면 과잉 탐지로 뒤집힌다")
            .isTrue()
    }

    @Test
    @DisplayName("**문자열 리터럴에 든 봉투 열은 쓴 것이 아니다** — 게이트 28 P-7 #5 (미선언 fail-open)")
    fun `문자열 리터럴에 든 봉투 대입은 준수가 아니다`() {
        val literal =
            probe(
                "literal",
                "UPDATE $target SET $column = :value, " +
                    "status = 'encryption_scheme = :s, key_version = :v' WHERE id = :id",
            )

        assertThat(literal.single().setsEnvelope)
            .describedAs(
                "작은따옴표 안은 **값**이다 — PostgreSQL 은 봉투 열을 하나도 대입하지 않는다. " +
                    "가드가 준수로 읽으면 세대가 오르지 않은 암호문을 승인하고, AAD 에 세대가 실리므로 " +
                    "그 행은 영원히 열리지 않는다. 이 갈래는 아래 「막지 못하는 것」에 적혀 있지 않았다",
            ).isFalse()
    }

    @Test
    @DisplayName("리터럴 걷어내기가 **참인 대입을 깨뜨리지 않는다** — 리터럴과 살아 있는 대입이 한 문장에 있어도")
    fun `리터럴 뒤의 살아 있는 봉투 대입은 살아 남는다`() {
        val compliant =
            probe(
                "literal-then-envelope",
                "UPDATE $target SET status = 'done', $column = :value, " +
                    "encryption_scheme = :s, key_version = :v WHERE id = :id",
            )

        assertThat(compliant.single().setsEnvelope)
            .describedAs("리터럴 걷어내기가 리터럴 **밖**을 지우면 과잉 탐지로 뒤집힌다 — 이 방향을 고정한다")
            .isTrue()
    }

    @Test
    @DisplayName("리터럴 안의 `''` 를 리터럴 종료로 읽지 않는다 — 짧게 읽으면 뒤가 술어로 되살아난다")
    fun `escaped quote 는 리터럴을 닫지 않는다`() {
        val literal =
            probe(
                "escaped-quote",
                "UPDATE $target SET $column = :value, " +
                    "status = 'a''b encryption_scheme = :s, key_version = :v' WHERE id = :id",
            )

        assertThat(literal.single().setsEnvelope)
            .describedAs("`''` 는 escaped quote 라 리터럴이 계속된다 — 여기서 끊으면 뒤의 텍스트가 대입으로 읽힌다")
            .isFalse()
    }

    @Test
    @DisplayName("SET 절이 없는 UPDATE 는 조용히 넘기지 않고 끊는다")
    fun `해석할 수 없는 UPDATE 는 끊는다`() {
        assertThatThrownBy { probe("broken", "UPDATE $target WHERE id = :id") }
            .hasMessageContaining("SET")
    }

    @Test
    @DisplayName("**upsert(`ON CONFLICT ... DO UPDATE SET`)도 암호문 쓰기로 잡는다** — 코드 리뷰 MEDIUM 지적")
    fun `upsert의 DO UPDATE SET 도 봉인 열 쓰기로 잡는다`() {
        val violating =
            probe(
                "violating-upsert",
                "INSERT INTO $target ($column) VALUES (:v) " +
                    "ON CONFLICT (id) DO UPDATE SET $column = EXCLUDED.$column",
            )
        val compliant =
            probe(
                "compliant-upsert",
                "INSERT INTO $target ($column) VALUES (:v) " +
                    "ON CONFLICT (id) DO UPDATE SET $column = EXCLUDED.$column, " +
                    "encryption_scheme = EXCLUDED.encryption_scheme, key_version = EXCLUDED.key_version",
            )

        assertThat(violating.single().setsEnvelope)
            .describedAs("payload 열만 SET 하고 key_version 이 빠진 upsert 를 준수로 읽으면 그 행은 영원히 열리지 않는다")
            .isFalse()
        assertThat(compliant.single().setsEnvelope).isTrue()
    }

    @Test
    @DisplayName("`DO UPDATE SET` 이 없는 순수 `INSERT` 는 조용히 넘어간다 — 과잉 탐지 0")
    fun `DO UPDATE SET 이 없는 INSERT 는 쓰기로 잡지 않는다`() {
        assertThat(probe("plain-insert", "INSERT INTO $target ($column) VALUES (:v)")).isEmpty()
    }

    /** probe 가 쓰는 테이블·열 이름. 리터럴로 적지 않고 [EncryptedField] 에서 조립한다. */
    private val target: String get() = EncryptedField.CONVERSION_EASY_TEXT.wireName.substringBefore('.')
    private val column: String get() = EncryptedField.CONVERSION_EASY_TEXT.wireName.substringAfter('.')

    /** 합성 소스 하나를 스캐너에 먹인다. */
    private fun probe(
        name: String,
        sql: String,
    ): List<Scanner.CiphertextWrite> {
        val directory = File(temp, name).apply { mkdirs() }
        File(directory, "probe.kt").writeText("package probe\n\nval sql = \"\"\"\n$sql\n\"\"\"\n")
        return Scanner.scan(directory.toPath())
    }

    private fun sourceRoot(): Path {
        val configured =
            System.getProperty(SOURCE_ROOT_PROPERTY)
                ?: error(
                    "시스템 프로퍼티 $SOURCE_ROOT_PROPERTY 가 없다. 이 가드는 소스 전수를 훑어야 " +
                        "의미가 있는데, 경로를 못 찾으면 0개 파일을 훑고 통과한다 — 그것은 통과가 아니라 미검사다.",
                )
        val root = Paths.get(configured)
        check(Files.isDirectory(root)) { "소스 루트가 디렉터리가 아니다: $root" }
        return root
    }

    /** `documents`·`conversions` 를 UPDATE 하는 SQL 중 암호문 열을 SET 하는 것을 뽑는다. */
    private object Scanner {
        /** 암호문 열 한 곳을 쓰는 UPDATE 문 하나. */
        data class CiphertextWrite(
            val file: String,
            val setClause: String,
            val setsEnvelope: Boolean,
        )

        /** 봉투 두 열. 이름이 스키마와 갈리면 `EncryptionSchemeSchemaTest` 가 먼저 빨개진다. */
        private val ENVELOPE_COLUMNS = listOf("encryption_scheme", "key_version")

        /** 감시 대상 테이블·열. 열거가 아니라 [EncryptedField] 에서 파생한다. */
        private val TABLES: Set<String> = EncryptedField.entries.map { it.wireName.substringBefore('.') }.toSet()
        private val CIPHERTEXT_COLUMNS: Set<String> =
            EncryptedField.entries.map { it.wireName.substringAfter('.') }.toSet()

        private val UPDATE_TARGET =
            Regex("""\bUPDATE\s+(${TABLES.joinToString("|")})\b""", RegexOption.IGNORE_CASE)

        /**
         * `INSERT ... ON CONFLICT ... DO UPDATE SET` upsert의 대상 테이블. `UPDATE_TARGET` 은
         * `UPDATE <table>` 로 시작하는 문장만 잡으므로, upsert의 `DO UPDATE SET` 은 별도로
         * 찾아야 한다 — 그 자리도 암호문 열을 쓰는 UPDATE다(코드 리뷰 MEDIUM 지적).
         */
        private val INSERT_TARGET =
            Regex("""\bINSERT\s+INTO\s+(${TABLES.joinToString("|")})\b""", RegexOption.IGNORE_CASE)
        private val ON_CONFLICT_DO_UPDATE_SET = Regex("""\bDO\s+UPDATE\s+SET\b""", RegexOption.IGNORE_CASE)
        private val SET_KEYWORD = Regex("""\bSET\b""", RegexOption.IGNORE_CASE)
        private val WHERE_KEYWORD = Regex("""\bWHERE\b""", RegexOption.IGNORE_CASE)

        /** 문장이 끝났다고 볼 자리. 이 저장소의 SQL 은 전부 Kotlin 문자열 안에 산다. */
        private val STATEMENT_END = Regex("""(;|\"\"\"|\")""")

        fun scan(root: Path): List<CiphertextWrite> =
            kotlinSources(root).flatMap { file ->
                val relative = root.relativize(file).joinToString("/")
                val text = file.readText()
                writesIn(relative, text) + upsertWritesIn(relative, text)
            }

        /**
         * 한 건도 없으면 끊는다. 「위반 0건」과 「대상 0건」은 완전히 다른 상태이고,
         * 후자를 초록으로 두면 이 파일은 아무것도 재지 않으면서 재는 척한다.
         */
        fun requireNonEmpty(writes: List<CiphertextWrite>) {
            check(writes.isNotEmpty()) {
                "암호문 열을 쓰는 UPDATE 를 한 건도 찾지 못했다 — 검사 대상 0건은 통과가 아니라 실패다. " +
                    "스캐너가 소스를 못 읽었거나(경로·확장자), 저장 경로가 통째로 사라졌다."
            }
        }

        private fun writesIn(
            file: String,
            text: String,
        ): List<CiphertextWrite> =
            UPDATE_TARGET
                .findAll(text)
                .mapNotNull { match ->
                    val setClause = setClauseOf(file, text, match.range.last + 1)

                    if (CIPHERTEXT_COLUMNS.none { assignsColumn(setClause, it) }) {
                        null
                    } else {
                        CiphertextWrite(
                            file = file,
                            setClause = setClause,
                            setsEnvelope =
                                LiveSql.of(setClause).let { live ->
                                    ENVELOPE_COLUMNS.all { assignsColumn(live, it) }
                                },
                        )
                    }
                }.toList()

        /** `SET` 과 `WHERE`(또는 문장 끝) 사이. 조건절을 섞으면 `WHERE key_version = …` 이 오탐한다. */
        private fun setClauseOf(
            file: String,
            text: String,
            from: Int,
        ): String {
            val statement = statementBodyFrom(text, from)
            val set =
                SET_KEYWORD.find(statement)
                    ?: error("$file 의 UPDATE 문에서 SET 절을 찾지 못했다 — 해석할 수 없는 문장을 조용히 넘기지 않는다: $statement")
            return trimAtWhere(statement.substring(set.range.last + 1))
        }

        /**
         * `INSERT ... ON CONFLICT ... DO UPDATE SET` upsert에서 암호문 열을 쓰는 것을 뽑는다.
         * 대상 테이블은 `INSERT INTO <table>` 에서 잡고, `SET` 절은 `DO UPDATE SET` 뒤다.
         *
         * `DO UPDATE SET` 이 없는 `INSERT` 는 조용히 건너뛴다(에러로 끊지 않는다) — 순수
         * `INSERT` 는 애초에 "쓰기 UPDATE" 가 아니고, `setClauseOf` 처럼 SET 절이 반드시
         * 있어야 하는 문장이 아니다(기존 관행 — 「저장 쪽은 INSERT 라 SET 절이 없어 이 조사에
         * 잡히지 않는다」).
         */
        private fun upsertWritesIn(
            file: String,
            text: String,
        ): List<CiphertextWrite> =
            INSERT_TARGET
                .findAll(text)
                .mapNotNull { match ->
                    val statement = statementBodyFrom(text, match.range.last + 1)
                    val doUpdateSet = ON_CONFLICT_DO_UPDATE_SET.find(statement) ?: return@mapNotNull null
                    val setClause = trimAtWhere(statement.substring(doUpdateSet.range.last + 1))

                    if (CIPHERTEXT_COLUMNS.none { assignsColumn(setClause, it) }) {
                        null
                    } else {
                        CiphertextWrite(
                            file = file,
                            setClause = setClause,
                            setsEnvelope =
                                LiveSql.of(setClause).let { live ->
                                    ENVELOPE_COLUMNS.all { assignsColumn(live, it) }
                                },
                        )
                    }
                }.toList()

        /** `from` 부터 문장 끝(`STATEMENT_END`, 없으면 텍스트 끝)까지. */
        private fun statementBodyFrom(
            text: String,
            from: Int,
        ): String {
            val end = STATEMENT_END.find(text, from)?.range?.first ?: text.length
            return text.substring(from, end)
        }

        /** `WHERE`(있으면) 앞까지 자른다 — 조건절을 섞으면 `WHERE key_version = …` 이 오탐한다. */
        private fun trimAtWhere(body: String): String =
            WHERE_KEYWORD.find(body)?.let { body.substring(0, it.range.first) } ?: body

        private fun assignsColumn(
            setClause: String,
            column: String,
        ): Boolean = Regex("""(?<![A-Za-z0-9_])$column\s*=""").containsMatchIn(setClause)

        /** 빌드·IDE 산출물(`build/`, `bin/`)은 소스가 아니다 — 넣으면 같은 파일을 두 번 센다. */
        private fun kotlinSources(root: Path): List<Path> =
            Files.walk(root).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.extension == "kt" }
                    .filter {
                        root.relativize(it).none { part -> part.toString() == "build" || part.toString() == "bin" }
                    }.sorted()
                    .toList()
            }
    }

    private companion object {
        const val SOURCE_ROOT_PROPERTY = "easydoc.kotlin.source.root"

        /**
         * 암호문 열을 쓰는 SQL 이 사는 파일. 면제 목록이 아니라 인구조사다 — 여기 없는
         * 파일이 봐주는 것이 아니라, 목록이 바뀌면 그 diff 가 리뷰에 올라온다.
         */
        val EXPECTED_FILES =
            listOf(
                "api/src/test/kotlin/kr/easydoc/api/AdminReachTest.kt",
                "api/src/test/kotlin/kr/easydoc/api/ConversionExportReachTest.kt",
                // 새 변환 하위 라우트의 소유권 은닉 실측도 완료 상태를 SQL 로 심는다 — 그
                // 문장이 봉투를 함께 쓴다.
                "api/src/test/kotlin/kr/easydoc/api/ConversionFeatureRouteReachTest.kt",
                // 피드백 실경로 테스트도 완료 상태를 SQL 로 심는다 — 그 문장이 봉투를 함께 쓴다.
                "api/src/test/kotlin/kr/easydoc/api/ConversionFeedbackReachTest.kt",
                "api/src/test/kotlin/kr/easydoc/api/ConversionReadReachTest.kt",
                "api/src/test/kotlin/kr/easydoc/api/ConversionReviewReachTest.kt",
                // R4 표 관계 계약 테스트가 document_table_structures의 payload_encrypted를 원시
                // UPDATE로 심는다(encryption_scheme·key_version도 같은 문장에서 쓴다).
                "api/src/test/kotlin/kr/easydoc/api/DocumentSourceTableRelationsReachTest.kt",
                // 보존 만료 창의 실경로 테스트도 완료 상태를 SQL 로 심는다 — 그 문장이 봉투를
                // 함께 쓴다(`MARK_DONE_SQL`). 만료된 변환이 조회·내보내기·검수 저장에서
                // 404 인지를 재려면 먼저 「내줄 것이 실재하는」 행을 세워야 한다.
                "api/src/test/kotlin/kr/easydoc/api/RetentionReadGuardReachTest.kt",
                // R2 후보/안내문 회전 두 문장. 각 표의 payload와 봉투 세대는 함께 바꾼다.
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/actionguide/" +
                    "ActionGuideContentKeyRotation.kt",
                // R2 안내문 CAS 갱신 문장도 payload와 봉투를 한 번에 쓴다.
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/actionguide/" +
                    "JdbcActionGuideContentRepository.kt",
                // 피드백 의견의 회전 UPDATE 하나와, 피드백 제출/재제출 upsert
                // (`ON CONFLICT (conversion_id) DO UPDATE SET`) 하나 — 문장 둘이다.
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/document/" +
                    "JdbcConversionFeedbackRepository.kt",
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/document/JdbcConversionRepository.kt",
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/document/JdbcConversionWorkStore.kt",
                // 업로드 원본의 회전 UPDATE (V3). 봉인 열이 하나라 문장도 하나다 — 저장 쪽은
                // INSERT 라 SET 절이 없어 이 조사에 잡히지 않는다.
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/document/" +
                    "JdbcDocumentOriginalRepository.kt",
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/document/JdbcDocumentRepository.kt",
                // 피드백 자유 의견 파기 배치의 UPDATE (2026-09-04, backlog §1.1 「conversion_feedback
                // 의 삭제 경로」 판단 ⑵) — 봉투 세 열을 함께 NULL 로 만든다.
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/document/JdbcFeedbackCommentPurge.kt",
                // R1 검수 payload의 일반 갱신과 키 회전 UPDATE. 인용·사유·상태를 한 봉투로 쓴다.
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/document/" +
                    "JdbcReviewAssessmentRepository.kt",
                // R5 과거 본문 스냅샷의 키 회전 UPDATE (`rewriteSnapshotEnvelope`). payload와
                // 봉투 두 값을 같은 문장에서 쓴다.
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/document/" +
                    "JdbcReviewHistoryRepository.kt",
                // R4 표 구조의 키 회전 UPDATE. payload와 봉투 두 값을 같은 문장에서 쓴다.
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/document/" +
                    "TableStructureKeyRotation.kt",
                // ER-16 그림 배치의 키 회전 UPDATE(`rewriteEnvelope`) 하나와, 저장(PUT) upsert
                // (`replaceOwned`, `ON CONFLICT (conversion_id) DO UPDATE SET`) 하나 — 문장 둘이다.
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/illustration/" +
                    "JdbcIllustrationPlacementRepository.kt",
                // 정기결제 세션·주문 upsert 둘 — `saveSession`(toss_billing_sessions)·
                // `saveOrder`(toss_billing_orders) 모두 `ON CONFLICT ... DO UPDATE SET`으로
                // payload_encrypted와 봉투 두 값을 같은 문장에서 쓴다(코드 리뷰 MEDIUM 지적으로
                // upsert 탐지를 더하며 새로 잡힌 파일 — 세 번째 `ON CONFLICT (workspace_id,id)
                // DO UPDATE`는 `status`·`refunded_amount`만 SET 해 암호문 열이 없으므로 잡히지
                // 않는다).
                "infrastructure/src/main/kotlin/kr/easydoc/infrastructure/subscription/" +
                    "JdbcTossBillingStore.kt",
                // 피드백 구버전 쓰기 호환 테스트가 옛 upsert SQL을 그대로 심는다 — 같은
                // `ON CONFLICT (conversion_id) DO UPDATE SET`이라 봉투를 함께 쓴다.
                "infrastructure/src/test/kotlin/kr/easydoc/infrastructure/document/" +
                    "ConversionFeedbackStorageTest.kt",
                "infrastructure/src/test/kotlin/kr/easydoc/infrastructure/document/ConversionReviewStorageTest.kt",
                "infrastructure/src/test/kotlin/kr/easydoc/infrastructure/document/EnvelopeRotationConcurrencyTest.kt",
                // 회전 배치 통합 테스트도 옛 세대 변환을 완료 상태로 심는다 — 그 문장이 봉투를
                // 함께 쓴다(`completeConversion`).
                "infrastructure/src/test/kotlin/kr/easydoc/infrastructure/document/KeyRotationBatchTest.kt",
                "infrastructure/src/test/kotlin/kr/easydoc/infrastructure/subscription/TossStoreTest.kt",
            )

        /**
         * 문장 수. 파일 목록만 보면 같은 파일 안에 한 문장을 더 넣는 편집이 조용하다.
         *
         * 14 → 16: §6.5 원본 서식 보존이 「저장된 원본을 열 수 없다」 갈래를 실측하면서
         * **두 파일이 각각** 저장된 원본 바이트를 열리지 않는 값으로 갈아 끼우는 UPDATE 를
         * 들였다(`BREAK_ORIGINAL_SQL`) — `ConversionReadReachTest` 는 그 갈래의 조회 판정이
         * `failed` 임을, `ConversionExportReachTest` 는 같은 갈래의 내려받기가 500 이지
         * 텍스트 대체본이 아님을 잰다. 두 문장 모두 봉투 두 값을 함께 SET 한다.
         *
         * 16 → 17: 보존 만료 창을 재는 `RetentionReadGuardReachTest` 의 `MARK_DONE_SQL` 이다.
         * 만료 뒤 404 를 재려면 만료 **전에** 내줄 것이 실재해야 하므로, 결과 열과 봉투를
         * 함께 채우는 문장 하나가 그 파일에 선다.
         *
         * 17 → 18: `KeyRotationBatchTest`(backlog §1.1 「키 회전에 운영 진입점이 없음」)의
         * `completeConversion` 이 옛 세대 변환을 완료 상태로 심는다 — 결과 열과 봉투를 함께
         * 채우는 문장 하나다.
         *
         * 18 → 19: 피드백 자유 의견 파기 배치(2026-09-04, backlog §1.1 「conversion_feedback 의
         * 삭제 경로」 판단 ⑵)의 `JdbcFeedbackCommentPurge` UPDATE 다 — 봉인 열이 하나라 문장도
         * 하나이고, `comment_encrypted`·`encryption_scheme`·`key_version` 셋을 같은 문장에서
         * 함께 `NULL`로 만든다(값을 대입하는 대신 비운다는 점만 회전 UPDATE 와 다르다).
         *
         * 19 → 20: `KeyRotationBatchTest` 의 CONTENDED 재현 케이스(독립 코드 리뷰 PR #15 LOW
         * 지적)가 더한 `rewriteEditedTextConcurrently` 다. 회전의 SELECT 와 UPDATE 사이에
         * 끼워 넣는 동시 쓰기이고, `edited_text_encrypted` 와 봉투 두 값을 같은 문장에서
         * 함께 쓴다 — 옛 세대 그대로 쓰는 실제 동시 쓰기를 흉내 내므로 이 저장소의 다른 쓰기와
         * 같은 불변식을 진다. `ReviewedBody` 를 만들 수 있는 자리가 아니라(privacy-gate X-5)
         * 제품 검수 저장 경로 대신 이 원시 SQL 을 쓴다 — 사유는 그 함수 KDoc.
         *
         * 20 → 22: R4/R5 키 회전 두 문장. `TableStructureKeyRotation.rewrite`가
         * `document_table_structures`의 payload와 봉투를, `JdbcReviewHistoryRepository
         * .rewriteSnapshotEnvelope`가 `review_snapshots`의 payload와 봉투를 각각 같은
         * 문장에서 함께 쓴다.
         *
         * 30 → 31 은 R4 표 관계 계약 테스트(`DocumentSourceTableRelationsReachTest`)가
         * 표 관계 payload를 심는 UPDATE다.
         *
         * 31 → 32 는 ER-16 그림 배치(`JdbcIllustrationPlacementRepository.rewriteEnvelope`)의
         * 키 회전 UPDATE다.
         *
         * 32 → 36 은 코드 리뷰 MEDIUM 지적으로 `INSERT ... ON CONFLICT ... DO UPDATE SET`
         * upsert도 암호문 쓰기로 잡도록 스캐너를 확장하면서(`upsertWritesIn`) 새로 잡힌 문장
         * 넷이다 — 전부 이미 봉투를 함께 쓰고 있었다(위반 0건, 이 가드가 이전까지 못 보고
         * 있었을 뿐이다):
         * ⑴ `JdbcIllustrationPlacementRepository.replaceOwned`(illustration_placements) —
         *   `UPDATE <table>` 로 시작하지 않아 옛 스캐너가 놓쳤다.
         * ⑵ `JdbcConversionFeedbackRepository`의 제출/재제출 upsert(conversion_feedback).
         * ⑶⑷ `JdbcTossBillingStore.saveSession`(toss_billing_sessions)·`saveOrder`
         *   (toss_billing_orders) — 이 파일은 옛 스캐너로는 한 번도 잡히지 않아 새로
         *   `EXPECTED_FILES`에 들어왔다(세 번째 `ON CONFLICT` 문장은 암호문 열을 SET 하지
         *   않아 여전히 안 잡힌다).
         * ⑸ `ConversionFeedbackStorageTest`의 구버전 쓰기 호환 테스트가 심는 옛 upsert SQL —
         *   같은 이유로 새로 `EXPECTED_FILES`에 들어왔다.
         *
         * 37 → 38 은 `ConversionFeatureRouteReachTest` 가 새 변환 하위 라우트의 소유권 은닉을
         * 실제 스택에서 재려고 심는 완료 상태 문장 하나다 — 「남의 것」팔에 내줄 것이
         * 실재해야 판정이 공회전하지 않는다. 결과 열과 봉투 두 값을 같은 문장에서 쓴다.
         */
        const val EXPECTED_STATEMENTS = 38
    }
}
