"""통과율의 **정의**와 판정 기준 지문의 **수집 범위**를 고정한다 — LLM 호출 없음.

2026-08-12 교차 리뷰가 실측으로 보인 두 구멍을 닫는 자리다. 둘 다 "게이트가 있는데 그
게이트를 무력화하는 편집이 스위트 916건을 전부 통과한다"는 같은 모양이었다.

- **Q-2 — 분모는 막아 놓고 분자의 정의가 열려 있다.** `evaluate_rules`의 세 조건(스타일·
  팩트·플레이스홀더)과 `evaluate_all`의 변환 실패 처리, `measure`의 분모를 **각각** 제거해도
  아무 테스트도 깨지지 않았다. 팩트 조건만 빼면 세 집단이 모두 올라(0.643→0.804)
  `IMPROVED`(비차단)로 판정되고 지문은 바이트 동일하다 — 재기록까지 마치면 바뀐 정의 위에
  새 하한선이 굳는다.
- **Q-1 — 판정 기준 지문이 자[尺]의 절반만 걷는다.** 지문이 대문자 상수 12개만 걷어,
  `_SENTENCE_SPLIT` 하나만 바꿔 56문서의 문장 길이 위반을 375→20건(-94.7%)으로 몰아도
  `criteria_sha256`가 바이트 동일했다.

여기서 고정하는 것은 "그 편집을 하면 무엇이 깨지는가"다. 각 테스트는 대응하는 편집 하나를
정확히 잡도록 썼다 — 하나가 여러 편집을 뭉뚱그려 잡으면 무엇이 무너졌는지 알 수 없다.
"""

import importlib.util
import json
import re
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

from app.easyread import style_rules
from app.easyread.goldenset import GoldenDocument
from app.services.conversion import ConversionOutcome
from tests.golden.baseline import criteria_payload, sha256_of
from tests.golden.evaluation import (
    DocumentEvaluation,
    evaluate_all,
    evaluate_rules,
    measure,
)

#: 판정 기준 지문이 걷어야 하는 모듈. 하나라도 빠지면 그 모듈의 판정이 조용히 바뀔 수 있다.
CRITERIA_MODULES = {
    "app.easyread.style_rules",
    "app.easyread.goldenset",
    "tests.golden.evaluation",
}


def _document(
    facts: list[str],
    *,
    document_id: str = "001",
    synthetic: bool = True,
    text: str = "10만 원",
) -> GoldenDocument:
    payload: dict[str, Any] = {
        "id": document_id,
        "title": f"문서 {document_id}",
        "category": "복지 안내문",
        "synthetic": synthetic,
        "source_text": text,
        "required_facts": facts,
    }
    if not synthetic:
        # 실수집 문서는 출처 없이 만들 수 없다(`GoldenDocument` 스키마 불변식).
        payload["source"] = {
            "organization": "○○구청",
            "license": "공공누리 제1유형",
            "collected_at": "2026-08-12",
        }
    return GoldenDocument.model_validate(payload)


def _outcome(text: str, missing_placeholders: list[str] | None = None) -> ConversionOutcome:
    return ConversionOutcome(
        easy_text=text,
        masked_items=[],
        missing_placeholders=missing_placeholders or [],
        model="fake",
        provider_name="fake",
    )


# ════════════════════════════════════════════ 통과율의 분자 — 조건 하나씩


def test_아무_조건도_걸리지_않으면_통과다() -> None:
    """전제 확인 — 아래 세 테스트가 "조건 하나만 걸린 입력"임을 보장한다."""
    document = _document(["10만 원"])
    assert evaluate_rules(document, _outcome("10만 원을 오늘까지 내세요.")) == []


def test_스타일_위반만_있어도_실패로_센다() -> None:
    document = _document(["10만 원"])
    failures = evaluate_rules(document, _outcome("10만 원을 금일까지 내세요."))
    assert len(failures) == 1
    assert failures[0].startswith("스타일위반")


def test_팩트_유실만_있어도_실패로_센다() -> None:
    """이 조건을 빼면 통과율이 0.643→0.804로 오르는데 지문은 그대로다(Q-2)."""
    document = _document(["10만 원"])
    assert evaluate_rules(document, _outcome("오늘까지 내세요.")) == ["팩트유실 1/1건"]


