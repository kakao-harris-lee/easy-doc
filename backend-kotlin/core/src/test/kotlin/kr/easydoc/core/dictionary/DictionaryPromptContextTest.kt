package kr.easydoc.core.dictionary

import kr.easydoc.core.document.charCountOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 프롬프트 컨텍스트 블록의 회귀 테스트 — `dictionary/DESIGN.md` §7.2.
 *
 * **출력 문자열은 계약이다.** 섹션 제목·머리말·잘림 안내는 참조 구현(`lookup.py`)과 한 글자도
 * 달라선 안 되므로, 여기서는 `contains` 가 아니라 통짜 비교로 고정한다.
 */
class DictionaryPromptContextTest {
    /**
     * 문자 예산을 끈 정책. [DictionaryContextPolicy] 의 기본값은 실측 정책값이라
     * `maxCharsRatio = 1.0` 이 붙어 있고, 그러면 짧은 테스트 문장에서는 항목이 전부
     * 잘려 나가 렌더링 자체를 볼 수 없다. 예산 동작은 아래 Budget 절이 따로 본다.
     */
    private val unlimited = DictionaryContextPolicy(maxChars = null, maxCharsRatio = null)

    @Nested
    @DisplayName("세 구역 렌더링")
    inner class Sections {
        private val index =
            DictionaryFixture()
                .add(
                    DictionaryEntry(
                        term = "내방",
                        easyTerm = "방문",
                        strategy = ReplaceStrategy.SUBSTITUTE,
                        risk = RiskLevel.NONE,
                        priority = 120,
                        definition = "찾아옴",
                    ),
                ).add(
                    DictionaryEntry(
                        term = "과태료",
                        easyTerm = "정해진 날짜보다 늦어서 더 내는 돈",
                        strategy = ReplaceStrategy.GLOSS,
                        risk = RiskLevel.HIGH,
                        priority = 130,
                        definition = "정해진 날짜를 넘겨서 더 내게 되는 돈입니다.",
                        caution = "벌금과는 법적으로 다른 개념입니다. 바꾸지 말고 그대로 쓰세요.",
                    ),
                ).build()

        @Test
        @DisplayName("머리말·세 구역 제목·항목 형식이 참조 구현과 같다")
        fun `참조 구현과 같은 블록을 만든다`() {
            val context = index.buildPromptContext("내방을 하실 때 과태료가 부과됩니다.", unlimited)

            val expected =
                """
                ## 이 문서에 나온 어려운 말 (반드시 아래 지침대로 처리하세요)

                ### 바꿔 쓰세요
                - 내방 → 방문

                ### 원래 말은 남기고, 바로 다음 문장에서 쉽게 풀어 설명하세요 (원래 말을 지우거나 괄호로 붙이지 마세요)
                - 과태료 — 뜻: 정해진 날짜보다 늦어서 더 내는 돈
                  이유: 정해진 날짜를 넘겨서 더 내게 되는 돈입니다.
                  주의: 벌금과는 법적으로 다른 개념입니다. 바꾸지 말고 그대로 쓰세요.

                ### 절대 바꾸지 마세요
                """.trimIndent() + "\n"
            assertThat(context).isEqualTo(expected)
        }

        @Test
        @DisplayName("문서에 없는 용어는 싣지 않는다")
        fun `등장하지 않은 용어는 빠진다`() {
            val context = index.buildPromptContext("내방을 하실 때", unlimited)
            assertThat(context).contains("- 내방 → 방문")
            assertThat(context).doesNotContain("과태료")
        }

        @Test
        @DisplayName("같은 엔트리가 여러 번 나와도 한 번만 싣는다")
        fun `엔트리 기준으로 중복을 제거한다`() {
            val context = index.buildPromptContext("내방을 하실 때. 내방 시 확인하세요.", unlimited)
            assertThat(context.split("- 내방 → 방문")).hasSize(2)
        }
    }

