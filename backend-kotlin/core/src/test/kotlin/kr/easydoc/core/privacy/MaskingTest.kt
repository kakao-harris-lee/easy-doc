package kr.easydoc.core.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 마스킹 파이프라인 — **보안 불변식**(`CLAUDE.md` 아키텍처 규칙 2)의 구현.
 *
 * 검증 축이 셋이다.
 *
 * 1. **빠짐없이 가린다** — 주민등록번호(외국인등록번호 포함)·카드번호가 자릿수·구분자
 *    변형이나 보이지 않는 문자 삽입으로 새어 나가지 않는다.
 * 2. **넘치게 가리지 않는다** — 범주는 2종뿐이고, 자릿수가 어긋난 숫자열은 건드리지
 *    않는다. 과잉 마스킹은 안내문의 팩트(금액·전화번호)를 지운다.
 * 3. **정확히 복원된다** — 자리표시자를 되돌리면 입력이 한 글자도 다르지 않다.
 *    이것이 깨지면 내보내기가 잘못된 원문을 꽂는다.
 * 4. **검수를 거치지 않은 본문에는 개인정보를 꽂지 않는다** — 위치를 확증하는 것은 사람뿐이다.
 *    이것이 깨지면 시민의 주민등록번호가 엉뚱한 자리에 박힌 문서가 배포된다.
 *
 * 복원은 **제품 코드의 [restoreForExport]** 로만 검증한다. 예전에는 이 파일 안에서
 * `replace` 로 되돌려 비교했는데, 그것은 테스트가 자기 헬퍼의 왕복을 증명한 것이지
 * 제품이 복원할 수 있다는 증거가 아니었다 — 실제로 그 헬퍼는 입력에 이미 있던
 * 자리표시자를 개인정보로 바꾸는 결함을 통과시켰다.
 *
 * 보이지 않는 문자는 소스에 리터럴로 적지 않는다 — 전부 `\uXXXX` 다.
 */
class MaskingTest {
    /**
     * 사람 검수를 거친 본문으로 복원한다.
     *
     * 제품 함수를 그대로 부르는 **얇은 어댑터**다 — 복원 로직을 다시 쓰지 않는다(클래스
     * KDoc 의 경고 참고). 담당자가 검수 화면에서 초안을 고치지 않고 그대로 제출한 경우와
     * 같다(`edited_text == easy_text`).
     */
    private fun restoreReviewed(
        text: String,
        items: List<MaskedItem>,
    ) = restoreForExport(ModelDraft(text), ReviewedBody(text), items)