def test_플레이스홀더_유실만_있어도_실패로_센다() -> None:
    document = _document(["10만 원"])
    outcome = _outcome("10만 원을 오늘까지 내세요.", missing_placeholders=["[[PHONE_1]]"])
    assert evaluate_rules(document, outcome) == ["플레이스홀더유실 1건"]


def test_변환에_실패한_문서는_통과로_세지_않는다() -> None:
    """판정하지 못한 것을 통과로 세면 provider가 전건 실패한 실행이 만점으로 보인다."""
    document = _document(["10만 원"])
    result = evaluate_all({}, [document])
    assert result.conversion_failures == ["001"]
    assert result.evaluations[0].passed is False
    assert result.measurement.overall.passed == 0


def test_통과는_사유가_하나도_없을_때만이다() -> None:
    assert (
        DocumentEvaluation(document_id="001", synthetic=True, measured=True, failures=[]).passed
        is True
    )
    assert (
        DocumentEvaluation(document_id="001", synthetic=True, measured=True, failures=["x"]).passed
        is False
    )


def test_측정_여부는_통과_여부와_별개로_기록된다() -> None:
    """**실패는 측정이 아니다** — 통과 0과 미측정을 구분하는 자리가 여기다.

    변환에 실패한 문서도 실패로 세어지지만(`passed is False`), 그 0은 품질이 아니라 부재다.
    두 상태가 `failures` 문자열로만 구분되면 문구 한 글자에 구분이 묶인다.
    """
    failed = DocumentEvaluation(
        document_id="001", synthetic=True, measured=True, failures=["팩트유실 1/1건"]
    )
    unconverted = DocumentEvaluation(
        document_id="002", synthetic=True, measured=False, failures=["변환실패(LLMProviderError)"]
    )
    assert failed.passed is unconverted.passed is False
    assert (failed.measured, unconverted.measured) == (True, False)


def test_미측정_건수가_측정치에_함께_실린다() -> None:
    """**C-1 의 근원.** 수치와 "그 수치에 부재가 몇 건 섞였는가"가 한 객체에서 나온다.

    따로 두면(예: `conversion_failures`) 소비자가 그 값을 따로 구해야 하고, 따로 구할 수
    있는 값은 따로 잃을 수 있다 — 기준선 파일에 실리는 것은 `measurement` 뿐이라 실제로
    잃었다. 2026-08-13 실행은 실수집 36건을 모델에 보내지도 못했는데 기록 경로의 가드
    넷을 전부 통과했다.
    """
    evaluations = [
        DocumentEvaluation(document_id="001", synthetic=True, measured=True, failures=[]),
        DocumentEvaluation(
            document_id="002", synthetic=False, measured=False, failures=["변환실패(x)"]
        ),
    ]
    measurement = measure(evaluations)
    assert (measurement.overall.unmeasured, measurement.overall.documents) == (1, 2)
    assert measurement.synthetic.unmeasured == 0
    assert measurement.collected.unmeasured == 1
    assert measurement.unmeasured_gaps(), "미측정이 있는데 갈래가 비었다"
    # 전건 측정이면 비어야 한다 — 항상 채우면 정상 실행까지 막는다.
    assert measure(evaluations[:1]).unmeasured_gaps() == []


# ════════════════════════════════════════════ 통과율의 분모


def test_분모는_평가_전건이다() -> None:
    """실패한 문서를 분모에서 빼면 통과율은 실력과 무관하게 오른다 — 문서를 빼는 우회와 같다."""
    evaluations = [
        DocumentEvaluation(document_id="001", synthetic=True, measured=True, failures=[]),
        DocumentEvaluation(
            document_id="002", synthetic=True, measured=True, failures=["팩트유실 1/1건"]
        ),
        DocumentEvaluation(
            document_id="003", synthetic=False, measured=True, failures=["팩트유실 1/1건"]
        ),
    ]
    measurement = measure(evaluations)
    assert (measurement.overall.passed, measurement.overall.documents) == (1, 3)
    assert (measurement.synthetic.passed, measurement.synthetic.documents) == (1, 2)
    assert (measurement.collected.passed, measurement.collected.documents) == (0, 1)


def test_평가는_받은_문서_전건을_돈다() -> None:
    """`evaluate_all`이 코퍼스를 인자로 받는 이유 — 무엇을 셌는지가 호출자에게 보여야 한다."""
    documents = [
        _document(["10만 원"]),
        _document(["10만 원"], document_id="002", synthetic=False),
    ]
    result = evaluate_all({}, documents)
    assert [item.document_id for item in result.evaluations] == ["001", "002"]
    assert result.measurement.overall.documents == 2


