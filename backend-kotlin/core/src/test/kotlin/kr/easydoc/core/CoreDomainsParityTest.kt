package kr.easydoc.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.easydoc.core.easyread.COMPOUND_HEAD_NOUNS
import kr.easydoc.core.easyread.COMPOUND_TAIL_KEYS
import kr.easydoc.core.easyread.DIFFICULT_WORD_REPLACEMENTS
import kr.easydoc.core.easyread.DOUBLE_PASSIVE_PATTERNS
import kr.easydoc.core.easyread.DocumentIdGenerator
import kr.easydoc.core.easyread.LEXICALIZED_GLOSSES
import kr.easydoc.core.easyread.MAX_COMMAS_PER_SENTENCE
import kr.easydoc.core.easyread.MAX_SENTENCE_CHARS
import kr.easydoc.core.easyread.MODIFIER_CHECKED_GLOSSES
import kr.easydoc.core.easyread.NOMINAL_GLOSSES
import kr.easydoc.core.easyread.PROMPT_ONLY_WORDS
import kr.easydoc.core.easyread.STYLE_PRINCIPLES
import kr.easydoc.core.easyread.SentenceIssue
import kr.easydoc.core.easyread.StyleRuleKind
import kr.easydoc.core.easyread.buildRepairPrompt
import kr.easydoc.core.easyread.buildSystemPrompt
import kr.easydoc.core.easyread.buildUserPrompt
import kr.easydoc.core.easyread.checkStyle
import kr.easydoc.core.easyread.findDifficultWords
import kr.easydoc.core.easyread.findGlossCollisions
import kr.easydoc.core.easyread.postprocess
import kr.easydoc.core.easyread.splitSentences
import kr.easydoc.core.parity.ParityActual
import kr.easydoc.core.parity.ParityCase
import kr.easydoc.core.parity.ParityFixtures
import kr.easydoc.core.privacy.MaskCategory
import kr.easydoc.core.privacy.ModelDraft
import kr.easydoc.core.privacy.maskText
import kr.easydoc.core.text.stripControlChars
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * `core` 가 소유한 다섯 도메인의 parity 산출물 생산자.
 *
 * `text` · `style` · `style-tables` · `prompts` · `postprocess`.
 *
 * ## 값을 판정하지 않는다
 *
 * fixture 의 `assert`·`reference` 를 읽지 않는다([ParityFixtures] 가 애초에 주지 않는다).
 * 여기서 하는 일은 `input` 을 core 함수에 넣고 결과를 비교기가 읽는 모양으로 적는 것뿐이다.
 * **기대값을 볼 수 있으면 그것에 맞추는 코드를 쓰게 된다.**
 *
 * 도메인마다 별도 `@Test` 인 이유: 하나가 깨졌을 때 나머지 넷의 산출물은 그대로 나와야
 * 어느 도메인이 원인인지 즉시 갈린다. 한 함수에 묶으면 첫 실패에서 멈춘다.
 */
class CoreDomainsParityTest {
    private companion object {
        /** 프롬프트 구분자 id 를 고정한다. 실행마다 달라지면 스냅샷 대조가 무의미해진다. */
        val FIXED_IDS = DocumentIdGenerator { "0123456789ab" }

        /**
         * 자리표시자를 되돌릴 합성값. **실제 개인정보가 아니다** — fixture 자신이 쓰는 표기다.
         *
         * 범주가 늘면 여기도 늘어야 한다. 빠뜨리면 `preimageOf` 의 왕복 단언이 막는다.
         */
        val SAMPLES =
            mapOf(
                MaskCategory.RRN to "900101-1234567",
                MaskCategory.CARD to "4111-1111-1111-1111",
            )
    }

    @Test
    @Tag("parity")
    @DisplayName("text — 제어문자 제거")
    fun `text 산출물을 만든다`() {
        write("text") { input ->
            JsonObject(mapOf("text" to JsonPrimitive(stripControlChars(input.string("text")))))
        }
    }

