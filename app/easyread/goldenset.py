"""골든셋 문서 로더.

골든셋 평가 테스트(tests/golden)와 벤더 비교 벤치마크(scripts/benchmark.py)가
같은 로더를 공유한다 — 로딩 규칙을 각자 복제하면 평가 기준이 갈라진다.
팩트 잔존 판정도 RequiredFact.retained_in() 한 곳에만 둔다(중복 구현 금지).
문서 본문은 합성(synthetic) 샘플이지만, 실제 수집본으로 교체될 자리이므로
로더는 본문을 로그·예외 메시지에 남기지 않는다.
"""

import json
from pathlib import Path

from pydantic import BaseModel, ConfigDict, model_validator


class RequiredFact(BaseModel):
    """변환 후에도 반드시 남아야 할 리터럴 하나.

    canonical은 원문에 그대로 존재하는 표기다(스키마 검증 기준).
    accept는 쉬운 글 변환에서 자연스럽게 나오는 동등 표기다 — "만 65세"를 "65세"로,
    "30퍼센트"를 "30%"로 바꾸는 것은 정보 손실이 아니므로 잔존 판정에서 함께 인정한다.
    accept를 넓히면 왜곡을 놓치므로 표기 차이에만 쓰고 의미가 달라지는 말은 넣지 않는다.
    """

    model_config = ConfigDict(extra="forbid")

    canonical: str
    accept: list[str] = []

    @model_validator(mode="before")
    @classmethod
    def _promote_plain_string(cls, value: object) -> object:
        """JSON에 문자열만 적힌 팩트는 canonical로 승격한다(변형이 필요 없는 경우)."""
        if isinstance(value, str):
            return {"canonical": value}
        return value

    def retained_in(self, text: str) -> bool:
        """canonical 또는 accept 중 하나라도 남아 있으면 보존된 것으로 본다."""
        return self.canonical in text or any(variant in text for variant in self.accept)


class GoldenSource(BaseModel):
    """수집 출처 메타 (synthetic=false 문서 필수).

    공공저작물은 출처 표시가 이용 조건이므로(공공누리 제1유형) 기관명·이용 조건 없이
    편입된 문서는 쓸 수 없다. collected_at은 같은 URL의 내용이 나중에 바뀌었을 때
    평가 결과가 어느 시점 본문의 것인지 특정하기 위해 남긴다.
    """

    model_config = ConfigDict(extra="forbid")

    url: str | None = None  # 웹 수집 시. 파일럿 기관이 직접 제공한 문서는 없다.
    organization: str
    license: str  # 예: "공공누리 제1유형", "파일럿 기관 제공"
    collected_at: str  # YYYY-MM-DD


class GoldenDocument(BaseModel):
    """골든셋 평가용 문서 한 건.

    required_facts에는 마스킹 대상 패턴(전화·이메일·계좌 등)을 절대 넣지 않는다.
    플레이스홀더로 치환되어 팩트 보존 검사가 항상 실패한다
    (tests/golden/test_schema.py가 기계적으로 검증).
    """

    # extra="forbid": 손으로 쓰는 JSON이라 필드명 오타가 조용히 무시되면 규칙이 헐거워진다.
    model_config = ConfigDict(extra="forbid")

    id: str
    title: str
    category: str
    synthetic: bool
    source_text: str
    required_facts: list[RequiredFact]
    # 합성 문서에는 출처가 없다(우리가 만들었다). 실제 수집본은 아래 검증이 강제한다.
    source: GoldenSource | None = None

    @model_validator(mode="after")
    def _require_source_when_collected(self) -> "GoldenDocument":
        """실제 수집 문서는 출처 없이 편입될 수 없다.

        출처 기록은 이용 조건 확인(공공누리 유형)과 재현성의 근거다. 편입 절차
        (docs/golden-collection-plan.md 3장)를 문서 하나가 건너뛰면 골든셋 전체의
        이용 근거가 흔들리므로 스키마에서 막는다.
        """
        if not self.synthetic and self.source is None:
            raise ValueError(f"실제 수집 문서에는 source가 필요합니다: {self.id}")
        return self

    def missing_facts(self, text: str) -> list[RequiredFact]:
        """변환문에 남지 않은 팩트 목록. 평가·벤치마크가 공유하는 유일한 판정 경로다."""
        return [fact for fact in self.required_facts if not fact.retained_in(text)]


def load_documents(directory: Path) -> list[GoldenDocument]:
    """디렉터리의 *.json을 모두 읽어 id 오름차순으로 돌려준다.

    id가 중복되면 평가 결과가 어느 문서 것인지 특정할 수 없으므로 ValueError.
    """
    documents: list[GoldenDocument] = []
    seen: set[str] = set()
    for path in sorted(directory.glob("*.json")):
        payload = json.loads(path.read_text(encoding="utf-8"))
        document = GoldenDocument.model_validate(payload)
        if document.id in seen:
            raise ValueError(f"골든셋 문서 id 중복: {document.id}")
        seen.add(document.id)
        documents.append(document)
    documents.sort(key=lambda document: document.id)
    return documents
