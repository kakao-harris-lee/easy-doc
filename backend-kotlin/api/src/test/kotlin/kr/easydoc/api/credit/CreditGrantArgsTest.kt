package kr.easydoc.api.credit

import kr.easydoc.core.credit.CreditReason
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import java.util.UUID

/**
 * [CreditGrantArgs.parse] 의 검증 분기 — Spring 컨텍스트도 DB도 없이,
 * [DefaultApplicationArguments] 를 직접 만들어 잰다. 실제 프로필 배선은
 * `CreditGrantProfileTest`(실 PostgreSQL)가 잰다.
 */
class CreditGrantArgsTest {
    private val workspaceId = UUID.randomUUID()

    @Test
    @DisplayName("유효한 인자는 그대로 파싱된다")
    fun `유효한 인자`() {
        val args =
            CreditGrantArgs.parse(
                DefaultApplicationArguments(
                    "--workspace=$workspaceId",
                    "--credits=50",
                    "--reason=manual",
                    "--note=파일럿 충전",
                ),
            )

        assertThat(args.workspaceId).isEqualTo(workspaceId)
        assertThat(args.credits).isEqualTo(50)
        assertThat(args.reason).isEqualTo(CreditReason.MANUAL)
        assertThat(args.note).isEqualTo("파일럿 충전")
    }

    @Test
    @DisplayName("note 는 선택이다 — 없으면 null")
    fun `note 없이도 통과한다`() {
        val args =
            CreditGrantArgs.parse(
                DefaultApplicationArguments("--workspace=$workspaceId", "--credits=10", "--reason=refund"),
            )

        assertThat(args.note).isNull()
    }

    @Test
    @DisplayName("음수 credits 는 허용된다 — adjust 로 갈린다(저장소 몫)")
    fun `음수 credits 허용`() {
        val args =
            CreditGrantArgs.parse(
                DefaultApplicationArguments("--workspace=$workspaceId", "--credits=-10", "--reason=manual"),
            )

        assertThat(args.credits).isEqualTo(-10)
    }

    @Test
    @DisplayName("--workspace 가 없으면 거절된다")
    fun `workspace 필수`() {
        assertThatThrownBy {
            CreditGrantArgs.parse(DefaultApplicationArguments("--credits=10", "--reason=manual"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--workspace")
    }

    @Test
    @DisplayName("--workspace 형식이 UUID 가 아니면 거절된다")
    fun `workspace 형식 오류`() {
        assertThatThrownBy {
            CreditGrantArgs.parse(
                DefaultApplicationArguments("--workspace=not-a-uuid", "--credits=10", "--reason=manual"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--workspace")
    }

    @Test
    @DisplayName("--credits 가 없으면 거절된다")
    fun `credits 필수`() {
        assertThatThrownBy {
            CreditGrantArgs.parse(DefaultApplicationArguments("--workspace=$workspaceId", "--reason=manual"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--credits")
    }

    @Test
    @DisplayName("--credits 가 정수가 아니면 거절된다")
    fun `credits 형식 오류`() {
        assertThatThrownBy {
            CreditGrantArgs.parse(
                DefaultApplicationArguments("--workspace=$workspaceId", "--credits=abc", "--reason=manual"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--credits")
    }

    @Test
    @DisplayName("--credits 가 0이면 거절된다")
    fun `credits 0은 거절된다`() {
        assertThatThrownBy {
            CreditGrantArgs.parse(
                DefaultApplicationArguments("--workspace=$workspaceId", "--credits=0", "--reason=manual"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--credits")
    }

    @Test
    @DisplayName("--reason 이 없으면 거절된다")
    fun `reason 필수`() {
        assertThatThrownBy {
            CreditGrantArgs.parse(DefaultApplicationArguments("--workspace=$workspaceId", "--credits=10"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--reason")
    }

    @Test
    @DisplayName("--reason 이 허용 목록 밖이면 거절된다 — signup·conversion 은 이 CLI 몫이 아니다")
    fun `reason 허용 목록 밖은 거절된다`() {
        assertThatThrownBy {
            CreditGrantArgs.parse(
                DefaultApplicationArguments("--workspace=$workspaceId", "--credits=10", "--reason=signup"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--reason")
    }

    @Test
    @DisplayName("--note 가 200자를 넘으면 거절된다")
    fun `note 200자 초과는 거절된다`() {
        assertThatThrownBy {
            CreditGrantArgs.parse(
                DefaultApplicationArguments(
                    "--workspace=$workspaceId",
                    "--credits=10",
                    "--reason=manual",
                    "--note=${"가".repeat(201)}",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--note")
    }

    @Test
    @DisplayName("--note 가 정확히 200자면 통과한다")
    fun `note 200자는 통과한다`() {
        val note = "가".repeat(200)
        val args =
            CreditGrantArgs.parse(
                DefaultApplicationArguments(
                    "--workspace=$workspaceId",
                    "--credits=10",
                    "--reason=manual",
                    "--note=$note",
                ),
            )

        assertThat(args.note).isEqualTo(note)
    }
}
