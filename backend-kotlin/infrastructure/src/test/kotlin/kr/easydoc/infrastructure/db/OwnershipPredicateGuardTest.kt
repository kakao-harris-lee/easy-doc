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

/**
 * **문서·변환 행에 닿는 제품 SQL 중 소유 매개변수가 걸리지 않은 문장을 전수에서 뽑는다** —
 * 게이트 27 M-3 (`privacy-gate` 판정 §4.2 후보 A).
 *
 * ## 왜 산문으로는 부족했나
 *
 * `DocumentPorts` 클래스 KDoc 이 *"읽기 메서드가 전부 `ownerId` 를 받는다"* 고 적고 있었는데
 * **거짓이었다** — 잠금 읽기 둘이 받지 않고, 같은 파일이 아래에서 그 예외를 적어 자기와
 * 모순이었다. 그리고 그 자리를 지키는 실행 장치는 **0 개**였다: `OWNERSHIP-403` 스캐너 규칙은
 * **403 토큰**을 찾지 소유 술어의 부재를 찾지 않아 세 파일 어느 줄도 적중시키지 못했고
 * (실측), ArchUnit 도 포트 시그니처 단언도 없었다.
 *
 * 프로젝트 규칙 4 가 정한 처분은 「선언을 다듬는 것」이 아니라 **탐지형으로 갈아타는 것**이다.
 * 형제 장치 [EnvelopeColumnWriteGuardTest] 가 같은 파일 이웃 자리에서 이미 한 번 집행한
 * 처분이고(계획 §9.2-ter D-r), 이 파일은 그 형태를 그대로 따른다.
 *
 * ## 분모를 열거하지 않는다
 *
 * 감시 테이블을 손으로 적지 않는다 — [EncryptedField] 의 `wireName`(`테이블.컬럼`) 앞부분에서
 * **파생**한다. 형제 장치와 같은 enum 을 쓰므로 이름의 근거가 한 곳이다.
 *
 * ## 빈 분모는 통과가 아니다
 *
 * 대상 문장을 하나도 못 찾으면 **빨강**이다(규칙 4 ⑶). [Scanner.requireNonEmpty] 가 그
 * 판정이고, [`빈 분모는 통과가 아니다`] 가 그것을 실행으로 확인한다 — 합성 인자로도,
 * 비어 있는 디렉터리를 실제로 훑는 것으로도.
 *
 * ## 핀은 **면제 목록이 아니라 인구조사**다
 *
 * `privacy-gate` §4.4 가 그은 경계선을 지킨다.
 *
 * - **패턴으로 맞추는 예외**(이름 접두·경로 표식·정규식) = 은폐형. 새 위반이 조용히 그 안에
 *   태어난다. 그래서 `lock` 접두를 제외하는 규칙도, 호출 지점 억제 표기도 **쓰지 않는다.**
 * - **정확 열거를 핀으로 고정**하고 늘거나 줄면 실패시키는 것 = 탐지형. 목록이 커지는 것
 *   자체가 diff 로 드러나 리뷰에 올라온다.
 *
 * 오늘의 잠금 읽기 둘과 회전 쓰기 둘은 **이름이 아니라** [EXPECTED_UNGUARDED] 의 항목으로
 * 다룬다. 목록은 순서 있는 **리스트**라 같은 파일에 같은 모양을 하나 더 넣는 편집도 드러난다.
 *
 * ## 이 장치가 **막지 못하는 것** (정직하게 적는다 — 적지 않으면 이것이 다음 거짓 전칭이 된다)
 *
 * - **문자열을 조립해 만든 SQL.** 테이블 이름을 못 읽으므로 대상에서 빠진다. 형제 장치가 같은
 *   한계를 이미 문서화했고, **이 파일의 probe 가 바로 그 형태**라 probe 가 실제 스캔의 분모를
 *   오염시키지 않는다 — 우회 통로이자 이 파일이 쓰는 통로다.
 * - **소유 매개변수가 무엇에 결속되는지는 증명하지 않는다.** 문장 안에 소유 열과 매개변수를
 *   `=` 로 묶은 자리가 **어디든** 있으면 통과다. 그것이 목표 테이블의 행을 실제로 좁히는지는
 *   판정하지 않는다 — 작업 공간 목록 질의가 문서를 **작업 공간 소유로 간접 좁히는** 형태가
 *   그 예다. 게이트 28 codex F-2 가 이 한계의 갈래 넷을 실제 입력으로 제시했고, 그 넷이
 *   **이 항목이 이미 선언하고 있던 범위**다(리더 판정 P-7 — 그래서 Major 이고 차단이 아니다).
 *   갈래를 여기 적어 둔다: ⑴ `WHERE` 밖(`SELECT` 목록·`ORDER BY`·`UNION` 의 한쪽 갈래)에
 *   놓인 술어, ⑵ 별칭 재정의로 소유 열이 **다른 테이블**을 가리키는 형태, ⑶ `OR TRUE` 로
 *   무력화된 술어, ⑷ `CROSS JOIN` 등으로 결속 대상이 목표 테이블이 아닌 형태.
 *   **⑸ 문자열 리터럴 안의 텍스트는 이 목록에 없었고, 2026-08-21 에 판정 쪽에서 닫혔다**
 *   ([LiveSql.redactLiterals] · `문자열 리터럴에 든 소유 술어는 방어가 아니다`).
 *   ⑴~⑷ 를 닫으려면 훑개가 아니라 파서가 필요하고, 그 판단은 이 단위 밖이다.
 * - **`=` 이 아닌 비교 형태**(`IN`·`ANY`)는 소유 술어 없음으로 읽는다. 과잉 탐지 방향이라
 *   fail-closed 다.
 * - **분모가 제품 소스(`src/main`)다.** 테스트 SQL 은 요청을 처리할 수 없어 세지 않는다.
 *   이 제외가 조용히 넓어지는 형태(모듈이 통째로 빠지는 것)는
 *   [`선언된 모듈이 전부 분모에 들어 있다`] 가 잡는다.
 * - **한 문자열 리터럴에 문장을 여럿 담으면 한 문장으로 읽는다.**
 * - **이 파일 자신의 삭제.** 최종 방어선은 `tests/test_kotlin_gate_reach.py` 의 선언 대조다.
 *
 * ## 주석은 **방향에 따라 다르게** 다룬다
 *
 * 하나의 정규화를 두 방향에 같이 쓰면 한쪽은 반드시 뒤집힌다. 실제로 뒤집혀 있었다 —
 * 초판은 **원시 청크에 소유 술어 정규식을 그대로** 걸어서
 * `WHERE c.id = :id -- AND d.user_id = :ownerId` 를 **방어가 있는 것으로 통과**시켰다(실측).
 * PostgreSQL 은 `--` 뒤와 `/* */` 안을 무시하므로 실제 질의에는 소유 조건이 없다. 소유권
 * 우회를 잡으라고 세운 장치가 **소유권 우회를 승인**한 것이다 — 프로젝트 규칙 2 가 겨누는
 * 「잘못된 근거를 만드는 도구」다.
 *
 * **⑴ 문장 발견(분모)** — 이 문장이 검사 대상인가. 주석·문자열 리터럴을 **그대로 둔다**:
 * Kotlin 주석·KDoc 이 품은 SQL 도 센다. 분모를 넓히는 쪽이라 **fail-closed** 다. 문장 전체를
 * 토큰화하는 렉서를 쓰지 않는 이유도 여기다 — 렉서 자신이 조용히 놓치는 표면이 된다.
 *
 * **⑵ 소유 술어 판정** — 이 문장에 방어가 **있는가**. **주석과 문자열 리터럴을 걷어낸 뒤**
 * 판정한다 ([LiveSql.of]). 죽은 술어를 방어로 세면 **fail-open** 이다 — 그 자리가 두 번
 * 뚫렸다: 주석에 든 술어(`d1ce78e` 가 수정)와 **문자열 리터럴에 든 술어**
 * (게이트 28 P-7 #3 의 미선언 갈래). 걷어내면 fail-closed — 술어가 사라진 문장은
 * 「소유 술어 없음」으로 핀에 올라온다.
 *
 * **이 둘을 하나로 합치지 마라.** ⑴ 의 근거(**분모는 넓을수록 안전하다**)를 ⑵ 에 옮겨
 * 적는 순간 이 결함이 그대로 되살아난다 — ⑵ 에서는 넓은 쪽이 **위험한 쪽**이다.
 *
 * 걷어내기는 살아 있는 술어를 깨뜨리지 않는다:
 * `… AND d.user_id = :ownerId -- 소유자로 좁힌다` 는 여전히 통과다
 * ([`살아 있는 술어 뒤의 주석은 술어를 죽이지 않는다`] 가 그 방향을 고정한다).
 *
 * ## 이 갈라치기가 **남기는 것** (정직하게 적는다)
 *
 * Kotlin 블록 주석·KDoc 은 문법이 `/* */` 라 걷어내기가 **함께 지운다.** 그래서 KDoc 이
 * 품은 SQL 은 **분모에는 남고 술어는 잃는다** — 「소유 술어 없음」으로 읽혀 핀이 늘어난다.
 * 과잉 탐지 방향이라 fail-closed 이고, 늘어난 핀은 diff 로 리뷰에 올라온다.
 * 반대로 Kotlin `//` 줄 주석은 **지우지 않는다** — SQL 주석이 아니기 때문이다. 그 안의
 * SQL 은 분모에도 들고 술어도 산 채로 읽힌다. `//` 까지 지우면 문자열 리터럴 안의 URL 이
 * 실제 술어를 잘라내므로, 이 비대칭은 알고 남긴 것이다.
 */
