package kr.easydoc.api

import com.fasterxml.jackson.annotation.JsonProperty
import kr.easydoc.api.document.ConversionResponse
import kr.easydoc.api.document.FormatPreservationResponse
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.ConversionView
import kr.easydoc.core.document.FormatPreservationStatus
import kr.easydoc.core.document.ReflectionOutcome
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.document.noOriginalPreservation
import kr.easydoc.core.document.reflectedPreservation
import kr.easydoc.core.document.unreadableOriginalPreservation
import kr.easydoc.core.easyread.ExportFormat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

/**
 * `ConversionResponse` **형식 셋**의 계약 — `DESIGN.md` §6.5 「필요한 계약 정보」의 읽기 절반.
 *
 * **기대값을 손으로 적지 않는다.** 세 값 집합은 전부 계약 파일에서 읽어 구현 enum 과 대조한다 —
 * 손으로 적으면 계약이 넓어진 날 이 파일만 옛 집합을 알고 통과한다.
 */
class ConversionFormatContractTest {
    @Test
    @DisplayName("계약 `SourceFormat` 의 값 집합이 구현 `SourceFormat` **전부**를 정확히 덮는다")
    fun `원본 형식 집합이 계약과 같다`() {
        val declared = ContractSpec.schemaEnum(SOURCE_FORMAT_SCHEMA)

        assertThat(declared)
            .withFailMessage(
                "계약 %s 의 enum 이 구현 전부를 덮지 않는다 — 계약 %s / 구현 %s",
                SOURCE_FORMAT_SCHEMA,
                declared,
                SourceFormat.entries.map { it.wireName },
            ).containsExactlyInAnyOrderElementsOf(SourceFormat.entries.map { it.wireName })
    }

    @Test
    @DisplayName("계약 `ExportFormat` 의 값 집합이 구현 `ExportFormat` **전부**를 정확히 덮고 `pdf` 가 **없다**")
    fun `내보내기 형식 집합이 계약과 같다`() {
        val declared = ContractSpec.schemaEnum(EXPORT_FORMAT_SCHEMA)

        assertThat(declared).containsExactlyInAnyOrderElementsOf(ExportFormat.entries.map { it.extension })

        // 계약이 그 자리에 사유를 적어 뒀다: 렌더러가 없는 형식을 값으로 먼저 넣으면
        // 계약이 없는 기능을 약속한다. 한쪽만 늘어나는 것을 이 단언이 막는다.
        assertThat(declared)
            .withFailMessage("계약 %s 에 `pdf` 가 들었다 — PDF 렌더러가 없는데 계약이 그것을 약속한다", EXPORT_FORMAT_SCHEMA)
            .doesNotContain(SourceFormat.PDF.wireName)
        assertThat(ExportFormat.entries.map { it.extension }).doesNotContain(SourceFormat.PDF.wireName)
    }

    @Test
    @DisplayName("계약 `FormatPreservationStatus` 가 구현 enum **전부**를 정확히 덮는다 — 오늘은 한 값뿐이다")
    fun `서식 유지 상태 집합이 계약과 같다`() {
        val declared = ContractSpec.schemaEnum(PRESERVATION_STATUS_SCHEMA)

        assertThat(declared)
            .withFailMessage(
                "계약 %s 의 enum 이 구현 전부를 덮지 않는다 — 한쪽만 넓어지면 응답이 계약 밖 상태를 싣는다: 계약 %s / 구현 %s",
                PRESERVATION_STATUS_SCHEMA,
                declared,
                FormatPreservationStatus.entries.map { it.wireName },
            ).containsExactlyInAnyOrderElementsOf(FormatPreservationStatus.entries.map { it.wireName })
    }

    @Test
    @DisplayName("`ExportFormat.ofSource` 가 **모든** 원본 형식에 답하고, 답한 값은 전부 계약 `ExportFormat` 안이다")
    fun `유도 규칙의 상이 계약 안이다`() {
        val declaredExports = ContractSpec.schemaEnum(EXPORT_FORMAT_SCHEMA)

        val derived = SourceFormat.entries.associateWith { ExportFormat.ofSource(it) }

        assertThat(derived.keys)
            .describedAs("유도 규칙의 정의역이 원본 형식 전부가 아니다")
            .containsExactlyInAnyOrderElementsOf(SourceFormat.entries)
        assertThat(derived.values.filterNotNull().map { it.extension })
            .withFailMessage("유도 결과가 계약 %s 밖의 값을 냈다: %s", EXPORT_FORMAT_SCHEMA, derived)
            .allSatisfy { assertThat(it).isIn(declaredExports) }
    }

