package kr.easydoc.api.invoice

import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.invoice.InvoiceRequestStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import java.util.UUID

/**
 * [InvoiceHandleArgs.parse] 의 검증 분기 — Spring 컨텍스트도 DB도 없이,
 * [DefaultApplicationArguments] 를 직접 만들어 잰다(`CreditGrantArgsTest`와 같은 형태).
 */
class InvoiceHandleArgsTest {
    private val id = UUID.randomUUID()

    @Test
    @DisplayName("유효한 인자는 그대로 파싱된다")
    fun `유효한 인자`() {
        val args =
            InvoiceHandleArgs.parse(
                DefaultApplicationArguments("--id=$id", "--status=issued", "--note=발급 완료"),
            )

        assertThat(args.id).isEqualTo(id)
        assertThat(args.status).isEqualTo(InvoiceRequestStatus.ISSUED)
        assertThat(args.note).isEqualTo("발급 완료")
    }

    @Test
    @DisplayName("note 는 선택이다 — 없으면 null")
    fun `note 없이도 통과한다`() {
        val args = InvoiceHandleArgs.parse(DefaultApplicationArguments("--id=$id", "--status=rejected"))

        assertThat(args.note).isNull()
    }

    @Test
    @DisplayName("--id 가 없으면 거절된다")
    fun `id 필수`() {
        assertThatThrownBy { InvoiceHandleArgs.parse(DefaultApplicationArguments("--status=issued")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--id")
    }

    @Test
    @DisplayName("--id 형식이 UUID 가 아니면 거절된다")
    fun `id 형식 오류`() {
        assertThatThrownBy {
            InvoiceHandleArgs.parse(DefaultApplicationArguments("--id=not-a-uuid", "--status=issued"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--id")
    }

    @Test
    @DisplayName("--status 가 없으면 거절된다")
    fun `status 필수`() {
        assertThatThrownBy { InvoiceHandleArgs.parse(DefaultApplicationArguments("--id=$id")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--status")
    }

    @Test
    @DisplayName("--status 가 허용 목록 밖이면 거절된다 — requested 로는 되돌릴 수 없다")
    fun `status 허용 목록 밖은 거절된다`() {
        assertThatThrownBy {
            InvoiceHandleArgs.parse(DefaultApplicationArguments("--id=$id", "--status=requested"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--status")
    }

    @Test
    @DisplayName("--note 가 500자를 넘으면 거절된다 — application 층 requireValidOperatorNote 로 위임한다")
    fun `note 500자 초과는 거절된다`() {
        assertThatThrownBy {
            InvoiceHandleArgs.parse(
                DefaultApplicationArguments("--id=$id", "--status=rejected", "--note=${"가".repeat(501)}"),
            )
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage("운영자 메모는 500자 이하여야 합니다")
    }

    @Test
    @DisplayName("--note 가 정확히 500자면 통과한다")
    fun `note 500자는 통과한다`() {
        val note = "가".repeat(500)
        val args =
            InvoiceHandleArgs.parse(
                DefaultApplicationArguments("--id=$id", "--status=rejected", "--note=$note"),
            )

        assertThat(args.note).isEqualTo(note)
    }

    @Test
    @DisplayName("--note 의 제어문자·앞뒤 공백은 다른 필드와 같은 규칙으로 걷어내진다")
    fun `note 의 제어문자는 정규화된다`() {
        val args =
            InvoiceHandleArgs.parse(
                DefaultApplicationArguments("--id=$id", "--status=rejected", "--note=\u0001 발급 보류 \u0001"),
            )

        assertThat(args.note).isEqualTo("발급 보류")
    }
}
