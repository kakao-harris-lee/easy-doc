package kr.easydoc.core.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** 마스킹 파이프라인 — 보안 불변식(`CLAUDE.md` 아키텍처 규칙 2)의 구현. */
class MaskingTest {
    /** 사람 검수를 거친 본문으로 복원한다. */
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

                "4111-1111-1111-1112",

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
            val text = "연도별 예산 $years 입니다."
            assertThat(maskText(text).maskedText.value).isEqualTo(text)
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [

                "0000-4111-1111-1111-1111",
                "0000 4111 1111 1111 1111",
                "1234-4111-1111-1111-1111",

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
            val aligned = maskText("카드 0000-0000-0000-0000-4111-1111-1111-1111 확인")

            assertThat(aligned.maskedText.value).contains("[[카드번호1]]")
        }

        @Test
        @DisplayName("거부된 카드 매치가 구간을 점유하지 않는다")
        fun `거부는 구간을 점유하지 않는다`() {
            val result = maskText("표 2021 2022 2023 2024 신청자 900101-1234567")

            assertThat(result.maskedText.value)
                .isEqualTo("표 2021 2022 2023 2024 신청자 [[주민등록번호1]]")
        }
    }

    @Nested
    @DisplayName("구분자 문법 SEP — 판정 §4-ter.2 의 12탐침")
    inner class SeparatorGrammar {
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
            val result = maskText("주민번호 $evasive 끝")

            assertThat(result.maskedText.value).isEqualTo("주민번호 [[주민등록번호1]] 끝")

            val item = result.items.single()
            assertThat(item.original.reveal()).isEqualTo(evasive)
        }

        @Test
        @DisplayName("뷰에서만 잡히는 경계는 원문 좌표로 되돌린다")
        fun `앞에 붙은 보이지 않는 문자를 삼키지 않는다`() {
            val result = maskText("1\u200B900101-1234567")

            assertThat(result.maskedText.value).isEqualTo("1\u200B[[주민등록번호1]]")
            val item = result.items.single()
            assertThat(item.original.reveal()).isEqualTo("900101-1234567")
        }
    }

    @Nested
    @DisplayName("표기 변형 — 유니코드 인식 패턴 안의 ASCII 리터럴")
    inner class NotationVariants {
        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [

                "９００１０１-１２３４５６７",

                "900101-１234567",

                "９００１０１-1234567",

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
            val text = "번호 $split 을 적으세요."

            assertThat(maskText(text).maskedText.value).isEqualTo(text)
            assertThat(maskText(text).items).isEmpty()
        }
    }

    @Nested
    @DisplayName("탐색 뷰의 경계 문자 — 판정 §4-ter.3 의 6케이스")
    inner class SearchViewBoundaries {
        private fun notCombined(separator: String) {
            val text = "번호 900101${separator}1234567 을 적으세요."

            assertThat(maskText(text).maskedText.value).isEqualTo(text)
            assertThat(maskText(text).items).isEmpty()
        }

        private fun combined(separator: String) {
            val raw = "900101${separator}1234567"
            val result = maskText("번호 $raw 확인.")

            assertThat(result.maskedText.value).isEqualTo("번호 [[주민등록번호1]] 확인.")

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
            val text = "번호 $tabbed 을 적으세요."

            assertThat(maskText(text).maskedText.value).isEqualTo(text)
            assertThat(maskText(text).items).isEmpty()
        }

        @Test
        @DisplayName("대가 — 탭으로 조판된 진짜 주민등록번호는 이제 놓친다")
        fun `누락 방향의 대가를 명시한다`() {
            val tabbed = "번호 900101\u0009-\u00091234567 확인."
            val spaced = "번호 900101  -  1234567 확인."

            assertThat(maskText(tabbed).items).isEmpty()
            assertThat(maskText(spaced).items).isEmpty()
        }

        @Test
        @DisplayName("탭은 탐색 뷰에서도 접지 않는다 — 접으면 정반대 결함이 된다")
        fun `INVISIBLE 범위는 건드리지 않았다`() {
            val text = "번호 900101\u00091234567 확인."

            assertThat(maskText(text).items).isEmpty()
        }
    }

    @Nested
    @DisplayName("탐색 뷰 접기 경계 — 판정 §4-septies.6 의 양성·음성 짝")
    inner class SearchViewBoundaryAxis {
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
            val result = maskText(outOfScope)

            assertThat(result.maskedText.value).isEqualTo(outOfScope)
            assertThat(result.items).isEmpty()
        }

        @Test
        @DisplayName("가리는 범주는 정확히 둘이다")
        fun `범주가 둘뿐이다`() {
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
            val input = "앞 [[주민등록번호1]] 뒤 900101-1234567 끝"
            val result = maskText(input)

            assertThat(result.maskedText.value)
                .isEqualTo("앞 [[!주민등록번호1]] 뒤 [[주민등록번호1]] 끝")

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
            val input = "[[전화번호1]] 과 [[주민등록번호]] 는 그대로 둔다"

            assertThat(maskText(input).maskedText.value).isEqualTo(input)
        }
    }

    @Nested
    @DisplayName("LLM 이 만들어 낸 자리표시자")
    inner class ForeignPlaceholdersInModelOutput {
        private fun maskedItems() = maskText("주민 900101-1234567").items

        @Test
        @DisplayName("복제된 자리표시자는 한 곳도 복원하지 않는다")
        fun `복제되면 복원하지 않는다`() {
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
            val restoration = restoreReviewed("모델이 쓴 [[!주민등록번호1]]", maskedItems())

            assertThat(restoration.text).isEqualTo("모델이 쓴 [[주민등록번호1]]")
            assertThat(restoration.text).doesNotContain("900101")
        }
    }

    @Nested
    @DisplayName("검수를 거치지 않은 본문")
    inner class UnreviewedBodyGate {
        private fun source() = maskText("신청자 주민등록번호는 900101-1234567 입니다.")

        @Test
        @DisplayName("단발 위조 — 검수 없는 본문에는 개인정보를 주입하지 않는다")
        fun `검수 없는 본문에는 주입하지 않는다`() {
            val masked = source()

            val forged = "담당자 [[주민등록번호1]] 에게 문의하세요."

            val result = restoreForExport(ModelDraft(forged), reviewed = null, items = masked.items)

            assertThat(result.text).isEqualTo(forged)
            assertThat(result.text).doesNotContain("900101")
            assertThat(result.withheld).containsExactly("[[주민등록번호1]]")

            assertThat(result.missing).isEmpty()
            assertThat(result.ambiguous).isEmpty()
        }

        @Test
        @DisplayName("검수본이면 자리가 옮겨져도 복원한다 — 위조와 정상 재작성을 구분하지 못한다는 뜻이기도 하다")
        fun `검수본은 복원한다`() {
            val masked = source()

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
            val masked = maskText("이 안내문에는 개인정보가 없습니다.")
            val draft = "안내문입니다. 개인정보는 없습니다."

            val result = restoreForExport(ModelDraft(draft), reviewed = null, items = masked.items)

            assertThat(result.text).isEqualTo(draft)
            assertThat(result.withheld).isEmpty()
        }

        @Test
        @DisplayName("탈출 표기는 검수 여부와 무관하게 벗긴다 — 사용자 본문의 복구일 뿐이다")
        fun `검수 없이도 탈출을 벗긴다`() {
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

            assertThat(item.toString()).doesNotContain("900101")
            assertThat(item.toString()).doesNotContain("1234567")

            assertThat(item.original.reveal()).isEqualTo("900101-1234567")
        }

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

            assertThat(masked.value).contains("02-1234-5678")
        }

        @Test
        @DisplayName("MaskingResult.toString 에 본문이 실리지 않는다")
        fun `MaskingResult 는 본문을 찍지 않는다`() {
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
