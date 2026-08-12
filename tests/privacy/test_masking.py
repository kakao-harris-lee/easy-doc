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


# ── 양성 케이스의 정확 출력 ────────────────────────────────────────────────────────
#
# `restores_input`(위 검사)은 이 자리를 대신하지 못한다. 역치환이 **삼킨 조사를 도로
# 꽂아** 주기 때문에, 마스킹 구간이 뒤 글자까지 한 칸 더 먹어도 복원은 성립한다.
# 즉 경계 과잉 잠식은 복원 성질을 통과한다. 잡으려면 마스킹 **결과 문자열 자체**를
# 봐야 한다. Kotlin이 내야 하는 것도 이 문자열이라 parity에 직결된다.
#
# 아래 검사는 전부 **양성**(가려져야 하는 입력)이다 — 이 파일의 다른 `masked_text ==`
# 단언은 모두 음성 케이스(가리면 안 되는 입력)라, 양성 쪽 정확 출력은 여기서만 고정된다.
# 숫자 바로 뒤에 조사를 붙여 두었다: 한 칸이라도 더 먹으면 조사가 사라져 실패한다.


def test_주민등록번호_양성_케이스의_정확한_출력() -> None:
    result = mask_text("신청자 900101-1234567님께 안내드립니다.")
    assert result.masked_text == "신청자 [[주민등록번호1]]님께 안내드립니다."
    assert [item.placeholder for item in result.items] == ["[[주민등록번호1]]"]
    assert result.items[0].original.get_secret_value() == "900101-1234567"


def test_카드번호_양성_케이스의_정확한_출력() -> None:
    result = mask_text("결제 카드 1234-5678-9012-3456으로 승인되었습니다.")
    assert result.masked_text == "결제 카드 [[카드번호1]]으로 승인되었습니다."
    assert [item.placeholder for item in result.items] == ["[[카드번호1]]"]
    assert result.items[0].original.get_secret_value() == "1234-5678-9012-3456"


def test_두_범주가_한_문장에_섞여도_정확한_출력이_나온다() -> None:
    """번호 매김과 경계를 한 번에 고정한다 — 구간이 밀리면 조사·조서가 어긋난다."""
    result = mask_text("주민번호 900101-1234567와 카드 1234-5678-9012-3456을 확인했습니다.")
    assert result.masked_text == "주민번호 [[주민등록번호1]]와 카드 [[카드번호1]]을 확인했습니다."


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


# ── 보이지 않는 문자로 인한 마스킹 회피 ────────────────────────────────────────────
#
# 숫자 사이에 폭 없는 문자·제어문자가 끼면 정규식이 뚫린다
# (`docs/migration/_workspace/02_privacy-gate_control-char-verdict.md` 판정 (가)).
# 악의적 회피가 아니라 사고성 유입이 주 경로다 — 실제 정부 문서 코퍼스에서 소프트하이픈
# (U+00AD)·NUL이 하이픈 자리를 대신하고 있는 사례가 실측됐고, PDF 추출과 JSON 붙여넣기
# 경로에는 이를 걸러 주는 것이 없다. 피해자는 문서에 등장하는 제3자 시민이다.
#
# 소스에 보이지 않는 문자를 박으면 다음 사람이 읽을 수 없으므로 전부 chr()로 만든다.
_SOFT_HYPHEN = chr(0x00AD)
_ZWSP = chr(0x200B)

# 판정서 §5.1이 프로토타입으로 차단을 실증한 6종.
_EVASION_CHARS = {
    "NUL": chr(0x0000),
    "FS": chr(0x001C),
    "US": chr(0x001F),
    "SOFT_HYPHEN": _SOFT_HYPHEN,
    "ZWSP": _ZWSP,
    "BOM": chr(0xFEFF),
}

_EVASION_TARGETS = (
    ("900101-1234567", MaskCategory.RRN),
    ("1234-5678-9012-3456", MaskCategory.CARD),
)


@pytest.mark.parametrize("char_name", sorted(_EVASION_CHARS))
@pytest.mark.parametrize(("secret", "category"), _EVASION_TARGETS)
def test_숫자_사이에_낀_보이지_않는_문자가_마스킹을_뚫지_못한다(
    char_name: str, secret: str, category: MaskCategory
) -> None:
    """삽입 위치를 전수로 돌린다 — 한 자리만 열려 있어도 그 자리로 새어 나간다."""
    char = _EVASION_CHARS[char_name]
    leaked = [
        position
        for position in range(1, len(secret))
        for payload in (secret[:position] + char + secret[position:],)
        for result in (mask_text(f"신청자 {payload} 확인"),)
        if payload in result.masked_text or [item.category for item in result.items] != [category]
    ]
    assert leaked == []


@pytest.mark.parametrize("char_name", sorted(_EVASION_CHARS))
@pytest.mark.parametrize(("secret", "category"), _EVASION_TARGETS)
def test_낀_문자가_있어도_자리표시자를_되돌리면_원문이_복원된다(
    char_name: str, secret: str, category: MaskCategory
) -> None:
    """마스킹은 정규화된 뷰에서 찾되 자르기는 원문 좌표로 한다 — 낀 문자는 original에 남는다.

    입력 자체를 정규화해서 넘겼다면 이 검사가 깨진다. 깨지면 내보내기가 잘못된 원문을
    꽂으므로, 회피 차단과 복원 가능성은 함께 성립해야 한다.
    """
    char = _EVASION_CHARS[char_name]
    for position in range(1, len(secret)):
        text = f"앞 {secret[:position]}{char}{secret[position:]} 뒤"
        result = mask_text(text)
        restored = result.masked_text
        for item in result.items:
            restored = restored.replace(item.placeholder, item.original.get_secret_value(), 1)
        assert restored == text


def test_낀_문자가_들어간_양성_케이스도_정확한_출력을_낸다() -> None:
    """회피 차단이 경계를 넓히지 않는다는 것까지 문자열로 못박는다."""
    result = mask_text(f"신청자 900101-123{_SOFT_HYPHEN}4567님께 안내드립니다.")
    assert result.masked_text == "신청자 [[주민등록번호1]]님께 안내드립니다."
    # 원문 조각이 남지 않아야 한다 — 낀 문자까지 통째로 구간에 들어간다.
    assert _SOFT_HYPHEN not in result.masked_text
    assert result.items[0].original.get_secret_value() == f"900101-123{_SOFT_HYPHEN}4567"


def test_구간_밖의_보이지_않는_문자는_건드리지_않는다() -> None:
    """뷰는 탐색용일 뿐이다 — 마스킹 대상이 아닌 자리의 문자는 원문 그대로 남는다."""
    text = f"{_ZWSP}안내{_ZWSP} 신청자 900101-1234567님{_ZWSP}"
    result = mask_text(text)
    assert result.masked_text == f"{_ZWSP}안내{_ZWSP} 신청자 [[주민등록번호1]]님{_ZWSP}"


@pytest.mark.parametrize(
    "text",
    [
        "표 항목 900101\n1234567 끝",  # 개행으로 갈린 두 숫자열
        "표 항목 1234\n5678\n9012\n3456 끝",
        "쪽 900101\r\n1234567 끝",
    ],
)
def test_개행으로_갈린_숫자열은_붙지_않는다(text: str) -> None:
    """탭·개행·공백은 뷰에서 지우지 않는다. 지우면 서로 다른 줄의 숫자가 붙어 과잉 마스킹된다."""
    result = mask_text(text)
    assert result.masked_text == text
    assert result.items == []
