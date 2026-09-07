package kr.easydoc.core.invoice

import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 국세청 체크섬(가중치 `1,3,7,1,3,7,1,3,5` + 9번째 자리 × 5 의 십의 자리 규칙) — 계획
 * `docs/plans/2026-09-07-invoice-requests.md` §2 결정 2. 유효 샘플은 이 파일 안에서
 * 같은 알고리즘으로 체크 자릿수를 계산해 만든다 — 리터럴을 그대로 신뢰하지 않는다.
 */
class BusinessNumberTest {
    @Test
    @DisplayName("하이픈·공백 포함 입력을 10자리 숫자로 정규화하고 체크섬을 통과하면 만들어진다")
    fun `유효한 사업자번호는 정규화되어 만들어진다`() {
        val base = "220-81-6251"
        val number = BusinessNumber.of("$base${checkDigitFor(base)}")

        assertThat(number.digits).hasSize(10)
        assertThat(number.digits.all(Char::isDigit)).isTrue()
    }

    @Test
    @DisplayName("공백이 섞인 입력도 같은 방식으로 정규화된다")
    fun `공백 포함 입력도 정규화된다`() {
        val base = "123 45 6789"
        val digitsOnly = base.filter(Char::isDigit)
        val check = checkDigitFor(digitsOnly)

        val number = BusinessNumber.of("$base $check")

        assertThat(number.digits).isEqualTo("$digitsOnly$check")
    }

    @Test
    @DisplayName("체크섬이 틀리면 422로 매핑될 예외를 던진다")
    fun `체크섬이 틀리면 거절된다`() {
        val base = "220-81-6251"
        val validCheck = checkDigitFor(base)
        val invalidCheck = (validCheck + 1) % 10

        assertThatThrownBy { BusinessNumber.of("$base$invalidCheck") }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(BusinessNumber.INVALID_MESSAGE)
    }

    @Test
    @DisplayName("전각 숫자는 ASCII 숫자로 세지 않는다 — 체크섬을 우연히 통과해도 422다")
    fun `전각 숫자는 거절된다`() {
        // '０'(U+FF10, 전각 0)은 Char.isDigit() 이 참이지만 ASCII '0'과 다른 코드포인트다 —
        // filter { it in '0'..'9' } 로 좁히지 않으면 통과해 DB CHECK 제약(ASCII만 허용)에서야 죽는다.
        assertThatThrownBy { BusinessNumber.of("０234567894") }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(BusinessNumber.INVALID_MESSAGE)
    }

    @Test
    @DisplayName("숫자가 10자리가 아니면 거절된다 — 체크섬이 우연히 맞아도")
    fun `자릿수가 다르면 거절된다`() {
        assertThatThrownBy { BusinessNumber.of("123456789") }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(BusinessNumber.INVALID_MESSAGE)

        assertThatThrownBy { BusinessNumber.of("") }
            .isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    @DisplayName("toString 은 값을 찍지 않는다")
    fun `toString 은 값을 가린다`() {
        val base = "220-81-6251"
        val number = BusinessNumber.of("$base${checkDigitFor(base)}")

        assertThat(number.toString()).doesNotContain(number.digits)
    }

    /** 테스트 전용 — [BusinessNumber] 와 같은 알고리즘으로 9자리(하이픈 허용)의 체크 자릿수를 낸다. */
    private fun checkDigitFor(nineDigitsRaw: String): Int {
        val digits = nineDigitsRaw.filter(Char::isDigit).map { it - '0' }
        check(digits.size == 9) { "테스트 헬퍼는 9자리 기반 입력만 받는다: $nineDigitsRaw" }
        val weights = intArrayOf(1, 3, 7, 1, 3, 7, 1, 3, 5)
        var sum = 0
        for (index in weights.indices) {
            sum += digits[index] * weights[index]
        }
        sum += (digits[8] * 5) / 10
        return (10 - sum % 10) % 10
    }
}
