"""문서 본문 암호화 (Fernet).

업로드 원문·변환 결과·마스킹 항목은 평문으로 저장하지 않는다 (master-plan 3.2).
저장 직전에 이 모듈을 통과시키고, 읽을 때만 되돌린다.

Fernet(AES-128-CBC + HMAC-SHA256)을 쓰는 이유는 인증 암호화를 직접 조립하지 않기
위해서다 — 암호문 변조가 복호화 실패로 드러나고, IV는 매번 새로 생성된다(같은 본문을
올린 사용자가 누구인지 암호문 비교만으로 드러나지 않는다).

키 회전은 이번 범위 밖이다. 필요해지면 `MultiFernet`으로 옛 키를 복호화 전용으로
남기는 방식으로 확장한다 — 그때 이 클래스의 생성자만 바뀌면 된다.
"""

from cryptography.fernet import Fernet, InvalidToken

from app.exceptions import ConfigurationError, StorageError

#: 지금 쓰는 암호화 키의 세대 번호. 암호문과 함께 저장한다.
#:
#: Fernet 토큰에는 어떤 키로 만들었는지가 담기지 않는다 — 키를 교체하는 날, 어떤 행이
#: 옛 키로 암호화됐는지 알아낼 방법이 없어 전수 복호화 시도밖에 남지 않는다. 컬럼 하나를
#: 지금 넣어두면 그때 `WHERE key_version = 1`로 대상만 골라 재암호화할 수 있다.
#: 키를 교체할 때 이 값을 올리고, 옛 값은 복호화 전용 키로 남긴다(MultiFernet).
CURRENT_KEY_VERSION = 1


class TextCipher:
    """텍스트를 Fernet 토큰으로 바꾸고 되돌린다.

    키가 아예 없는 경우(설정 누락)는 이 클래스가 아니라 호출부(`app/api/deps.py`,
    워커 startup)가 판단한다 — 여기서는 "키는 있는데 형식이 틀린" 경우만 다룬다.
    """

    def __init__(self, fernet_key: str) -> None:
        """Args:
            fernet_key: url-safe base64로 인코딩된 32바이트 키.

        Raises:
            ConfigurationError: 키 형식이 Fernet 규격에 맞지 않는다.
        """
        try:
            self._fernet = Fernet(fernet_key)
        except (ValueError, TypeError):
            # 원본 예외를 매달지 않는다 — cryptography의 메시지에 키 값이 실리지는
            # 않지만, 예외 체인이 붙으면 트레이스백에 생성자 인자가 노출되는 지점이
            # 늘어난다. 사용자 잘못이 아니므로 5xx가 되는 예외로 올린다.
            raise ConfigurationError("문서 암호화 키 설정이 올바르지 않습니다") from None

    def encrypt(self, text: str) -> bytes:
        """텍스트를 암호화한다. 같은 입력이라도 매번 다른 결과가 나온다."""
        return self._fernet.encrypt(text.encode("utf-8"))

    def decrypt(self, data: bytes) -> str:
        """암호문을 원래 텍스트로 되돌린다.

        Raises:
            StorageError: 키가 다르거나 암호문이 손상·변조됐다. 사용자 입력 문제가
                아니라 저장된 데이터를 읽을 수 없는 상황이므로 5xx로 올린다.
        """
        try:
            return self._fernet.decrypt(data).decode("utf-8")
        except (InvalidToken, UnicodeDecodeError):
            # 암호문·예외 메시지를 남기지 않는다(from None). 어떤 문서였는지는 호출부가
            # 문서 ID로 로깅한다.
            raise StorageError("저장된 문서를 읽을 수 없습니다") from None