    @Test
    @DisplayName("`ExportFormat.ofSource` 가 계약 `x-export-format-derivation.mapping` 과 **한 항목도 다르지 않다**")
    fun `유도 규칙이 계약 표와 같다`() {
        val declared = ContractSpec.exportFormatDerivation()

        // 정의역이 먼저다 — 표에 빠진 형식은 「모른다」가 아니라 누락이고, 그 갈래는 대조를 받지 않는다.
        assertThat(declared.keys)
            .withFailMessage("계약 유도표의 정의역이 `SourceFormat` 전부가 아니다: 표 %s", declared.keys)
            .containsExactlyInAnyOrderElementsOf(SourceFormat.entries.map { it.wireName })

        val implemented = SourceFormat.entries.associate { it.wireName to ExportFormat.ofSource(it)?.extension }

        assertThat(implemented)
            .withFailMessage(
                "유도 규칙이 계약 `x-export-format-derivation.mapping` 과 다르다 — 계약 %s / 구현 %s. " +
                    "`null` 을 대체 형식으로 접으면 계약이 우회 다운로드를 권하는 것이 된다(DESIGN.md §6.5).",
                declared,
                implemented,
            ).isEqualTo(declared)

        assertThat(declared.values)
            .describedAs("표에 `null` 갈래가 하나도 없다 — 「내보낼 수단이 없다」를 재는 대조가 공허해진다")
            .containsNull()
    }

    @Test
    @DisplayName("계약 `enforcement` 가 유도표와 **같은 절**에 있고, 처분이 오퍼레이션 선언과 맞물린다")
    fun `강제 선언이 계약 안에서 닫힌다`() {
        val enforcement = ContractSpec.exportEnforcement()
        val declaredStatuses = ContractSpec.responseStatuses(EXPORT_PATH, GET)

        assertThat(enforcement.parameter)
            .describedAs("강제가 가리키는 파라미터가 그 오퍼레이션에 없다")
            .isEqualTo(ContractSpec.queryParameters(EXPORT_PATH, GET).single().name)
        assertThat(ContractSpec.queryParameters(EXPORT_PATH, GET).single().required)
            .withFailMessage("계약 `enforcement.required` 와 오퍼레이션 선언이 갈렸다 — 두 자리가 같은 사실을 다르게 말한다")
            .isEqualTo(enforcement.required)

        // 처분으로 쓰는 상태 코드가 그 오퍼레이션에 선언돼 있어야 한다 — 없으면 계약에 없는 응답을 약속한다.
        listOf(
            enforcement.onMismatch,
            enforcement.onNullMapping,
            enforcement.onUnknownValue,
            enforcement.onAbsentWithChoices,
            enforcement.onChoiceMismatch,
        ).forEach { status ->
            assertThat(declaredStatuses)
                .withFailMessage("`enforcement` 가 선언되지 않은 상태 %d 를 처분으로 쓴다", status)
                .contains(status.toString())
        }
    }

    @Test
    @DisplayName("불일치 처분이 **자원 상태**의 코드다 — 검증 층 코드(422)로 두면 소유 은닉보다 먼저 답한다")
    fun `불일치 처분이 검증 코드와 갈린다`() {
        val enforcement = ContractSpec.exportEnforcement()

        assertThat(enforcement.onMismatch)
            .withFailMessage(
                "불일치가 값 집합 거절(%d)과 같은 코드다 — 그러면 그 판정이 소유 확인보다 먼저 나가고 " +
                    "남의 문서의 원본 형식이 샌다(계약 `x-why-409-and-not-422`)",
                enforcement.onUnknownValue,
            ).isNotEqualTo(enforcement.onUnknownValue)
        assertThat(enforcement.onNullMapping)
            .describedAs("「내보낼 수단이 없다」도 자원 상태다 — 불일치와 같은 코드여야 한다")
            .isEqualTo(enforcement.onMismatch)
        assertThat(enforcement.onAbsentWithChoices)
            .describedAs("선택지 생략도 자원 상태다 — 값 집합 거절(422)과 갈려야 소유 은닉이 지켜진다")
            .isNotEqualTo(enforcement.onUnknownValue)
        assertThat(enforcement.onChoiceMismatch)
            .describedAs("선택지 밖 요청도 자원 상태다 — 불일치와 같은 코드여야 한다")
            .isEqualTo(enforcement.onMismatch)
    }

