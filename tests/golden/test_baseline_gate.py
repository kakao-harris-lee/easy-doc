"""기준선·하한선·필수 정보 보존 장치의 **재현 테스트** — LLM 호출 없음, 기본 스위트 포함.

각 기제마다 "이 장치가 없으면 무엇이 통과하는가"를 고정한다. 특히 코퍼스 지문은
**문서를 하나 빼고 돌려서** 통과하지 않는 것을 보인다 — 그것이 상대 기준의 가장 큰
함정이고, 문서 063·064가 이미 세 차례 제외 후보로 지목돼 있어 실제로 일어날 일이다.

차단 게이트가 LLM 없이 도는지도 여기서 함께 고정된다. 이 파일 전체가 `-m llm` 밖이라
매 CI 실행에서 돈다 — 게이트 로직이 외부 API에 묶이면 이 파일이 먼저 깨진다.
"""

import json
from pathlib import Path
from types import ModuleType, SimpleNamespace
from typing import Any

import pytest

from app.easyread.goldenset import GoldenDocument, find_fact_losses, load_documents
from tests.golden import DOCUMENTS_DIR
from tests.golden import report as golden_report
from tests.golden.baseline import (
    Baseline,
    Fingerprint,
    GroupMeasurement,
    Measurement,
    RunContext,
    Verdict,
    baseline_body,
    baseline_changes,
    compare,
    criteria_payload,
    load_baseline,
    stored_body,
    write_baseline,
)

DOCUMENTS: list[GoldenDocument] = load_documents(DOCUMENTS_DIR)

#: 실측으로 제외 후보에 세 차례 오른 문서 — 추출 아티팩트 의심
#: (`02_quality-baseline.md` §4.5). 재현 시나리오의 주인공이다.
EXCLUSION_CANDIDATE = "063"


def _context() -> RunContext:
    return RunContext(
        provider="fake",
        judge_provider="fake",
        model="fake-model",
        # 2026-08-12: 기록에는 **관측된** 모델과 effort 가 있어야 한다
        # (`write_baseline` fail-closed). 설정값만으로는 무엇이 응답했는지 모른다.
        observed_models=["fake-model"],
        effort=None,
    )


def _measurement(overall: tuple[int, int], synthetic: tuple[int, int]) -> Measurement:
    """(통과, 전체) 쌍으로 측정치를 만든다. 실수집은 나머지로 계산한다."""
    return Measurement(
        overall=GroupMeasurement(passed=overall[0], documents=overall[1]),
        synthetic=GroupMeasurement(passed=synthetic[0], documents=synthetic[1]),
        collected=GroupMeasurement(
            passed=overall[0] - synthetic[0], documents=overall[1] - synthetic[1]
        ),
    )


def _baseline(fingerprint: Fingerprint, measurement: Measurement) -> Baseline:
    return Baseline.model_validate(baseline_body(fingerprint, measurement, _context()))


def _document(
    document_id: str,
    *,
    synthetic: bool = True,
    text: str = "안내문 본문입니다.",
    facts: list[Any] | None = None,
) -> GoldenDocument:
    payload: dict[str, Any] = {
        "id": document_id,
        "title": f"문서 {document_id}",
        "category": "복지 안내문",
        "synthetic": synthetic,
        "source_text": text,
        "required_facts": facts if facts is not None else ["10만 원"],
    }
    if not synthetic:
        # 실수집 문서는 출처 없이 만들 수 없다(`GoldenDocument` 스키마 불변식).
        payload["source"] = {
            "organization": "○○구청",
            "license": "공공누리 제1유형",
            "collected_at": "2026-08-12",
        }
    return GoldenDocument.model_validate(payload)


# ═══════════════════════════════════════════════ A. 코퍼스 지문 — 문서를 빼는 우회


def test_문서를_하나_빼면_코퍼스_지문이_달라진다() -> None:
    """지문이 문서 집합에 반응하지 않으면 아래 재현이 전부 무의미하다 — 전제 확인."""
    full = Fingerprint.of(DOCUMENTS)
    reduced = Fingerprint.of([d for d in DOCUMENTS if d.id != EXCLUSION_CANDIDATE])
    assert full.corpus_sha256 != reduced.corpus_sha256
    assert full.document_count == reduced.document_count + 1
    # 판정 기준은 건드리지 않았으므로 그대로여야 한다 — 두 축이 실제로 분리돼 있는지 확인.
    assert full.criteria_sha256 == reduced.criteria_sha256


