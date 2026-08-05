"""마스킹 파이프라인 단위 테스트. 테스트 데이터는 전부 가짜 개인정보다."""

import pytest

from app.privacy.masking import MaskCategory, mask_text


def test_주민등록번호_마스킹() -> None:
    result = mask_text("신청자 홍길동(900101-1234567)님께 안내드립니다.")
    assert "900101-1234567" not in result.masked_text
    assert result.items[0].category == MaskCategory.RRN
    assert result.items[0].placeholder in result.masked_text


@pytest.mark.parametrize(
    "rrn",
    [
        "900101-1234567",  # 내국인 남/여
        "900101-2234567",
        "010101-3234567",
        "010101-4234567",
        "900101-5234567",  # 외국인등록번호(고유식별정보) — 누락 시 보안 구멍
        "900101-6234567",
        "010101-7234567",
        "010101-8234567",
    ],
)
def test_성별코드_1부터_8까지_모두_마스킹된다(rrn: str) -> None:
    """5~8은 외국인등록번호다. 개인정보보호법상 동일한 고유식별정보."""
    result = mask_text(f"신청자 {rrn} 확인")
    assert rrn not in result.masked_text
    assert [item.category for item in result.items] == [MaskCategory.RRN]


def test_구분자_없는_주민번호도_마스킹된다() -> None:
    """하이픈 없이 13자리로 적힌 표기도 놓치지 않는다."""
    result = mask_text("주민번호 9001011234567 를 확인했습니다.")
    assert "9001011234567" not in result.masked_text
    assert [item.category for item in result.items] == [MaskCategory.RRN]


def test_구분자_없는_카드번호도_마스킹된다() -> None:
    result = mask_text("결제 카드 1234567890123456 승인")
    assert "1234567890123456" not in result.masked_text
    assert [item.category for item in result.items] == [MaskCategory.CARD]


@pytest.mark.parametrize("phone", ["070-1234-5678", "0507-1234-5678", "080-123-4567"])
def test_인터넷전화_안심번호도_전화번호로_분류된다(phone: str) -> None:
    """계좌번호가 아니라 전화번호로 분류되어야 한다."""
    result = mask_text(f"문의 {phone} 입니다.")
    assert phone not in result.masked_text
    assert [item.category for item in result.items] == [MaskCategory.PHONE]


def test_휴대전화_마스킹() -> None:
    result = mask_text("문의: 010-1234-5678로 연락하세요.")
    assert "010-1234-5678" not in result.masked_text
    assert result.items[0].category == MaskCategory.PHONE


def test_이메일_마스킹() -> None:
    result = mask_text("이메일 hong@korea.kr 로 회신 바랍니다.")
    assert "hong@korea.kr" not in result.masked_text


def test_이메일에_붙은_조사와_마침표는_보존된다() -> None:
    """주소만 정확히 잘라내야 뒤따르는 한글 조사가 손실되지 않는다."""
    result = mask_text("hong@korea.kr으로 회신 바랍니다.")
    assert result.masked_text == "[[이메일1]]으로 회신 바랍니다."


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


def test_문서번호는_계좌번호로_과잉_마스킹된다() -> None:
    """의도된 동작을 고정한다: 누락보다 과잉이 안전하다는 방침(특성 테스트)."""
    result = mask_text("제2024-123-4567호 공문")
    assert [item.category for item in result.items] == [MaskCategory.ACCOUNT]


def test_원문은_SecretStr로_보관된다() -> None:
    """실수로 로그·응답에 찍혀도 원문이 노출되지 않아야 한다."""
    item = mask_text("문의: 010-1234-5678").items[0]
    assert "010-1234-5678" not in repr(item)
    assert "010-1234-5678" not in str(item.model_dump())
    assert item.original.get_secret_value() == "010-1234-5678"