    @Test
    @DisplayName(
        "P-40 `choices` 의 정의역이 `mapping` 과 맞물린다 — `null` 인 키는 `choices` 에, " +
            "값을 낸 키는 `choices` 에 **없어야** 한다 (2.6.0)",
    )
    fun `선택지 정의역이 유도표와 맞물린다`() {
        val mapping = ContractSpec.exportFormatDerivation()
        val choices = ContractSpec.exportFormatChoices()

        val nullMappedKeys = mapping.filterValues { it == null }.keys
        assertThat(choices.keys)
            .withFailMessage(
                "choices 의 정의역이 mapping 의 null 갈래와 다르다 — mapping null 키 %s / choices 키 %s",
                nullMappedKeys,
                choices.keys,
            ).isEqualTo(nullMappedKeys)

        val declaredExports = ContractSpec.schemaEnum(EXPORT_FORMAT_SCHEMA)
        choices.forEach { (source, values) ->
            assertThat(values)
                .withFailMessage("%s 가 mapping 에서 null 인데 choices 도 비었다 — 내보낼 방법이 없다", source)
                .isNotEmpty()
            assertThat(values)
                .withFailMessage("%s 의 choices 가 계약 %s 밖의 값을 냈다: %s", source, EXPORT_FORMAT_SCHEMA, values)
                .allSatisfy { assertThat(it).isIn(declaredExports) }
        }

        // `ExportFormat.choicesFor` 가 계약과 한 항목도 다르지 않다.
        val implemented =
            SourceFormat.entries.associate { source ->
                source.wireName to ExportFormat.choicesFor(source).map { it.extension }
            }
        val declaredWithEmpty =
            SourceFormat.entries.associate { source -> source.wireName to (choices[source.wireName] ?: emptyList()) }
        assertThat(implemented)
            .withFailMessage(
                "`ExportFormat.choicesFor` 가 계약 `choices` 와 다르다 — 계약 %s / 구현 %s",
                declaredWithEmpty,
                implemented,
            ).isEqualTo(declaredWithEmpty)
    }

    @Test
    @DisplayName("P-40 유도값과 선택지가 **겹치지 않는다** — 서버가 정한 원본에 사용자가 또 고를 자리가 없다")
    fun `유도값과 선택지가 겹치지 않는다`() {
        SourceFormat.entries.forEach { source ->
            val derived = ExportFormat.ofSource(source)
            val choices = ExportFormat.choicesFor(source)
            if (derived != null) {
                assertThat(choices).withFailMessage("%s 는 유도값이 있는데 선택지도 있다", source).isEmpty()
            }
            if (choices.isNotEmpty()) {
                assertThat(derived).withFailMessage("%s 는 선택지가 있는데 유도값도 있다", source).isNull()
            }
        }
    }

    @Test
    @DisplayName("내보낼 수 없는 원본은 **PDF 하나뿐**이다 — 문구가 그 형식을 이름으로 부를 수 있는 근거")
    fun `내보낼 수 없는 원본이 하나뿐이다`() {
        val declared = ContractSpec.exportFormatDerivation()

        assertThat(declared.filterValues { it == null }.keys)
            .withFailMessage(
                "유도표의 `null` 갈래가 늘었다 — `EXPORT_FORMAT_UNAVAILABLE_MESSAGE` 가 PDF 를 이름으로 부르므로 " +
                    "그 문구가 거짓이 된다: %s",
                declared,
            ).containsExactly(SourceFormat.PDF.wireName)
    }

    @Test
    @DisplayName("`FormatPreservationResponse` 의 JSON 키가 계약 `FormatPreservation.required` 와 정확히 같다 (P-33)")
    fun `서식 유지 DTO 의 키가 계약 required 와 같다`() {
        assertThat(jsonPropertyNames(FormatPreservationResponse::class))
            .isEqualTo(ContractSpec.schemaRequired(PRESERVATION_SCHEMA))
    }

    @Test
    @DisplayName("`ConversionResponse` 의 JSON 키가 계약 `ConversionResponse.required` 와 정확히 같다 (P-33)")
    fun `변환 응답 DTO 의 키가 계약 required 와 같다`() {
        assertThat(jsonPropertyNames(ConversionResponse::class))
            .isEqualTo(ContractSpec.schemaRequired(CONVERSION_SCHEMA))
    }

    @Test
    @DisplayName("구현이 낼 수 있는 판정이 **전부 계약 값 집합 안**이다 — 네 갈래를 모두 지난다")
    fun `서식 유지 판정이 계약 값 집합 안이다`() {
        val declaredStatuses = ContractSpec.schemaEnum(PRESERVATION_STATUS_SCHEMA)

        val judgments =
            mapOf(
                "원본이 없다" to noOriginalPreservation(),
                "짝이 정확히 맞았다" to reflectedPreservation(ReflectionOutcome(0, 0, 0, 0)),
                "일부가 달라진다" to reflectedPreservation(ReflectionOutcome(2, 1, 3, 0)),
                "원본을 열 수 없다" to unreadableOriginalPreservation(),
            )

        judgments.forEach { (why, judged) ->
            assertThat(judged.status.wireName)
                .withFailMessage("%s: 판정 값이 계약 %s 밖이다", why, PRESERVATION_STATUS_SCHEMA)
                .isIn(declaredStatuses)
        }
        assertThat(judgments.getValue("일부가 달라진다").details)
            .describedAs("「일부」라고 말해 놓고 무엇이 달라지는지 말하지 않으면 사용자가 취할 행동이 없다")
            .isNotEmpty()
        assertThat(judgments.getValue("짝이 정확히 맞았다").details)
            .describedAs("알릴 영향 항목이 없다 — `null` 이 아니라 빈 배열이다")
            .isEmpty()
    }