# ════════════════════════════════════════════ 판정 기준 지문의 수집 범위 (Q-1)


def test_판정_기준_지문이_세_모듈을_모두_걷는다() -> None:
    """자[尺]는 style_rules 하나가 아니다 — 팩트 잔존 판정과 통과율 정의도 판정에 참여한다."""
    payload = criteria_payload()
    assert set(payload) == CRITERIA_MODULES
    for entry in payload.values():
        assert len(entry["source_sha256"]) == 64, "소스 축이 비었다 — 지문이 값만 걷고 있다"


def test_대문자가_아닌_규칙_전역도_지문에_들어온다(monkeypatch: pytest.MonkeyPatch) -> None:
    """codex 리뷰 #3의 재현 — `_SENTENCE_SPLIT` 교체만으로 문장 길이 판정이 통째로 바뀐다."""
    before = sha256_of(criteria_payload())
    monkeypatch.setattr(style_rules, "_SENTENCE_SPLIT", re.compile(r"\s+"))
    assert sha256_of(criteria_payload()) != before


def test_숫자_상수도_이름_규칙과_무관하게_들어온다(monkeypatch: pytest.MonkeyPatch) -> None:
    before = sha256_of(criteria_payload())
    monkeypatch.setattr(style_rules, "_JONGSEONG_COUNT", 99)
    assert sha256_of(criteria_payload()) != before


def _load(path: Path, source: str) -> ModuleType:
    """같은 이름·같은 경로로 다시 읽는다 — 이름이 달라져서 지문이 갈리는 것을 배제한다."""
    path.write_text(source, encoding="utf-8")
    spec = importlib.util.spec_from_file_location("golden_criteria_probe", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


_PROBE_ORIGINAL = '''"""판정 기준 흉내."""

MAX_SENTENCE_CHARS = 50


def check(text: str) -> bool:
    """길이 검사."""
    return len(text) <= MAX_SENTENCE_CHARS
'''

_PROBE_LOOSENED = '''"""판정 기준 흉내."""

MAX_SENTENCE_CHARS = 50


def check(text: str) -> bool:
    """길이 검사."""
    return len(text) <= MAX_SENTENCE_CHARS * 2
'''

_PROBE_REWORDED = '''"""판정 기준 흉내 — 설명을 고쳐 썼다."""

MAX_SENTENCE_CHARS = 50


def check(text: str) -> bool:
    """길이 검사. 근거: 국립국어원 지침."""
    # 주석도 붙였다.
    return len(text) <= MAX_SENTENCE_CHARS
'''


def _probe_entry(path: Path, source: str) -> dict[str, Any]:
    payload = criteria_payload(_load(path, source))
    entry: dict[str, Any] = payload["golden_criteria_probe"]
    return entry


def test_판정_함수의_본문이_바뀌면_지문이_움직인다(tmp_path: Path) -> None:
    """**상수를 건드리지 않는 완화**가 정확히 이 모양이다 — 값 축은 그대로고 코드만 바뀐다."""
    path = tmp_path / "probe.py"
    original = _probe_entry(path, _PROBE_ORIGINAL)
    loosened = _probe_entry(path, _PROBE_LOOSENED)
    assert original["values"] == loosened["values"], "전제 실패 — 값 축이 함께 움직였다"
    assert original["source_sha256"] != loosened["source_sha256"]


def test_주석과_docstring만_고치면_지문이_그대로다(tmp_path: Path) -> None:
    """과민한 지문은 '항상 비교 불가'라는 반대 방향 고장이다.

    이 저장소의 판정 코드는 근거를 긴 docstring으로 남기는 규약이라, 문서를 고칠 때마다
    기준선을 다시 기록해야 하면 게이트를 끄게 된다.
    """
    path = tmp_path / "probe.py"
    original = _probe_entry(path, _PROBE_ORIGINAL)
    reworded = _probe_entry(path, _PROBE_REWORDED)
    assert original["source_sha256"] == reworded["source_sha256"]


def test_지문_값에_메모리_주소가_섞이지_않는다() -> None:
    """`_canonical`의 `repr` 낙하는 지문을 파이썬 구현 세부·실행마다 달라지는 값에 묶는다."""
    dumped = json.dumps(criteria_payload(), ensure_ascii=False)
    assert " object at 0x" not in dumped