    @Nested
    @DisplayName("매칭 0건")
    inner class ZeroMatches {
        private val index =
            DictionaryFixture()
                .add(
                    DictionaryEntry(
                        term = "내방",
                        easyTerm = "방문",
                        strategy = ReplaceStrategy.SUBSTITUTE,
                        risk = RiskLevel.NONE,
                        priority = 120,
                    ),
                ).build()

        /**
         * 구역 제목만 남은 골격(130자). **빈 문자열이 아니다** — 참조 구현으로 대조 확인한
         * 값이고, 참조 출력 픽스처가 이 형식에 맞춰 생성돼 있어 바이트 단위로 대조된다.
         *
         * "매칭이 없으면 프롬프트에 아예 싣지 않는다"는 판단은 **제품 배선 조각의 몫**이지
         * 엔진의 몫이 아니다. 엔진은 참조 구현을 그대로 따른다 — 여기서 빈 문자열로 "고치면"
         * 픽스처 대조가 전부 깨진다.
         */
        private val skeleton =
            """
            ## 이 문서에 나온 어려운 말 (반드시 아래 지침대로 처리하세요)

            ### 바꿔 쓰세요

            ### 원래 말은 남기고, 바로 다음 문장에서 쉽게 풀어 설명하세요 (원래 말을 지우거나 괄호로 붙이지 마세요)

            ### 절대 바꾸지 마세요
            """.trimIndent() + "\n"

        /** 사전 용어가 하나도 없는 문서. 비율 상한(1.0)이 골격(130자)보다 커지도록 충분히 길다. */
        private val noTerms = "이 안내문에는 사전에 실린 어려운 말이 하나도 나오지 않습니다. ".repeat(4)

        @Test
        @DisplayName("빈 문자열이 아니라 세 구역 골격을 돌려준다")
        fun `매칭이 없어도 골격을 낸다`() {
            assertThat(index.findAll(noTerms)).isEmpty()

            val context = index.buildPromptContext(noTerms, DictionaryContextPolicy())
            assertThat(context).isNotEmpty()
            assertThat(context).isEqualTo(skeleton)
            assertThat(charCountOf(context)).isEqualTo(130)
        }

        @Test
        @DisplayName("예산을 끄더라도 같은 골격이다 — 골격은 예산이 만든 결과가 아니다")
        fun `예산과 무관하게 골격을 낸다`() {
            assertThat(index.buildPromptContext("안내입니다.", unlimited)).isEqualTo(skeleton)
        }

        @Test
        @DisplayName("골격만으로 비율 상한을 넘기면 잘림 안내가 붙는다")
        fun `짧은 문서에서는 골격에 잘림 안내가 붙는다`() {
            // 물리적 하한(130자) 아래로 예산이 내려가면 참조 구현은 항목이 0개여도 안내를 켠다.
            // 예문 감축·항목 제거로도 예산을 못 맞추므로 마지막 시도가 그대로 나온다.
            val expected =
                skeleton + "\n(용어 0개 중 0개만 표시했습니다. " +
                    "위험도·우선순위가 높은 항목을 우선했으며, 일부가 생략되었습니다.)\n"
            assertThat(index.buildPromptContext("안내입니다.", DictionaryContextPolicy()))
                .isEqualTo(expected)
        }

        @Test
        @DisplayName("골격뿐인 결과는 실린 항목도 찾은 항목도 0이다")
        fun `골격의 항목 수는 0이다`() {
            val rendered = index.renderPromptContext(noTerms, DictionaryContextPolicy())
            assertThat(rendered.renderedTerms).isZero()
            assertThat(rendered.totalTerms).isZero()
            assertThat(rendered.text).isEqualTo(skeleton)
        }
    }

