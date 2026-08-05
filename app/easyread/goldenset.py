"""골든셋 문서 로더.

골든셋 평가 테스트(tests/golden)와 벤더 비교 벤치마크(scripts/benchmark.py)가
같은 로더를 공유한다 — 로딩 규칙을 각자 복제하면 평가 기준이 갈라진다.
문서 본문은 합성(synthetic) 샘플이지만, 실제 수집본으로 교체될 자리이므로
로더는 본문을 로그·예외 메시지에 남기지 않는다.
"""

import json
from pathlib import Path

from pydantic import BaseModel, ConfigDict


class GoldenDocument(BaseModel):
    """골든셋 평가용 문서 한 건.

    required_facts는 변환 후에도 반드시 남아야 할 리터럴이다. 마스킹 대상 패턴
    (전화·이메일·계좌 등)은 플레이스홀더로 치환되므로 절대 넣지 않는다
    (tests/golden/test_schema.py가 기계적으로 검증).
    """

    # extra="forbid": 손으로 쓰는 JSON이라 필드명 오타가 조용히 무시되면 규칙이 헐거워진다.
    model_config = ConfigDict(extra="forbid")

    id: str
    title: str
    category: str
    synthetic: bool
    source_text: str
    required_facts: list[str]


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