    @Test
    @Tag("parity")
    @DisplayName("style — 문장 분리와 길이·쉼표 규칙")
    fun `style 산출물을 만든다`() {
        write("style") { input ->
            val text = input.string("text")
            val sentences = splitSentences(text)
            val issues = checkStyle(text).issues

            // 비교기는 **산출물이 스스로 보고한 `sentences`** 에 규칙을 다시 적용해 대조한다.
            // 문장 분리 경계는 휴리스틱이라 요구사항으로 적히지 않으므로 판정하지 않고,
            // "그 문장들을 받았을 때 규칙을 같게 적용하는가"만 본다.
            //
            // 두 목록을 `kind` 로 가른다 — 사유 문자열을 되파싱하지 않는다(그 문구는 보정
            // 프롬프트에 실려 앞으로도 다듬어진다).
            // 단언이 요구하는 것은 앞의 셋뿐이지만 참고값이 가진 모양을 함께 낸다 —
            // 산출물 모양이 참고값보다 좁으면 **원장이 매번 "갈림"으로 찍혀** 진짜 값 차이를
            // 덮는다. 원장은 값이 갈린 자리를 위한 것이지 필드 개수가 다른 자리가 아니다.
            JsonObject(
                mapOf(
                    "sentences" to JsonArray(sentences.map(::JsonPrimitive)),
                    "length_violations" to violations(issues, StyleRuleKind.LENGTH),
                    "comma_violations" to violations(issues, StyleRuleKind.COMMA),
                    "difficult_words" to strings(findDifficultWords(text)),
                    "gloss_collisions" to strings(findGlossCollisions(text)),
                    "check_style" to
                        JsonObject(
                            mapOf(
                                "total_sentences" to JsonPrimitive(sentences.size),
                                "issues" to
                                    JsonArray(
                                        issues.map { issue ->
                                            JsonObject(
                                                mapOf(
                                                    "sentence" to JsonPrimitive(issue.sentence),
                                                    "reason" to JsonPrimitive(issue.reason),
                                                    // `JsonPrimitive(String?)` 은 null 에
                                                    // JsonNull 을 준다 — 키를 빼지 않는다.
                                                    "word" to JsonPrimitive(issue.word),
                                                ),
                                            )
                                        },
                                    ),
                            ),
                        ),
                ),
            )
        }
    }

    @Test
    @Tag("parity")
    @DisplayName("style-tables — 정책 상수와 큐레이션 표")
    fun `style-tables 산출물을 만든다`() {
        write("style-tables") {
            JsonObject(
                mapOf(
                    // 값이 같아야 하는 것 — 프롬프트가 이 숫자를 문구에 박아 쓰고 채점도
                    // 같은 숫자를 쓴다. 갈리면 지시한 것과 채점하는 것이 달라진다.
                    "MAX_SENTENCE_CHARS" to JsonPrimitive(MAX_SENTENCE_CHARS),
                    "MAX_COMMAS_PER_SENTENCE" to JsonPrimitive(MAX_COMMAS_PER_SENTENCE),
                    // 표제어를 잃지 않아야 하는 것 — 누락 금지·추가 허용(`contains_all`).
                    // 값으로 통째 비교하면 사전에 한 항목을 더하는 순간 개선이 회귀로 잡힌다.
                    "STYLE_PRINCIPLES" to strings(STYLE_PRINCIPLES),
                    "DIFFICULT_WORD_REPLACEMENTS" to
                        JsonObject(
                            DIFFICULT_WORD_REPLACEMENTS.mapValues { (_, gloss) ->
                                JsonPrimitive(gloss)
                            },
                        ),
                    "PROMPT_ONLY_WORDS" to strings(PROMPT_ONLY_WORDS),
                    "DOUBLE_PASSIVE_PATTERNS" to strings(DOUBLE_PASSIVE_PATTERNS),
                    "COMPOUND_HEAD_NOUNS" to strings(COMPOUND_HEAD_NOUNS),
                    "COMPOUND_TAIL_KEYS" to strings(COMPOUND_TAIL_KEYS),
                    "LEXICALIZED_GLOSSES" to strings(LEXICALIZED_GLOSSES),
                    "NOMINAL_GLOSSES" to strings(NOMINAL_GLOSSES),
                    "MODIFIER_CHECKED_GLOSSES" to strings(MODIFIER_CHECKED_GLOSSES),
                    "counts" to
                        JsonObject(
                            mapOf(
                                "MAX_SENTENCE_CHARS" to JsonPrimitive(MAX_SENTENCE_CHARS),
                                "MAX_COMMAS_PER_SENTENCE" to JsonPrimitive(MAX_COMMAS_PER_SENTENCE),
                                "DOUBLE_PASSIVE_PATTERNS" to JsonPrimitive(DOUBLE_PASSIVE_PATTERNS.size),
                                "STYLE_PRINCIPLES" to JsonPrimitive(STYLE_PRINCIPLES.size),
                                "DIFFICULT_WORD_REPLACEMENTS" to
                                    JsonPrimitive(DIFFICULT_WORD_REPLACEMENTS.size),
                                "PROMPT_ONLY_WORDS" to JsonPrimitive(PROMPT_ONLY_WORDS.size),
                                "LEXICALIZED_GLOSSES" to JsonPrimitive(LEXICALIZED_GLOSSES.size),
                                "COMPOUND_HEAD_NOUNS" to JsonPrimitive(COMPOUND_HEAD_NOUNS.size),
                                "COMPOUND_TAIL_KEYS" to JsonPrimitive(COMPOUND_TAIL_KEYS.size),
                                "NOMINAL_GLOSSES" to JsonPrimitive(NOMINAL_GLOSSES.size),
                                "MODIFIER_CHECKED_GLOSSES" to
                                    JsonPrimitive(MODIFIER_CHECKED_GLOSSES.size),
                            ),
                        ),
                ),
            )
        }
    }

