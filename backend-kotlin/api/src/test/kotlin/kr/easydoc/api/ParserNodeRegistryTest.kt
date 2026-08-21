package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/** 계약 파서 노드(`P-*`)의 레지스트리 강제자 — 계약 계획 §3-3. */
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

    /** `| P-N | …` 형태의 정의 행만 뽑는다. 산문 안의 언급은 세지 않는다. */
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

    /** 파일 안 어디서든 불린 번호. 정의 행이 아닌 사후 등재(P-22)를 함께 본다. */
    private fun mentionedIds(file: File): Set<Int> =
        ANY_NODE.findAll(file.readText()).map { it.groupValues[1].toInt() }.toSet()

    /** `ContractSpec.kt` KDoc 의 굵은 라벨(`P-N`). 평문 주석 번호는 세지 않는다. */
    private fun contractSpecLabels(): Set<Int> =
        BOLD_LABEL
            .findAll(repositoryRoot().resolve(CONTRACT_SPEC_FILE).readText())
            .map { it.groupValues[1].toInt() }
            .toSet()

    private fun allRegisteredIds(): Set<Int> = definitionsByFile().values.flatten().toSet() + contractSpecLabels()

    private fun specFiles(): List<File> = SPEC_PATHS.map { repositoryRoot().resolve(it) }

    /** 계약 파일과 같은 기준점을 쓴다 — 상대 경로를 손으로 조립하면 기계마다 갈린다. */
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

        /** 세 명세 §4 표의 정의 행 총수 — auth 15 · workspaces 6 · documents 22. */
        const val EXPECTED_DEFINITION_ROWS = 43

        /** 정의 행 없이 `ContractSpec.kt` 라벨로만 사는 번호 — P-22 하나. */
        const val EXPECTED_CONTRACT_SPEC_ONLY = 1

        /** 합집합 — `P-1`~`P-44` 연속. */
        const val EXPECTED_UNION = 44
    }
}