class OwnershipPredicateGuardTest {
    @TempDir
    lateinit var temp: File

    @Test
    @DisplayName("문서·변환에 닿는 제품 SQL 중 소유 술어가 없는 것은 정확 열거 핀 안에만 있다")
    fun `소유 술어 없는 질의는 핀 안에만 있다`() {
        val accesses = Scanner.scan(sourceRoot())
        Scanner.requireNonEmpty(accesses)

        val unguarded = accesses.filterNot { it.hasOwnerPredicate }

        assertThat(unguarded.map { it.pin })
            .withFailMessage { unguardedFailure(unguarded) }
            .isEqualTo(EXPECTED_UNGUARDED)
    }

    @Test
    @DisplayName("**빈 분모는 통과가 아니다** — 대상 문장을 하나도 못 찾으면 빨강이다")
    fun `빈 분모는 통과가 아니다`() {
        // 실제 스캔의 분모부터 확인한다. 여기가 0이면 위 케이스는 「위반 0건」으로 초록인데
        // 실제로는 아무것도 재지 않은 것이다.
        assertThat(Scanner.scan(sourceRoot()).map { it.pin })
            .describedAs("문서·변환에 닿는 제품 SQL 인구조사 — 늘거나 줄면 그 diff 가 리뷰에 올라가야 한다")
            .isEqualTo(EXPECTED_STATEMENTS)

        // 그리고 「0건이면 실패한다」는 판정 자체를 두 방향으로 확인한다.
        assertThatThrownBy { Scanner.requireNonEmpty(emptyList()) }
            .hasMessageContaining("한 건도 찾지 못했다")
        assertThatThrownBy { Scanner.requireNonEmpty(Scanner.scan(emptyRoot())) }
            .hasMessageContaining("한 건도 찾지 못했다")
    }

