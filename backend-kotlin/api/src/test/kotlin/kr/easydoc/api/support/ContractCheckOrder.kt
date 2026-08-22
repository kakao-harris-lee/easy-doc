package kr.easydoc.api.support

/** P-43 — `description` 의 검사 순서 목록을 읽는다. */
object ContractCheckOrder {
    private const val CHECK_ORDER_MARKER = "검사 순서:"

    private const val CHECK_ORDER_TERMINATOR = "\n\n"

    private const val COMPOUND_CLAUSE_MARKER = "복합 결함에도 적용된다"

    private val CHECK_ORDER_STAGE = Regex("([^→(]+)\\((\\d{3})\\)")

    private val EMPHASIS = Regex("\\*+")

    /** **빈 목록·한 단계는 통과가 아니다.** */
    private const val MIN_CHECK_ORDER_STAGES = 5

    /**
     * 단계 이름과 상태 코드의 **순서열**. 순서를 테스트 코드에 복제하지 않는 것이 목적이다 —
     * 복제하면 계약이 순서를 바꿔도 옛 순서를 요구하는 테스트가 초록이다.
     */
    fun stages(
        path: String,
        method: String,
    ): List<ContractCheckStage> {
        val description = ContractSpec.text("paths", path, method, "description")
        val start = description.indexOf(CHECK_ORDER_MARKER)
        require(start >= 0) {
            "계약 $method $path 의 description 에 검사 순서 목록이 없다 — 이 조항이 사라지면 " +
                "복합 결함의 기대값을 유도할 근거가 없다"
        }
        val clause = description.substring(start).substringBefore(CHECK_ORDER_TERMINATOR)
        val stages =
            CHECK_ORDER_STAGE
                .findAll(clause)
                .map { match ->
                    ContractCheckStage(
                        label = match.groupValues[1].replace(EMPHASIS, "").trim(),
                        status = match.groupValues[2].toInt(),
                    )
                }.toList()
        require(stages.size >= MIN_CHECK_ORDER_STAGES) {
            "검사 순서 목록에서 단계를 ${stages.size} 개만 읽었다 — 파서가 조항을 놓쳤거나 조항이 " +
                "줄었다. 이 목록이 비면 복합 결함 케이스가 자기 기대값에 기대게 된다"
        }
        return stages
    }

    /** 복합 결함 적용 조항이 있는가. 전문이 아니라 표식만 본다. */
    fun declaresCompound(
        path: String,
        method: String,
    ): Boolean = ContractSpec.text("paths", path, method, "description").contains(COMPOUND_CLAUSE_MARKER)
}

/** 검사 순서의 한 단계. **자리**가 곧 우선순위다. */
data class ContractCheckStage(
    val label: String,
    val status: Int,
)
