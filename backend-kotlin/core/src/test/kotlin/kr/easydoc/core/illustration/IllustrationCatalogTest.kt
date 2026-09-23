package kr.easydoc.core.illustration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class IllustrationCatalogTest {
    @Test
    fun `11개면 실패한다`() {
        val entries = (1..11).map { reviewed(assetId = "asset-$it") }

        assertThatThrownBy { IllustrationCatalog(entries) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `asset id가 중복되면 실패한다`() {
        val entries = listOf(reviewed(assetId = "visit-office"), reviewed(assetId = "visit-office"))

        assertThatThrownBy { IllustrationCatalog(entries) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `selectable은 검수된 항목만 원래 순서로 돌려준다`() {
        val reviewed1 = reviewed(assetId = "visit-office")
        val unreviewed = unreviewed(assetId = "phone-call")
        val reviewed2 = reviewed(assetId = "submit-document")
        val catalog = IllustrationCatalog(listOf(reviewed1, unreviewed, reviewed2))

        assertThat(catalog.selectable()).containsExactly(reviewed1, reviewed2)
    }

    @Test
    fun `검수됐는데 검수자가 없으면 실패한다`() {
        assertThatThrownBy {
            Illustration(
                assetId = IllustrationAssetId.of("visit-office"),
                caption = "기관 방문",
                purpose = IllustrationPurpose.VISIT_OFFICE,
                altText = "사람이 건물 입구로 걸어 들어가는 그림",
                license = "CC0-1.0",
                source = "easy-doc",
                reviewStatus = IllustrationReviewStatus.REVIEWED,
                reviewedBy = null,
                reviewedAt = null,
                version = 1,
                mappingExamples = listOf("주민센터에 직접 가서 신청하세요."),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `mappingExamples가 4개면 실패한다`() {
        assertThatThrownBy {
            Illustration(
                assetId = IllustrationAssetId.of("visit-office"),
                caption = "기관 방문",
                purpose = IllustrationPurpose.VISIT_OFFICE,
                altText = "사람이 건물 입구로 걸어 들어가는 그림",
                license = "CC0-1.0",
                source = "easy-doc",
                reviewStatus = IllustrationReviewStatus.REVIEWED,
                reviewedBy = "harris.lee",
                reviewedAt = LocalDate.of(2026, 9, 23),
                version = 1,
                mappingExamples = listOf("첫째.", "둘째.", "셋째.", "넷째."),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `toString에 caption과 altText가 안 찍힌다`() {
        val illustration = reviewed(assetId = "visit-office", caption = "비밀-캡션", altText = "비밀-대체텍스트")

        val text = illustration.toString()

        assertThat(text).doesNotContain("비밀-캡션")
        assertThat(text).doesNotContain("비밀-대체텍스트")
        assertThat(text).contains("visit-office")
    }

    @Test
    fun `asset id 패턴이 대문자를 거부한다`() {
        assertThatThrownBy { IllustrationAssetId.of("Visit-Office") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `asset id 패턴이 공백을 거부한다`() {
        assertThatThrownBy { IllustrationAssetId.of("visit office") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `asset id 패턴이 41자를 거부한다`() {
        val tooLong = "a".repeat(41)

        assertThatThrownBy { IllustrationAssetId.of(tooLong) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `asset id parse는 실패하면 null이다`() {
        assertThat(IllustrationAssetId.parse("Bad Id")).isNull()
        assertThat(IllustrationAssetId.parse("visit-office")).isEqualTo(IllustrationAssetId.of("visit-office"))
    }

    private fun reviewed(
        assetId: String,
        caption: String = "기관 방문",
        altText: String = "사람이 건물 입구로 걸어 들어가는 그림",
    ): Illustration =
        Illustration(
            assetId = IllustrationAssetId.of(assetId),
            caption = caption,
            purpose = IllustrationPurpose.VISIT_OFFICE,
            altText = altText,
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