    @Test
    @DisplayName("`settings.gradle.kts` 가 선언한 모듈이 전부 분모에 기여한다 — 모듈이 통째로 빠지지 않는다")
    fun `선언된 모듈이 전부 분모에 들어 있다`() {
        val root = sourceRoot()
        val scanned = Scanner.productSources(root).map { root.relativize(it).first().toString() }.toSet()

        assertThat(Scanner.declaredModules(root))
            .describedAs("선언 모듈 전부가 제품 소스 스캔에 기여해야 한다 — 하나가 빠지면 그 모듈의 SQL 이 통째로 탐지 밖이다")
            .isNotEmpty()
            .allSatisfy { module -> assertThat(scanned).contains(module) }
    }

    @Test
    @DisplayName("스캐너 음성 대조 — 소유 술어 없는 질의는 잡고, 조인으로 소유를 좁힌 질의는 통과시킨다")
    fun `스캐너가 소유 술어의 유무를 가른다`() {
        val unguarded = probe("unguarded", "SELECT $column FROM $conversions WHERE id = :id")
        val guarded =
            probe(
                "guarded",
                "SELECT c.$column FROM $conversions c JOIN $documents d ON d.id = c.document_id " +
                    "WHERE c.id = :id AND d.user_id = :ownerId",
            )

        assertThat(unguarded.single().hasOwnerPredicate).isFalse()
        assertThat(guarded.single().hasOwnerPredicate).isTrue()
    }

