package kr.easydoc.core.credit

import kr.easydoc.core.document.ReadingLevel
import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

class CreditsTest {
    @Test
    @DisplayName("음수 크레딧은 만들 수 없다")
    fun `음수는 거절된다`() {
        assertThatThrownBy { Credits(-1) }.isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    @DisplayName("0 크레딧은 허용된다")
    fun `0은 허용된다`() {
        assertThat(Credits(0).amount).isZero()
    }

    @Test
    @DisplayName("0.1 단위 크레딧은 만들 수 있다")
    fun `소수 첫째 자리 크레딧은 허용된다`() {
        assertThat(Credits(BigDecimal("0.1")).amount).isEqualByComparingTo("0.1")
        assertThat(Credits(BigDecimal("5")).amount).isEqualByComparingTo("5.0")
    }

    @ParameterizedTest(name = "{0}자 -> {1}크레딧")
    @CsvSource(
        "0, 0.0",
        "1, 0.1",
        "99, 0.1",
        "100, 0.1",
        "101, 0.2",
        "999, 1.0",
        "1000, 1.0",
        "1001, 1.1",
        "2147483647, 2147483.7",
    )
    @DisplayName("requiredFor 는 100자당 0.1크레딧으로 올림한다")
    fun `필요 크레딧은 100자 단위로 올림한다`(
        charCount: Int,
        expected: String,
    ) {
        assertThat(Credits.requiredFor(charCount).amount).isEqualByComparingTo(expected)
    }

    @ParameterizedTest(name = "기본 {0}크레딧 -> 더 쉽게 {1}크레딧")
    @CsvSource("1, 0.2", "101, 0.3", "901, 1.2")
    fun `더 쉬운 수준은 기존 단위의 1점2배를 올림한다`(
        charCount: Int,
        expected: String,
    ) {
        assertThat(Credits.requiredFor(charCount, ReadingLevel.GRADE_3_4).amount)
            .isEqualByComparingTo(expected)
        assertThat(Credits.requiredFor(charCount, ReadingLevel.GRADE_5_6))
            .isEqualTo(Credits.requiredFor(charCount))
    }

    @Test
    @DisplayName("음수 문자 수는 거절된다")
    fun `음수 문자 수는 거절된다`() {
        assertThatThrownBy { Credits.requiredFor(-1) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("음수 및 0.1 단위가 아닌 크레딧은 거절된다")
    fun `음수와 십분의 일 미만 소수는 거절된다`() {
        assertThatThrownBy { Credits(BigDecimal("-0.1")) }
            .isInstanceOf(InvalidInputException::class.java)
        assertThatThrownBy { Credits(BigDecimal("0.01")) }
            .isInstanceOf(InvalidInputException::class.java)
    }
}

class CreditTransactionKindTest {
    @Test
    @DisplayName("wireName 왕복")
    fun `wireName 왕복`() {
        CreditTransactionKind.entries.forEach {
            assertThat(CreditTransactionKind.ofWireName(it.wireName)).isEqualTo(it)
        }
    }

    @Test
    @DisplayName("알 수 없는 값은 거절된다")
    fun `모르는 값은 거절된다`() {
        assertThatThrownBy { CreditTransactionKind.ofWireName("unknown") }
            .isInstanceOf(InvalidInputException::class.java)
    }
}

class CreditReasonTest {
    @Test
    @DisplayName("wireName 왕복")
    fun `wireName 왕복`() {
        CreditReason.entries.forEach {
            assertThat(CreditReason.ofWireName(it.wireName)).isEqualTo(it)
        }
    }
}
