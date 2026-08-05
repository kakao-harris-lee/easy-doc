"""골든셋 로더 단위 테스트 — 실제 문서 내용이 아니라 로딩 규칙만 검증한다."""

import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.easyread.goldenset import GoldenDocument, load_documents


def _write(directory: Path, filename: str, **overrides: object) -> None:
    """골든셋 문서 JSON 한 건을 만든다(누락 필드는 기본값으로 채움)."""
    payload: dict[str, object] = {
        "id": "001",
        "title": "안내문",
        "category": "복지 안내문",
        "synthetic": True,
        "source_text": "본문",
        "required_facts": ["3월 2일"],
    }
    payload.update(overrides)
    (directory / filename).write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")


def test_문서를_id_순으로_로드한다(tmp_path: Path) -> None:
    """파일명 순서가 아니라 id 순서를 보장해야 리포트·테스트 출력이 안정적이다."""
    _write(tmp_path, "zzz.json", id="003")
    _write(tmp_path, "aaa.json", id="002")
    _write(tmp_path, "mmm.json", id="001")
    assert [document.id for document in load_documents(tmp_path)] == ["001", "002", "003"]


def test_모든_필드를_그대로_담는다(tmp_path: Path) -> None:
    _write(
        tmp_path,
        "001.json",
        title="기초연금 신청 안내",
        synthetic=False,
        required_facts=["3월 2일", "25만 원"],
    )
    document = load_documents(tmp_path)[0]
    assert document == GoldenDocument(
        id="001",
        title="기초연금 신청 안내",
        category="복지 안내문",
        synthetic=False,
        source_text="본문",
        required_facts=["3월 2일", "25만 원"],
    )


def test_id가_중복이면_ValueError(tmp_path: Path) -> None:
    _write(tmp_path, "001.json", id="001")
    _write(tmp_path, "002.json", id="001")
    with pytest.raises(ValueError, match="id 중복"):
        load_documents(tmp_path)


def test_필드가_빠지면_검증_실패(tmp_path: Path) -> None:
    (tmp_path / "001.json").write_text('{"id": "001"}', encoding="utf-8")
    with pytest.raises(ValidationError):
        load_documents(tmp_path)


def test_정의되지_않은_필드는_거부한다(tmp_path: Path) -> None:
    """오타 난 필드가 조용히 무시되면 골든셋 규칙이 헐거워진다."""
    _write(tmp_path, "001.json", requiredfacts=["오타"])
    with pytest.raises(ValidationError):
        load_documents(tmp_path)


def test_json이_없으면_빈_목록(tmp_path: Path) -> None:
    assert load_documents(tmp_path) == []