    @Test
    @Tag("parity")
    @DisplayName("prompts — 시스템·사용자·보정 프롬프트")
    fun `prompts 산출물을 만든다`() {
        write("prompts") { input ->
            val maskedText = input.string("masked_text")
            val violations = input.violations()

            // **마스킹된 본문을 직접 감쌀 수 없다.** `MaskedText` 를 만드는 통로는 `maskText`
            // 하나뿐이고, 그것이 마스킹 선행 불변식의 실체다(`Masking.kt` KDoc).
            // 그래서 fixture 의 `masked_text` 를 **되돌린 원문**을 만들어 마스킹을 통과시킨다 —
            // 프로덕션에서 실제로 일어나는 순서와 같다. 되돌린 결과가 fixture 와 한 글자라도
            // 다르면 아래 단언이 막는다(조용히 다른 프롬프트를 내지 않는다).
            val masking = maskText(preimageOf(maskedText))
            assertThat(masking.maskedText.value)
                .withFailMessage(
                    "마스킹 왕복이 fixture 의 masked_text 와 다르다. preimageOf 의 합성값이 " +
                        "그 자리표시자를 되살리지 못했다 — 새 범주가 늘었는지 확인하라.",
                ).isEqualTo(maskedText)

            val fields =
                mutableMapOf<String, JsonElement>(
                    "system_prompt" to JsonPrimitive(buildSystemPrompt(masking.maskedText)),
                    "user_prompt" to JsonPrimitive(buildUserPrompt(masking.maskedText, FIXED_IDS)),
                )
            // ModelDraft 로 감싸도 되는 자리다 — 값의 출처가 "1차 변환문"이고, 여기서는
            // fixture 가 그 자리에 둔 본문이다(`Masking.kt` provenance 규약).
            val repair = buildRepairPrompt(ModelDraft(maskedText), violations, FIXED_IDS)
            fields["repair_system_prompt"] = JsonPrimitive(repair.system)
            fields["repair_user_prompt"] = JsonPrimitive(repair.user)
            JsonObject(fields)
        }
    }

    @Test
    @Tag("parity")
    @DisplayName("postprocess — 껍데기 제거")
    fun `postprocess 산출물을 만든다`() {
        write("postprocess") { input ->
            JsonObject(mapOf("text" to JsonPrimitive(postprocess(input.string("raw")))))
        }
    }

    // ── 공통 ────────────────────────────────────────────────────────────────

    private fun write(
        domain: String,
        produce: (JsonObject) -> JsonElement,
    ) {
        val cases = ParityFixtures.cases(domain)
        val produced = cases.map { ParityCase(id = it.id, actual = produce(it.input)) }

        val written = ParityActual.write(domain, "$domain.json", produced)

        assertThat(produced).hasSameSizeAs(cases)
        assertThat(written.fileName.toString()).isEqualTo("$domain.json")
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.content
            ?: error("fixture 입력에 `$key` 가 없다 — fixture 형식이 바뀌었는지 확인하라")

    private fun JsonObject.violations(): List<SentenceIssue> =
        this["violations"]?.jsonArray?.map { element ->
            val entry = element.jsonObject
            SentenceIssue(
                sentence = entry.string("sentence"),
                // 이 도메인은 `kind` 를 판정하지 않는다 — 프롬프트 문면에 실리는 것은
                // 사유와 낱말뿐이다. fixture 의 위반은 전부 어려운 표현 잔존이다.
                kind = StyleRuleKind.DIFFICULT_WORD,
                reason = entry.string("reason"),
                word = entry["word"]?.jsonPrimitive?.content,
            )
        } ?: emptyList()

    private fun violations(
        issues: List<SentenceIssue>,
        kind: StyleRuleKind,
    ): JsonArray = JsonArray(issues.filter { it.kind == kind }.map { JsonPrimitive(it.sentence) })

    private fun strings(values: Iterable<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

    /**
     * 마스킹된 본문에서 **마스킹 전 원문**을 되만든다.
     *
     * 자리표시자를 그 범주의 합성값으로 되돌린다. 값은 이 파일에서 만든 것이고 실제
     * 개인정보가 아니다 — fixture 자신이 쓰는 표기를 따랐다.
     */
    private fun preimageOf(maskedText: String): String {
        var source = maskedText
        for (category in MaskCategory.entries) {
            val sample = SAMPLES.getValue(category)
            var ordinal = 1
            while ("[[${category.label}$ordinal]]" in source) {
                source = source.replace("[[${category.label}$ordinal]]", sample)
                ordinal++
            }
        }
        return source
    }
}
