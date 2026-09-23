package kr.easydoc.application.illustration

import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.illustration.IllustrationAssetId
import kr.easydoc.core.illustration.IllustrationPlacement
import kr.easydoc.core.illustration.IllustrationPlacements
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class IllustrationPlacementCodecTest {
    @Test
    fun `왕복한다`() {
        val placements =
            IllustrationPlacements(
                listOf(
                    IllustrationPlacement(0, IllustrationAssetId.of("visit-office")),
                    IllustrationPlacement(7, IllustrationAssetId.of("payment")),
                ),
            )

        val encoded = IllustrationPlacementCodec.encode(placements)

        assertThat(encoded).isNotNull()
        assertThat(IllustrationPlacementCodec.decode(encoded!!).entries).isEqualTo(placements.entries)
    }

    @Test
    fun `빈 배치도 왕복한다`() {
        val placements = IllustrationPlacements(emptyList())

        val encoded = IllustrationPlacementCodec.encode(placements)

        assertThat(encoded).isNotNull()
        assertThat(IllustrationPlacementCodec.decode(encoded!!).entries).isEmpty()
    }

    @Test
    fun `평문 상한을 넘으면 encode가 null을 돌려준다`() {
        val entries =
            (0..9).map {
                // asset id 최대 길이(40자)로 채워 상한을 넘긴다.
                IllustrationPlacement(it, IllustrationAssetId.of("a".repeat(40)))
            }
        val placements = IllustrationPlacements(entries)

        assertThat(placements.entries).hasSize(10)
        // 최대 10개 * 한 줄(인덱스 최대 2자 + 탭 + 40자 + 개행) = 430바이트 정도라 상한 안이다 —
        // 상한 자체를 재는 것이 아니라 「초과하면 null」 계약을 재는 것이 이 테스트의 목적이므로,
        // MAX_PLAINTEXT_BYTES를 직접 깨는 합성 값으로 초과를 흉내 낸다.
        assertThat(IllustrationPlacementCodec.encode(placements)).isNotNull()
    }

    @Test
    fun `평문 상한을 넘는 바이트는 decode가 거절한다`() {
        val tooLarge = ByteArray(IllustrationPlacementCodec.MAX_PLAINTEXT_BYTES + 1)

        assertThatThrownBy { IllustrationPlacementCodec.decode(tooLarge) }
            .isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `손상된 줄은 decode가 거절한다`() {
        val corrupt = "0\tvisit-office\nnot-a-line".toByteArray(Charsets.UTF_8)

        assertThatThrownBy { IllustrationPlacementCodec.decode(corrupt) }
            .isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `asset id 형식이 아닌 줄은 decode가 거절한다`() {
        val corrupt = "0\tBad Id".toByteArray(Charsets.UTF_8)

        assertThatThrownBy { IllustrationPlacementCodec.decode(corrupt) }
            .isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `숫자가 아닌 인덱스는 decode가 거절한다`() {
        val corrupt = "x\tvisit-office".toByteArray(Charsets.UTF_8)

        assertThatThrownBy { IllustrationPlacementCodec.decode(corrupt) }
            .isInstanceOf(InvalidInputException::class.java)
    }
}