def test_문서를_빼서_통과율이_올라도_통과가_아니라_비교_불가다() -> None:
    """**이 파일의 핵심 재현.**

    문서 063을 빼면 통과율이 오른다(분모가 줄고 그 문서는 원래 실패했으므로). 지문이
    없으면 "직전보다 낮아지지 않았다"가 충족되어 **조용히 통과**한다. 지문이 있으면
    수치를 아예 읽지 않고 비교 불가로 떨어진다.
    """
    baseline = _baseline(Fingerprint.of(DOCUMENTS), _measurement((36, 56), (17, 20)))
    reduced_documents = [d for d in DOCUMENTS if d.id != EXCLUSION_CANDIDATE]
    # 063은 실수집이고 원래 실패한 문서로 둔다 — 빼면 실수집 분모만 1 줄어든다.
    current = _measurement((36, 55), (17, 20))

    # 전제: 지문을 보지 않으면 이 시나리오는 '개선'으로 보인다.
    assert current.overall.pass_rate > baseline.measurement.overall.pass_rate
    assert current.collected.pass_rate > baseline.measurement.collected.pass_rate

    judgement = compare(baseline, Fingerprint.of(reduced_documents), current)
    assert judgement.verdict is Verdict.INCOMPARABLE
    assert judgement.blocking is True
    assert judgement.requires_record is True
    assert EXCLUSION_CANDIDATE in judgement.summary()


def test_문서를_추가해도_비교_불가다() -> None:
    """편입도 같은 문제다 — 실측으로 25→36건 편입만으로 0.51→0.446이 됐다."""
    baseline = _baseline(Fingerprint.of(DOCUMENTS), _measurement((36, 56), (17, 20)))
    added = [*DOCUMENTS, _document("999", synthetic=False)]
    judgement = compare(baseline, Fingerprint.of(added), _measurement((36, 57), (17, 20)))
    assert judgement.verdict is Verdict.INCOMPARABLE
    assert "999" in judgement.summary()


def test_문서_본문이_바뀌면_비교_불가다() -> None:
    """id 집합이 같아도 본문이 바뀌면 난도가 바뀐다 — 수치를 비교할 수 없다."""
    original = [_document("001", text="원래 본문")]
    edited = [_document("001", text="더 쉬운 본문으로 갈아 끼웠다")]
    baseline = _baseline(Fingerprint.of(original), _measurement((0, 1), (0, 1)))
    judgement = compare(baseline, Fingerprint.of(edited), _measurement((1, 1), (1, 1)))
    assert judgement.verdict is Verdict.INCOMPARABLE
    assert "내용" in judgement.summary()


def test_required_facts가_바뀌면_비교_불가다() -> None:
    """팩트를 줄이면 통과율이 오른다 — 문서를 빼는 것과 같은 우회다."""
    before = [_document("001", facts=["10만 원", "3월 31일", "만 65세"])]
    after = [_document("001", facts=["10만 원"])]
    baseline = _baseline(Fingerprint.of(before), _measurement((0, 1), (0, 1)))
    judgement = compare(baseline, Fingerprint.of(after), _measurement((1, 1), (1, 1)))
    assert judgement.verdict is Verdict.INCOMPARABLE


def test_판정_기준이_바뀌면_비교_불가다(monkeypatch: pytest.MonkeyPatch) -> None:
    """자[尺]가 바뀌는 우회 — 문장 길이 상한을 풀면 통과율이 뛴다.

    실제 사건: 2026-08-09에 뜻풀이 축자 삽입 축(패턴 123종)이 신설되어 그 이전 수치와의
    비교가 깨졌다. 코퍼스만 지문으로 잡으면 이 경로가 열린 채 남는다.
    """
    import tests.golden.baseline as module

    loosened: ModuleType = SimpleNamespace(  # type: ignore[assignment]
        # 규칙 상수만 흉내 낸다 — criteria_payload는 대문자 비호출 전역만 걷는다.
        MAX_SENTENCE_CHARS=80,
        MAX_COMMAS_PER_SENTENCE=2,
    )
    original = criteria_payload()
    monkeypatch.setattr(module, "style_rules", loosened)
    assert criteria_payload() != original

    strict = [_document("001")]
    baseline_fingerprint = Fingerprint.of(strict)
    monkeypatch.setattr(
        module,
        "style_rules",
        SimpleNamespace(MAX_SENTENCE_CHARS=200, MAX_COMMAS_PER_SENTENCE=9),
    )
    judgement = compare(
        _baseline(baseline_fingerprint, _measurement((0, 1), (0, 1))),
        Fingerprint.of(strict),
        _measurement((1, 1), (1, 1)),
    )
    assert judgement.verdict is Verdict.INCOMPARABLE
    assert "판정 기준" in judgement.summary()


