"""마스킹 파이프라인 단위 테스트. 테스트 데이터는 전부 가짜 개인정보다.

범주는 주민등록번호(외국인등록번호 포함)·카드번호 2종이다(2026-08-12 축소,
master-plan 3.2). 전화번호·이메일·계좌번호를 **가리지 않는다는 것**도 이 파일이
함께 고정한다 — 축소는 감수하기로 한 정책이지 미완성이 아니라서, 누군가 패턴을
다시 넓히면 그 자리에서 걸려야 한다.
"""

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


@pytest.mark.parametrize(
    "rrn",
    [
        "900101 - 1234567",  # 하이픈 앞뒤 공백
        "900101\t-\t1234567",  # 표 붙여넣기에서 실제로 나오는 탭 구분
    ],
)
def test_구분자_주변_공백_표기도_마스킹된다(rrn: str) -> None:
    result = mask_text(f"주민등록번호 {rrn} 확인.")
    assert rrn not in result.masked_text
    assert [item.category for item in result.items] == [MaskCategory.RRN]


def test_구분자_없는_카드번호도_마스킹된다() -> None:
    result = mask_text("결제 카드 1234567890123456 승인")
    assert "1234567890123456" not in result.masked_text
    assert [item.category for item in result.items] == [MaskCategory.CARD]


@pytest.mark.parametrize("card", ["1234-5678-9012-3456", "1234 5678 9012 3456"])
def test_구분자_있는_카드번호도_마스킹된다(card: str) -> None:
    result = mask_text(f"카드번호 {card} 을 입력합니다.")
    assert card not in result.masked_text
    assert [item.category for item in result.items] == [MaskCategory.CARD]


# ── 범위 밖 — 가리지 않는 것이 요구사항이다 ────────────────────────────────────────
#
# 아래 세 묶음은 "아직 구현하지 않은 것"이 아니라 **감수하기로 한 대가**다
# (master-plan 3.2). 이 값들은 마스킹 없이 그대로 LLM(국외 포함)으로 전송된다.
# 테스트가 존재하는 이유는 그 사실을 코드에 못박아, 패턴을 다시 넓히는 변경이
# 조용히 들어오지 못하게 하기 위해서다. 재확대는 정책 결정을 먼저 바꾼 뒤에 한다.


@pytest.mark.parametrize(
    "phone",
    [
        "010-1234-5678",  # 휴대전화
        "02-1234-5678",  # 기관 대표번호
        "070-1234-5678",  # 인터넷전화
        "0507-1234-5678",  # 안심번호
        "080-123-4567",
    ],
)
def test_전화번호는_범위_밖이라_평문으로_남는다(phone: str) -> None:
    result = mask_text(f"문의 {phone} 입니다.")
    assert phone in result.masked_text
    assert result.items == []


def test_이메일은_범위_밖이라_평문으로_남는다() -> None:
    text = "이메일 hong@korea.kr 로 회신 바랍니다."
    result = mask_text(text)
    assert result.masked_text == text
    assert result.items == []


def test_계좌번호는_범위_밖이라_평문으로_남는다() -> None:
    text = "계좌 123-456-789012 로 입금하세요."
    result = mask_text(text)
    assert result.masked_text == text
    assert result.items == []


def test_문서번호는_더_이상_과잉_마스킹되지_않는다() -> None:
    """계좌번호 패턴이 빠지면서 사라진 오탐이다 — 축소가 가져온 이득 쪽."""
    text = "제2024-123-4567호 공문"
    result = mask_text(text)
    assert result.masked_text == text
    assert result.items == []


def test_이메일_지역부의_전화번호_숫자열은_그대로_남는다() -> None:
    """EMAIL 패턴이 빠져 더 이상 주소 전체를 선점하지 않는다 — 주소도 숫자열도 평문이다."""
    text = "회신 주소: hong01012345678@naver.com"
    result = mask_text(text)
    assert result.masked_text == text
    assert result.items == []


@pytest.mark.parametrize(
    ("local_part", "secret", "expected"),
    [
        ("hong9001011234567", "9001011234567", MaskCategory.RRN),
        ("hong1234567890123456", "1234567890123456", MaskCategory.CARD),
    ],
)
def test_이메일_지역부에_박힌_2종은_주소_안에서도_가려진다(
    local_part: str, secret: str, expected: MaskCategory
) -> None:
    """5종 시절에는 EMAIL이 주소 전체를 선점했다. 이제 도메인은 남고 숫자열만 가려진다."""
    result = mask_text(f"회신 주소: {local_part}@naver.com")
    assert secret not in result.masked_text
    assert "naver.com" in result.masked_text
    assert [item.category for item in result.items] == [expected]


# ── 번호 매김·복원 ────────────────────────────────────────────────────────────────


def test_여러_항목_카테고리별_번호_부여() -> None:
    text = "주민 900101-1234567 와 850505-2345678 두 건."
    result = mask_text(text)
    placeholders = [item.placeholder for item in result.items]
    assert len(placeholders) == len(set(placeholders)) == 2


def test_번호는_카테고리마다_1부터_매겨진다() -> None:
    """전역 일련번호가 아니라 카테고리별 번호여야 한다."""
    result = mask_text("900101-1234567 그리고 1234-5678-9012-3456 또는 850505-2345678")
    assert [item.placeholder for item in result.items] == [
        "[[주민등록번호1]]",
        "[[카드번호1]]",
        "[[주민등록번호2]]",
    ]


def test_자리표시자를_되돌리면_원문이_복원된다() -> None:
    """대응표가 실제로 복원 가능해야 내보내기가 성립한다 (parity `restores_input`)."""
    text = "주민 900101-1234567 카드 1234-5678-9012-3456 입니다."
    result = mask_text(text)
    restored = result.masked_text
    for item in result.items:
        restored = restored.replace(item.placeholder, item.original.get_secret_value())
    assert restored == text


def test_개인정보_없는_문서는_그대로() -> None:
    text = "3월 2일부터 주민센터에서 신청할 수 있습니다."
    result = mask_text(text)
    assert result.masked_text == text
    assert result.items == []


@pytest.mark.parametrize("digits", ["2026-01-01", "123456789012", "12345678901234"])
def test_날짜와_긴_숫자열은_과잉_마스킹되지_않는다(digits: str) -> None:
    """안내문의 핵심 팩트다 — 지우면 사용자는 팩트가 사라진 결과를 받는다."""
    result = mask_text(f"확인 {digits} 끝")
    assert digits in result.masked_text
    assert result.items == []


def test_원문은_SecretStr로_보관된다() -> None:
    """실수로 로그·응답에 찍혀도 원문이 노출되지 않아야 한다."""
    item = mask_text("주민등록번호 900101-1234567 확인").items[0]
    assert "900101-1234567" not in repr(item)
    assert "900101-1234567" not in str(item.model_dump())
    assert item.original.get_secret_value() == "900101-1234567"