    @Nested
    @DisplayName("계층적 상세도")
    inner class DetailTiers {
        private fun contextFor(entry: DictionaryEntry): String =
            DictionaryFixture()
                .add(entry)
                .build()
                .buildPromptContext("${entry.term} 안내입니다.", unlimited)

        @Test
        @DisplayName("substitute + risk none 은 최소 상세도 — 지시문 한 줄뿐이다")
        fun `substitute 는 이유 줄을 붙이지 않는다`() {
            val context =
                contextFor(
                    DictionaryEntry(
                        term = "지참",
                        easyTerm = "가져오기",
                        strategy = ReplaceStrategy.SUBSTITUTE,
                        risk = RiskLevel.NONE,
                        priority = 120,
                        definition = "가지고 옴",
                        caution = "이 줄은 나오면 안 된다",
                    ),
                )
            assertThat(context).contains("- 지참 → 가져오기")
            assertThat(context).doesNotContain("이유:")
            assertThat(context).doesNotContain("주의:")
        }

        @Test
        @DisplayName("risk high 는 전략과 무관하게 최대 상세도로 올린다")
        fun `고위험 substitute 도 이유와 주의를 싣는다`() {
            val context =
                contextFor(
                    DictionaryEntry(
                        term = "부과",
                        easyTerm = "매김",
                        strategy = ReplaceStrategy.SUBSTITUTE,
                        risk = RiskLevel.HIGH,
                        priority = 120,
                        definition = "세금을 물림",
                        caution = "금액 표기를 함께 확인하세요.",
                    ),
                )
            assertThat(context).contains("  이유: 세금을 물림")
            assertThat(context).contains("  주의: 금액 표기를 함께 확인하세요.")
        }

        @Test
        @DisplayName("gloss + risk low 는 중간 상세도 — 주의 줄은 붙지 않는다")
        fun `중간 상세도는 이유까지만 싣는다`() {
            val context =
                contextFor(
                    DictionaryEntry(
                        term = "차상위",
                        easyTerm = "형편이 조금 나은",
                        strategy = ReplaceStrategy.GLOSS,
                        risk = RiskLevel.LOW,
                        priority = 130,
                        definition = "소득이 기준보다 조금 높은",
                        caution = "이 줄은 나오면 안 된다",
                    ),
                )
            assertThat(context).contains("  이유: 소득이 기준보다 조금 높은")
            assertThat(context).doesNotContain("주의:")
        }

        @Test
        @DisplayName("definition 이 easy_term 과 실질적으로 같으면 이유 줄을 생략한다")
        fun `뜻풀이 중복은 싣지 않는다`() {
            val context =
                contextFor(
                    DictionaryEntry(
                        term = "내방",
                        easyTerm = "찾아옴",
                        strategy = ReplaceStrategy.GLOSS,
                        risk = RiskLevel.LOW,
                        priority = 120,
                        definition = "찾아옴.",
                    ),
                )
            assertThat(context).contains("- 내방 — 뜻: 찾아옴")
            assertThat(context).doesNotContain("이유:")
        }

        @Test
        @DisplayName("keep 은 표제어만 싣고 화살표를 붙이지 않는다")
        fun `keep 항목은 원어만 보여준다`() {
            val context =
                contextFor(
                    DictionaryEntry(
                        term = "국민기초생활 보장법",
                        easyTerm = "국민기초생활 보장법",
                        strategy = ReplaceStrategy.KEEP,
                        risk = RiskLevel.HIGH,
                        priority = 200,
                        definition = "법 이름입니다.",
                        caution = "법령명은 절대 바꾸지 않습니다.",
                    ),
                )
            assertThat(context).contains("- 국민기초생활 보장법\n  이유: 법 이름입니다.")
            assertThat(context).contains("  주의: 법령명은 절대 바꾸지 않습니다.")
            assertThat(context).doesNotContain("→")
        }
    }