    @Test
    @DisplayName("**주석에 든 소유 술어는 방어가 아니다** — `--` 로 죽은 술어를 방어로 세지 않는다")
    fun `줄 주석에 든 소유 술어는 방어가 아니다`() {
        val commented =
            probe(
                "line-commented",
                "SELECT c.$column FROM $conversions c JOIN $documents d ON d.id = c.document_id " +
                    "WHERE c.id = :id -- AND d.user_id = :ownerId",
            )

        assertThat(commented.single().hasOwnerPredicate)
            .describedAs(
                "`--` 뒤는 PostgreSQL 이 무시한다. 실제 질의에 소유 조건이 없는데 방어가 있다고 읽으면 " +
                    "이 가드가 소유권 우회를 승인한다 — fail-open 이다",
            ).isFalse()
    }

    @Test
    @DisplayName("**블록 주석에 든 소유 술어도 방어가 아니다** — `/* */` 안은 질의가 아니다")
    fun `블록 주석에 든 소유 술어는 방어가 아니다`() {
        val commented =
            probe(
                "block-commented",
                "SELECT c.$column FROM $conversions c JOIN $documents d ON d.id = c.document_id " +
                    "WHERE c.id = :id /* AND d.user_id = :ownerId */",
            )

        assertThat(commented.single().hasOwnerPredicate)
            .describedAs("`/* */` 안도 PostgreSQL 이 무시한다 — 줄 주석과 같은 fail-open 이다")
            .isFalse()
    }

    @Test
    @DisplayName("**중첩 블록 주석**도 끝까지 걷어낸다 — 첫 닫힘에서 끊으면 죽은 술어가 되살아난다")
    fun `중첩 블록 주석에 든 소유 술어는 방어가 아니다`() {
        val nested =
            probe(
                "nested-comment",
                "SELECT c.$column FROM $conversions c JOIN $documents d ON d.id = c.document_id " +
                    "WHERE c.id = :id /* 보류 /* 사유 */ AND d.user_id = :ownerId */",
            )

        assertThat(nested.single().hasOwnerPredicate)
            .describedAs(
                "PostgreSQL 블록 주석은 중첩한다 — 첫 닫힘에서 끊는 비탐욕 정규식이라면 " +
                    "`AND d.user_id = :ownerId` 를 주석 밖으로 남겨 fail-open 이 된다",
            ).isFalse()
    }

    @Test
    @DisplayName("주석 제거가 **참인 술어를 깨뜨리지 않는다** — 술어 뒤에 붙은 설명 주석은 무해하다")
    fun `살아 있는 술어 뒤의 주석은 술어를 죽이지 않는다`() {
        val guarded =
            probe(
                "trailing-comment",
                "SELECT c.$column FROM $conversions c JOIN $documents d ON d.id = c.document_id " +
                    "WHERE c.id = :id AND d.user_id = :ownerId -- 소유자로 좁힌다",
            )

        assertThat(guarded.single().hasOwnerPredicate)
            .describedAs("주석 제거는 죽은 술어만 지워야 한다 — 살아 있는 술어까지 지우면 과잉 탐지로 뒤집힌다")
            .isTrue()
    }

    @Test
    @DisplayName("**문자열 리터럴에 든 소유 술어는 방어가 아니다** — 게이트 28 P-7 #3 의 미선언 갈래")
    fun `문자열 리터럴에 든 소유 술어는 방어가 아니다`() {
        val literal =
            probe(
                "literal",
                "SELECT 'user_id = :ownerId' AS note, c.$column FROM $conversions c WHERE c.id = :id",
            )

        assertThat(literal.single().hasOwnerPredicate)
            .describedAs(
                "작은따옴표 안은 **값**이다 — 실제 질의에 소유 조건이 없다. 반례 5종 중 넷은 이미 " +
                    "「막지 못하는 것」에 선언돼 있었고 이 갈래만 미선언이었다(리더 판정 P-7)",
            ).isFalse()
    }