    @Nested
    @DisplayName("주민등록번호")
    inner class RrnMasking {
        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                "900101-1234567",
                "9001011234567",
                "900101 - 1234567",
                "900101\t-\t1234567",
                "900101-5234567",
                "900101-8234567",
            ],
        )
        @DisplayName("구분자·공백 변형과 외국인등록번호(성별코드 5~8)를 모두 가린다")
        fun `표기 변형을 가린다`(rrn: String) {
            val result = maskText("주민번호는 $rrn 입니다.")

            assertThat(result.maskedText.value).isEqualTo("주민번호는 [[주민등록번호1]] 입니다.")
            val item = result.items.single()
            assertThat(item.category).isEqualTo(MaskCategory.RRN)
            assertThat(item.original.reveal()).isEqualTo(rrn)
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                "900101-9234567",
                "900101-0234567",
                "900101-123456",
                "90010-1234567",
                "90010112345678",
            ],
        )
        @DisplayName("성별코드나 자릿수가 어긋나면 가리지 않는다")
        fun `자릿수 오차는 가리지 않는다`(notRrn: String) {
            // 과잉 마스킹은 안내문의 팩트를 지운다. 자릿수는 판정의 근거이지 여유분이 아니다.
            val text = "번호는 $notRrn 입니다."
            val result = maskText(text)

            assertThat(result.maskedText.value).isEqualTo(text)
            assertThat(result.items).isEmpty()
        }
    }

    @Nested
    @DisplayName("카드번호")
    inner class CardMasking {
        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                "1234-5678-9012-3456",
                "1234 5678 9012 3456",
                "1234567890123456",
            ],
        )
        @DisplayName("구분자 변형을 가린다")
        fun `구분자 변형을 가린다`(card: String) {
            val result = maskText("카드 $card 로 결제")

            assertThat(result.maskedText.value).isEqualTo("카드 [[카드번호1]] 로 결제")
            val item = result.items.single()
            assertThat(item.category).isEqualTo(MaskCategory.CARD)
            assertThat(item.original.reveal()).isEqualTo(card)
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = ["123456789012345", "12345678901234567"])
        @DisplayName("15자리·17자리는 카드번호가 아니다")
        fun `자릿수 경계를 지킨다`(notCard: String) {
            val text = "번호 $notCard 입니다."
            assertThat(maskText(text).maskedText.value).isEqualTo(text)
        }
    }

    @Nested
    @DisplayName("보이지 않는 문자를 이용한 회피")
    inner class InvisibleCharEvasion {
        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                "900101\u00AD1234567",
                "900101\u200B-1234567",
                "900101-123\u00004567",
            ],
        )
        @DisplayName("숫자 사이에 보이지 않는 문자가 끼어도 가린다")
        fun `회피 경로를 막는다`(evasive: String) {
            // 악의적 회피가 아니라 **사고성 유입**이 주 경로다. 실제 정부 문서 코퍼스에서
            // 소프트하이픈·NUL 이 하이픈 자리를 대신한 사례가 실측됐고, PDF 추출과 붙여넣기
            // 경로에는 이를 걸러 주는 것이 아무것도 없다. 피해자는 문서에 등장하는 제3자
            // 시민이고, 누락은 조용해서 담당자도 알아채지 못한다.
            val result = maskText("주민번호 $evasive 끝")

            assertThat(result.maskedText.value).isEqualTo("주민번호 [[주민등록번호1]] 끝")
            // 잘라내는 것은 언제나 원문이다 — 낀 문자가 original 에 그대로 들어가야 복원된다.
            val item = result.items.single()
            assertThat(item.original.reveal()).isEqualTo(evasive)
        }

        @Test
        @DisplayName("뷰에서만 잡히는 경계는 원문 좌표로 되돌린다")
        fun `앞에 붙은 보이지 않는 문자를 삼키지 않는다`() {
            // 뷰에서는 앞 숫자가 붙어 lookbehind 가 깨지지만 원문에서는 성립한다.
            // 두 경로의 합집합이라 현행 적중을 잃지 않는다.
            val result = maskText("1\u200B900101-1234567")

            assertThat(result.maskedText.value).isEqualTo("1\u200B[[주민등록번호1]]")
            val item = result.items.single()
            assertThat(item.original.reveal()).isEqualTo("900101-1234567")
        }
    }

    @Nested
    @DisplayName("표기 변형 — 유니코드 인식 패턴 안의 ASCII 리터럴")
    inner class NotationVariants {
        // privacy-gate 판정 `07_privacy-gate_masking-verdicts.md` §1 이 실측으로 가른 두 종류다.
        // 둘 다 "가려야 할 고유식별정보·카드번호가 그대로 외부 모델로 나가는" 방향이고,
        // 아래 케이스는 전부 **수정 전에는 실패하던 것**이다(음성 대조 기록은 산출물 문서).

        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                // 전각 숫자로만 적은 주민등록번호. 성별코드가 `[1-8]` 이던 시절 통째로 통과했다.
                "９００１０１-１２３４５６７",
                // 성별코드 한 자리만 전각이어도 매치가 끊겼다 — 결함의 위치를 정확히 짚는 케이스.
                "900101-１234567",
                // 앞 6자리만 전각인 표기는 수정 전에도 잡혔다. 회귀 가드로 남긴다.
                "９００１０１-1234567",
                // 아라비아-인도 숫자 성별코드(٥ = 5, 외국인등록번호대).
                "900101-٥234567",
            ],
        )
        @DisplayName("종류 A — 성별코드가 ASCII 가 아니어도 가린다 (값으로 판정한다)")
        fun `전각 성별코드를 가린다`(rrn: String) {
            val result = maskText("주민번호는 $rrn 입니다.")

            assertThat(result.maskedText.value).isEqualTo("주민번호는 [[주민등록번호1]] 입니다.")
            assertThat(result.items.single().category).isEqualTo(MaskCategory.RRN)
            assertThat(
                result.items
                    .single()
                    .original
                    .reveal(),
            ).isEqualTo(rrn)
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                // 성별코드 9·0 거부는 이미 ASCII 로 단언돼 있다. **전각에서도 성립해야 한다** —
                // 값 판정으로 바꾸면 자동으로 성립하지만, 단언 없이 두면 다음 회차에 되돌아간다.
                "９００１０１-９２３４５６７",
                "９００１０１-０２３４５６７",
                "900101-９234567",
                "900101-０234567",
            ],
        )
        @DisplayName("종류 A 과잉 마스킹 가드 — 전각 성별코드 9·0 도 거부한다")
        fun `전각 성별코드 9와 0은 가리지 않는다`(notRrn: String) {
            val text = "번호는 $notRrn 입니다."

            assertThat(maskText(text).maskedText.value).isEqualTo(text)
            assertThat(maskText(text).items).isEmpty()
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                "900101\uFF0D1234567",
                "900101\u22121234567",
                "900101\u20131234567",
                "900101\u20141234567",
                "900101\u20101234567",
                "900101\u00A01234567",
                "900101\u30001234567",
                "900101\u20071234567",
                "900101\u202F1234567",
            ],
        )
        @DisplayName("종류 B — 구분자가 ASCII 하이픈·공백이 아니어도 가린다 (RRN)")
        fun `표기 변형 구분자를 가린다`(rrn: String) {
            val result = maskText("주민번호는 $rrn 입니다.")

            assertThat(result.maskedText.value).isEqualTo("주민번호는 [[주민등록번호1]] 입니다.")
            // 잘라내는 것은 언제나 원문이다 — 구분자가 그대로 보존돼야 복원이 성립한다.
            assertThat(
                result.items
                    .single()
                    .original
                    .reveal(),
            ).isEqualTo(rrn)
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                // 카드번호에는 종류 A 가 없다(숫자 자리가 전부 `\d`). 뚫려 있던 것은 구분자뿐이고,
                // 두 리뷰 어디도 카드번호를 보지 않아 privacy-gate 실측이 처음 찾은 자리다.
                "1234\uFF0D5678\uFF0D9012\uFF0D3456",
                "1234\u00A05678\u00A09012\u00A03456",
                "1234\u30005678\u30009012\u30003456",
                "1234\u20135678\u20139012\u20133456",
                "１２３４\uFF0D５６７８\uFF0D９０１２\uFF0D３４５６",
            ],
        )
        @DisplayName("종류 B — 구분자가 ASCII 하이픈·공백이 아니어도 가린다 (CARD)")
        fun `카드번호 표기 변형 구분자를 가린다`(card: String) {
            val result = maskText("카드 $card 로 결제")

            assertThat(result.maskedText.value).isEqualTo("카드 [[카드번호1]] 로 결제")
            assertThat(result.items.single().category).isEqualTo(MaskCategory.CARD)
            assertThat(
                result.items
                    .single()
                    .original
                    .reveal(),
            ).isEqualTo(card)
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                "900101\u000A1234567",
                "900101\u000D1234567",
                "1234\u000A5678\u000A9012\u000A3456",
            ],
        )
        @DisplayName("과잉 마스킹 가드 — 개행·캐리지리턴은 구분자가 아니다")
        fun `줄이 갈린 숫자열은 붙이지 않는다`(split: String) {
            // 구분자 집합에 `\s` 를 쓰면 여기서 두 줄의 숫자열이 이어 붙어 **진짜 과잉
            // 마스킹**이 된다. 안내문에서 접수번호와 관리번호가 연달아 적히는 표는 흔하다.
            val text = "번호 $split 을 적으세요."

            assertThat(maskText(text).maskedText.value).isEqualTo(text)
            assertThat(maskText(text).items).isEmpty()
        }
    }

    @Nested
    @DisplayName("범주는 2종뿐이다")
    inner class ScopeIsTwoCategories {
        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                "010-1234-5678",
                "02-123-4567",
                "담당자 이메일은 hong@korea.kr 입니다.",
                "계좌 123-456-789012 로 보내세요.",
                "국민은행 12345678901234 계좌",
            ],
        )
        @DisplayName("전화번호·이메일·계좌번호는 가리지 않는다 (master-plan 3.2, 2026-08-12 5종→2종)")
        fun `범위 밖 3종은 그대로 나간다`(outOfScope: String) {
            // **이 셋은 문서에 섞여 들어오면 마스킹 없이 그대로 LLM(국외 포함)으로 전송된다.**
            // 나중에 채워 넣으려고 비워 둔 자리가 아니라 명시적으로 감수하기로 한 대가다.
            // 패턴을 넓혀 다시 잡게 만드는 것은 개선이 아니라 **정책 위반**이다 — 계약·처리방침에
            // 적힌 범주(2종)보다 구현이 넓어져도 위반이다.
            val result = maskText(outOfScope)

            assertThat(result.maskedText.value).isEqualTo(outOfScope)
            assertThat(result.items).isEmpty()
        }

        @Test
        @DisplayName("가리는 범주는 정확히 둘이다")
        fun `범주가 둘뿐이다`() {
            // 계약(easy-doc-v1.yaml::MaskedItemResponse)이 이 한국어 문자열을 enum 으로
            // 못박았다 — 자리표시자의 복원 키이기도 하므로 영문 코드로 바꾸면 계약 위반이다.
            assertThat(MaskCategory.entries.map { it.label })
                .containsExactly("주민등록번호", "카드번호")
        }
    }

    @Nested
    @DisplayName("자리표시자와 복원")
    inner class PlaceholderAndRestore {
        @Test
        @DisplayName("범주별로 1부터 번호를 매긴다")
        fun `범주별 일련번호를 매긴다`() {
            val result =
                maskText("주민 900101-1234567 카드 1234-5678-9012-3456 주민 800101-2345678")

            assertThat(result.maskedText.value)
                .isEqualTo("주민 [[주민등록번호1]] 카드 [[카드번호1]] 주민 [[주민등록번호2]]")
            assertThat(result.items.map { it.placeholder })
                .containsExactly("[[주민등록번호1]]", "[[카드번호1]]", "[[주민등록번호2]]")
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                "이 안내문에는 개인정보가 없습니다.",
                // 입력에 자리표시자 모양이 이미 있는 경우 — 아래 PlaceholderCollision 참고.
                "앞 [[주민등록번호1]] 뒤 900101-1234567 끝",
                "[[카드번호1]] 만 있고 개인정보는 없다",
                "이미 탈출된 모양 [[!주민등록번호1]] 과 900101-1234567",
                "자리표시자 안에 13자리가 든 경우 [[주민등록번호1234567890123]]",
                "주민 900101-1234567 카드 1234-5678-9012-3456",
                "900101\u00AD1234567 과 1\u200B900101-1234567",
                "",
            ],
        )
        @DisplayName("자리표시자를 되돌리면 입력이 정확히 복원된다")
        fun `복원하면 입력과 같다`(input: String) {
            // 이것이 깨지면 내보내기가 잘못된 원문을 꽂는다. 마스킹 앞단에서 입력을
            // 정규화하지 않는 이유가 이 성질이다.
            val result = maskText(input)
            val restoration = restoreReviewed(result.maskedText.value, result.items)

            assertThat(restoration.text).isEqualTo(input)
            assertThat(restoration.missing).isEmpty()
            assertThat(restoration.ambiguous).isEmpty()
            assertThat(restoration.foreign).isEmpty()
        }

        @Test
        @DisplayName("개인정보가 없으면 한 글자도 바뀌지 않는다")
        fun `개인정보가 없으면 그대로다`() {
            val text = "이 안내문에는 개인정보가 없습니다."
            val result = maskText(text)

            assertThat(result.maskedText.value).isEqualTo(text)
            assertThat(result.items).isEmpty()
        }

        @Test
        @DisplayName("빈 입력에서 예외를 던지지 않는다")
        fun `빈 입력을 견딘다`() {
            val result = maskText("")

            assertThat(result.maskedText.value).isEmpty()
            assertThat(result.items).isEmpty()
        }
    }

    @Nested
    @DisplayName("입력에 이미 있던 자리표시자 모양")
    inner class PlaceholderCollision {
        @Test
        @DisplayName("같은 자리표시자가 둘이 되지 않는다 — 입력 쪽을 탈출시킨다")
        fun `입력의 자리표시자와 충돌하지 않는다`() {
            // codex stop-time 리뷰가 짚은 재현 입력. 고치기 전에는 마스킹 결과에
            // [[주민등록번호1]] 이 둘이었고, 되돌리면 원문에 원래 있던 글자까지
            // 주민등록번호로 바뀌었다.
            val input = "앞 [[주민등록번호1]] 뒤 900101-1234567 끝"
            val result = maskText(input)

            assertThat(result.maskedText.value)
                .isEqualTo("앞 [[!주민등록번호1]] 뒤 [[주민등록번호1]] 끝")
            // 우리가 만든 자리표시자는 본문에 딱 하나다. 이 성질이 복원 판정의 근거다.
            assertThat(result.maskedText.value.split("[[주민등록번호1]]")).hasSize(2)
            assertThat(restoreReviewed(result.maskedText.value, result.items).text)
                .isEqualTo(input)
        }

        @Test
        @DisplayName("탈출 표기 자체도 탈출된다 — 겹쳐도 정확히 되돌아온다")
        fun `탈출 문자를 탈출한다`() {
            val input = "[[!주민등록번호1]] 과 [[!!카드번호9]]"
            val result = maskText(input)

            assertThat(result.maskedText.value).isEqualTo("[[!!주민등록번호1]] 과 [[!!!카드번호9]]")
            assertThat(restoreReviewed(result.maskedText.value, result.items).text)
                .isEqualTo(input)
        }

        @Test
        @DisplayName("범위 밖 범주는 자리표시자가 아니므로 건드리지 않는다")
        fun `범위 밖 라벨은 탈출하지 않는다`() {
            // 마스킹하지 않는 범주의 라벨은 우리 자리표시자와 충돌할 수 없다.
            // 탈출 대상을 넓히면 본문을 이유 없이 바꾼다.
            val input = "[[전화번호1]] 과 [[주민등록번호]] 는 그대로 둔다"

            assertThat(maskText(input).maskedText.value).isEqualTo(input)
        }
    }

    @Nested
    @DisplayName("LLM 이 만들어 낸 자리표시자")
    inner class ForeignPlaceholdersInModelOutput {
        // 복원 대상은 **LLM 출력**이다. 모델이 자리표시자 모양을 엉뚱한 자리에 만들어 내면
        // 거기에 진짜 주민등록번호가 꽂힌다 — 마스킹의 목적이 정면으로 뒤집히는 경로다.
        // 아래 셋이 그 경로를 고정한다.

        private fun maskedItems() = maskText("주민 900101-1234567").items

        @Test
        @DisplayName("복제된 자리표시자는 한 곳도 복원하지 않는다")
        fun `복제되면 복원하지 않는다`() {
            // 마스킹은 각 자리표시자를 정확히 한 번만 넣는다. 둘이라는 것은 우리가 만든
            // 본문이 아니라는 뜻이고, 어느 쪽이 우리 자리인지 판정할 근거가 없다.
            // 전부 채우면 개인정보를 모델이 고른 자리에 심는 것이다.
            val modelOutput = "요약: [[주민등록번호1]] 참고. 담당자 [[주민등록번호1]] 문의"
            val restoration = restoreReviewed(modelOutput, maskedItems())

            assertThat(restoration.text).isEqualTo(modelOutput)
            assertThat(restoration.text).doesNotContain("900101")
            assertThat(restoration.ambiguous).containsExactly("[[주민등록번호1]]")
            assertThat(restoration.missing).isEmpty()
        }

        @Test
        @DisplayName("우리가 만들지 않은 자리표시자는 그대로 둔다")
        fun `모르는 자리표시자를 채우지 않는다`() {
            // 모델이 [[주민등록번호9]] 를 만들어 냈다. 우리 목록에 없으므로 채울 값이 없고,
            // 지우지도 않는다 — 우리가 만들지 않은 표기를 지워 본문을 훼손하지 않는다.
            val modelOutput = "본인 [[주민등록번호1]] 과 배우자 [[주민등록번호9]]"
            val restoration = restoreReviewed(modelOutput, maskedItems())

            assertThat(restoration.text).isEqualTo("본인 900101-1234567 과 배우자 [[주민등록번호9]]")
            assertThat(restoration.foreign).containsExactly("[[주민등록번호9]]")
            assertThat(restoration.ambiguous).isEmpty()
        }

        @Test
        @DisplayName("사라진 자리표시자를 보고한다")
        fun `사라지면 보고한다`() {
            val restoration = restoreReviewed("주민번호는 생략합니다", maskedItems())

            assertThat(restoration.text).isEqualTo("주민번호는 생략합니다")
            assertThat(restoration.missing).containsExactly("[[주민등록번호1]]")
        }

        @Test
        @DisplayName("모델이 만들어 낸 탈출 표기는 라벨로 남을 뿐 개인정보가 되지 않는다")
        fun `모델이 만든 탈출 표기`() {
            // 탈출 표기는 우리 자리표시자가 아니므로 값이 채워지지 않는다.
            // 한 겹 벗겨진 라벨이 남고, 라벨은 개인정보가 아니다(계약).
            val restoration = restoreReviewed("모델이 쓴 [[!주민등록번호1]]", maskedItems())

            assertThat(restoration.text).isEqualTo("모델이 쓴 [[주민등록번호1]]")
            assertThat(restoration.text).doesNotContain("900101")
        }
    }

    @Nested
    @DisplayName("검수를 거치지 않은 본문")
    inner class UnreviewedBodyGate {
        // codex stop-time 리뷰가 남긴 잔여를 고정한다. 모델이 우리 자리표시자를 지우고
        // **다른 자리에 하나** 만들면 개수가 여전히 1이라 그 자리에 복원된다 — 개수 판정으로는
        // 정상적인 문장 재작성과 구분할 수단이 없다. 위치를 확증하는 것은 분할 화면을 본
        // 사람뿐이므로, 사람이 제출하지 않은 본문에는 아예 꽂지 않는다.

        private fun source() = maskText("신청자 주민등록번호는 900101-1234567 입니다.")

        @Test
        @DisplayName("단발 위조 — 검수 없는 본문에는 개인정보를 주입하지 않는다")
        fun `검수 없는 본문에는 주입하지 않는다`() {
            val masked = source()
            // 모델이 우리 자리표시자를 지우고 담당자 자리에 하나 만들어 냈다.
            val forged = "담당자 [[주민등록번호1]] 에게 문의하세요."

            val result = restoreForExport(ModelDraft(forged), reviewed = null, items = masked.items)

            assertThat(result.text).isEqualTo(forged)
            assertThat(result.text).doesNotContain("900101")
            assertThat(result.withheld).containsExactly("[[주민등록번호1]]")
            // 개수 판정이 이 경로를 **못 잡는다**는 사실 자체를 고정한다. missing 도 ambiguous 도
            // 비어 있으므로 계약의 409 조건에도 걸리지 않는다 — 그래서 타입 쪽에서 막았다.
            assertThat(result.missing).isEmpty()
            assertThat(result.ambiguous).isEmpty()
        }

        @Test
        @DisplayName("검수본이면 자리가 옮겨져도 복원한다 — 위조와 정상 재작성을 구분하지 못한다는 뜻이기도 하다")
        fun `검수본은 복원한다`() {
            val masked = source()
            // 위 테스트와 **같은 본문**이다. 다른 것은 사람이 제출했다는 사실 하나뿐이고,
            // core 가 가진 근거도 그것뿐이다. 검수본 안의 단발 위조는 사람 눈이 마지막 방어다.
            val submitted = "담당자 [[주민등록번호1]] 에게 문의하세요."

            val result =
                restoreForExport(ModelDraft(masked.maskedText.value), ReviewedBody(submitted), masked.items)

            assertThat(result.text).isEqualTo("담당자 900101-1234567 에게 문의하세요.")
            assertThat(result.withheld).isEmpty()
        }

        @Test
        @DisplayName("검수본이 있으면 그것이 최종본이다 — 초안은 쓰지 않는다")
        fun `검수본이 초안을 이긴다`() {
            val masked = source()

            val result =
                restoreForExport(
                    ModelDraft("버려질 초안 [[주민등록번호1]]"),
                    ReviewedBody("검수본 [[주민등록번호1]] 입니다."),
                    masked.items,
                )

            assertThat(result.text).isEqualTo("검수본 900101-1234567 입니다.")
        }

        @Test
        @DisplayName("개인정보가 없는 문서는 검수가 없어도 한 글자도 바뀌지 않는다")
        fun `개인정보가 없으면 규칙이 물지 않는다`() {
            // 주 용도인 공용 안내문은 대개 여기 해당한다. 이 규칙이 비용을 물리는 대상은
            // 정확히 실수 비용이 가장 큰 문서(개인정보가 실제로 잡힌 문서)뿐이다.
            val masked = maskText("이 안내문에는 개인정보가 없습니다.")
            val draft = "안내문입니다. 개인정보는 없습니다."

            val result = restoreForExport(ModelDraft(draft), reviewed = null, items = masked.items)

            assertThat(result.text).isEqualTo(draft)
            assertThat(result.withheld).isEmpty()
        }

        @Test
        @DisplayName("탈출 표기는 검수 여부와 무관하게 벗긴다 — 사용자 본문의 복구일 뿐이다")
        fun `검수 없이도 탈출을 벗긴다`() {
            // 마스킹이 입력에 있던 자리표시자 모양을 [[!주민등록번호1]] 로 탈출시켰다.
            // 그것을 되돌리는 것은 우리가 바꿔 놓은 사용자 본문을 복구하는 일이라
            // 개인정보가 새로 들어가지 않는다.
            val masked = maskText("앞 [[주민등록번호1]] 뒤 900101-1234567 끝")

            val result =
                restoreForExport(ModelDraft(masked.maskedText.value), reviewed = null, items = masked.items)

            assertThat(result.text).isEqualTo("앞 [[주민등록번호1]] 뒤 [[주민등록번호1]] 끝")
            assertThat(result.text).doesNotContain("900101")
            assertThat(result.text).doesNotContain("[[!")
        }

        @Test
        @DisplayName("검수가 없어도 유실·복제는 보고한다 — 라벨이라 개인정보가 아니다")
        fun `검수 없이도 본문 상태를 보고한다`() {
            val masked = source()

            val dropped =
                restoreForExport(ModelDraft("주민번호는 생략합니다"), reviewed = null, items = masked.items)
            assertThat(dropped.missing).containsExactly("[[주민등록번호1]]")
            assertThat(dropped.withheld).isEmpty()

            val duplicated =
                restoreForExport(
                    ModelDraft("[[주민등록번호1]] 와 [[주민등록번호1]]"),
                    reviewed = null,
                    items = masked.items,
                )
            assertThat(duplicated.ambiguous).containsExactly("[[주민등록번호1]]")
            assertThat(duplicated.text).doesNotContain("900101")
            assertThat(duplicated.withheld).isEmpty()
        }
    }

    @Nested
    @DisplayName("유출 차단")
    inner class LeakPrevention {
        @Test
        @DisplayName("원문이 toString 으로 새지 않는다")
        fun `로그 경로로 평문이 새지 않는다`() {
            val item = maskText("주민 900101-1234567").items.single()

            // 데이터 클래스의 기본 toString 은 모든 필드를 그대로 찍는다. Secret 로 감싸지
            // 않으면 로그 한 줄이 곧 개인정보 유출이다.
            assertThat(item.toString()).doesNotContain("900101")
            assertThat(item.toString()).doesNotContain("1234567")
            // 값이 필요하면 명시적으로 꺼내야 한다 — 그 호출은 코드 리뷰에서 눈에 띈다.
            assertThat(item.original.reveal()).isEqualTo("900101-1234567")
        }
    }
}