    @Nested
    @DisplayName("예산 파이프라인")
    inner class Budget {
        // 위험도·우선순위가 서로 다른 9개 용어. substitute 다섯은 전부 risk=none 이라
        // 보호가 없으면 잘림에서 언제나 가장 먼저 통째로 사라진다(§7.2 실측 문서 051).
        private val text = "가가 나나 다다 라라 마마 바바 사사 아아 자자 안내입니다."
        private val nonReservedTerms = listOf("가가", "나나", "다다", "라라", "사사", "아아", "자자")
        private val probedBudgets = 150..400

        private val index =
            DictionaryFixture()
                .add(gloss("가가", RiskLevel.HIGH, 190))
                .add(gloss("나나", RiskLevel.HIGH, 180))
                .add(
                    DictionaryEntry(
                        term = "다다",
                        easyTerm = "다다",
                        strategy = ReplaceStrategy.KEEP,
                        risk = RiskLevel.LOW,
                        priority = 170,
                        definition = "바꾸면 안 되는 이름입니다.",
                        caution = "제도 명칭이라 그대로 두세요.",
                    ),
                ).add(gloss("라라", RiskLevel.LOW, 160))
                .add(substitute("마마", 150))
                .add(substitute("바바", 140))
                .add(substitute("사사", 130))
                .add(substitute("아아", 120))
                .add(substitute("자자", 110))
                .build()

        private fun gloss(
            term: String,
            risk: RiskLevel,
            priority: Int,
        ) = DictionaryEntry(
            term = term,
            easyTerm = "쉬운$term",
            strategy = ReplaceStrategy.GLOSS,
            risk = risk,
            priority = priority,
        )

        private fun substitute(
            term: String,
            priority: Int,
        ) = DictionaryEntry(
            term = term,
            easyTerm = "쉬운$term",
            strategy = ReplaceStrategy.SUBSTITUTE,
            risk = RiskLevel.NONE,
            priority = priority,
        )

        @Test
        @DisplayName("maxTerms 로 잘리면 잘림 안내가 반드시 붙는다")
        fun `잘림을 조용히 넘기지 않는다`() {
            val context = index.buildPromptContext(text, unlimited.copy(maxTerms = 4, minSubstitute = 0))
            assertThat(context).endsWith(
                "(용어 9개 중 4개만 표시했습니다. 위험도·우선순위가 높은 항목을 우선했으며, 일부가 생략되었습니다.)\n",
            )
        }

        @Test
        @DisplayName("maxTerms 잘림은 risk → priority 내림차순으로 상위만 남긴다")
        fun `중요도 순으로 남긴다`() {
            val context = index.buildPromptContext(text, unlimited.copy(maxTerms = 4, minSubstitute = 0))
            assertThat(context).contains("- 가가", "- 나나", "- 다다", "- 라라")
            assertThat(context).doesNotContain("- 마마")
        }

        @Test
        @DisplayName("min_substitute 예약석은 maxTerms 잘림에서 보호된다")
        fun `예약석이 maxTerms 잘림을 견딘다`() {
            val context = index.buildPromptContext(text, unlimited.copy(maxTerms = 4, minSubstitute = 2))
            assertThat(context).contains("- 마마 → 쉬운마마", "- 바바 → 쉬운바바")
            assertThat(context).doesNotContain("- 다다", "- 라라")
        }

        @Test
        @DisplayName("min_substitute 예약석은 maxChars 항목 제거에서도 마지막까지 남는다")
        fun `예약석이 maxChars 제거를 견딘다`() {
            var reservedOutlivedOthers = false
            for (budget in probedBudgets) {
                val context =
                    index.buildPromptContext(text, unlimited.copy(maxChars = budget, minSubstitute = 2))
                val shownOthers = nonReservedTerms.filter { context.contains("- $it") }
                if (!context.contains("- 마마 → ")) {
                    assertThat(shownOthers)
                        .withFailMessage(
                            "예약석(마마)이 비예약석보다 먼저 제거됐다. 예산 %s 에서 남은 항목: %s",
                            budget,
                            shownOthers,
                        ).isEmpty()
                } else if (shownOthers.isEmpty()) {
                    reservedOutlivedOthers = true
                }
            }
            assertThat(reservedOutlivedOthers)
                .withFailMessage("어떤 예산에서도 예약석만 남는 상태가 나오지 않았다 — 제거 순서를 확인하라")
                .isTrue()
        }

        @Test
        @DisplayName("§7.2.1 불변식: 표시된 항목은 언제나 자기 위험도에 맞는 완전한 설명을 갖는다")
        fun `예산이 빠듯해도 상세도를 낮추지 않는다`() {
            for (budget in probedBudgets) {
                val context = index.buildPromptContext(text, unlimited.copy(maxChars = budget))
                if (context.contains("- 다다")) {
                    assertThat(context)
                        .withFailMessage("keep 항목이 예산 %s 에서 이유·주의를 잃었다:%n%s", budget, context)
                        .contains("  이유: 바꾸면 안 되는 이름입니다.", "  주의: 제도 명칭이라 그대로 두세요.")
                }
            }
        }

        @Test
        @DisplayName("maxChars 와 maxCharsRatio 가 함께 오면 작은 쪽을 쓴다")
        fun `비율 상한이 더 작으면 그쪽을 따른다`() {
            val short = "가가 나나"
            val withoutRatio = index.buildPromptContext(short, unlimited)
            val withRatio = index.buildPromptContext(short, unlimited.copy(maxCharsRatio = 1.0))

            assertThat(withoutRatio).contains("- 가가 — 뜻: 쉬운가가")
            assertThat(withRatio).doesNotContain("- 가가")
            assertThat(withRatio).contains("용어 2개 중 0개만 표시했습니다.")
        }

        @Test
        @DisplayName("항목을 다 비워도 물리적 하한을 못 맞추면 최선을 돌려준다")
        fun `하한 아래 예산에서도 블록을 돌려준다`() {
            val context = index.buildPromptContext(text, unlimited.copy(maxChars = 1))
            assertThat(context).contains("## 이 문서에 나온 어려운 말")
            assertThat(charCountOf(context)).isGreaterThan(1)
        }

        @Test
        @DisplayName("실린 항목 수를 함께 돌려준다 — 배선이 출력 문자열을 훑지 않고 주입 여부를 정한다")
        fun `렌더된 항목 수를 돌려준다`() {
            val full = index.renderPromptContext(text, unlimited)
            assertThat(full.renderedTerms).isEqualTo(full.totalTerms)

            val termTruncated = index.renderPromptContext(text, unlimited.copy(maxTerms = 4, minSubstitute = 0))
            assertThat(termTruncated.totalTerms).isEqualTo(9)
            assertThat(termTruncated.renderedTerms).isEqualTo(4)

            // 매칭은 9건인데 실린 것은 0건 — 「찾은 게 없다」와 다른 상태이고, 이 둘을 개수로
            // 가를 수 있어야 배선이 골격 주입을 막을 수 있다.
            val emptied = index.renderPromptContext(text, unlimited.copy(maxChars = 1))
            assertThat(emptied.totalTerms).isEqualTo(9)
            assertThat(emptied.renderedTerms).isZero()
        }

        @Test
        @DisplayName("buildPromptContext 는 같은 렌더링의 문자열이다 — 기존 호출자의 반환값이 바뀌지 않는다")
        fun `문자열 창구가 같은 결과를 돌려준다`() {
            for (policy in listOf(unlimited, unlimited.copy(maxTerms = 4), unlimited.copy(maxChars = 1))) {
                assertThat(index.buildPromptContext(text, policy))
                    .isEqualTo(index.renderPromptContext(text, policy).text)
            }
        }
    }