    @Test
    @DisplayName("리터럴 걷어내기가 **참인 술어를 깨뜨리지 않는다** — 리터럴과 술어가 한 문장에 있어도")
    fun `리터럴 뒤의 살아 있는 술어는 살아 남는다`() {
        val guarded =
            probe(
                "literal-then-predicate",
                "SELECT c.$column FROM $conversions c JOIN $documents d ON d.id = c.document_id " +
                    "WHERE c.status = 'done' AND d.user_id = :ownerId",
            )

        assertThat(guarded.single().hasOwnerPredicate)
            .describedAs("리터럴 걷어내기가 리터럴 **밖**을 지우면 과잉 탐지로 뒤집힌다 — 이 방향을 고정한다")
            .isTrue()
    }

    @Test
    @DisplayName("소유 열을 매개변수가 아닌 것과 묶은 비교는 소유 술어가 아니다 — 과소 탐지 0")
    fun `컬럼끼리의 비교는 소유 술어가 아니다`() {
        val joined = probe("joined", "SELECT 1 FROM $documents d JOIN owners o ON d.user_id = o.user_id")

        assertThat(joined.single().hasOwnerPredicate).isFalse()
    }

    @Test
    @DisplayName("소문자 SQL 과 감시 대상 아닌 테이블을 가른다 — 대소문자로 빠져나갈 수 없다")
    fun `대소문자와 대상 테이블을 가른다`() {
        val lower = probe("lower", "select id from $documents where id = :id")
        val other = probe("other", "SELECT id FROM workspaces WHERE id = :id")

        assertThat(lower.single().hasOwnerPredicate).isFalse()
        assertThat(other).isEmpty()
    }

    @Test
    @DisplayName("SQL 동사를 찾을 수 없는 문장은 조용히 넘기지 않고 끊는다")
    fun `해석할 수 없는 문장은 끊는다`() {
        assertThatThrownBy { probe("verbless", "잘린 조각 FROM $documents WHERE id = :id") }
            .hasMessageContaining("SQL 동사")
    }

    /**
     * probe 가 쓰는 테이블·열 이름. **리터럴로 적지 않고 [EncryptedField] 에서 조립한다.**
     *
     * 형제 장치와 같은 이유 둘이다. ⑴ 이름의 근거를 한 곳(enum)에 둔다. ⑵ 조립한 문자열은
     * 스캐너가 읽지 못하므로 **이 파일의 probe 가 실제 스캔의 분모를 오염시키지 않는다.**
     */
    private val documents: String get() = EncryptedField.DOCUMENT_SOURCE_TEXT.wireName.substringBefore('.')
    private val conversions: String get() = EncryptedField.CONVERSION_EASY_TEXT.wireName.substringBefore('.')
    private val column: String get() = EncryptedField.CONVERSION_EASY_TEXT.wireName.substringAfter('.')

    /**
     * 합성 소스 하나를 스캐너에 먹인다.
     *
     * **probe 마다 독립 디렉터리**를 준다 — 같은 디렉터리에 쌓으면 뒤 probe 의 결과에 앞
     * probe 의 문장이 섞인다(형제 장치가 실측으로 밟았다).
     *
     * 파일을 `src/main` 아래에 놓는 이유는 그것이 **분모의 조건 자체**이기 때문이다. 조건을
     * 우회한 자리에 probe 를 두면 probe 가 실제 스캔과 다른 경로를 재게 된다.
     */
    private fun probe(
        name: String,
        sql: String,
    ): List<Scanner.TableAccess> {
        val directory = File(temp, "$name/src/main/kotlin").apply { mkdirs() }
        File(directory, "Probe.kt").writeText("package probe\n\nval sql = \"\"\"\n$sql\n\"\"\"\n")
        return Scanner.scan(File(temp, name).toPath())
    }

    /**
     * 아무 소스도 없는 루트. 「분모 0」을 합성 인자가 아니라 **실제 훑기**로 만든다.
     *
     * `src/main` 뼈대까지 만들어 둔다 — 디렉터리가 아예 없어서 0건인 것과, 훑을 자리는
     * 있는데 대상이 0건인 것은 다른 상태이고 재야 하는 것은 후자다.
     */
    private fun emptyRoot(): Path {
        File(temp, "empty/src/main/kotlin").mkdirs()
        return File(temp, "empty").toPath()
    }

