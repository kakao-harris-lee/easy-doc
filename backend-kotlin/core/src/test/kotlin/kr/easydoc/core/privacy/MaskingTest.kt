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
                // "900101<TAB>-<TAB>1234567" 은 여기서 **음성으로 옮겼다** —
                // 아래 「TAB 은 열 경계다」. privacy-gate 판정 §4-septies.7.
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
                // **Luhn 유효 값이어야 한다.** `1234-5678-9012-3456` 은 Luhn 실패라
                // CARD 의 accept 훅이 거부한다 — 그대로 두면 이 케이스가 무력화된다
                // (privacy-gate §4-decies.4 가 "가장 놓치기 쉬운 자리"로 지목한 곳).
                "4111-1111-1111-1111",
                "4111 1111 1111 1111",
                "4111111111111111",
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

        // ── Luhn 체크디짓 (privacy-gate §4-decies.4) ──────────────────────────────
        //
        // **양방향으로 잰다.** 한쪽만 재면 두 실패 모드 중 하나가 조용히 통과한다 —
        // 훅이 아무것도 안 물면 오탐 29건이 그대로고, 너무 물면 진짜 카드가 샌다.
        // 후자가 훨씬 무거우므로 양성 쪽을 표준 테스트 카드번호로 고정한다.

        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                "4111-1111-1111-1111", // Visa 테스트 번호
                "5555-5555-5555-4444", // Mastercard 테스트 번호
                "4242-4242-4242-4242", // 널리 쓰이는 테스트 번호
            ],
        )
        @DisplayName("Luhn 유효 카드는 여전히 가려진다")
        fun `유효 카드는 가려진다`(card: String) {
            assertThat(maskText("결제 $card 완료").maskedText.value)
                .isEqualTo("결제 [[카드번호1]] 완료")
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                "4111-1111-1111-1111",
                "4111 1111 1111 1111",
                "4111111111111111",
                "4111\u00A0-\u00A01111\u00A0-\u00A01111\u00A0-\u00A01111",
            ],
        )
        @DisplayName("Luhn 판정은 구분자를 걷어낸 숫자열로 한다")
        fun `구분자가 Luhn 판정을 바꾸지 않는다`(card: String) {
            assertThat(maskText("카드 $card").maskedText.value).isEqualTo("카드 [[카드번호1]]")
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                // 한 자리만 틀린 카드형 — 체크디짓이 맞지 않는다.
                "4111-1111-1111-1112",
                // 예전 합성 카드번호. Luhn 실패다.
                "1234-5678-9012-3456",
            ],
        )
        @DisplayName("Luhn 실패 카드형은 카드번호가 아니다")
        fun `Luhn 실패는 가리지 않는다`(notCard: String) {
            val text = "번호 $notCard 입니다."
            assertThat(maskText(text).maskedText.value).isEqualTo(text)
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                "2021 2022 2023 2024",
                "1998 1999 2000 2001",
                "2021\t2022\t2023\t2024",
            ],
        )
        @DisplayName("연도 4열 표는 카드번호가 아니다 — 훅이 실제로 무는 자리")
        fun `연도 배열을 가리지 않는다`(years: String) {
            // 실문서 카드형 적중 30건 중 29건이 이 모양이었다. 연도가 `[[카드번호1]]` 로
            // 바뀐 안내문은 **실패로 보고되지 않고 그대로 배포된다** — 조용한 손해다.
            val text = "연도별 예산 $years 입니다."
            assertThat(maskText(text).maskedText.value).isEqualTo(text)
        }

        // ── 거부된 매치가 커서를 전진시키는가 (게이트 12 차단①) ──────────────────
        //
        // `findAll(...).filter(accept)` 는 필터가 **findAll 뒤**라, 거부된 매치도 이미
        // 커서를 전진시킨 뒤다. 그래서 거부된 구간과 **겹치는** 유효 카드가 탐색조차 되지
        // 않는다. Luhn 도입 전에는 앞 16자리가 그냥 가려졌으므로, 이 결함은 §4-decies.4
        // 배치가 **만든 회귀**다 — 재현율이 도입 전보다 낮아지면 안 된다는 조건을 깼다.

        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                // 5그룹 — 카드가 그룹 2~5 에 있다. 그룹 1~4 는 Luhn 실패라 거부된다.
                "0000-4111-1111-1111-1111",
                "0000 4111 1111 1111 1111",
                "1234-4111-1111-1111-1111",
                // 6그룹 — 거부가 두 번 일어난 뒤에도 찾아야 한다.
                "0000-0000-4111-1111-1111-1111",
            ],
        )
        @DisplayName("거부된 매치와 겹친 유효 카드를 놓치지 않는다")
        fun `겹친 유효 카드를 찾는다`(text: String) {
            val result = maskText("카드 $text 확인")

            assertThat(result.maskedText.value)
                .describedAs("거부된 매치가 커서를 전진시켜 겹친 유효 카드를 삼켰다")
                .contains("[[카드번호1]]")
            assertThat(
                result.items
                    .single()
                    .original
                    .reveal(),
            ).describedAs("가린 구간이 유효 카드 16자리와 일치하지 않는다")
                .endsWith("4111-1111-1111-1111".replace("-", text.substring(4, 5)))
        }

        @Test
        @DisplayName("재현율은 Luhn 도입 전보다 낮아지지 않는다")
        fun `재현율이 낮아지지 않는다`() {
            // **focus ① 의 조건을 직접 잰다.** Luhn 은 정밀도를 올리는 장치이지 재현율을
            // 깎는 장치가 아니다. 유효 카드가 들어 있는 입력에서 가려진 숫자 수가 0이면
            // 도입 전(앞 16자리를 무조건 가리던 때)보다 **나빠진 것**이다.
            val text = "카드 0000-4111-1111-1111-1111 확인"
            val masked = maskText(text).maskedText.value

            val remainingDigits = masked.count { it.isDigit() }
            val originalDigits = text.count { it.isDigit() }

            assertThat(remainingDigits)
                .describedAs("가려진 자리가 하나도 없다 — Luhn 도입 전에는 16자리가 가려졌다")
                .isLessThan(originalDigits)
        }

        @Test
        @DisplayName("자가치유 경계 — 4의 배수 정렬이면 거부 뒤에도 정렬이 맞는다")
        fun `4의 배수 정렬은 스스로 회복한다`() {
            // 그룹 수가 4의 배수만큼 앞서면 거부된 매치의 끝이 다음 카드의 시작과 맞아
            // 재탐색 없이도 찾아진다. **이 경우가 통과한다는 사실이 결함을 가렸다** —
            // 경계를 케이스로 고정해 두지 않으면 다음에 또 "되던데"로 넘어간다.
            val aligned = maskText("카드 0000-0000-0000-0000-4111-1111-1111-1111 확인")

            assertThat(aligned.maskedText.value).contains("[[카드번호1]]")
        }

        @Test
        @DisplayName("거부된 카드 매치가 구간을 점유하지 않는다")
        fun `거부는 구간을 점유하지 않는다`() {
            // `accept` 가 거부한 매치는 `spans` 에 들어가지 않아야 한다. 점유하면 같은 자리에
            // 겹치는 다른 판정이 사라진다 — `acceptsRrnGenderCode` 와 같은 성질이다.
            val result = maskText("표 2021 2022 2023 2024 신청자 900101-1234567")

            assertThat(result.maskedText.value)
                .isEqualTo("표 2021 2022 2023 2024 신청자 [[주민등록번호1]]")
        }
    }

    @Nested
    @DisplayName("구분자 문법 SEP — 판정 §4-ter.2 의 12탐침")
    inner class SeparatorGrammar {
        // SEP := (?: SPACE? HYPHEN SPACE? | SPACE? )  — 최대 3문자로 유한하다.
        //
        // 이 12건은 privacy-gate 가 `java Grammar.java` 로 돌린 탐침을 그대로 옮긴 것이다.
        // **정당 5 유지 · 과잉 4 탈락 · 누락 2 신규 검출**이 한 문법에서 동시에 성립한다는
        // 것이 판정의 본체다. 방향이 반대인 두 지적(C-01② 넓히기 / C-10 좁히기)을 따로
        // 처방했다면 한쪽이 다른 쪽을 되돌렸을 자리다.

        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                "900101-1234567",
                "900101\u00201234567",
                "9001011234567",
                "900101\u0020-\u00201234567",
                "900101\u00A0-\u00A01234567",
            ],
        )
        @DisplayName("정당한 표기 5종 — 구분자 자리 공백 0~1개는 구분자다")
        fun `정당한 구분자는 가린다`(rrn: String) {
            val result = maskText("주민번호는 $rrn 입니다.")

            assertThat(result.maskedText.value).isEqualTo("주민번호는 [[주민등록번호1]] 입니다.")
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
                // 표 열 맞춤으로 떨어져 있는 접수번호 6자리 + 관리번호 7자리다.
                // 이것을 결합해 가리면 안내문의 팩트가 조용히 사라진다(STY-03).
                "900101\u3000\u3000\u30001234567",
                "900101\u0020\u0020\u0020\u0020\u00201234567",
                "900101\u0020\u00201234567",
                "4111\u3000\u3000\u30001111\u3000\u3000\u30001111\u3000\u3000\u30001111",
            ],
        )
        @DisplayName("과잉 4종 — 같은 자리에 공백 2개 이상은 구분이 아니라 정렬이다")
        fun `정렬 여백은 가리지 않는다`(notMasked: String) {
            val text = "번호 $notMasked 를 적으세요."

            assertThat(maskText(text).maskedText.value).isEqualTo(text)
            assertThat(maskText(text).items).isEmpty()
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                // 이전 판 CARD 패턴은 구분자가 **한 문자**뿐이라 이 둘을 놓쳤다(C-01②).
                "4111\u0020-\u00201111\u0020-\u00201111\u0020-\u00201111",
                "4111\u00A0-\u00A01111\u00A0-\u00A01111\u00A0-\u00A01111",
                "4111-1111-1111-1111",
            ],
        )
        @DisplayName("누락 2종 + 기존 1종 — 복합 구분자 카드번호를 가린다")
        fun `복합 구분자 카드번호를 가린다`(card: String) {
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

        @Test
        @DisplayName("RRN 과 CARD 가 같은 SEP 상수를 쓴다 — 대칭이 코드에서 성립한다")
        fun `두 패턴의 구분자 문법이 같다`() {
            // 상수를 두 벌로 적으면 다음 확장에서 한쪽만 늘어난다. 그 비대칭이 실제로
            // C-01②(카드만 좁음)를 만들었으므로, 같은 입력 모양에서 두 범주가 같이
            // 움직이는지를 값으로 확인한다.
            val rrnSpaced = maskText("번호 900101\u0020-\u00201234567 확인.")
            val cardSpaced = maskText("카드 4111\u0020-\u00201111\u0020-\u00201111\u0020-\u00201111 확인.")
            val rrnAligned = maskText("번호 900101\u0020\u00201234567 확인.")
            val cardAligned = maskText("카드 4111\u0020\u00201111\u0020\u00201111\u0020\u00201111 확인.")

            assertThat(rrnSpaced.items).hasSize(1)
            assertThat(cardSpaced.items).hasSize(1)
            assertThat(rrnAligned.items).isEmpty()
            assertThat(cardAligned.items).isEmpty()
        }
    }

    @Nested
    @DisplayName("성별코드 판정의 계수 단위 — 판정 §4-ter.1")
    inner class GenderCodeCounting {
        // 결함의 종류는 커버리지가 아니라 **정합성**이었다 — 패턴은 코드포인트로 세고
        // 가드는 UTF-16 Char 로 세어, 둘이 "숫자 한 자"의 정의를 다르게 갖고 있었다.
        // U+1D7CF(MATHEMATICAL BOLD DIGIT ONE)는 서로게이트 쌍이라 `singleOrNull()` 이
        // 거부했고, 그런 십진 숫자가 보충 평면에 310개 있다.

        /** U+1D7CF — 십진값 1. 성별코드 자리가 서로게이트 쌍인 경우. */
        private val boldOne = String(Character.toChars(0x1D7CF))

        /** U+1D7D7 — 십진값 9. 거부돼야 한다. */
        private val boldNine = String(Character.toChars(0x1D7D7))

        @Test
        @DisplayName("양성 — 보충 평면 성별코드도 값이 1~8이면 가린다")
        fun `보충 평면 성별코드를 가린다`() {
            val rrn = "900101-${boldOne}234567"

            val result = maskText("주민번호는 $rrn 입니다.")

            assertThat(result.maskedText.value).isEqualTo("주민번호는 [[주민등록번호1]] 입니다.")
            assertThat(
                result.items
                    .single()
                    .original
                    .reveal(),
            ).isEqualTo(rrn)
        }

        @Test
        @DisplayName("음성 — 보충 평면이어도 값이 9면 거부한다 (과잉 마스킹 가드)")
        fun `보충 평면 성별코드 9는 거부한다`() {
            val text = "번호는 900101-${boldNine}234567 입니다."

            assertThat(maskText(text).maskedText.value).isEqualTo(text)
            assertThat(maskText(text).items).isEmpty()
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
                "4111\uFF0D1111\uFF0D1111\uFF0D1111",
                "4111\u00A01111\u00A01111\u00A01111",
                "4111\u30001111\u30001111\u30001111",
                "4111\u20131111\u20131111\u20131111",
                "４１１１\uFF0D１１１１\uFF0D１１１１\uFF0D１１１１",
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
                "4111\u000A1111\u000A1111\u000A1111",
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
    @DisplayName("탐색 뷰의 경계 문자 — 판정 §4-ter.3 의 6케이스")
    inner class SearchViewBoundaries {
        // `searchView` 는 "보이지 않는 문자"를 지워 그 사이로 끊긴 숫자열을 잇는다. 그런데
        // **줄·페이지 경계 문자를 지우면 서로 다른 줄의 숫자열이 결합된다** — 과잉 마스킹이다.
        //
        // 이전 판은 `INVISIBLE_RANGES` 에 `0x000B..0x000C` 를 넣어 두어 VT·FF 가 결합됐다.
        // LF·CR 만 확인하던 가드는 이것을 잡지 못했다 — **열거로 범위를 정한 것의 전형적
        // 실패**라, 여섯을 각각 독립 케이스로 둔다. 묶으면 다음에 하나가 빠져도 모른다.

        private fun notCombined(separator: String) {
            val text = "번호 900101${separator}1234567 을 적으세요."

            assertThat(maskText(text).maskedText.value).isEqualTo(text)
            assertThat(maskText(text).items).isEmpty()
        }

        private fun combined(separator: String) {
            val raw = "900101${separator}1234567"
            val result = maskText("번호 $raw 확인.")

            assertThat(result.maskedText.value).isEqualTo("번호 [[주민등록번호1]] 확인.")
            // 잘라내는 것은 언제나 원문이다 — 낀 문자가 그대로 들어가야 복원이 성립한다.
            assertThat(
                result.items
                    .single()
                    .original
                    .reveal(),
            ).isEqualTo(raw)
        }

        @Test
        @DisplayName("LF(U+000A) 는 줄 경계 — 결합하지 않는다")
        fun `개행은 결합하지 않는다`() = notCombined("\u000A")

        @Test
        @DisplayName("CR(U+000D) 은 줄 경계 — 결합하지 않는다")
        fun `캐리지리턴은 결합하지 않는다`() = notCombined("\u000D")

        @Test
        @DisplayName("VT(U+000B) 는 수직 탭 = 줄 경계 — 결합하지 않는다")
        fun `수직탭은 결합하지 않는다`() = notCombined("\u000B")

        @Test
        @DisplayName("FF(U+000C) 는 폼피드 = 페이지 경계 — 결합하지 않는다")
        fun `폼피드는 결합하지 않는다`() = notCombined("\u000C")

        @Test
        @DisplayName("ZWSP(U+200B) 는 폭 없는 문자 — 결합한다(회피 차단)")
        fun `폭없는공백은 결합한다`() = combined("\u200B")

        @Test
        @DisplayName("SHY(U+00AD) 는 소프트하이픈 — 결합한다(실문서에서 실측된 회피 경로)")
        fun `소프트하이픈은 결합한다`() = combined("\u00AD")
    }

    @Nested
    @DisplayName("TAB 은 여백이 아니라 열 경계다 — 판정 §4-septies.7")
    inner class TabIsColumnBoundary {
        // SEP 의 개수 상한은 **폭의 대리 지표**다("자리당 한 칸까지는 구분, 둘 이상은 정렬").
        // TAB 은 정의상 다음 탭 스톱까지 밀어내는 문자라, 공백으로는 2개 이상 있어야 하는
        // 일을 **1개로 한다** — 대리 지표가 TAB 에서만 성립하지 않는다.
        //
        // 이 네 케이스는 전부 **수정 전에는 가려지던 것**이다(과잉 마스킹 = STY-03 팩트 소실).

        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                "900101\u00091234567",
                "4111\u00091111\u00091111\u00091111",
                "1200\u00093400\u00095600\u00097800",
                "900101\u0009-\u00091234567",
            ],
        )
        @DisplayName("탭으로 갈린 열은 하나의 값이 아니다")
        fun `탭은 구분자가 아니다`(tabbed: String) {
            // 표 4열이 통째로 카드번호가 되던 자리다. 붙여넣기 경로로 표를 복사하면
            // 클립보드 TSV 가 그대로 TAB 이라 실제로 들어온다.
            val text = "번호 $tabbed 을 적으세요."

            assertThat(maskText(text).maskedText.value).isEqualTo(text)
            assertThat(maskText(text).items).isEmpty()
        }

        @Test
        @DisplayName("대가 — 탭으로 조판된 진짜 주민등록번호는 이제 놓친다")
        fun `누락 방향의 대가를 명시한다`() {
            // **이 테스트는 결함을 고정하는 것이 아니라 감수한 대가를 고정한다.**
            // §4-ter.2 가 개수 상한을 두면서 이미 감수한 누락과 같은 종류다 —
            // 아래 두 입력은 조판 문자만 다르고 결과가 같아야 한다. TAB 만 예외로 두면
            // "어느 공백 문자로 조판됐는지에 따라 결과가 갈린다"가 되살아난다.
            val tabbed = "번호 900101\u0009-\u00091234567 확인."
            val spaced = "번호 900101  -  1234567 확인."

            assertThat(maskText(tabbed).items).isEmpty()
            assertThat(maskText(spaced).items).isEmpty()
        }

        @Test
        @DisplayName("탭은 탐색 뷰에서도 접지 않는다 — 접으면 정반대 결함이 된다")
        fun `INVISIBLE 범위는 건드리지 않았다`() {
            // TAB 을 접기 대상으로 만들면 서로 다른 열의 숫자가 뷰에서 붙어 다시 가려진다.
            // 구분자 집합에서 뺀 것과 접기 대상으로 넣는 것은 **반대 방향**이다.
            val text = "번호 900101\u00091234567 확인."

            assertThat(maskText(text).items).isEmpty()
        }
    }

    @Nested
    @DisplayName("탐색 뷰 접기 경계 — 판정 §4-septies.6 의 양성·음성 짝")
    inner class SearchViewBoundaryAxis {
        // 경계축(`(?<!\d)`·`(?!\d)`)은 **거부권**이다 — 거부는 원문 읽기와 접힌 읽기가
        // **둘 다** 거부할 때만 성립한다. 그래서 폭 0인 문자 **한 개**가 "긴 숫자열의
        // 일부"라는 거부 근거를 무효화한다(열거 1,120조합 중 90건, 전부 이 한 종류).
        //
        // **양성과 음성을 반드시 짝으로 둔다.** 따로 두면 다음 사람이 음성 쪽만 보고
        // "경계 검사가 있다"고 읽어 합집합을 지운다 — 그 순간 §4-septies.5 의 판정이
        // 조용히 뒤집힌다. 짝을 이루는 두 입력은 **폭 0 문자 하나만 다르다.**

        private fun masked(text: String) = maskText(text).items.size

        @Test
        @DisplayName("앞 경계 — RRN")
        fun `앞에 붙은 폭 0 문자가 거부를 무효화한다`() {
            assertThat(masked("1\u200B900101-1234567")).isEqualTo(1)
            assertThat(masked("1900101-1234567")).isZero()
        }

        @Test
        @DisplayName("뒤 경계 — RRN (앞 경계와 대칭)")
        fun `뒤에 붙은 폭 0 문자도 같다`() {
            assertThat(masked("900101-1234567\u200B8")).isEqualTo(1)
            assertThat(masked("900101-12345678")).isZero()
        }

        @Test
        @DisplayName("앞 경계 — CARD (범주 대칭)")
        fun `카드번호에서도 같다`() {
            assertThat(masked("1\u200B4111-1111-1111-1111")).isEqualTo(1)
            assertThat(masked("14111-1111-1111-1111")).isZero()
        }

        @Test
        @DisplayName("분리축 × 접기 교차 — 개수 판정은 접기에 흔들리지 않는다")
        fun `가시 간격으로 센다`() {
            // K-1 이 "SEP 반복 상한이 무너진다"고 본 자리. 실측은 반대다 — 개수 기준이
            // 재는 것은 **여백의 폭**이고 폭 0인 문자는 몇 개가 와도 폭을 만들지 않는다.
            assertThat(masked("900101\u200B\u200B\u200B\u200B\u200B1234567")).isEqualTo(1)
            assertThat(masked("900101\u200B \u200B1234567")).isEqualTo(1)
            assertThat(masked("900101 \u200B 1234567")).isZero()
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
                maskText("주민 900101-1234567 카드 4111-1111-1111-1111 주민 800101-2345678")

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
                "주민 900101-1234567 카드 4111-1111-1111-1111",
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

        // ── value class 3종의 toString (privacy-gate 판정 5 / §4-bis) ──────────────
        //
        // 셋 다 문서 본문을 감싸는데 재정의가 하나도 없었다. 일반 class·data class 에는
        // 이미 같은 규율이 있었으므로(`LlmPrompt`·`LlmCompletion`·`Secret`·
        // `PlaceholderRestoration`) 결함은 한 건이 아니라 **종류**였다.
        //
        // **단언은 "길이 표기가 있다"가 아니라 "본문이 없다"를 본다.** 전자는 형식이 바뀌면
        // 조용히 통과한다 — `MaskedText(48자) value=...` 같은 출력도 통과시킨다.

        /** 마스킹 범주 밖이라 정책상 그대로 남는 값을 일부러 섞는다(전화·이메일). */
        private val bodyWithUnmaskedPii =
            "문의 02-1234-5678 또는 hong@korea.kr 로 연락하세요. 신청자 900101-1234567 님."

        @Test
        @DisplayName("MaskedText.toString 에 본문이 실리지 않는다")
        fun `MaskedText 는 본문을 찍지 않는다`() {
            val masked = maskText(bodyWithUnmaskedPii).maskedText

            assertThat(masked.toString())
                .withFailMessage("MaskedText.toString 이 본문을 노출한다 — 로거 인자 한 번이면 문서 본문이 로그 수집기로 나간다")
                .doesNotContain("02-1234-5678")
                .doesNotContain("hong@korea.kr")
                .doesNotContain("신청자")
                .doesNotContain("[[주민등록번호1]]")
            // 마스킹했어도 안전하지 않다 — 가려지는 것은 2종뿐이고, 전화·이메일은 **전송**을
            // 감수한 것이지 **로그 적재**를 감수한 것이 아니다.
            assertThat(masked.value).contains("02-1234-5678")
        }

        @Test
        @DisplayName("MaskingResult.toString 에 본문이 실리지 않는다")
        fun `MaskingResult 는 본문을 찍지 않는다`() {
            // 두 필드가 각각은 이미 안전하다(MaskedText 는 길이만, MaskedItem.original 은
            // Secret). **그러나 그것은 전이 안전이지 이 타입의 성질이 아니다** — 여기에
            // 본문 필드를 하나 더하는 순간 조용히 샌다. 형제 다섯이 전부 명시적으로 가려진
            // 상태에서 이 하나만 남의 안전에 얹혀 있었다(판정 §4-quinquies).
            val rendered = maskText(bodyWithUnmaskedPii).toString()

            assertThat(rendered)
                .withFailMessage("MaskingResult.toString 이 본문을 노출한다: %s", rendered)
                .doesNotContain("02-1234-5678")
                .doesNotContain("hong@korea.kr")
                .doesNotContain("신청자")
                .doesNotContain("[[주민등록번호1]]")
        }

        @Test
        @DisplayName("ModelDraft·ReviewedBody 의 toString 에 본문이 실리지 않는다")
        fun `provenance 래퍼는 본문을 찍지 않는다`() {
            val draft = ModelDraft(bodyWithUnmaskedPii)
            val reviewed = ReviewedBody(bodyWithUnmaskedPii)

            listOf(draft.toString(), reviewed.toString()).forEach { rendered ->
                assertThat(rendered)
                    .withFailMessage("provenance 래퍼의 toString 이 본문을 노출한다: %s", rendered)
                    .doesNotContain("02-1234-5678")
                    .doesNotContain("hong@korea.kr")
                    .doesNotContain("900101-1234567")
            }
        }

        @Test
        @DisplayName("보간·Any 인자·컬렉션 — 재정의가 실제 노출 경로 전부에서 듣는다")
        fun `노출 경로 네 갈래를 모두 막는다`() {
            // privacy-gate 가 kotlinc 로 실측한 네 경로다. `Any` 인자(로거 형태)와 컬렉션은
            // value class 가 **박싱되는** 경로라, 재정의가 인라인 자리에서만 듣고 박싱
            // 자리에서 새면 여기서 드러난다.
            val draft = ModelDraft(bodyWithUnmaskedPii)
            val asAny: Any = draft

            val paths =
                mapOf(
                    "문자열 보간" to "$draft",
                    "명시 호출" to draft.toString(),
                    "Any 인자(로거)" to logLike("변환 완료 {}", asAny),
                    "컬렉션" to listOf(draft).toString(),
                )

            paths.forEach { (path, rendered) ->
                assertThat(rendered)
                    .withFailMessage("[%s] 경로로 본문이 샌다: %s", path, rendered)
                    .doesNotContain("02-1234-5678")
                    .doesNotContain("hong@korea.kr")
                    .doesNotContain("900101-1234567")
            }
        }

        /** 로거가 `Any` 인자를 포매팅하는 형태를 흉내 낸다. 실제 로거를 끌어오지 않는다. */
        private fun logLike(
            template: String,
            argument: Any,
        ): String = template.replace("{}", argument.toString())
    }
}
