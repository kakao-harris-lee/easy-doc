"""마스킹 파이프라인 단위 테스트. 테스트 데이터는 전부 가짜 개인정보다."""

from app.privacy.masking import MaskCategory, mask_text


def test_주민등록번호_마스킹() -> None:
    result = mask_text("신청자 홍길동(900101-1234567)님께 안내드립니다.")
    assert "900101-1234567" not in result.masked_text
    assert result.items[0].category == MaskCategory.RRN
    assert result.items[0].placeholder in result.masked_text


def test_휴대전화_마스킹() -> None:
    result = mask_text("문의: 010-1234-5678로 연락하세요.")
    assert "010-1234-5678" not in result.masked_text
    assert result.items[0].category == MaskCategory.PHONE


def test_이메일_마스킹() -> None:
    result = mask_text("이메일 hong@korea.kr 로 회신 바랍니다.")
    assert "hong@korea.kr" not in result.masked_text


def test_여러_항목_카테고리별_번호_부여() -> None:
    text = "연락처 010-1111-2222 또는 010-3333-4444"
    result = mask_text(text)
    placeholders = [item.placeholder for item in result.items]
    assert len(placeholders) == len(set(placeholders)) == 2


def test_번호는_카테고리마다_1부터_매겨진다() -> None:
    """전역 일련번호가 아니라 카테고리별 번호여야 한다."""
    result = mask_text("010-1111-2222 그리고 hong@korea.kr 또는 010-3333-4444")
    assert [item.placeholder for item in result.items] == [
        "[[전화번호1]]",
        "[[이메일1]]",
        "[[전화번호2]]",
    ]


def test_개인정보_없는_문서는_그대로() -> None:
    text = "3월 2일부터 주민센터에서 신청할 수 있습니다."
    result = mask_text(text)
    assert result.masked_text == text
    assert result.items == []


def test_이메일_안에_전화번호가_있어도_주소_전체가_마스킹된다() -> None:
    """전화번호 패턴이 이메일 지역부만 삼켜 도메인이 남는 유출을 막는다."""
    result = mask_text("회신 주소: hong01012345678@naver.com")
    assert "naver.com" not in result.masked_text
    assert [item.category for item in result.items] == [MaskCategory.EMAIL]


def test_기관_대표번호도_마스킹된다() -> None:
    # 프로토타입은 과잉 마스킹을 허용한다(누락보다 안전). 문맥 구분은 이후 단계.
    result = mask_text("보건소 02-1234-5678")
    assert result.items[0].category == MaskCategory.PHONE
