package kr.easydoc.infrastructure.dictionary

import kr.easydoc.core.dictionary.DictionaryContextPolicy
import kr.easydoc.core.quality.GoldenDocument
import kr.easydoc.core.quality.GoldenDocumentLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * 고정 문서 52건의 Kotlin 사전 컨텍스트 회귀 픽스처.
 * 2026-09-11 독해 정책 개정으로 Python 참조 문자열과의 동등성 검사를 대체한다.
 * 기존 문서 목록·매칭·예산 회귀 범위는 유지하며 실제 LLM 품질 검증과 구분한다.
 */
class DictionaryReferenceContextTest {
    @TestFactory
    @DisplayName("고정 52건 각각이 참조 출력과 문자열이 같다")
    fun `참조 출력과 일치한다`(): List<DynamicTest> {
        val documents = goldenDocuments().filter { it.id in FROZEN_REFERENCE_DOCUMENT_IDS }
        return documents.map { document ->
            DynamicTest.dynamicTest(document.id) {
                val expected =
                    referenceOutput(document.id)
                        ?: error("참조 출력 픽스처가 없다: $REFERENCE_DIRECTORY/${document.id}$FIXTURE_SUFFIX")

                assertThat(index.buildPromptContext(document.sourceText, REFERENCE_POLICY))
                    .withFailMessage(
                        "문서 %s 의 컨텍스트가 고정 픽스처와 다르다 — 매칭·예산·렌더링 변경을 확인한다. " +
                            "본문은 여기 찍지 않는다: 산출물을 직접 견주려면 " +
                            "%s/%s%s 와 대조한다.",
                        document.id,
                        REFERENCE_DIRECTORY,
                        document.id,
                        FIXTURE_SUFFIX,
                    ).isEqualTo(expected)
            }
        }
    }

    @Test
    @DisplayName("고정 52건 전원이 참조 픽스처를 갖는다 — 픽스처 삭제를 여기서 잡는다")
    fun `고정 목록의 픽스처가 전부 있다`() {
        val missing = FROZEN_REFERENCE_DOCUMENT_IDS.filter { referenceOutput(it) == null }
        assertThat(missing)
            .withFailMessage(
                "고정 목록(FROZEN_REFERENCE_DOCUMENT_IDS)에 있는데 픽스처가 없는 문서: %s",
                missing,
            ).isEmpty()
    }

    @Test
    @DisplayName("제품 기본 설정이 픽스처를 뽑은 파라미터와 같다 — 갈리면 위 대조가 거짓말을 한다")
    fun `제품 기본값이 참조 파라미터와 같다`() {
        assertThat(DictionaryProperties().policy()).isEqualTo(REFERENCE_POLICY)
    }

    /** 골든 문서가 없는 환경이면 [건너뜀]으로 밝힌다 — "통과"가 아니라 "검사 안 함"이다. */
    private fun goldenDocuments(): List<GoldenDocument> {
        val directory =
            runCatching { GoldenDocumentLoader.documentsDirectory() }
                .getOrNull()
                ?.takeIf { it.isDirectory }
                ?: abort<File>(
                    "[건너뜀] 골든 문서 디렉터리가 없다 — 참조 대조를 하지 않았다. " +
                        "\"통과\"가 아니라 \"검사 안 함\"이다.",
                )
        return GoldenDocumentLoader.loadDirectory(directory).documents
    }

    private fun referenceOutput(documentId: String): String? =
        javaClass.getResourceAsStream("$REFERENCE_DIRECTORY/$documentId$FIXTURE_SUFFIX")?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    private companion object {
        /** 색인 적재는 한 번이면 된다 — 56건이 같은 색인을 읽는다. */
        val index = DictionaryIndexJsonReader().readClasspathResource()

        /**
         * 픽스처를 뽑을 때 쓴 파라미터 (`reference/README.md`).
         *
         * 여기 값을 제품 기본값에서 끌어오지 **않는다**. 픽스처는 이 숫자로 구워졌으므로 이것은
         * 고정된 사실이고, 제품 기본값이 여기서 갈라지는지는 위 테스트가 따로 본다. 한쪽에서
         * 끌어오면 둘이 함께 움직여 대조가 아무것도 재지 않게 된다.
         */
        val REFERENCE_POLICY =
            DictionaryContextPolicy(
                maxTerms = 40,
                maxChars = 4000,
                maxCharsRatio = 1.0,
                minSubstitute = 5,
                maxExamples = 3,
            )

        const val REFERENCE_DIRECTORY = "/dictionary/reference"
        const val FIXTURE_SUFFIX = ".txt"

        /**
         * 2026-08 코퍼스 56건 승격 시점에 있던 문서 id — **사용자 결정(2026-09-04)으로 동결**됐다.
         *
         * "픽스처가 있으면 대조한다"가 아니라 "이 목록에 있으면 대조한다"다. 이후 코퍼스에
         * 추가된 문서(2026-09-02 승격 7건: `022`·`023`·`047`·`050`·`105`·`106`·`107` 등, 그리고
         * 앞으로 추가될 문서)는 이 목록에 넣지 않는다 — 이식본↔참조 구현 대조도, 픽스처 보유도
         * 요구하지 않는다. 목록을 늘리려면 다시 사용자 결정이 필요하다.
         */
        val FROZEN_REFERENCE_DOCUMENT_IDS: Set<String> =
            setOf(
                "001",
                "002",
                "003",
                "004",
                "005",
                "006",
                "007",
                "008",
                "009",
                "010",
                "011",
                "012",
                "013",
                "014",
                "015",
                "016",
                "017",
                "018",
                "019",
                "020",
                "039",
                "041",
                "042",
                "048",
                "049",
                "051",
                "052",
                "063",
                "064",
                "070",
                "072",
                "074",
                "077",
                "078",
                "079",
                "080",
                "081",
                "087",
                "088",
                "089",
                "090",
                "091",
                "092",
                "093",
                "094",
                "095",
                "096",
                "097",
                "098",
                "099",
                "100",
                "101",
            )
    }
}
