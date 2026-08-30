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
 * Kotlin 이식본이 참조 구현(`dictionary/src/easydict/lookup.py`)과 **문자열까지 같은지** 본다.
 *
 * 이것이 §6.7 경계 규칙의 이식 누락을 잡는 유일한 기계적 장치다. 규칙 하나가 빠져도 결과는
 * 그럴듯한 컨텍스트라 사람 눈으로는 안 보이고, 그 사이 원문은 조용히 훼손된다(`CCTV` 에서
 * `CT` 가 매칭돼 `C전류 변성기V` 가 되는 종류).
 *
 * 입력은 골든 문서 56건의 **원문**이다 — 마스킹된 본문이 아니다. 픽스처를 그렇게 뽑았고,
 * 여기서 재는 것은 배선이 아니라 이식본과 참조 구현의 동등성이기 때문이다. 제품 경로가
 * 마스킹된 본문을 쓰는 것은 `IndexedDictionaryContextSource` 쪽 계약이다.
 */
class DictionaryReferenceContextTest {
    @TestFactory
    @DisplayName("골든 문서마다 참조 출력과 문자열이 같다")
    fun `참조 출력과 일치한다`(): List<DynamicTest> {
        val documents = goldenDocuments()
        return documents.map { document ->
            DynamicTest.dynamicTest(document.id) {
                val expected =
                    referenceOutput(document.id)
                        ?: error("참조 출력 픽스처가 없다: $REFERENCE_DIRECTORY/${document.id}$FIXTURE_SUFFIX")

                assertThat(index.buildPromptContext(document.sourceText, REFERENCE_POLICY))
                    .withFailMessage(
                        "문서 %s 의 컨텍스트가 참조 구현과 다르다 — §6.7 경계 규칙이 빠졌을 수 있다. " +
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
    }
}