    @Nested
    @DisplayName("참고 예문")
    inner class Examples {
        private val longBefore = "이 안내문을 확인하신 뒤 기한 안에 서류를 제출하여 주시기 바랍니다."
        private val longAfter = "이 안내문을 확인한 뒤 정해진 날짜 안에 서류를 내 주시기 바랍니다."

        private fun exampleAt(order: Int) =
            DictionaryExample(
                before = "${order}번 $longBefore",
                after = "${order}번 $longAfter",
                isGolden = order == 3,
            )

        private val index =
            DictionaryFixture()
                .add(
                    DictionaryEntry(
                        term = "마마",
                        easyTerm = "쉬운마마",
                        strategy = ReplaceStrategy.SUBSTITUTE,
                        risk = RiskLevel.NONE,
                        priority = 150,
                        examples = listOf(exampleAt(1), exampleAt(2)),
                    ),
                ).add(
                    DictionaryEntry(
                        term = "바바",
                        easyTerm = "쉬운바바",
                        strategy = ReplaceStrategy.SUBSTITUTE,
                        risk = RiskLevel.NONE,
                        priority = 140,
                        examples = listOf(exampleAt(3), exampleAt(4)),
                    ),
                ).build()

        private val text = "마마 바바 안내입니다."

        @Test
        @DisplayName("is_golden 예문을 먼저, 그 안에서는 priority 높은 엔트리를 먼저 싣는다")
        fun `검수 완료 예문을 우선한다`() {
            val context = index.buildPromptContext(text, unlimited)
            assertThat(context).contains("### 참고 예문")
            // 3번만 검수 완료라, priority 가 더 낮은 '바바'의 예문인데도 맨 앞에 온다.
            assertThat(context.indexOf("- 전: 3번")).isLessThan(context.indexOf("- 전: 1번"))
            // 검수되지 않은 것끼리는 엔트리 priority 순 — '마마'(150)가 '바바'(140)보다 먼저다.
            assertThat(context.indexOf("- 전: 1번")).isLessThan(context.indexOf("- 전: 2번"))
            assertThat(context).doesNotContain("- 전: 4번")
        }

        @Test
        @DisplayName("예산이 모자라면 항목이 아니라 예문부터 줄인다")
        fun `예문을 먼저 줄인다`() {
            val full = index.buildPromptContext(text, unlimited)
            val reduced =
                index.buildPromptContext(text, unlimited.copy(maxChars = charCountOf(full) - 1))

            assertThat(reduced).contains("- 마마 → 쉬운마마", "- 바바 → 쉬운바바")
            assertThat(reduced.split("  후: ")).hasSizeLessThan(full.split("  후: ").size)
        }

        @Test
        @DisplayName("§7.2.2 gloss 예문은 sentence 스타일 지시와 모순되므로 풀에서 뺀다")
        fun `gloss 엔트리 예문은 싣지 않는다`() {
            val glossIndex =
                DictionaryFixture()
                    .add(
                        DictionaryEntry(
                            term = "가가",
                            easyTerm = "쉬운가가",
                            strategy = ReplaceStrategy.GLOSS,
                            risk = RiskLevel.HIGH,
                            priority = 190,
                            examples = listOf(exampleAt(9)),
                        ),
                    ).add(
                        DictionaryEntry(
                            term = "마마",
                            easyTerm = "쉬운마마",
                            strategy = ReplaceStrategy.SUBSTITUTE,
                            risk = RiskLevel.NONE,
                            priority = 150,
                            examples = listOf(exampleAt(1)),
                        ),
                    ).build()

            val context = glossIndex.buildPromptContext("가가 마마 안내입니다.", unlimited)
            assertThat(context).contains("- 전: 1번")
            assertThat(context).doesNotContain("- 전: 9번")
        }
    }

    @Nested
    @DisplayName("정책 기본값")
    inner class PolicyDefaults {
        @Test
        @DisplayName("기본값은 easy-doc 연동 문서 §4 의 실측 권장값이다")
        fun `실측 권장값을 기본값으로 든다`() {
            val policy = DictionaryContextPolicy()
            assertThat(policy.maxTerms).isEqualTo(40)
            assertThat(policy.maxChars).isEqualTo(4000)
            assertThat(policy.maxCharsRatio).isEqualTo(1.0)
            assertThat(policy.minSubstitute).isEqualTo(5)
            assertThat(policy.maxExamples).isEqualTo(3)
        }
    }
}
