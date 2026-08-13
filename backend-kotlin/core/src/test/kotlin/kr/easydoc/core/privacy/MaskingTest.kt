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
 *
 * 보이지 않는 문자는 소스에 리터럴로 적지 않는다 — 전부 `\uXXXX` 다.
 */
class MaskingTest {
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
            val restored =
                result.items.fold(result.maskedText.value) { text, item ->
                    text.replace(item.placeholder, item.original.reveal())
                }

            assertThat(restored).isEqualTo(input)
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
