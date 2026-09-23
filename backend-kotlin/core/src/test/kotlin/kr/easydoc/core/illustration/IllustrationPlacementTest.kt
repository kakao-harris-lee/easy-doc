package kr.easydoc.core.illustration

import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class IllustrationPlacementTest {
    @Test
    fun `easyUnitIndex가 음수면 실패한다`() {
        assertThatThrownBy { IllustrationPlacement(-1, IllustrationAssetId.of("visit-office")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `11개면 거부한다`() {
        val entries = (0..10).map { IllustrationPlacement(it, IllustrationAssetId.of("visit-office")) }

        assertThatThrownBy { IllustrationPlacements(entries) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(PLACEMENT_TOO_MANY_MESSAGE)
    }

    @Test
    fun `10개는 허용한다`() {
        val entries = (0..9).map { IllustrationPlacement(it, IllustrationAssetId.of("visit-office")) }

        val placements = IllustrationPlacements(entries)

        assertThat(placements.entries).hasSize(10)
    }

    @Test
    fun `easyUnitIndex가 중복되면 거부한다`() {
        val entries =
            listOf(
                IllustrationPlacement(0, IllustrationAssetId.of("visit-office")),
                IllustrationPlacement(0, IllustrationAssetId.of("phone-call")),
            )

        assertThatThrownBy { IllustrationPlacements(entries) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(PLACEMENT_DUPLICATE_UNIT_MESSAGE)
    }

    @Test
    fun `easyUnitIndex 오름차순으로 정규화한다`() {
        val entries =
            listOf(
                IllustrationPlacement(3, IllustrationAssetId.of("visit-office")),
                IllustrationPlacement(1, IllustrationAssetId.of("phone-call")),
                IllustrationPlacement(2, IllustrationAssetId.of("payment")),
            )

        val placements = IllustrationPlacements(entries)

        assertThat(placements.entries.map { it.easyUnitIndex }).containsExactly(1, 2, 3)
    }

    @Test
    fun `빈 목록은 허용한다`() {
        val placements = IllustrationPlacements(emptyList())

        assertThat(placements.entries).isEmpty()
    }

    @Test
    fun `본문 줄 수 밖의 easyUnitIndex는 거부한다`() {
        val placements =
            IllustrationPlacements(listOf(IllustrationPlacement(5, IllustrationAssetId.of("visit-office"))))

        assertThatThrownBy { placements.validateAgainst(unitCount = 5, catalog = catalogOf(reviewed("visit-office"))) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(PLACEMENT_UNIT_OUT_OF_RANGE_MESSAGE)
    }

    @Test
    fun `본문 줄 수 안의 easyUnitIndex는 통과한다`() {
        val placements =
            IllustrationPlacements(listOf(IllustrationPlacement(4, IllustrationAssetId.of("visit-office"))))

        placements.validateAgainst(unitCount = 5, catalog = catalogOf(reviewed("visit-office")))
    }

    @Test
    fun `검수되지 않은 asset은 거부한다`() {
        val placements = IllustrationPlacements(listOf(IllustrationPlacement(0, IllustrationAssetId.of("phone-call"))))

        assertThatThrownBy {
            placements.validateAgainst(unitCount = 5, catalog = catalogOf(unreviewed("phone-call")))
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(PLACEMENT_ASSET_NOT_SELECTABLE_MESSAGE)
    }

    @Test
    fun `카탈로그에 없는 asset도 같은 문구로 거부한다`() {
        val placements =
            IllustrationPlacements(listOf(IllustrationPlacement(0, IllustrationAssetId.of("does-not-exist"))))

        assertThatThrownBy {
            placements.validateAgainst(unitCount = 5, catalog = catalogOf(reviewed("visit-office")))
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(PLACEMENT_ASSET_NOT_SELECTABLE_MESSAGE)
    }

    @Test
    fun `entries가 같으면 동등하다`() {
        val a = IllustrationPlacements(listOf(IllustrationPlacement(1, IllustrationAssetId.of("visit-office"))))
        val b = IllustrationPlacements(listOf(IllustrationPlacement(1, IllustrationAssetId.of("visit-office"))))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `toString은 개수만 낸다`() {
        val placements =
            IllustrationPlacements(
                listOf(
                    IllustrationPlacement(0, IllustrationAssetId.of("visit-office")),
                    IllustrationPlacement(7, IllustrationAssetId.of("payment")),
                ),
            )

        val text = placements.toString()

        assertThat(text).doesNotContain("visit-office", "payment", "7")
        assertThat(text).contains("2")
    }

    private fun catalogOf(vararg entries: Illustration): IllustrationCatalog = IllustrationCatalog(entries.toList())

    private fun reviewed(assetId: String): Illustration =
        Illustration(
            assetId = IllustrationAssetId.of(assetId),
            caption = "기관 방문",
            purpose = IllustrationPurpose.VISIT_OFFICE,
            altText = "사람이 건물 입구로 걸어 들어가는 그림",
            license = "CC0-1.0",
            source = "easy-doc",
            reviewStatus = IllustrationReviewStatus.REVIEWED,
            reviewedBy = "harris.lee",
            reviewedAt = LocalDate.of(2026, 9, 23),
            version = 1,
            mappingExamples = listOf("주민센터에 직접 가서 신청하세요."),
        )

    private fun unreviewed(assetId: String): Illustration =
        Illustration(
            assetId = IllustrationAssetId.of(assetId),
            caption = "전화 문의",
            purpose = IllustrationPurpose.PHONE_CALL,
            altText = "수화기를 든 손 그림",
            license = "CC0-1.0",
            source = "easy-doc",
            reviewStatus = IllustrationReviewStatus.UNREVIEWED,
            reviewedBy = null,
            reviewedAt = null,
            version = 1,
            mappingExamples = listOf("전화로 물어보세요."),
        )
}
