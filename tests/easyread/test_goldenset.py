"""골든셋 로더 단위 테스트 — 실제 문서 내용이 아니라 로딩·팩트 판정 규칙만 검증한다."""

import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.easyread.goldenset import GoldenDocument, GoldenSource, RequiredFact, load_documents


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
    """synthetic=false 문서라 출처(source)도 함께 왕복해야 한다."""
    _write(
        tmp_path,
        "001.json",
        title="기초연금 신청 안내",
        synthetic=False,
        required_facts=["3월 2일", "25만 원"],
        source={
            "url": "https://example.go.kr/notice/1",
            "organization": "○○구청",
            "license": "공공누리 제1유형",
            "collected_at": "2026-08-06",
        },
    )
    document = load_documents(tmp_path)[0]
    assert document == GoldenDocument(
        id="001",
        title="기초연금 신청 안내",
        category="복지 안내문",
        synthetic=False,
        source_text="본문",
        required_facts=[RequiredFact(canonical="3월 2일"), RequiredFact(canonical="25만 원")],
        source=GoldenSource(
            url="https://example.go.kr/notice/1",
            organization="○○구청",
            license="공공누리 제1유형",
            collected_at="2026-08-06",
        ),
    )


def test_문자열_팩트는_canonical로_승격된다(tmp_path: Path) -> None:
    """변형이 필요 없는 팩트는 JSON에 문자열로만 적을 수 있어야 한다."""
    _write(tmp_path, "001.json", required_facts=["주민센터"])
    fact = load_documents(tmp_path)[0].required_facts[0]
    assert (fact.canonical, fact.accept) == ("주민센터", [])


def test_객체_팩트는_허용_변형을_담는다(tmp_path: Path) -> None:
    _write(
        tmp_path,
        "001.json",
        required_facts=[{"canonical": "만 65세", "accept": ["65세"]}, "주민센터"],
    )
    facts = load_documents(tmp_path)[0].required_facts
    assert facts[0] == RequiredFact(canonical="만 65세", accept=["65세"])
    assert facts[1] == RequiredFact(canonical="주민센터")


def test_팩트_잔존_판정은_허용_변형을_인정한다() -> None:
    """ "만 65세"를 "65세"로 쓰는 것은 정보 손실이 아니므로 보존으로 본다."""
    fact = RequiredFact(canonical="만 65세", accept=["65세"])
    assert fact.retained_in("65세 이상 어르신이 받습니다.")
    assert fact.retained_in("만 65세 이상 어르신이 받습니다.")
    assert not fact.retained_in("어르신이 받습니다.")


def test_허용_변형이_없으면_canonical만_본다() -> None:
    fact = RequiredFact(canonical="25만 원")
    assert fact.retained_in("25만 원을 받습니다.")
    assert not fact.retained_in("25만원을 받습니다.")


def test_남지_않은_팩트만_돌려준다(tmp_path: Path) -> None:
    """평가 테스트와 벤치마크가 공유하는 유일한 판정 경로다."""
    _write(
        tmp_path,
        "001.json",
        required_facts=[{"canonical": "30퍼센트", "accept": ["30%"]}, "주민센터", "25만 원"],
    )
    document = load_documents(tmp_path)[0]
    missing = document.missing_facts("30%를 주민센터에서 확인하세요.")
    assert [fact.canonical for fact in missing] == ["25만 원"]


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


def test_팩트에_정의되지_않은_필드도_거부한다(tmp_path: Path) -> None:
    _write(tmp_path, "001.json", required_facts=[{"canonical": "25만 원", "허용": ["25만원"]}])
    with pytest.raises(ValidationError):
        load_documents(tmp_path)


def test_json이_없으면_빈_목록(tmp_path: Path) -> None:
    assert load_documents(tmp_path) == []