def test_채점에_쓰이지_않는_필드는_지문을_흔들지_않는다() -> None:
    """과민한 지문은 '항상 비교 불가'라는 반대 방향 고장이다 — 제목 오타 수정은 통과해야 한다."""
    before = Fingerprint.of([_document("001")])
    renamed = _document("001")
    after = Fingerprint.of([renamed.model_copy(update={"title": "제목 오타 수정"})])
    assert before.corpus_sha256 == after.corpus_sha256


def test_프롬프트_개선은_비교_불가를_만들지_않는다() -> None:
    """지문에 프롬프트를 넣지 않은 근거 — 넣으면 개선할 때마다 하한선이 리셋된다.

    지문은 코퍼스와 판정 기준만 본다. 둘 다 그대로면 같은 지문이고, 그래야 프롬프트를
    고쳐 오른 통과율이 '개선'으로 판정되어 하한선에 쌓인다.
    """
    fingerprint = Fingerprint.of(DOCUMENTS)
    baseline = _baseline(fingerprint, _measurement((36, 56), (17, 20)))
    judgement = compare(baseline, Fingerprint.of(DOCUMENTS), _measurement((40, 56), (18, 20)))
    assert judgement.verdict is Verdict.IMPROVED


# ═══════════════════════════════════════════════════════ C. 상대 하한선 판정


def test_기준선이_없으면_통과가_아니라_차단이다() -> None:
    """부재를 통과로 처리하면 첫 실행이 조용히 지나가고 하한선이 영영 서지 않는다."""
    judgement = compare(None, Fingerprint.of(DOCUMENTS), _measurement((0, 56), (0, 20)))
    assert judgement.verdict is Verdict.ABSENT
    assert judgement.blocking is True
    assert judgement.requires_record is True


def test_하락하면_차단한다() -> None:
    fingerprint = Fingerprint.of(DOCUMENTS)
    baseline = _baseline(fingerprint, _measurement((36, 56), (17, 20)))
    judgement = compare(baseline, fingerprint, _measurement((35, 56), (17, 20)))
    assert judgement.verdict is Verdict.REGRESSED
    assert judgement.blocking is True
    # 하락은 재기록으로 닫는 것이 아니다 — 기준선을 낮춰 통과시키는 경로를 열지 않는다.
    assert judgement.requires_record is False


def test_유지하면_통과한다() -> None:
    fingerprint = Fingerprint.of(DOCUMENTS)
    baseline = _baseline(fingerprint, _measurement((36, 56), (17, 20)))
    judgement = compare(baseline, fingerprint, _measurement((36, 56), (17, 20)))
    assert judgement.verdict is Verdict.HELD
    assert judgement.blocking is False
    assert judgement.requires_record is False


def test_개선은_차단하지_않되_재기록을_요구한다() -> None:
    """개선까지 차단하면 LLM 잡음으로 오르내리는 수치가 매 실행 게이트를 막아 게이트를 끄게 된다."""
    fingerprint = Fingerprint.of(DOCUMENTS)
    baseline = _baseline(fingerprint, _measurement((36, 56), (17, 20)))
    judgement = compare(baseline, fingerprint, _measurement((38, 56), (18, 20)))
    assert judgement.verdict is Verdict.IMPROVED
    assert judgement.blocking is False
    assert judgement.requires_record is True


def test_집단_하나만_하락해도_차단한다() -> None:
    """**집단을 나눠 비교하는 이유.** 합성이 오르고 실수집이 내리면 전체는 유지될 수 있다.

    전체만 보면 이 회귀는 보이지 않는다 — 실측으로 두 집단의 분포가 다르다는 것이
    확인된 이상(합성 스타일 위반 0/20 대 실수집 11/36) 합친 수치는 근거가 약하다.
    """
    fingerprint = Fingerprint.of(DOCUMENTS)
    baseline = _baseline(fingerprint, _measurement((36, 56), (17, 20)))
    current = _measurement((36, 56), (19, 20))  # 합성 +2, 실수집 -2, 전체 동일
    assert current.overall.passed == baseline.measurement.overall.passed
    judgement = compare(baseline, fingerprint, current)
    assert judgement.verdict is Verdict.REGRESSED
    assert judgement.blocking is True


