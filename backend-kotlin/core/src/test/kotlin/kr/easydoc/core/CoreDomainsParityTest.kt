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

/** `core` 가 소유한 다섯 도메인의 parity 산출물 생산자. */
class CoreDomainsParityTest {
    private companion object {
        /** 프롬프트 구분자 id 를 고정한다. 실행마다 달라지면 스냅샷 대조가 무의미해진다. */
        val FIXED_IDS = DocumentIdGenerator { "0123456789ab" }

        /** 자리표시자를 되돌릴 합성값. 실제 개인정보가 아니다 — fixture 자신이 쓰는 표기다. */
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
                    "MAX_SENTENCE_CHARS" to JsonPrimitive(MAX_SENTENCE_CHARS),
                    "MAX_COMMAS_PER_SENTENCE" to JsonPrimitive(MAX_COMMAS_PER_SENTENCE),
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

    /** 마스킹된 본문에서 마스킹 전 원문을 되만든다. */
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