    private fun unguardedFailure(unguarded: List<Scanner.TableAccess>): String =
        "문서·변환 행에 닿는 제품 SQL 중 소유 매개변수가 걸리지 않은 문장 목록이 핀과 다르다.\n" +
            unguarded.joinToString("\n") { "  - ${it.pin}\n      ${it.statement}" } +
            "\n  기대(정확 열거 핀):\n" +
            EXPECTED_UNGUARDED.joinToString("\n") { "  - $it" } +
            "\n  늘었다면 그 문장이 남의 행을 내줄 수 있는지 먼저 판정하라 — 사용자 요청 경로라면\n" +
            "  소유 조건을 SQL `WHERE` 안에 넣어야 한다(읽고 나서 비교하는 형태는 금지다).\n" +
            "  줄었다면 지켜지던 질의가 사라진 것이니 그것도 diff 로 설명돼야 한다.\n" +
            "  핀은 면제 목록이 아니라 인구조사다 — 이름 패턴으로 예외를 만들지 마라."

    private fun sourceRoot(): Path {
        val configured =
            System.getProperty(SOURCE_ROOT_PROPERTY)
                ?: error(
                    "시스템 프로퍼티 $SOURCE_ROOT_PROPERTY 가 없다. 이 가드는 제품 소스 전수를 훑어야 " +
                        "의미가 있는데, 경로를 못 찾으면 0개 파일을 훑고 통과한다 — 그것은 통과가 아니라 미검사다.",
                )
        val root = Paths.get(configured)
        check(Files.isDirectory(root)) { "소스 루트가 디렉터리가 아니다: $root" }
        return root
    }

    /**
     * 감시 테이블에 닿는 SQL 문장을 **제품 소스**에서 뽑고, 소유 매개변수의 유무를 판정한다.
     *
     * 파서가 아니라 훑개다. 파서를 들이지 않은 사유는 계획 §2.1 에 있다 — 어려운 부분(Kotlin
     * 소스에서 SQL 조각을 잘라 내는 일)을 대신해 주지 않고, 파싱 실패가 **새 무성 표면**이 된다.
     */
    private object Scanner {
        /** 감시 테이블에 닿는 문장 하나. */
        data class TableAccess(
            val file: String,
            val verb: String,
            val tables: List<String>,
            val statement: String,
            val hasOwnerPredicate: Boolean,
        ) {
            /**
             * 핀에 적히는 표기.
             *
             * 테이블 이름을 대괄호로 감싸는 것은 **다른 탐지기와의 간섭을 피하려는 것**이다 —
             * 형제 장치가 `표 이름이 곧바로 뒤따르는 갱신문`을 소스 전수에서 찾으므로, 이 상수
             * 목록이 그 모양이면 형제 장치가 자기 분모로 잘못 세고 해석에 실패한다(실측 아님 —
             * 그 장치의 판정 규칙을 읽고 미리 피했다).
             */
            val pin: String get() = "$file | $verb [${tables.joinToString(", ")}]"
        }

        /** 감시 대상 테이블. **열거가 아니라 [EncryptedField] 에서 파생한다.** */
        private val TABLES: List<String> =
            EncryptedField.entries
                .map { it.wireName.substringBefore('.') }
                .distinct()
                .sorted()

        /** 문장이 이 테이블에 닿는가. 이름 앞의 낱말로 「SQL 안의 테이블 자리」인지 가른다. */
        private val TABLE_REFERENCE =
            Regex("""\b(?:FROM|JOIN|UPDATE|INTO)\s+(${TABLES.joinToString("|")})\b""", RegexOption.IGNORE_CASE)

        private val VERB = Regex("""\b(SELECT|INSERT|UPDATE|DELETE|WITH)\b""", RegexOption.IGNORE_CASE)

        /**
         * 소유 열을 **매개변수**와 묶은 자리. 이름 붙은 매개변수(`:이름`)와 위치 매개변수(`?`)
         * 둘 다 받는다. 컬럼끼리의 비교는 받지 않는다 — 그것은 소유를 좁히지 않는다.
         */
        private val OWNER_PREDICATE =
            Regex("""(?<![A-Za-z0-9_])user_id\s*=\s*(?::[A-Za-z_]\w*|\?)""", RegexOption.IGNORE_CASE)

        /** 문장이 끝났다고 볼 자리. 이 저장소의 SQL 은 전부 Kotlin 문자열 안에 산다. */
        private val STATEMENT_END = Regex("""(;|\"\"\"|\")""")

