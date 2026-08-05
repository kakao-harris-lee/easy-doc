"""도메인 예외 정의. 라우터 레벨에서 HTTP 응답으로 변환한다."""


class EasyDocError(Exception):
    """서비스 공통 최상위 예외."""


class LLMProviderError(EasyDocError):
    """LLM 호출 실패."""


class LLMTruncatedError(LLMProviderError):
    """출력 토큰 한도에서 응답이 잘렸다.

    LLMProviderError 하위로 두어 기존 호출 계약(단일 except)을 유지하면서,
    벤치마크·평가 리포트가 실패 유형을 구분할 수 있게 한다.
    """


class LLMEmptyResultError(LLMProviderError):
    """응답이 비었거나 후처리 후 본문이 남지 않았다."""


class InvalidInputError(EasyDocError):
    """사용자 입력이 도메인 규칙을 위반했다 (형식·길이 등)."""


class EmailAlreadyRegisteredError(EasyDocError):
    """이미 가입된 이메일로 다시 가입을 시도했다."""


class InvalidCredentialsError(EasyDocError):
    """인증 실패.

    이메일 부재와 비밀번호 불일치, 토큰 만료와 위조를 구분하지 않는다 — 어느 쪽인지
    알려주면 가입 여부가 새어 나가 계정 열거(enumeration) 공격의 단서가 된다.
    """


class NotFoundError(EasyDocError):
    """요청한 리소스가 없다."""


class ConfigurationError(EasyDocError):
    """서버 설정이 비어 있어 기능을 제공할 수 없다 (예: JWT 비밀키 미설정).

    사용자 잘못이 아니라 운영 설정 문제이므로 5xx로 매핑한다.
    """