# ═══════════════════════════════════════════════════ C. 기록 실행은 판정이 아니다


def test_첫_기록은_지적이_0건이어도_변경으로_센다(tmp_path: Path) -> None:
    """원장이 겪은 결함(X-12)의 재현 — **'지적 건수'는 '변경 여부'의 대리 지표가 아니다.**

    파일이 없고 관측에 이상이 없으면 지적은 0건이다. 그 0건을 '변경 없음'으로 읽으면
    기준선을 새로 만들고도 게이트가 닫힌다. 부재는 '변경 없음'이 아니라 '비교할 이전
    내용이 없음'이다.
    """
    path = tmp_path / "baseline.json"
    body = baseline_body(Fingerprint.of(DOCUMENTS), _measurement((36, 56), (17, 20)), _context())
    assert not path.exists()
    changes = baseline_changes(body, path)
    assert changes, "파일 부재를 변경 없음으로 처리하면 첫 기록이 조용히 통과한다"
    assert "없음 → 있음도 변경이다" in "\n".join(changes)


def test_같은_내용을_다시_기록하면_변경이_없다(tmp_path: Path) -> None:
    """기록 모드라도 바뀐 것이 없으면 정상 판정으로 떨어져야 한다.

    빈 diff를 리뷰에 올리지 않기 위해서이기도 하고, "바뀐 것이 없다"를 파일로도
    말할 수 있어야 하기 때문이기도 하다.
    """
    path = tmp_path / "baseline.json"
    body = baseline_body(Fingerprint.of(DOCUMENTS), _measurement((36, 56), (17, 20)), _context())
    write_baseline(body, path)
    assert baseline_changes(body, path) == []


def test_기록_시각은_변경_판정에_들어가지_않는다(tmp_path: Path) -> None:
    """**정반대 고장의 재현.** 시각을 비교 대상에 넣으면 매 실행 값이 달라 기록 실행이
    항상 차단되고, 그러면 기록 실행이 영원히 아무것도 닫지 못한다.
    """
    path = tmp_path / "baseline.json"
    body = baseline_body(Fingerprint.of(DOCUMENTS), _measurement((36, 56), (17, 20)), _context())
    write_baseline(body, path)
    first = json.loads(path.read_text(encoding="utf-8"))["recorded_at"]
    # 시각만 다른 파일을 만든다.
    path.write_text(
        json.dumps(
            {**first_payload(path), "recorded_at": "2000-01-01T00:00:00Z"}, ensure_ascii=False
        ),
        encoding="utf-8",
    )
    assert json.loads(path.read_text(encoding="utf-8"))["recorded_at"] != first
    assert baseline_changes(body, path) == []


def first_payload(path: Path) -> dict[str, Any]:
    loaded: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    return loaded


def test_수치가_달라지면_변경으로_센다(tmp_path: Path) -> None:
    path = tmp_path / "baseline.json"
    fingerprint = Fingerprint.of(DOCUMENTS)
    write_baseline(baseline_body(fingerprint, _measurement((36, 56), (17, 20)), _context()), path)
    changed = baseline_body(fingerprint, _measurement((38, 56), (18, 20)), _context())
    assert any("measurement" in line for line in baseline_changes(changed, path))


def test_judge_관측은_기준선에_실리지_않는다(tmp_path: Path) -> None:
    """**스키마 축소를 고정한다**(2026-08-13). judge 를 다시 넣으면 이 테스트가 깨진다.

    judge 는 비차단축이라 애초에 하한선의 구성요소가 아니다 — 채점 모델을 고정할 수단이
    없어 우리 코드를 고치지 않아도 값이 움직인다(`Baseline` docstring). 예전에는 기준선에
    실려 "이 수치가 어느 실행의 것인가"라는 출처 문제를 만들었고 그 자리에 검사가 여섯 번
    얹혔다. 관측 자체는 실행 리포트에 그대로 남는다 — 하한선에서 내렸을 뿐이다.
    """
    path = tmp_path / "baseline.json"
    fingerprint = Fingerprint.of(DOCUMENTS)
    body = baseline_body(fingerprint, _measurement((36, 56), (17, 20)), _context())
    assert "judge_observed" not in body
    # 필드 자체가 없다 — 인자를 지웠을 뿐 스키마에 남겨 두면 되살리기가 한 줄이다.
    assert "judge_observed" not in Baseline.model_fields
    write_baseline(body, path)
    written = stored_body(path)
    assert written is not None
    assert "judge_observed" not in written, "디스크에 쓰인 기준선에 judge 자리가 남아 있다"
    assert baseline_changes(body, path) == []