        /** `include("core", …)` 에서 모듈 이름을 뽑는다. */
        private val INCLUDE = Regex("""^include\(([^)]*)\)""", RegexOption.MULTILINE)
        private val QUOTED = Regex("\"([A-Za-z0-9_-]+)\"")

        fun scan(root: Path): List<TableAccess> =
            productSources(root).flatMap { file ->
                accessesIn(root.relativize(file).joinToString("/"), file.readText())
            }

        /**
         * **한 건도 없으면 끊는다.** 「위반 0건」과 「대상 0건」은 완전히 다른 상태이고,
         * 후자를 초록으로 두면 이 파일은 아무것도 재지 않으면서 재는 척한다.
         */
        fun requireNonEmpty(accesses: List<TableAccess>) {
            check(accesses.isNotEmpty()) {
                "문서·변환 테이블에 닿는 제품 SQL 을 한 건도 찾지 못했다 — 검사 대상 0건은 통과가 아니라 실패다. " +
                    "스캐너가 소스를 못 읽었거나(경로·확장자·소스셋 이름), 저장 경로가 통째로 사라졌다."
            }
        }

        /**
         * 제품 소스. `src/main` 아래의 `.kt` 를 **깊이 제한 없이** 걷는다.
         *
         * Gradle 루트의 직계 자식만 훑지 않는 이유는 그 형태가 이미 지적받은 결함이기 때문이다
         * (게이트 27 codex C-8 — 중첩 모듈이 통째로 분모 밖에 남는다).
         */
        fun productSources(root: Path): List<Path> =
            Files.walk(root).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.extension == "kt" }
                    .filter { isProductSource(root.relativize(it)) }
                    .sorted()
                    .toList()
            }

        /** `settings.gradle.kts` 의 `include` 선언에서 파생한 모듈 이름. */
        fun declaredModules(root: Path): List<String> {
            val settings = root.resolve("settings.gradle.kts")
            check(Files.isRegularFile(settings)) {
                "$settings 를 찾지 못했다 — 모듈 목록을 파생할 기준이 없다"
            }
            val declaration =
                INCLUDE.find(settings.readText())
                    ?: error("$settings 에 `include(...)` 선언이 없다 — 모듈 목록을 파생할 기준이 없다")
            return QUOTED.findAll(declaration.groupValues[1]).map { it.groupValues[1] }.toList()
        }

        private fun isProductSource(relative: Path): Boolean {
            val parts = relative.map { it.toString() }
            return "src" in parts && "main" in parts && "build" !in parts
        }

        private fun accessesIn(
            file: String,
            text: String,
        ): List<TableAccess> = STATEMENT_END.split(text).mapNotNull { chunk -> accessOf(file, chunk) }

        private fun accessOf(
            file: String,
            chunk: String,
        ): TableAccess? {
            val tables =
                TABLE_REFERENCE
                    .findAll(chunk)
                    .map { it.groupValues[1].lowercase() }
                    .distinct()
                    .sorted()
                    .toList()
            if (tables.isEmpty()) return null
            val excerpt = excerptOf(chunk)
            return TableAccess(
                file = file,
                verb = verbOf(file, chunk, excerpt),
                tables = tables,
                statement = excerpt,
                // **원시 청크가 아니라 걷어낸 사본에 건다.** 방향이 다르면 정규화도 다르다 —
                // 위 `tables` 는 원시 청크를 쓰고(분모, fail-closed), 이 판정은 주석과 문자열
                // 리터럴을 지운 사본을 쓴다(방어 존재, fail-open 방지). 클래스 KDoc
                // 「주석은 방향에 따라 다르게 다룬다」.
                hasOwnerPredicate = OWNER_PREDICATE.containsMatchIn(LiveSql.of(chunk)),
            )
        }

        /** 문장의 SQL 동사. 못 찾으면 **끊는다** — 해석할 수 없는 문장을 조용히 넘기지 않는다. */
        private fun verbOf(
            file: String,
            chunk: String,
            excerpt: String,
        ): String {
            val match =
                VERB.find(chunk)
                    ?: error("$file: 감시 테이블에 닿는 문장에서 SQL 동사를 찾지 못했다 — 조용히 넘기지 않는다: $excerpt")
            return match.groupValues[1].uppercase()
        }

        /** 실패 메시지에만 쓰는 한 줄 요약. 판정에는 쓰지 않는다. */
        private fun excerptOf(chunk: String): String {
            val flat = chunk.split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
            return if (flat.length <= EXCERPT_LIMIT) flat else flat.take(EXCERPT_LIMIT) + " …"
        }

        private const val EXCERPT_LIMIT = 160
    }

    private companion object {
        const val SOURCE_ROOT_PROPERTY = "easydoc.kotlin.source.root"

        /** 핀 문자열의 경로 앞부분. 목록이 읽히게 하려고 상수로 접는다. */
        private const val MAIN = "infrastructure/src/main/kotlin/kr/easydoc/infrastructure"
        private const val AUTH = "$MAIN/auth"
        private const val DOCUMENT = "$MAIN/document"

        /**
         * 문서·변환에 닿는 제품 SQL **전부**. 소유 술어가 있는 것도 함께 적는다.
         *
         * **면제 목록이 아니라 인구조사다.** 여기 없는 문장이 봐주는 것이 아니라, 목록이
         * 바뀌면 그 diff 가 리뷰에 올라온다. 소유 술어가 있던 문장에서 그것이 빠지면 이 목록은
         * 그대로인 채 [EXPECTED_UNGUARDED] 가 늘어난다 — 두 목록이 서로 다른 사건을 잡는다.
         */
        val EXPECTED_STATEMENTS =
            listOf(
                "$AUTH/JdbcWorkspaceRepository.kt | SELECT [documents]",
                "$AUTH/JdbcWorkspaceRepository.kt | SELECT [documents]",
                "$DOCUMENT/JdbcConversionRepository.kt | SELECT [conversions]",
                "$DOCUMENT/JdbcConversionRepository.kt | UPDATE [conversions]",
                "$DOCUMENT/JdbcConversionRepository.kt | INSERT [conversions]",
                "$DOCUMENT/JdbcDocumentRepository.kt | SELECT [documents]",
                "$DOCUMENT/JdbcDocumentRepository.kt | UPDATE [documents]",
                // 즉시 파기(C5). **소유 술어가 있어** 아래 [EXPECTED_UNGUARDED] 에는 없다 —
                // 두 목록이 서로 다른 사건을 잡는다는 것이 여기서 관측된다.
                "$DOCUMENT/JdbcDocumentRepository.kt | DELETE [documents]",
                "$DOCUMENT/JdbcDocumentRepository.kt | INSERT [documents]",
                "$DOCUMENT/JdbcDocumentRepository.kt | SELECT [conversions, documents]",
            )

        /**
         * 그중 **소유 매개변수가 걸리지 않은** 문장. 오늘 일곱이고, 각각 사유가 있다.
         *
         * | 문장 | 오늘 소유 술어가 없는 사유 |
         * |---|---|
         * | 작업 공간의 문서 수 | 호출자가 같은 트랜잭션에서 소유 작업 공간을 이미 잠갔다 |
         * | 변환 잠금 읽기 · 문서 잠금 읽기 | **M-3 대상.** 사용자 경로 전용 포트(해제 조건 ⒜)는 C6 몫이다 |
         * | 회전 갱신 둘 | 키 회전 배치. 낙관적 조건이 잠근 채 읽은 행 전부다 |
         * | 새 행 삽입 둘 | 소유자를 **조건이 아니라 값으로** 적는다 |
         *
         * 사유가 여기 적혀 있다고 해서 봐주는 것이 아니다 — 목록이 늘면 빨개지고, 그때 새
         * 항목의 사유를 리뷰가 판정한다.
         */
        val EXPECTED_UNGUARDED =
            listOf(
                "$AUTH/JdbcWorkspaceRepository.kt | SELECT [documents]",
                "$DOCUMENT/JdbcConversionRepository.kt | SELECT [conversions]",
                "$DOCUMENT/JdbcConversionRepository.kt | UPDATE [conversions]",
                "$DOCUMENT/JdbcConversionRepository.kt | INSERT [conversions]",
                "$DOCUMENT/JdbcDocumentRepository.kt | SELECT [documents]",
                "$DOCUMENT/JdbcDocumentRepository.kt | UPDATE [documents]",
                "$DOCUMENT/JdbcDocumentRepository.kt | INSERT [documents]",
            )
    }
}
