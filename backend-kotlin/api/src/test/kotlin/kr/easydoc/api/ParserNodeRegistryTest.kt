package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **계약 파서 노드(`P-*`)의 레지스트리 강제자** — 계약 계획 §3-3.
 *
 * ## 왜 이 장치가 있는가
 *
 * `P-*` 는 「계약의 이 노드를 테스트가 **읽어서** 쓴다」는 표식이고, 세 배치 명세의 §4 표와
 * `ContractSpec.kt` 의 KDoc 라벨 **두 곳**에서 붙는다. **단일 소유 문서가 없다.** 그래서
 * 같은 번호가 두 뜻을 갖는 사고가 실제로 났다 — 구현 레인이 D-2 판정 커밋에서 `P-22` 를
 * 선점했는데 문서 배치 명세가 같은 번호를 다른 노드에 배정했다(게이트 26 K5).
 *
 * 번호만 고치면 같은 사고가 다음 배치에서 다시 난다. 원인이 「레지스트리에 소유자가
 * 없다」이므로 **탐지기**를 세운다.
 *
 * ## 재는 것 넷
 *
 * 1. **중복 정의 없음** — 같은 ID 가 둘 이상의 명세에서 **정의 행**으로 나오면 실패.
 * 2. **미등재 라벨 없음** — `ContractSpec.kt` 의 라벨이 어느 명세에도 등재돼 있지 않으면
 *    실패. *P-22 가 태어난 자리를 정확히 막는다.*
 * 3. **번호 연속** — 정의된 ID 의 합집합이 `1..max` 연속이어야 한다. 구멍은 「번호를
 *    뽑아 놓고 등재를 안 했다」의 흔적이므로 그 자체가 신호다.
 * 4. **총수 고정** — 정의 행 수와 「`ContractSpec.kt` 전용 등재」 수를 **정확 일치**로
 *    못박는다. 새 노드를 더하면 이 숫자가 diff 로 올라온다.
 *
 * ## 「정의」와 「등재」를 가른다
 *
 * - **정의 행** — 명세 §4 표의 `| **P-N** | …` 한 줄. 그 노드가 무엇을 읽고 어느 케이스를
 *   먹이는지를 적는 자리다.
 * - **등재** — 명세 안 **어디서든** 그 ID 가 불린 것. `P-22` 는 정의 행이 아니라 documents
 *   명세 §4 서문의 **사후 등재** 문단으로 살아 있다. 구현이 먼저 선점한 번호를 명세가
 *   나중에 받아들인 형태이고, 그 형태를 정의 행으로 옮기면 **이미 도는 식별자를 바꾸는**
 *   편집이 되어 K5 가 막으려던 것을 「같은 뜻이 두 이름」으로 바꿀 뿐이다.
 *
 * ## 빈 분모는 통과가 아니다
 *
 * 명세 파일을 못 찾거나 정의 행이 0건이면 **실패한다**. 저장소의 parity 게이트가
 * 「선언 도메인 0개에서 exit 0」이었던 것이 정확히 이 결함이라 같은 자리를 만들지 않는다.
 */
class ParserNodeRegistryTest {
    @Test
    @DisplayName("규칙 1 — 같은 P- 번호가 둘 이상의 명세에서 정의되지 않는다 (분모 비어 있지 않음 포함)")
    fun `한 번호를 두 명세가 정의하지 않는다`() {
        val byFile = definitionsByFile()

        byFile.forEach { (file, ids) ->
            assertThat(ids)
                .withFailMessage("%s 에서 P- 정의 행을 하나도 찾지 못했다 — 이 파일은 검사받지 않고 있다", file.name)
                .isNotEmpty()
        }

        val duplicated =
            byFile.entries
                .flatMap { (file, ids) -> ids.map { it to file.name } }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size > 1 }

