"""LLM Provider 추상화 — 모든 LLM 호출의 유일한 관문 (master-plan 3.1).

서비스 코드는 벤더 SDK를 직접 import하지 않고 이 인터페이스만 사용한다.
모든 구현체는 입력 데이터 학습 미사용(no-training) 계약을 전제로 한다.
"""

from abc import ABC, abstractmethod
from typing import ClassVar

from pydantic import BaseModel

# 출력 상한이지 지출이 아니다 — 과금은 실제 생성한 토큰만큼이다. 넉넉히 잡는 이유는
# claude-sonnet-5가 thinking 미지정 시 적응형 사고를 켜고 그 사고 토큰이 max_tokens를
# 본문과 나눠 쓰기 때문이다. 1차 벤치마크(docs/benchmarks/2026-08-08-1642-llm-benchmark.md)
# 에서 anthropic 장문 20건(36%)이 빈 응답·절단으로 실패한 원인이 옛 상한 4,096이었다
# (완주 문서도 출력 토큰 중앙값 3,616으로 상한에 붙어 있었다).
# 16,000은 비스트리밍 요청의 권장 상한이다 — 더 키우면 SDK가 스트리밍을 요구할 수 있다.
DEFAULT_MAX_TOKENS = 16000
DEFAULT_TEMPERATURE = 0.2
# SDK 기본값(read 600초 × 재시도 3회)은 문서 변환 워커에 과도하다 — 명시적으로 좁힌다.
DEFAULT_TIMEOUT_SECONDS = 60.0
DEFAULT_MAX_RETRIES = 2


class LLMResponse(BaseModel):
    """LLM 완성 응답."""

    text: str
    model: str
    input_tokens: int = 0
    output_tokens: int = 0
    # 출력 상한에 걸려 잘렸다는 사실만 알린다. 재시도·분할 등 정책 판단은 변환 서비스 몫.
    truncated: bool = False


class LLMProvider(ABC):
    """LLM 벤더 공통 인터페이스."""

    name: ClassVar[str]

    @abstractmethod
    async def complete(
        self,
        *,
        system: str,
        user: str,
        max_tokens: int = DEFAULT_MAX_TOKENS,
        temperature: float = DEFAULT_TEMPERATURE,
    ) -> LLMResponse:
        """단일 완성 요청. 실패 시 LLMProviderError를 던진다."""

    # B027 억제 사유: 빈 본문이지만 추상 메서드가 아니다. 정리할 자원이 없는 구현체가
    # 그대로 물려받도록 의도한 기본 no-op이다.
    async def aclose(self) -> None:  # noqa: B027
        """보유 자원(HTTP 커넥션 풀 등)을 정리한다.

        수명 규약: provider는 앱·워커·스크립트 수명 단위로 한 번 생성해 재사용하고,
        종료 시 aclose()를 호출한다. 요청마다 새로 만들면 커넥션 풀이 재사용되지 않는다.
        기본 구현은 정리할 자원이 없는 구현체(FakeProvider 등)를 위한 no-op이다.
        """
