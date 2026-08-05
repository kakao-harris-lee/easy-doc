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


class EmailAlreadyRegisteredError(EasyDocError):
    """이미 가입된 이메일로 다시 가입을 시도했다."""
