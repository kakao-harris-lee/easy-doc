"""도메인 예외 정의. 라우터 레벨에서 HTTP 응답으로 변환한다."""


class EasyDocError(Exception):
    """서비스 공통 최상위 예외."""


class LLMProviderError(EasyDocError):
    """LLM 호출 실패."""
