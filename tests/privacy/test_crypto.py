"""TextCipher 테스트 — 저장 전 암호화 왕복과 실패 경로.

암호문·예외에 평문이 남지 않는지까지 본다. 문서 본문은 개인정보를 담을 수 있고
(master-plan 3.2), 저장·로그 어느 쪽으로도 새면 안 된다.
"""

import pytest
from cryptography.fernet import Fernet

from app.exceptions import ConfigurationError, StorageError
from app.privacy.crypto import TextCipher

_TEXT = "홍길동 님의 신청서가 접수되었습니다. 연락처 010-1234-5678"


@pytest.fixture
def key() -> str:
    """테스트마다 새로 만든 Fernet 키."""
    return Fernet.generate_key().decode()


@pytest.fixture
def cipher(key: str) -> TextCipher:
    """정상 키로 만든 암호기."""
    return TextCipher(key)


def test_암호화한_텍스트를_그대로_복호화한다(cipher: TextCipher) -> None:
    assert cipher.decrypt(cipher.encrypt(_TEXT)) == _TEXT


def test_빈_문자열도_왕복한다(cipher: TextCipher) -> None:
    """빈 본문은 상위 계층이 막지만, 암호기 자체가 예외를 내면 안 된다."""
    assert cipher.decrypt(cipher.encrypt("")) == ""


def test_긴_한국어_본문도_왕복한다(cipher: TextCipher) -> None:
    long_text = _TEXT * 5_000

    assert cipher.decrypt(cipher.encrypt(long_text)) == long_text


def test_같은_평문을_두_번_암호화하면_암호문이_다르다(cipher: TextCipher) -> None:
    """Fernet은 매번 새 IV를 쓴다 — 결정적이면 같은 본문 여부가 저장소에서 드러난다."""
    first = cipher.encrypt(_TEXT)
    second = cipher.encrypt(_TEXT)

    assert first != second
    assert cipher.decrypt(first) == cipher.decrypt(second) == _TEXT


def test_암호문에_평문_조각이_남지_않는다(cipher: TextCipher) -> None:
    encrypted = cipher.encrypt(_TEXT)

    assert b"010-1234-5678" not in encrypted
    assert "홍길동".encode() not in encrypted


def test_다른_키로는_복호화할_수_없다(cipher: TextCipher) -> None:
    """키를 교체했는데 옛 데이터가 남은 상황 — 조용히 빈 값이 되면 안 된다."""
    other = TextCipher(Fernet.generate_key().decode())

    with pytest.raises(StorageError):
        other.decrypt(cipher.encrypt(_TEXT))


def test_손상된_암호문은_도메인_예외가_된다(cipher: TextCipher) -> None:
    with pytest.raises(StorageError):
        cipher.decrypt(b"not-a-fernet-token")


def test_복호화_실패_예외에_원본이_매달리지_않는다(cipher: TextCipher) -> None:
    """예외 체인이 붙으면 트레이스백을 찍는 지점마다 암호문이 로그로 새어 나간다."""
    with pytest.raises(StorageError) as error:
        cipher.decrypt(b"broken")

    assert error.value.__cause__ is None
    assert "broken" not in str(error.value)


@pytest.mark.parametrize("bad_key", ["", "너무-짧은-키", "not-base64!!", "c2hvcnQ="])
def test_형식이_틀린_키는_설정_오류다(bad_key: str) -> None:
    """사용자 잘못이 아니라 운영 설정 문제다 — 4xx가 아니라 5xx로 알린다."""
    with pytest.raises(ConfigurationError) as error:
        TextCipher(bad_key)

    # 키 값이 메시지에 실리면 로그·응답으로 비밀이 샌다.
    assert bad_key not in str(error.value) or not bad_key
    assert error.value.__cause__ is None