    @Test
    @DisplayName("판정 문구는 **개수와 고정 문장만** 담는다 — 문서 본문이 실릴 통로가 없다")
    fun `판정 문구가 본문을 담지 않는다`() {
        val everyDetail =
            listOf(
                noOriginalPreservation(),
                unreadableOriginalPreservation(),
                reflectedPreservation(ReflectionOutcome(3, 4, 5, 0)),
                // 머리말 자리와 겹쳐 옮겨 붙은 문단을 말하는 갈래도 같은 규칙을 지나야 한다.
                reflectedPreservation(ReflectionOutcome(3, 0, 0, 5)),
            ).flatMap { it.details }

        assertThat(everyDetail).allSatisfy { detail ->
            assertThat(detail)
                .withFailMessage("계약 `FormatPreservation.details` 는 구조 요소의 종류와 개수만 허용한다: %s", detail)
                .matches("""[^A-Za-z<>\[\]{}]*""")
        }
    }

    @Test
    @DisplayName("조립된 응답의 형식 셋이 **전부 계약 값 집합 안**이다 — 원본 형식 넷 전부를 지난다")
    fun `조립된 응답의 형식 셋이 계약 안이다`() {
        val declaredSources = ContractSpec.schemaEnum(SOURCE_FORMAT_SCHEMA)
        val declaredExports = ContractSpec.schemaEnum(EXPORT_FORMAT_SCHEMA)
        val declaredStatuses = ContractSpec.schemaEnum(PRESERVATION_STATUS_SCHEMA)
        val preservationKeys = ContractSpec.schemaRequired(PRESERVATION_SCHEMA)

        SourceFormat.entries.forEach { source ->
            listOf(true, false).forEach { hasOriginal ->
                val response = ConversionResponse.of(viewOf(source, hasOriginal))

                assertThat(response.sourceFormat)
                    .withFailMessage("원본 형식 %s 가 계약 값 집합 밖으로 나갔다", response.sourceFormat)
                    .isIn(declaredSources)
                response.exportFormat?.let {
                    assertThat(it).withFailMessage("내보내기 형식 %s 가 계약 값 집합 밖으로 나갔다", it).isIn(declaredExports)
                }
                response.formatPreservation?.let { preservation ->
                    assertThat(preservation.status).isIn(declaredStatuses)
                    assertThat(jsonPropertyNames(preservation::class)).isEqualTo(preservationKeys)
                }
            }
        }
    }

    /** 완료 변환 하나 — 형식 셋만 갈아 끼운다. 결과 필드는 노출 가드를 지나는 최소값이다. */
    private fun viewOf(
        source: SourceFormat,
        hasStoredOriginal: Boolean,
    ): ConversionView =
        ConversionView(
            id = UUID.randomUUID(),
            documentId = UUID.randomUUID(),
            status = ConversionStatus.DONE,
            sourceFormat = source,
            exportFormat = ExportFormat.ofSource(source),
            exportFormatChoices = ExportFormat.choicesFor(source),
            formatPreservation =
                if (hasStoredOriginal) {
                    reflectedPreservation(ReflectionOutcome(1, 1, 1, 0))
                } else {
                    noOriginalPreservation()
                },
            easyText = PlainBody("쉬운 글 초안입니다."),
            editedText = null,
            reviewedAt = null,
            feedbackSubmittedAt = null,
            maskedItems = emptyList(),
            missingPlaceholders = emptyList(),
            segmentMap = null,
            model = "stub-model",
            providerName = "stub-provider",
            inputTokens = 1,
            outputTokens = 2,
            failureCode = null,
        )

    /** 응답 DTO 가 실제로 내보내는 JSON 키. `@get:JsonProperty` 를 읽는다. */
    private fun jsonPropertyNames(type: KClass<*>): Set<String> =
        type.memberProperties
            .mapNotNull { property ->
                property.getter.annotations
                    .filterIsInstance<JsonProperty>()
                    .firstOrNull()
                    ?.value
            }.toSet()

    private companion object {
        const val EXPORT_PATH = "/conversions/{conversion_id}/export"
        const val GET = "get"

        const val CONVERSION_SCHEMA = "ConversionResponse"
        const val SOURCE_FORMAT_SCHEMA = "SourceFormat"
        const val EXPORT_FORMAT_SCHEMA = "ExportFormat"
        const val PRESERVATION_SCHEMA = "FormatPreservation"
        const val PRESERVATION_STATUS_SCHEMA = "FormatPreservationStatus"
    }
}
