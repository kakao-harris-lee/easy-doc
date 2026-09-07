package kr.easydoc.core.credit

import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

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
    @DisplayName("requiredFor 는 1,000자당 1크레딧을 올림한다")
    fun `필요 크레딧은 올림한다`() {
        assertThat(Credits.requiredFor(1).amount).isEqualTo(1)
        assertThat(Credits.requiredFor(999).amount).isEqualTo(1)
        assertThat(Credits.requiredFor(1000).amount).isEqualTo(1)
        assertThat(Credits.requiredFor(1001).amount).isEqualTo(2)
        assertThat(Credits.requiredFor(2500).amount).isEqualTo(3)
        assertThat(Credits.requiredFor(0).amount).isEqualTo(0)
    }

    @Test
    @DisplayName("음수 문자 수는 거절된다")
    fun `음수 문자 수는 거절된다`() {
        assertThatThrownBy { Credits.requiredFor(-1) }.isInstanceOf(IllegalArgumentException::class.java)
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