def test_깨진_기준선은_통과가_아니라_기준선_없음이다(tmp_path: Path) -> None:
    """읽을 수 없는 파일을 관대하게 넘기면 파일을 망가뜨리는 것이 게이트 우회가 된다."""
    path = tmp_path / "baseline.json"
    path.write_text("{ 이건 JSON이 아니다", encoding="utf-8")
    assert stored_body(path) is None
    assert load_baseline(path) is None
    judgement = compare(
        load_baseline(path), Fingerprint.of(DOCUMENTS), _measurement((56, 56), (20, 20))
    )
    assert judgement.verdict is Verdict.ABSENT
    assert judgement.blocking is True


def test_기록한_기준선을_그대로_다시_읽을_수_있다(tmp_path: Path) -> None:
    """왕복이 깨지면 기록 실행이 매번 '변경'으로 잡혀 게이트가 영영 닫히지 않는다."""
    path = tmp_path / "baseline.json"
    fingerprint = Fingerprint.of(DOCUMENTS)
    measurement = _measurement((36, 56), (17, 20))
    write_baseline(baseline_body(fingerprint, measurement, _context()), path)
    loaded = load_baseline(path)
    assert loaded is not None
    assert loaded.fingerprint == fingerprint
    assert loaded.measurement == measurement
    assert compare(loaded, fingerprint, measurement).verdict is Verdict.HELD


# ═══════════════════════════════════════════ E. 필수 정보 보존 — 절대 기준·결정적


def test_필수_정보가_빠지면_검출된다() -> None:
    document = _document("001", facts=["10만 원", "3월 31일", "만 65세"])
    losses = find_fact_losses({"001": (document, "10만 원을 3월 31일까지 신청하세요.")})
    assert [(loss.document_id, loss.missing, loss.required) for loss in losses] == [("001", 1, 3)]


def test_필수_정보가_남으면_검출되지_않는다() -> None:
    document = _document("001", facts=["10만 원", "3월 31일"])
    assert find_fact_losses({"001": (document, "10만 원을 3월 31일까지 내세요.")}) == []


def test_허용_변형도_보존으로_인정된다() -> None:
    """`accept`는 쉬운 글 변환에서 자연스럽게 나오는 동등 표기다 — 보존으로 본다."""
    document = _document("001", facts=[{"canonical": "만 65세", "accept": ["65세"]}])
    assert find_fact_losses({"001": (document, "65세부터 신청할 수 있어요.")}) == []


def test_보존_검사는_결정적이고_LLM을_쓰지_않는다() -> None:
    """절대 기준을 세울 수 있는 근거가 이것이다 — 같은 입력에 언제나 같은 답이 나온다."""
    document = _document("001", facts=["10만 원", "3월 31일"])
    pairs = {"001": (document, "3월 31일까지 신청하세요.")}
    first = find_fact_losses(pairs)
    assert first == find_fact_losses(pairs) == find_fact_losses(dict(pairs))
    assert [loss.missing for loss in first] == [1]


def test_보존_검사는_리터럴을_출력에_남기지_않는다() -> None:
    """실패 메시지·리포트에 그대로 실리는 값이라 본문·팩트 리터럴이 새면 안 된다."""
    secret = "특정한 팩트 리터럴 12345"
    document = _document("001", facts=[secret])
    losses = find_fact_losses({"001": (document, "아무 관련 없는 변환문")})
    assert secret not in json.dumps([loss.model_dump() for loss in losses], ensure_ascii=False)


def test_실제_코퍼스_전건에_보존_검사가_돈다() -> None:
    """56건 전부가 required_facts를 가지고 있어야 이 게이트가 설 근거가 있다.

    근거가 없는데 장치만 만드는 것이 가장 나쁜 실패다. 원문을 그대로 넣으면 팩트는 전부
    남아 있어야 한다(`test_schema.py`가 canonical의 원문 존재를 이미 강제한다).
    """
    assert all(document.required_facts for document in DOCUMENTS)
    unchanged = {document.id: (document, document.source_text) for document in DOCUMENTS}
    assert find_fact_losses(unchanged) == []