        assertThat(duplicated.keys)
            .withFailMessage(
                "같은 P- 번호를 두 명세가 정의한다 — 같은 이름이 두 뜻을 갖는다(게이트 26 K5 와 같은 형태):\n%s",
                duplicated.entries.joinToString("\n") { (id, files) -> "  - P-$id: ${files.joinToString(", ")}" },
            ).isEmpty()
    }

    @Test
    @DisplayName("규칙 2 — ContractSpec 의 P- 라벨이 전부 어느 명세엔가 등재돼 있다 (P-22 가 태어난 자리)")
    fun `미등재 라벨이 없다`() {
        val labels = contractSpecLabels()
        assertThat(labels)
            .withFailMessage("%s 에서 P- 라벨을 하나도 찾지 못했다 — 이 대조는 아무것도 재지 않는다", CONTRACT_SPEC_FILE)
            .isNotEmpty()

        val registered = specFiles().flatMap { mentionedIds(it) }.toSet()
        val orphans = labels - registered

        assertThat(orphans)
            .withFailMessage(
                "어느 명세에도 등재되지 않은 P- 라벨이 있다: %s — 번호가 코드에서만 살면 다음 배치가 같은 번호를 다른 노드에 배정한다",
                orphans.map { "P-$it" },
            ).isEmpty()
    }

    @Test
    @DisplayName("규칙 3 — 정의된 번호의 합집합이 P-1 부터 연속이다 (구멍은 미등재의 흔적)")
    fun `번호가 연속이다`() {
        val union = allRegisteredIds()

        assertThat(union).isNotEmpty()
        assertThat(union.min()).isEqualTo(FIRST_NODE)
        assertThat(union)
            .withFailMessage(
                "P- 번호에 구멍이 있다: %s — 번호를 뽑아 놓고 등재하지 않았거나 정의 행이 지워졌다",
                (FIRST_NODE..union.max()).toSet() - union,
            ).containsExactlyInAnyOrderElementsOf((FIRST_NODE..union.max()).toList())
    }

    @Test
    @DisplayName("규칙 4 — 정의 행 39 · ContractSpec 전용 등재 1 · 합집합 40 을 **정확 일치**로 고정한다")
    fun `총수가 고정돼 있다`() {
        val definitions = definitionsByFile().values.flatten()
        val exclusive = contractSpecLabels() - definitions.toSet()

        assertThat(definitions)
            .withFailMessage("정의 행이 중복 없이 세어지는지부터 확인하라 — 규칙 1 이 먼저 깨져야 한다")
            .doesNotHaveDuplicates()
        assertThat(definitions.size)
            .withFailMessage(
                "세 명세의 P- 정의 행 수가 기록과 다르다 (기대 %d / 실제 %d).\n  " +
                    "**늘었다면** 노드를 더한 것이다 — 이 숫자를 함께 올려라. 그 한 줄이 " +
                    "「이번에 무엇이 계약에서 읽히기 시작했는가」를 리뷰에 드러낸다.\n  " +
                    "**줄었다면** 정의 행이 사라졌거나 표 모양이 바뀌어 파서가 놓치기 시작한 것이다.\n  파일별 수: %s",
                EXPECTED_DEFINITION_ROWS,
                definitions.size,
                definitionsByFile().entries.joinToString(", ") { (file, ids) -> "${file.name}=${ids.size}" },
            ).isEqualTo(EXPECTED_DEFINITION_ROWS)

        assertThat(exclusive)
            .withFailMessage(
                "정의 행 없이 `ContractSpec.kt` 라벨로만 사는 번호가 기록과 다르다: %s",
                exclusive.map { "P-$it" },
            ).hasSize(EXPECTED_CONTRACT_SPEC_ONLY)

        assertThat(allRegisteredIds()).hasSize(EXPECTED_UNION)
    }

    @Test
    @DisplayName("명세 세 파일이 실재한다 — 경로가 어긋나면 위 대조가 전부 공허해진다")
    fun `명세 파일이 실재한다`() {
        val files = specFiles()

        assertThat(files).hasSize(EXPECTED_SPEC_FILES)
        files.forEach { file ->
            assertThat(file.isFile)
                .withFailMessage("명세 파일을 찾지 못했다: %s", file.path)
                .isTrue()
        }
    }

    // ================================================================ 훑기

    /** `| **P-N** | …` 형태의 **정의 행**만 뽑는다. 산문 안의 언급은 세지 않는다. */
    private fun definitionsByFile(): Map<File, List<Int>> =
        specFiles().associateWith { file ->
            file.readLines().mapNotNull { line ->
                DEFINITION_ROW
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
            }
        }

    /** 파일 안 **어디서든** 불린 번호. 정의 행이 아닌 사후 등재(P-22)를 함께 본다. */
    private fun mentionedIds(file: File): Set<Int> =
        ANY_NODE.findAll(file.readText()).map { it.groupValues[1].toInt() }.toSet()

    /** `ContractSpec.kt` KDoc 의 **굵은** 라벨(`**P-N`). 평문 주석 번호는 세지 않는다. */
    private fun contractSpecLabels(): Set<Int> =
        BOLD_LABEL
            .findAll(repositoryRoot().resolve(CONTRACT_SPEC_FILE).readText())
            .map { it.groupValues[1].toInt() }
            .toSet()

    private fun allRegisteredIds(): Set<Int> = definitionsByFile().values.flatten().toSet() + contractSpecLabels()

    private fun specFiles(): List<File> = SPEC_PATHS.map { repositoryRoot().resolve(it) }

    /** 계약 파일과 **같은 기준점**을 쓴다 — 상대 경로를 손으로 조립하면 기계마다 갈린다. */
    private fun repositoryRoot(): File =
        ContractSpec.file.parentFile.parentFile
            ?: error("계약 파일의 저장소 루트를 찾지 못했다")

    private companion object {
        val DEFINITION_ROW = Regex("""^\|\s*\*\*P-(\d+)\*\*""")
        val BOLD_LABEL = Regex("""\*\*P-(\d+)""")
        val ANY_NODE = Regex("""\bP-(\d+)\b""")

        const val CONTRACT_SPEC_FILE = "backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ContractSpec.kt"

        val SPEC_PATHS =
            listOf(
                "docs/migration/_workspace/03_contract-keeper_auth-test-spec.md",
                "docs/migration/_workspace/03_contract-keeper_workspaces-test-spec.md",
                "docs/migration/_workspace/04_contract-keeper_documents-test-spec.md",
            )

        const val EXPECTED_SPEC_FILES = 3
        const val FIRST_NODE = 1

        /**
         * 세 명세 §4 표의 정의 행 총수 — **auth 15 · workspaces 6 · documents 18**.
         *
         * documents 가 15 → 18 로 늘어난 것은 계약 v1.3.0 이 `x-stored-text-domain`(P-38)·
         * `x-retired-responses`(P-39)·`x-title-policy`(P-40)를 신설했기 때문이다.
         */
        const val EXPECTED_DEFINITION_ROWS = 39

        /**
         * 정의 행 없이 `ContractSpec.kt` 라벨로만 사는 번호 — **P-22 하나**.
         *
         * 구현 레인이 D-2 판정 커밋에서 선점했고 documents 명세 §4 서문이 **사후 등재**했다.
         * 이 수가 늘면 같은 사고가 다시 난 것이다.
         */
        const val EXPECTED_CONTRACT_SPEC_ONLY = 1

        /** 합집합 — `P-1`~`P-40` 연속. */
        const val EXPECTED_UNION = 40
    }
}