# ═══════════════════════════════════════════════════ B. 리포트는 항상 수치를 남긴다


def _report(measurement: Measurement) -> golden_report.GoldenRunReport:
    return golden_report.GoldenRunReport(
        fingerprint=Fingerprint.of(DOCUMENTS),
        context=_context(),
        targets=golden_report.Targets(
            pass_rate=0.9, judge_coverage=0.9, judge_score=4.0, fidelity_floor=2
        ),
        measurement=measurement,
    )


def test_리포트가_합성과_실수집을_나눠_싣는다() -> None:
    """합친 평균은 어느 집단도 대표하지 않는다 — 두 집단이 각각 보여야 한다."""
    rendered = _report(_measurement((36, 56), (17, 20))).render()
    assert "합성" in rendered and "실수집" in rendered
    assert "17/20" in rendered
    assert "19/36" in rendered


def test_리포트가_목표선_대비_현재를_함께_찍는다() -> None:
    """0.90은 차단에서 내려왔지만 목표로 남는다 — 리포트에서까지 지우면 위치를 볼 수 없다."""
    rendered = _report(_measurement((36, 56), (17, 20))).render()
    assert "목표 0.90" in rendered
    assert "-0.257" in rendered  # 36/56 = 0.643 → 목표 대비 -0.257


def test_통과하는_실행도_수치가_남는다() -> None:
    """**이 장치가 없으면 통과 실행은 아무 수치도 남기지 않는다**(assert 메시지뿐이었다)."""
    golden_report.reset()
    assert golden_report.latest() is None
    perfect = _measurement((56, 56), (20, 20))
    golden_report.record(_report(perfect))
    latest = golden_report.latest()
    assert latest is not None
    assert "56/56" in latest.render()
    golden_report.reset()


def test_리포트에_본문이_실리지_않는다() -> None:
    """문서 id·건수·점수만 싣는다. 렌더 결과에 원문 조각이 섞이면 로그 규칙 위반이다."""
    rendered = _report(_measurement((36, 56), (17, 20))).render()
    for document in DOCUMENTS[:5]:
        assert document.source_text[:40] not in rendered


# ═════════════════════════════════════ F. 실행 조건이 비면 기록하지 않는다 (fail-closed)
#
# 2026-08-12 첫 기록이 `context.model = null`로 남았다(`settings.llm_model` 미설정). 그 상태로
# 파일이 써져 **무엇으로 쟀는지 모르는 수치가 하한선이 될 뻔했다** — 게다가 그 값은 직전 저장
# 실행보다 9%p 낮았다. 무엇으로 쟀는지 모르는 수치가 하한선이 되면 그 하락이 정상이 된다.
# 아래 셋은 `write_baseline`의 가드를 붙잡는 음성 대조다 — 가드를 지우면 이 셋이 깨진다.


def test_관측_모델_없이는_기준선을_쓰지_않는다() -> None:
    """설정값만 있고 **관측값이 없으면** 기록을 거부한다.

    `settings.llm_model`은 주장이고 `LLMResponse.model`이 증거다. 별칭 해석·폴백이
    있으면 설정값과 실제로 응답한 모델이 갈린다.
    """
    with pytest.raises(AssertionError, match="관측된 모델이 없다"):
        write_baseline({"context": {"provider": "fake", "model": "설정값", "effort": None}})


def test_모델이_섞인_실행은_기준선이_되지_못한다() -> None:
    """한 실행에서 두 모델이 응답했으면 그 수치는 어느 모델의 하한선도 아니다."""
    with pytest.raises(AssertionError, match="모델이 섞였다"):
        write_baseline(
            {"context": {"provider": "fake", "observed_models": ["a", "b"], "effort": None}}
        )


def test_effort_는_키_자체가_있어야_한다() -> None:
    """값이 없으면 `null`로 **명시**한다 — 키의 부재와 값의 부재는 다르다.

    같은 모델이라도 effort가 결과를 크게 움직인다. 키가 없으면 그 실행이 어떤
    effort였는지 영영 알 수 없어 9%p 하락 같은 것의 원인을 가릴 수 없다.
    """
    with pytest.raises(AssertionError, match="effort"):
        write_baseline({"context": {"provider": "fake", "observed_models": ["a"]}})
