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
from pydantic import ValidationError

from app.easyread.goldenset import GoldenDocument, find_fact_losses, load_documents
from tests.golden import DOCUMENTS_DIR
from tests.golden import report as golden_report
from tests.golden.baseline import (
    OVERALL_FLOOR_TOLERANCE,
    TOLERANCE_BLIND_SPOTS,
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
from tests.golden.evaluation import RuleEvaluation

DOCUMENTS: list[GoldenDocument] = load_documents(DOCUMENTS_DIR)

#: 실측으로 제외 후보에 세 차례 오른 문서 — 추출 아티팩트 의심
#: (`02_quality-baseline.md` §4.5). 재현 시나리오의 주인공이다.
EXCLUSION_CANDIDATE = "063"


def _context(
    *,
    provider: str = "fake",
    observed_models: list[str] | None = None,
    effort: str | None = None,
) -> RunContext:
    """비교의 조건 하나.

    **세 인자가 producer 축(지문)의 원재료다**(2026-08-13). 나머지(`judge_provider`·설정값
    `model`)는 기록만 되므로 고정해 둔다 — 그 둘로 지문이 흔들리지 않는 것도 아래에서 본다.
    """
    return RunContext(
        provider=provider,
        judge_provider="fake",
        model="fake-model",
        # 2026-08-12: 기록에는 **관측된** 모델과 effort 가 있어야 한다
        # (`write_baseline` fail-closed). 설정값만으로는 무엇이 응답했는지 모른다.
        observed_models=["fake-model"] if observed_models is None else observed_models,
        effort=effort,
    )


def _measurement(
    overall: tuple[int, int],
    synthetic: tuple[int, int],
    unmeasured: tuple[int, int] = (0, 0),
) -> Measurement:
    """(통과, 전체) 쌍으로 측정치를 만든다. 실수집은 나머지로 계산한다.

    `unmeasured` 는 (전체, 합성)의 **미측정 건수**이고 기본은 전건 측정(0, 0)이다. 기본을
    0으로 둔 것은 이 파일의 시나리오 대부분이 "정상적으로 잰 수치끼리의 판정"이기 때문이고,
    미측정이 섞인 입력은 그것을 확인하는 테스트가 **명시적으로** 넘긴다 — 기본값에 숨어
    있으면 무엇을 확인한 것인지 말할 수 없다.
    """
    return Measurement(
        overall=GroupMeasurement(passed=overall[0], documents=overall[1], unmeasured=unmeasured[0]),
        synthetic=GroupMeasurement(
            passed=synthetic[0], documents=synthetic[1], unmeasured=unmeasured[1]
        ),
        collected=GroupMeasurement(
            passed=overall[0] - synthetic[0],
            documents=overall[1] - synthetic[1],
            unmeasured=unmeasured[0] - unmeasured[1],
        ),
    )


def _baseline(
    fingerprint: Fingerprint, measurement: Measurement, context: RunContext | None = None
) -> Baseline:
    """기준선 하나. `context`를 지문과 **같은 것으로** 넘길 수 있어야 한다 —
    producer 축이 생긴 뒤로 둘이 갈린 기준선은 자기모순이라 시험 입력으로도 부적절하다.
    """
    return Baseline.model_validate(
        baseline_body(fingerprint, measurement, context if context is not None else _context())
    )


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
    full = Fingerprint.of(DOCUMENTS, _context())
    reduced = Fingerprint.of([d for d in DOCUMENTS if d.id != EXCLUSION_CANDIDATE], _context())
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
    baseline = _baseline(Fingerprint.of(DOCUMENTS, _context()), _measurement((36, 56), (17, 20)))
    reduced_documents = [d for d in DOCUMENTS if d.id != EXCLUSION_CANDIDATE]
    # 063은 실수집이고 원래 실패한 문서로 둔다 — 빼면 실수집 분모만 1 줄어든다.
    current = _measurement((36, 55), (17, 20))

    # 전제: 지문을 보지 않으면 이 시나리오는 '개선'으로 보인다.
    assert current.overall.pass_rate > baseline.measurement.overall.pass_rate
    assert current.collected.pass_rate > baseline.measurement.collected.pass_rate

    judgement = compare(baseline, Fingerprint.of(reduced_documents, _context()), current)
    assert judgement.verdict is Verdict.INCOMPARABLE
    assert judgement.blocking is True
    assert judgement.requires_record is True
    assert EXCLUSION_CANDIDATE in judgement.summary()


def test_문서를_추가해도_비교_불가다() -> None:
    """편입도 같은 문제다 — 실측으로 25→36건 편입만으로 0.51→0.446이 됐다."""
    baseline = _baseline(Fingerprint.of(DOCUMENTS, _context()), _measurement((36, 56), (17, 20)))
    added = [*DOCUMENTS, _document("999", synthetic=False)]
    judgement = compare(
        baseline, Fingerprint.of(added, _context()), _measurement((36, 57), (17, 20))
    )
    assert judgement.verdict is Verdict.INCOMPARABLE
    assert "999" in judgement.summary()


def test_문서_본문이_바뀌면_비교_불가다() -> None:
    """id 집합이 같아도 본문이 바뀌면 난도가 바뀐다 — 수치를 비교할 수 없다."""
    original = [_document("001", text="원래 본문")]
    edited = [_document("001", text="더 쉬운 본문으로 갈아 끼웠다")]
    baseline = _baseline(Fingerprint.of(original, _context()), _measurement((0, 1), (0, 1)))
    judgement = compare(baseline, Fingerprint.of(edited, _context()), _measurement((1, 1), (1, 1)))
    assert judgement.verdict is Verdict.INCOMPARABLE
    assert "내용" in judgement.summary()


def test_required_facts가_바뀌면_비교_불가다() -> None:
    """팩트를 줄이면 통과율이 오른다 — 문서를 빼는 것과 같은 우회다."""
    before = [_document("001", facts=["10만 원", "3월 31일", "만 65세"])]
    after = [_document("001", facts=["10만 원"])]
    baseline = _baseline(Fingerprint.of(before, _context()), _measurement((0, 1), (0, 1)))
    judgement = compare(baseline, Fingerprint.of(after, _context()), _measurement((1, 1), (1, 1)))
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
    baseline_fingerprint = Fingerprint.of(strict, _context())
    monkeypatch.setattr(
        module,
        "style_rules",
        SimpleNamespace(MAX_SENTENCE_CHARS=200, MAX_COMMAS_PER_SENTENCE=9),
    )
    judgement = compare(
        _baseline(baseline_fingerprint, _measurement((0, 1), (0, 1))),
        Fingerprint.of(strict, _context()),
        _measurement((1, 1), (1, 1)),
    )
    assert judgement.verdict is Verdict.INCOMPARABLE
    assert "판정 기준" in judgement.summary()


def test_채점에_쓰이지_않는_필드는_지문을_흔들지_않는다() -> None:
    """과민한 지문은 '항상 비교 불가'라는 반대 방향 고장이다 — 제목 오타 수정은 통과해야 한다."""
    before = Fingerprint.of([_document("001")], _context())
    renamed = _document("001")
    after = Fingerprint.of([renamed.model_copy(update={"title": "제목 오타 수정"})], _context())
    assert before.corpus_sha256 == after.corpus_sha256


def test_프롬프트_개선은_비교_불가를_만들지_않는다() -> None:
    """지문에 프롬프트를 넣지 않은 근거 — 넣으면 개선할 때마다 하한선이 리셋된다.

    지문은 코퍼스와 판정 기준만 본다. 둘 다 그대로면 같은 지문이고, 그래야 프롬프트를
    고쳐 오른 통과율이 '개선'으로 판정되어 하한선에 쌓인다.
    """
    fingerprint = Fingerprint.of(DOCUMENTS, _context())
    baseline = _baseline(fingerprint, _measurement((36, 56), (17, 20)))
    judgement = compare(
        baseline, Fingerprint.of(DOCUMENTS, _context()), _measurement((40, 56), (18, 20))
    )
    assert judgement.verdict is Verdict.IMPROVED


# ═══════════════════════════════════════════════════════ C. 상대 하한선 판정


def test_기준선이_없으면_통과가_아니라_차단이다() -> None:
    """부재를 통과로 처리하면 첫 실행이 조용히 지나가고 하한선이 영영 서지 않는다."""
    judgement = compare(None, Fingerprint.of(DOCUMENTS, _context()), _measurement((0, 56), (0, 20)))
    assert judgement.verdict is Verdict.ABSENT
    assert judgement.blocking is True
    assert judgement.requires_record is True


def test_허용_폭을_넘겨_하락하면_차단한다() -> None:
    """폭을 **넘긴** 하락만 차단이다(2026-08-13 완화 이후). 폭 안쪽은 아래 두 테스트가 잡는다."""
    fingerprint = Fingerprint.of(DOCUMENTS, _context())
    baseline = _baseline(fingerprint, _measurement((36, 56), (17, 20)))
    넘긴_수치 = 36 - OVERALL_FLOOR_TOLERANCE - 1
    judgement = compare(baseline, fingerprint, _measurement((넘긴_수치, 56), (17, 20)))
    assert judgement.verdict is Verdict.REGRESSED
    assert judgement.blocking is True
    # 하락은 재기록으로 닫는 것이 아니다 — 기준선을 낮춰 통과시키는 경로를 열지 않는다.
    assert judgement.requires_record is False


def test_허용_폭은_5회_실측_표에서_나온_값이다() -> None:
    """**값 자체를 못박는다.** 아래 경계 테스트들은 상수를 기호로 쓰므로 폭을 2→5로 올려도
    함께 따라 움직여 조용히 통과한다 — 게이트를 넓히는 편집이 무검출이라는 뜻이다.
    `EXPECTED_*` 상수들과 같은 이유로 여기서만 **리터럴**로 고정한다(`tests/
    test_harness_scope_reach.py`의 `EXPECTED_ROWS`가 하한에서 정확 일치로 바뀐 것과 같은 자리).

    브리틀한 것이 아니라 **마찰이 의도된 자리**다. 이 줄과 상수를 함께 고치는 diff가
    "게이트를 이만큼 열었다"는 신호로 리뷰에 올라가는 것이 이 테스트의 값어치다.
    """
    assert OVERALL_FLOOR_TOLERANCE == 2, (
        "허용 폭이 바뀌었다. 근거는 2026-08-13 5회 실측(같은 커밋·코퍼스·판정 기준·producer)의 "
        "**전체 축 관측 폭 2**다 — 표본 33·31·32·33·33. 올리려면 새 표본을 실측해 "
        "`OVERALL_FLOOR_TOLERANCE`의 표를 교체하고 그 실행 조건을 함께 적어라. "
        "표 없이 숫자만 올리는 diff는 근거가 없다"
    )


def test_허용_폭_경계는_정확히_그_건수까지다() -> None:
    """**경계를 양쪽에서 고정한다** — 폭이 조용히 넓어지거나 좁아지면 여기서 깨진다.

    사용자 결정(2026-08-13)의 문장 그대로다: 기준선 33이면 **31 미만에서 차단**하고
    31·32·33은 통과한다. 실측 근거는 `OVERALL_FLOOR_TOLERANCE`의 5회 표본이다.
    """
    fingerprint = Fingerprint.of(DOCUMENTS, _context())
    baseline = _baseline(fingerprint, _measurement((33, 56), (16, 20)))

    딱_폭만큼 = compare(
        baseline, fingerprint, _measurement((33 - OVERALL_FLOOR_TOLERANCE, 56), (16, 20))
    )
    assert 딱_폭만큼.blocking is False, "허용 폭 경계값이 차단됐다 — 폭이 좁아졌다"
    assert 딱_폭만큼.verdict is Verdict.TOLERATED

    한_건_더 = compare(
        baseline, fingerprint, _measurement((33 - OVERALL_FLOOR_TOLERANCE - 1, 56), (16, 20))
    )
    assert 한_건_더.blocking is True, "허용 폭을 넘긴 하락이 통과했다 — 폭이 넓어졌다"
    assert 한_건_더.verdict is Verdict.REGRESSED


def test_허용_폭_안의_하락은_기준선을_갱신하지_않는다() -> None:
    """**사다리를 만들지 않는다.** 허용 폭 안의 하락을 기록하면 그 수치가 다음 하한선이 되고,
    다음 실행이 다시 폭만큼 내려가도 통과한다 — 폭 2씩 무한히 내려가는 경로가 열린다.
    """
    fingerprint = Fingerprint.of(DOCUMENTS, _context())
    baseline = _baseline(fingerprint, _measurement((33, 56), (16, 20)))
    judgement = compare(baseline, fingerprint, _measurement((31, 56), (16, 20)))
    assert judgement.blocking is False
    assert judgement.requires_record is False, "허용 폭 안의 하락이 기준선을 끌어내린다"


def test_유지하면_통과한다() -> None:
    fingerprint = Fingerprint.of(DOCUMENTS, _context())
    baseline = _baseline(fingerprint, _measurement((36, 56), (17, 20)))
    judgement = compare(baseline, fingerprint, _measurement((36, 56), (17, 20)))
    assert judgement.verdict is Verdict.HELD
    assert judgement.blocking is False
    assert judgement.requires_record is False


def test_개선은_차단하지_않되_재기록을_요구한다() -> None:
    """개선까지 차단하면 LLM 잡음으로 오르내리는 수치가 매 실행 게이트를 막아 게이트를 끄게 된다."""
    fingerprint = Fingerprint.of(DOCUMENTS, _context())
    baseline = _baseline(fingerprint, _measurement((36, 56), (17, 20)))
    judgement = compare(baseline, fingerprint, _measurement((38, 56), (18, 20)))
    assert judgement.verdict is Verdict.IMPROVED
    assert judgement.blocking is False
    assert judgement.requires_record is True


def test_하위_축만_하락하면_차단하지_않고_경고만_남긴다() -> None:
    """**이 게이트가 못 잡게 된 것 — 2026-08-13 완화의 값을 여기 고정한다.**

    2026-08-13 이전에는 이 입력이 차단이었다(`test_집단_하나만_하락해도_차단한다`).
    지금은 통과한다. 바꾼 근거는 같은 날 5회 실측이다 — 폭이 전체 2 < 합성 3 < 실수집 4로
    **하위 축이 전체보다 더 흔들려**, 코드 무변경 4회 중 3회가 하위 축 때문에 차단됐다.
    run 4가 정확히 이 형태였다: 합성 14(최저)·실수집 19(최고)·전체 33(기준선과 동일).

    **잃은 것을 적어 둔다.** 합성이 20/20 → 14/20으로 무너져도 실수집이 그만큼 오르면
    이 게이트는 막지 않는다. 남은 방어는 경고 한 줄뿐이고, 그 줄을 읽는 것은 사람이다 —
    그래서 아래에서 경고가 실제로 나오는지까지 확인한다. 차단하지 않는 것과 안 보이게
    하는 것은 다르다.
    """
    fingerprint = Fingerprint.of(DOCUMENTS, _context())
    baseline = _baseline(fingerprint, _measurement((36, 56), (17, 20)))
    current = _measurement((36, 56), (19, 20))  # 합성 +2, 실수집 -2, 전체 동일
    assert current.overall.passed == baseline.measurement.overall.passed
    judgement = compare(baseline, fingerprint, current)
    assert judgement.verdict is Verdict.TOLERATED
    assert judgement.blocking is False
    assert judgement.requires_record is False

    summary = judgement.summary()
    assert "실수집: 기준선 19/36" in summary, "차단하지 않는다고 델타까지 지우면 사람이 볼 수 없다"
    assert "⚠" in summary, "경고 표시가 없으면 통과 로그에 묻힌다"
    assert "차단하지 않았다" in summary


def test_통과한_실행의_리포트에도_하위_축_경고가_남는다() -> None:
    """**차단하지 않는 것과 안 보이게 하는 것은 다르다.**

    하위 축 하락이 비차단이 된 이상, 그 사실이 사람 눈에 닿는 경로는 리포트 렌더 하나뿐이다.
    `FloorJudgement`에만 담기고 렌더에 실리지 않으면 완화는 **무성(無聲)**이 된다.
    """
    fingerprint = Fingerprint.of(DOCUMENTS, _context())
    baseline = _baseline(fingerprint, _measurement((36, 56), (17, 20)))
    current = _measurement((36, 56), (19, 20))  # run 4 형태
    report = _report(current)
    report.floor = compare(baseline, fingerprint, current)

    # **통과한 실행이라는 것을 먼저 못박는다.** 이 줄이 없으면 차단된 실행의 리포트에도
    # 같은 델타와 ⚠가 찍히므로, 아래 확인이 "통과 실행에서도 보인다"를 뜻하지 못한다
    # (실측: 차단축을 되돌린 변이에서 이 테스트만 조용히 통과했다).
    assert report.floor.blocking is False
    rendered = report.render()
    assert "── 상대 하한선 ──" in rendered
    assert "실수집: 기준선 19/36" in rendered, "통과 실행의 리포트에서 하위 축 델타가 사라졌다"
    assert "⚠" in rendered


def test_완화가_무엇을_못_잡는지_차단과_통과_양쪽_메시지에_적힌다() -> None:
    """**게이트의 사각은 막힌 사람보다 통과한 사람이 알아야 한다.**

    완화를 코드에만 적고 출력에 적지 않으면 읽는 사람은 초록불을 "회귀가 없다"로 읽는다.
    실제 뜻은 "차단축이 허용 폭 안이다"이고 그 둘은 다르다. 이 테스트가 없으면 다음 편집이
    메시지에서 이 문장들을 지워도 아무것도 깨지지 않는다.
    """
    fingerprint = Fingerprint.of(DOCUMENTS, _context())
    baseline = _baseline(fingerprint, _measurement((36, 56), (17, 20)))
    차단 = compare(baseline, fingerprint, _measurement((20, 56), (10, 20)))
    통과 = compare(baseline, fingerprint, _measurement((36, 56), (19, 20)))
    assert 차단.blocking is True and 통과.blocking is False

    for judgement in (차단, 통과):
        summary = judgement.summary()
        for note in TOLERANCE_BLIND_SPOTS:
            assert note in summary, f"완화 고지가 빠졌다 — {judgement.verdict.value}"
        # 셋 중 가장 잃기 쉬운 것 둘을 문구로도 못박는다.
        assert "하위 축에만 갇힌 회귀는 차단하지 않는다" in summary
        assert "분포 추정이 아니다" in summary


def test_허용_폭_상수가_판정식에_실제로_쓰인다() -> None:
    """**상수와 판정식이 갈리지 않는지 본다.** 상수를 고쳐도 판정이 그대로면 그 상수는 장식이고,
    "이 상수의 diff가 리뷰에 올라가는 것이 값어치"라는 근거가 통째로 무너진다.

    비교식(`dropped_beyond`)을 직접 두드린다 — `tolerance=0`이 완화 이전 판정식
    (`dropped_from`)과 정확히 같아야 하고, 폭을 주면 그만큼만 관대해져야 한다.
    """
    기준선 = GroupMeasurement(passed=33, documents=56, unmeasured=0)
    for 통과 in range(28, 34):
        현재 = GroupMeasurement(passed=통과, documents=56, unmeasured=0)
        assert 현재.dropped_beyond(기준선, 0) is 현재.dropped_from(기준선), (
            "허용 폭 0이 완화 이전 판정식과 다르다 — 되돌릴 수 없는 완화다"
        )
        assert 현재.dropped_beyond(기준선, OVERALL_FLOOR_TOLERANCE) is (
            통과 < 33 - OVERALL_FLOOR_TOLERANCE
        )


# ═══════════════════════════════════════════════════ C. 기록 실행은 판정이 아니다


def test_첫_기록은_지적이_0건이어도_변경으로_센다(tmp_path: Path) -> None:
    """원장이 겪은 결함(X-12)의 재현 — **'지적 건수'는 '변경 여부'의 대리 지표가 아니다.**

    파일이 없고 관측에 이상이 없으면 지적은 0건이다. 그 0건을 '변경 없음'으로 읽으면
    기준선을 새로 만들고도 게이트가 닫힌다. 부재는 '변경 없음'이 아니라 '비교할 이전
    내용이 없음'이다.
    """
    path = tmp_path / "baseline.json"
    body = baseline_body(
        Fingerprint.of(DOCUMENTS, _context()), _measurement((36, 56), (17, 20)), _context()
    )
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
    body = baseline_body(
        Fingerprint.of(DOCUMENTS, _context()), _measurement((36, 56), (17, 20)), _context()
    )
    write_baseline(body, path)
    assert baseline_changes(body, path) == []


def test_기록_시각은_변경_판정에_들어가지_않는다(tmp_path: Path) -> None:
    """**정반대 고장의 재현.** 시각을 비교 대상에 넣으면 매 실행 값이 달라 기록 실행이
    항상 차단되고, 그러면 기록 실행이 영원히 아무것도 닫지 못한다.
    """
    path = tmp_path / "baseline.json"
    body = baseline_body(
        Fingerprint.of(DOCUMENTS, _context()), _measurement((36, 56), (17, 20)), _context()
    )
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
    fingerprint = Fingerprint.of(DOCUMENTS, _context())
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
    fingerprint = Fingerprint.of(DOCUMENTS, _context())
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
        load_baseline(path), Fingerprint.of(DOCUMENTS, _context()), _measurement((56, 56), (20, 20))
    )
    assert judgement.verdict is Verdict.ABSENT
    assert judgement.blocking is True


def test_기록한_기준선을_그대로_다시_읽을_수_있다(tmp_path: Path) -> None:
    """왕복이 깨지면 기록 실행이 매번 '변경'으로 잡혀 게이트가 영영 닫히지 않는다."""
    path = tmp_path / "baseline.json"
    fingerprint = Fingerprint.of(DOCUMENTS, _context())
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


def _evaluation(measurement: Measurement) -> RuleEvaluation:
    """리포트를 결속할 평가 하나. 여기서는 **키로만** 쓰이므로 최소 구성으로 만든다.

    `outcomes` 도 `documents` 도 필수 필드다(기본값을 주지 않는다) — 무엇을 평가했는지·
    무엇을 쟀는지 말할 수 없는 평가를 만들 수 있으면, judge 가 대상을 다른 데서 구하고
    지문이 분모를 다른 데서 구하던 통로가 되살아난다. 여기서 빈 값을 명시하는 것은 이
    객체가 **게이트에 들어가지 않고 등록부의 키로만** 쓰이기 때문이다.
    """
    return RuleEvaluation(
        documents=[],
        outcomes={},
        evaluations=[],
        measurement=measurement,
        failure_reasons={},
        conversion_failures=[],
        fact_losses=[],
        observed_models=["fake-model"],
    )


def _report(measurement: Measurement) -> golden_report.GoldenRunReport:
    return golden_report.GoldenRunReport(
        fingerprint=Fingerprint.of(DOCUMENTS, _context()),
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
    evaluation = _evaluation(perfect)
    recorded = golden_report.record(_report(perfect), evaluation)
    latest = golden_report.latest()
    assert latest is not None
    assert "56/56" in latest.render()
    # 등록은 **평가에 결속된다** — 같은 평가로 조회하면 같은 리포트가 나온다.
    assert golden_report.for_evaluation(evaluation) is recorded
    # 다른 평가로 조회하면 없다. 수치가 같아도(값으로는 구분되지 않아도) 잡히지 않는다.
    other = _evaluation(perfect)
    assert other.measurement == evaluation.measurement
    assert golden_report.for_evaluation(other) is None
    golden_report.reset()
    assert golden_report.for_evaluation(evaluation) is None, "reset 이 등록부를 비우지 않았다"


def test_지문과_context가_갈린_리포트는_만들어지지_않는다() -> None:
    """**기준선과 같은 이중 기록에 같은 불변식을 건다**(2026-08-13).

    `GoldenRunReport` 는 `Baseline` 과 같은 모양이다 — 같은 값이 지문(비교에 쓰이는 면)과
    `context`(사람이 읽는 기록) 두 자리에 실리고, 갈리지 않는 근거는 `build_report` 가 한
    `RunContext` 로 둘 다 만든다는 **호출 규약**뿐이었다. 그 형태가 불충분함은 `Baseline`
    에서 이미 증명됐다(독립 검증이 갈린 본문을 통과시켰다).

    갈리면 렌더 첫 줄의 `producer` 라벨과 그 아래 `변환 provider` 줄이 서로 다른 실행을
    가리키고, 읽는 사람은 그것을 한 실행의 결과로 읽는다.
    """
    with pytest.raises(ValidationError, match="지문의 producer와 context가 갈렸다"):
        golden_report.GoldenRunReport(
            fingerprint=Fingerprint.of(
                DOCUMENTS, _context(provider="anthropic", observed_models=["model-a"])
            ),
            context=_context(provider="openai", observed_models=["model-b"]),
            targets=golden_report.Targets(
                pass_rate=0.9, judge_coverage=0.9, judge_score=4.0, fidelity_floor=2
            ),
            measurement=_measurement((36, 56), (17, 20)),
        )


def test_producer_밖의_조건이_달라도_리포트는_만들어진다() -> None:
    """**대조군.** `judge_provider`·설정값 `model` 은 producer 축이 아니다.

    그 둘까지 결속하면 판정과 무관한 변경이 리포트 생성을 막는다 — 과민한 불변식은 반대
    방향 고장이다. `Baseline` 쪽 대조군(`test_한_실행_조건에서_유도한_본문은…`)과 같은 경계다.
    """
    context = _context(provider="anthropic", observed_models=["model-a"])
    report = golden_report.GoldenRunReport(
        fingerprint=Fingerprint.of(DOCUMENTS, context),
        context=context.model_copy(
            update={"judge_provider": "다른-채점자", "model": "다른-설정값"}
        ),
        targets=golden_report.Targets(
            pass_rate=0.9, judge_coverage=0.9, judge_score=4.0, fidelity_floor=2
        ),
        measurement=_measurement((36, 56), (17, 20)),
    )
    assert report.context.judge_provider == "다른-채점자"


def test_리포트가_미측정을_수치_옆에_적는다() -> None:
    """0.000을 품질로 읽는 오독을 렌더에서 막는다.

    아래 `변환 실패` 줄과 겹쳐 보이지만 그쪽은 문서 id 목록이고, 이쪽은 **그 비율의 분자에
    부재가 몇 건 섞였는가**다. 떨어뜨려 놓으면 사람이 통과율 줄만 보고 지나간다.
    """
    rendered = _report(_measurement((16, 56), (16, 20), (36, 0))).render()
    assert "미측정 36건" in rendered
    assert "부재다" in rendered
    # 전건을 잰 실행에는 붙지 않는다 — 항상 붙으면 경고가 신호를 잃는다.
    assert "미측정" not in _report(_measurement((36, 56), (17, 20))).render()


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
# 아래 넷은 `write_baseline`의 가드를 붙잡는 음성 대조다 — 가드를 지우면 이 넷이 깨진다.
#
# **넷 다 `tmp_path`로 쓴다**(2026-08-13). 예전에는 `path` 인자 없이 불러 대상이 저장소의
# `tests/golden/baseline.json`이었다. 지금은 가드가 먼저 나서 파일이 생기지 않지만, 그것은
# **가드가 있는 동안에만** 참이다 — 즉 "가드를 지우면 이 테스트가 깨진다"와 "가드를 지우면 이
# 테스트가 저장소에 하한선 파일을 만든다"가 같은 편집에 묶여 있었다. 하한선 파일은 존재하는
# 순간 활성화되므로, 가드를 만지는 편집이 곧 하한선을 세우는 편집이 되는 결합은 끊는다.


def test_provider_없이는_기준선을_쓰지_않는다(tmp_path: Path) -> None:
    """**`write_baseline`의 직접 호출 계약이다** — 하네스 경로로는 여기까지 오지 않는다.

    **2026-08-13 정정.** 이 docstring은 원래 "`GOLDEN_PROVIDER=`(빈 값)로 돌리면 하네스
    경로로 도달한다"고 적었다. 그 서술이 **틀렸다**(독립 검증 A-2, 실측으로 확인):
    `GOLDEN_PROVIDER=`이면 provider fixture가 `require_provider("")` →
    `create_provider("", settings)`를 부르고, 거기서 이름이 어느 벤더와도 맞지 않아
    `ValueError: 지원하지 않는 provider 이름:`이 난다. 변환도 평가도 기록도 시작되지 않으므로
    `write_baseline`은 **불리지 않는다.**

        >>> create_provider("", Settings())
        ValueError: 지원하지 않는 provider 이름:

    그래서 이 테스트는 위 두 하네스 테스트(`test_관측_모델이_없으면…`·`test_모델이_섞이면…`,
    `test_floor_gate_wiring.py`)와 **같은 수준의 배선 증거가 아니다.** 저쪽은 실제 기록
    경로를 태워 가드에 닿고, 이쪽은 함수를 직접 부른다. 그 구분을 흐리면 "배선을 확인했다"는
    말이 아무것도 뜻하지 않게 된다.

    **그래도 가드와 이 테스트는 남는다.** `write_baseline`은 dict를 받는 공개 함수이고
    손으로 조립한 body가 실제로 들어온다(바로 아래 셋이 그 형태다). provider가 비면 producer
    축의 첫 칸부터 비어 "누가 낸 수치인가"를 말할 수 없고, 무엇으로 쟀는지 모르는 수치가
    하한선이 되면 그 하락이 정상이 된다 — 이 절 전체의 근거다. 지키는 것이 *하네스 경로*가
    아니라 *직접 호출 계약*일 뿐이다.
    """
    path = tmp_path / "baseline.json"
    # 전제 — 이 상태는 만들 수 있다. 스키마가 막아 준다면 이 가드는 죽은 분기다.
    reachable = RunContext(provider="", observed_models=["fake-model"], effort=None)
    assert reachable.provider == ""
    body = baseline_body(
        Fingerprint.of(DOCUMENTS, reachable), _measurement((36, 56), (17, 20)), reachable
    )
    assert body["context"]["provider"] == "", "전제 — 빈 provider가 본문까지 그대로 실린다"

    with pytest.raises(AssertionError, match="`provider`가 비어 있다"):
        write_baseline(body, path)
    assert not path.exists(), "거부했는데 파일이 생겼다"


def test_관측_모델_없이는_기준선을_쓰지_않는다(tmp_path: Path) -> None:
    """설정값만 있고 **관측값이 없으면** 기록을 거부한다.

    `settings.llm_model`은 주장이고 `LLMResponse.model`이 증거다. 별칭 해석·폴백이
    있으면 설정값과 실제로 응답한 모델이 갈린다.
    """
    path = tmp_path / "baseline.json"
    with pytest.raises(AssertionError, match="관측된 모델이 없다"):
        write_baseline({"context": {"provider": "fake", "model": "설정값", "effort": None}}, path)
    assert not path.exists()


def test_모델이_섞인_실행은_기준선이_되지_못한다(tmp_path: Path) -> None:
    """한 실행에서 두 모델이 응답했으면 그 수치는 어느 모델의 하한선도 아니다."""
    path = tmp_path / "baseline.json"
    with pytest.raises(AssertionError, match="모델이 섞였다"):
        write_baseline(
            {"context": {"provider": "fake", "observed_models": ["a", "b"], "effort": None}}, path
        )
    assert not path.exists()


def test_effort_는_키_자체가_있어야_한다(tmp_path: Path) -> None:
    """값이 없으면 `null`로 **명시**한다 — 키의 부재와 값의 부재는 다르다.

    같은 모델이라도 effort가 결과를 크게 움직인다. 키가 없으면 그 실행이 어떤
    effort였는지 영영 알 수 없어 9%p 하락 같은 것의 원인을 가릴 수 없다.

    **이 가드만 성격이 다르다.** `baseline_body`가 pydantic `Baseline`을 dump하므로 하네스
    경로로 만든 본문에는 `effort` 키가 언제나 있다 — 이것은 손으로 조립한 body에 대한 방어이고,
    위 셋처럼 실행 조건으로 재현되지 않는다.

    **2026-08-13 정정 — "그래서 도달 0으로 묶어 고칠 대상이 아니다"는 쓰기 경계에서만 참이다.**
    독립 검증(A-3)이 **읽기 경계에 살아 있는 분기**를 짚었고 실측으로 재현됐다: `effort`에
    기본값이 있으면 `load_baseline()`이 디스크의 `context.effort` 키가 없는 파일을
    `effort=None`인 정상 기준선으로 복구했고, 그 기준선이 같은 지문에 `유지`를 돌려줬다.
    쓰기에서 "키가 없으면 안 된다"고 해 놓고 읽기에서 없는 키를 만들어 주고 있었던 것이다.
    이제 `RunContext.effort`에 기본값이 없어 그 파일은 검증 실패 → `None` → 차단이 된다
    (아래 `test_effort_키가_없는_기준선_파일은_읽히지_않는다`).
    """
    path = tmp_path / "baseline.json"
    with pytest.raises(AssertionError, match="effort"):
        write_baseline({"context": {"provider": "fake", "observed_models": ["a"]}}, path)
    assert not path.exists()


# ═══════════════════════════════════ G. producer 축 — 만든 쪽이 바뀌면 비교하지 않는다
#
# 2026-08-13 사용자 결정: "provider·모델이 바뀌면 하한선 비교를 **비교 불가로 막는다** —
# 지문에 추가." 그전까지 provider·모델·effort는 `RunContext`에만 있어 비교 가능성 판정에
# 들어가지 않았고, anthropic으로 기록한 기준선과 openai 실행이 **비교 가능**으로 읽혀 수치
# 판정이 났다. 다른 모델의 통과율끼리 비교하는 것은 의미가 없다.
#
# 아래는 세 필드 각각의 음성 대조와 대조군 하나다. 대조군이 없으면 "축을 넣어 전부 막았다"와
# 구분되지 않는다 — 지문을 과민하게 만드는 것은 '항상 비교 불가'라는 반대 방향 고장이다.


def _producer_case(
    baseline_context: RunContext, current_context: RunContext
) -> tuple[Verdict, str]:
    """조건만 다른 두 실행을 대조한다. **수치와 코퍼스는 같게 둔다** — 막히거나 통과하는
    이유가 producer 하나로 좁혀져야 무엇을 확인한 것인지 말할 수 있다.
    """
    measurement = _measurement((36, 56), (17, 20))
    baseline = _baseline(Fingerprint.of(DOCUMENTS, baseline_context), measurement, baseline_context)
    judgement = compare(baseline, Fingerprint.of(DOCUMENTS, current_context), measurement)
    return judgement.verdict, judgement.summary()


def test_provider가_바뀌면_비교_불가다() -> None:
    """anthropic으로 기록한 하한선과 openai 실행은 비교되지 않는다 — 사용자 결정의 본문."""
    verdict, summary = _producer_case(
        _context(provider="anthropic", observed_models=["claude-sonnet-5"]),
        _context(provider="openai", observed_models=["claude-sonnet-5"]),
    )
    assert verdict is Verdict.INCOMPARABLE
    # **해시가 아니라 값으로 담은 이유가 이 줄이다** — 무엇이 달라졌는지 이름으로 말한다.
    assert "provider: anthropic → openai" in summary


def test_관측_모델이_바뀌면_비교_불가다() -> None:
    """provider가 같아도 모델이 다르면 같은 자로 잰 수치가 아니다.

    같은 벤더 안에서 모델을 갈아 끼우는 것이 제품에서 훨씬 흔한 변경이라, provider만 막으면
    실제로 일어나는 경로가 열린 채 남는다.
    """
    verdict, summary = _producer_case(
        _context(provider="anthropic", observed_models=["claude-sonnet-5"]),
        _context(provider="anthropic", observed_models=["claude-opus-5"]),
    )
    assert verdict is Verdict.INCOMPARABLE
    assert "관측 모델: claude-sonnet-5 → claude-opus-5" in summary
    assert "provider:" not in summary, "안 바뀐 필드까지 적으면 무엇이 달라졌는지 흐려진다"


def test_effort가_바뀌면_비교_불가다() -> None:
    """**짐작이 아니라 이 저장소의 기록이다.** 9%p 하락의 후보로 `.env`의 `LLM_EFFORT`
    차이를 스스로 지목했다(`04_goldenset-first-run.md`). 모델과 같은 부류다.

    값이 없는 쪽은 `null`이 아니라 이름(`없음`)으로 적힌다 — 없음도 값이고, 그것이 어느
    쪽인지 사람이 읽어야 한다.
    """
    verdict, summary = _producer_case(
        _context(effort="high"),
        _context(effort=None),
    )
    assert verdict is Verdict.INCOMPARABLE
    assert "effort: high → 없음" in summary


def test_producer가_같으면_정상_비교된다() -> None:
    """**대조군.** 셋이 다 같으면 수치 판정이 그대로 난다.

    이것이 없으면 "축을 넣어 전부 막았다"와 구분되지 않는다. 과민한 지문은 하한선이 영영
    축적되지 않는 반대 방향 고장이라, 막는 쪽만 확인하는 것으로는 부족하다.
    """
    context = _context(provider="anthropic", observed_models=["claude-sonnet-5"], effort="high")
    fingerprint = Fingerprint.of(DOCUMENTS, context)
    baseline = _baseline(fingerprint, _measurement((36, 56), (17, 20)), context)
    held = compare(baseline, fingerprint, _measurement((36, 56), (17, 20)))
    assert held.verdict is Verdict.HELD
    # 수치 판정이 실제로 도는지까지 본다 — '전부 통과'가 아니라 '수치를 읽는다'가 맞는 상태다.
    dropped = compare(baseline, fingerprint, _measurement((30, 56), (17, 20)))
    assert dropped.verdict is Verdict.REGRESSED


def test_기록만_하는_조건은_producer_를_흔들지_않는다() -> None:
    """**축의 경계를 고정한다.** 담는 것은 provider·관측 모델·effort **셋뿐**이다.

    `judge_provider`는 비차단축의 채점자라 규칙 통과율과 무관하고, 설정값 `model`은
    별칭 해석·폴백이 있으면 실제와 갈리는 *주장*이다. 둘을 넣으면 판정과 무관한 변경이
    하한선을 리셋한다 — 지문을 과민하게 만드는 쪽의 고장이다.
    """
    claimed = RunContext(
        provider="anthropic",
        judge_provider="openai",
        model="설정값-별칭",
        observed_models=["claude-sonnet-5"],
        effort="high",
    )
    other_claim = claimed.model_copy(update={"judge_provider": "anthropic", "model": "다른-설정값"})
    assert (
        Fingerprint.of(DOCUMENTS, claimed).producer
        == Fingerprint.of(DOCUMENTS, other_claim).producer
    )


def test_관측_모델이_비거나_섞여도_사실대로_적힌다() -> None:
    """**가드가 없는 쪽을 확인한다.** "비면 안 되고 섞이면 안 된다"는 `write_baseline`의
    fail-closed 가드가 보장하는데, **그 가드는 기록 경로에만 있다.** 판정 경로(비기록 실행)는
    전건 변환 실패로 관측이 비거나 별칭 해석·폴백으로 섞인 채 여기까지 온다.

    그래서 단일 값으로 접지 않고 목록을 사실대로 담는다. 결과는 어느 쪽이든 **비교 불가**이고
    그것이 맞다 — 없는 모델을 있는 것처럼 접거나 섞인 것 중 하나를 고르면, 그 실행이 남의
    하한선을 통과해 버린다.
    """
    normal = _context(provider="anthropic", observed_models=["claude-sonnet-5"])
    empty = _context(provider="anthropic", observed_models=[])
    mixed = _context(provider="anthropic", observed_models=["claude-sonnet-5", "claude-opus-5"])

    assert Fingerprint.of(DOCUMENTS, empty).producer.label() == "anthropic/모델 없음(effort=없음)"
    assert (
        Fingerprint.of(DOCUMENTS, mixed).producer.label()
        == "anthropic/claude-sonnet-5+claude-opus-5(effort=없음)"
    )
    for broken in (empty, mixed):
        verdict, summary = _producer_case(normal, broken)
        assert verdict is Verdict.INCOMPARABLE
        assert "관측 모델: claude-sonnet-5 → " in summary


# ════════════════ H. producer 결속 — 지문과 context 가 갈린 기준선은 만들어지지 않는다
#
# **12번째 지적**(2026-08-13 독립 검증 A-1, high). producer 축을 넣을 때 같은 값이
# `fingerprint.producer`(기계가 비교하는 면)와 `context`(사람이 읽는 기록) 두 자리에 실렸고,
# 저자는 "둘이 갈리지 않는 것은 한 `RunContext`에서 유도하기 때문"이라며 **호출 규약으로만**
# 막았다. 그때의 위험 평가는 "갈려도 비교는 지문이 하므로 판정은 안전하고 사람이 읽는 기록만
# 틀어진다"였다.
#
# **그 평가가 틀렸다.** 실측 재현: 지문 `anthropic/model-a`, context `openai/model-b`인 본문이
# writer 를 통과했고(`write_baseline: 통과`), 다음 model-a 실행이 그 파일을 읽어 지문만
# 대조하고 **`개선`/차단 False** 를 냈다. 즉 **model-b 로 잰 낮은 수치가 model-a 실행의
# 하한선으로 채택된다.** 틀어진 것은 기록이 아니라 하한선 자체다.
#
# 검사를 얹는 대신 **그 상태를 표현할 수 없게** 했다. 아래 넷은 성분별 음성 대조와 대조군이고,
# 그 아래 둘이 경로(writer·읽기)를, 마지막 하나가 위 재현 전체를 회귀로 고정한다.


def _mismatched_body(fingerprint_context: RunContext, body_context: RunContext) -> dict[str, Any]:
    """지문과 context 를 **다른 `RunContext`에서** 유도해 본문을 만들려 시도한다.

    이것이 A-1 재현의 첫 줄이다. 성공하면 그 뒤가 전부 성립하고, 여기서 막히면 뒤가 없다.
    """
    return baseline_body(
        Fingerprint.of(DOCUMENTS, fingerprint_context),
        _measurement((36, 56), (17, 20)),
        body_context,
    )


def test_provider만_갈려도_본문이_조립되지_않는다() -> None:
    """성분 하나만 어긋나도 거부한다 — 셋을 묶어 보면 어느 성분이 열려 있는지 알 수 없다."""
    with pytest.raises(ValidationError, match="지문의 producer와 context가 갈렸다") as caught:
        _mismatched_body(_context(provider="anthropic"), _context(provider="openai"))
    message = str(caught.value)
    assert "provider: 지문 anthropic / context openai" in message
    assert "관측 모델:" not in message, "안 갈린 성분까지 적으면 무엇이 어긋났는지 흐려진다"


def test_관측_모델만_갈려도_본문이_조립되지_않는다() -> None:
    """같은 벤더 안에서 모델만 갈리는 것이 제품에서 훨씬 흔하다 — provider 만 보면 열려 있다."""
    with pytest.raises(ValidationError, match="지문의 producer와 context가 갈렸다") as caught:
        _mismatched_body(
            _context(observed_models=["model-a"]), _context(observed_models=["model-b"])
        )
    message = str(caught.value)
    assert "관측 모델: 지문 model-a / context model-b" in message
    assert "provider:" not in message


def test_effort만_갈려도_본문이_조립되지_않는다() -> None:
    """`effort` 도 producer 축이다 — 값이 없는 쪽은 이름(`없음`)으로 적힌다."""
    with pytest.raises(ValidationError, match="지문의 producer와 context가 갈렸다") as caught:
        _mismatched_body(_context(effort="high"), _context(effort=None))
    message = str(caught.value)
    assert "effort: 지문 high / context 없음" in message
    assert "provider:" not in message


def test_한_실행_조건에서_유도한_본문은_그대로_조립된다() -> None:
    """**대조군.** 막는 쪽만 확인하면 "전부 거부하게 만들었다"와 구분되지 않는다.

    `judge_provider`·설정값 `model` 은 producer 축이 아니므로 **달라도 통과해야 한다** —
    그 둘까지 결속하면 판정과 무관한 변경이 기록을 거부한다(과민한 지문은 반대 방향 고장이다).
    """
    context = _context(provider="anthropic", observed_models=["model-a"], effort="high")
    body = baseline_body(
        Fingerprint.of(DOCUMENTS, context), _measurement((36, 56), (17, 20)), context
    )
    assert body["fingerprint"]["producer"]["provider"] == "anthropic"

    # producer 축 밖의 필드만 다른 두 조건 — 결속은 세 성분만 본다.
    other_claim = context.model_copy(
        update={"judge_provider": "다른-채점자", "model": "다른-설정값"}
    )
    assert (
        baseline_body(
            Fingerprint.of(DOCUMENTS, context), _measurement((36, 56), (17, 20)), other_claim
        )["context"]["judge_provider"]
        == "다른-채점자"
    )


def test_손으로_조립한_갈린_본문은_writer가_거부한다(tmp_path: Path) -> None:
    """**G5** — `baseline_body` 를 거치지 않는 통로를 같은 층위에서 막는다.

    `write_baseline` 은 모델이 아니라 dict 를 받으므로 `Baseline` 의 불변식이 닿지 않는다.
    위 G1~G4 를 붙잡는 음성 대조 넷이 정확히 그 형태(손으로 조립한 body)이므로, 이 통로가
    있다는 것은 가정이 아니라 이 파일이 이미 쓰고 있는 사실이다.
    """
    path = tmp_path / "baseline.json"
    body = {
        "fingerprint": {
            "producer": {"provider": "anthropic", "observed_models": ["model-a"], "effort": "low"}
        },
        "context": {"provider": "openai", "observed_models": ["model-b"], "effort": "low"},
    }
    with pytest.raises(AssertionError, match="지문의 producer와 context가 갈렸다"):
        write_baseline(body, path)
    assert not path.exists(), "거부했는데 파일이 생겼다"


def test_지문_없는_본문도_writer가_거부한다(tmp_path: Path) -> None:
    """결속을 **확인할 수 없는** 본문도 거부다 — 없는 것은 맞는 것이 아니다.

    지문이 빠진 body 는 G1~G4 를 전부 지난다(그 넷은 `context` 만 본다). 결속을 대조할
    상대가 없다는 이유로 통과시키면, 필드 하나를 지우는 것이 가드를 지나는 방법이 된다.
    """
    path = tmp_path / "baseline.json"
    with pytest.raises(AssertionError, match="지문의 producer와 context가 갈렸다"):
        write_baseline(
            {"context": {"provider": "fake", "observed_models": ["model-a"], "effort": None}}, path
        )
    assert not path.exists()


def test_갈린_기준선_파일은_읽기_경계에서도_거부된다(tmp_path: Path) -> None:
    """**쓰기만 막으면 부족하다** — 이전 코드가 남긴 파일과 손으로 고친 파일이 있다.

    커밋되는 하한선 파일은 사람이 열어 고칠 수 있고, 이 가드가 없던 시절의 파일도 있을 수
    있다. 읽기 경계가 열려 있으면 그런 파일이 그대로 하한선이 된다 — A-1 재현의 후반부가
    정확히 "이미 디스크에 있는 갈린 파일을 읽어 판정한다"이다.
    """
    path = tmp_path / "baseline.json"
    context = _context(provider="anthropic", observed_models=["model-a"], effort="low")
    body = baseline_body(
        Fingerprint.of(DOCUMENTS, context), _measurement((10, 56), (5, 20)), context
    )
    # 디스크에 이미 있는 갈린 파일을 손으로 만든다(writer 를 거치지 않는다).
    body["context"]["provider"] = "openai"
    body["context"]["observed_models"] = ["model-b"]
    path.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")

    assert stored_body(path) is not None, "전제 — JSON 으로는 멀쩡한 파일이다"
    assert load_baseline(path) is None, "갈린 파일이 기준선으로 읽혔다"
    judgement = compare(
        load_baseline(path), Fingerprint.of(DOCUMENTS, context), _measurement((40, 56), (18, 20))
    )
    assert judgement.verdict is Verdict.ABSENT
    assert judgement.blocking is True, "갈린 파일을 읽고도 차단하지 않았다"


def test_effort_키가_없는_기준선_파일은_읽히지_않는다(tmp_path: Path) -> None:
    """**A-3 의 읽기 경계.** 쓰기에서 요구한 것을 읽기에서 만들어 주지 않는다.

    고치기 전 실측: `RunContext.effort` 의 기본값 때문에 `load_baseline()` 이 디스크의
    `context.effort` 키가 없는 파일을 `effort=None` 인 정상 기준선으로 복구했고, 그 기준선이
    같은 지문에 판정을 돌려줬다. G4 가 쓰기에서 "키의 부재와 값의 부재는 다르다"고 막는
    동안 읽기가 그 구분을 지우고 있었다.

    `null` 로 **명시된** 파일은 그대로 읽혀야 한다 — 막는 것은 키의 부재이지 값의 부재가 아니다.
    """
    path = tmp_path / "baseline.json"
    context = _context(provider="anthropic", observed_models=["model-a"], effort=None)
    body = baseline_body(
        Fingerprint.of(DOCUMENTS, context), _measurement((10, 56), (5, 20)), context
    )
    assert body["context"]["effort"] is None, "전제 — 값 없음은 `null` 로 명시된다"
    path.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
    assert load_baseline(path) is not None, "`null` 명시는 정상 기준선이다"

    del body["context"]["effort"]
    path.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
    assert stored_body(path) is not None, "전제 — JSON 으로는 멀쩡한 파일이다"
    assert load_baseline(path) is None, "키가 없는 파일이 `effort=None` 으로 복구됐다"


# ══════════════ I. 미측정 — 변환에 실패한 문서가 있는 실행은 기준선이 되지 못한다
#
# **2026-08-13 사고.** 합성 20건은 변환에 성공하고 실수집 36건이 전부 HTTP 400(크레딧 소진)
# 으로 실패한 실행이 기록 경로의 가드 넷(provider·관측 모델·모델 섞임·effort 키)을 **전부
# 통과**했다. 관측 모델은 성공한 20건에서만 모이므로 지문이 건강한 실행과 한 글자도 다르지
# 않았기 때문이다. 그날 그 파일이 하한선이 되는 것을 막은 것은 사람이 손으로 되돌린 것뿐이다.
#
# 그 수치가 하한선이 되면 두 방향이 함께 고장난다(리뷰어 실측):
#
#     2026-08-12 실측 31/56 (16/20·15/36)  → 하락  blocking=True   ← 정상 실행이 거짓 차단
#     실수집 전멸 재발 0/36                 → 유지  blocking=False  ← 실수집 축 영구 개방
#
# 아래는 그 사슬의 마디를 하나씩 끊는다. 마지막 하나는 **끊긴 뒤에도 두 행이 성립하지
# 않는가**를 리뷰어의 수치 그대로 확인한다.

#: 2026-08-13 실행의 형태 — 합성 20건은 변환됐고(16건 통과) 실수집 36건은 전멸했다.
_사고_수치 = ((16, 56), (16, 20), (36, 0))


def _사고_측정치() -> Measurement:
    passed, synthetic, unmeasured = _사고_수치
    return _measurement(passed, synthetic, unmeasured)


def test_미측정이_섞인_기준선은_조립되지_않는다() -> None:
    """**첫 마디** — 본문 dict가 생기기 전에 끝난다.

    `baseline_body` 가 하는 첫 일이 `Baseline` 조립이라, 미측정이 섞인 측정치는 파일로
    나갈 수 있는 모양이 되기 전에 거부된다.
    """
    with pytest.raises(ValidationError, match="측정하지 못한 문서가 있다") as caught:
        baseline_body(Fingerprint.of(DOCUMENTS, _context()), _사고_측정치(), _context())
    message = str(caught.value)
    assert "실수집: 36/36건" in message, "어느 축이 통째로 비었는지가 메시지에 없다"
    assert "합성:" not in message, "전건을 잰 집단까지 적으면 무엇이 비었는지 흐려진다"


def test_전건을_잰_실행은_그대로_조립된다() -> None:
    """**대조군.** 막는 쪽만 확인하면 "기록을 통째로 잠갔다"와 구분되지 않는다.

    수치가 낮은 것은 막지 않는다 — 하한선은 현재 위치에서 출발하고, 여기서 보는 것은
    "이 수치가 측정인가"이지 "이 수치가 좋은가"가 아니다.
    """
    body = baseline_body(
        Fingerprint.of(DOCUMENTS, _context()), _measurement((0, 56), (0, 20)), _context()
    )
    assert body["measurement"]["collected"]["unmeasured"] == 0


def test_손으로_조립한_미측정_본문은_writer가_거부한다(tmp_path: Path) -> None:
    """**둘째 마디(G6)** — `baseline_body` 를 거치지 않는 dict 통로도 같은 규칙을 받는다."""
    path = tmp_path / "baseline.json"
    forged = baseline_body(
        Fingerprint.of(DOCUMENTS, _context()), _measurement((16, 56), (16, 20)), _context()
    )
    forged["measurement"]["collected"]["unmeasured"] = 36
    forged["measurement"]["overall"]["unmeasured"] = 36
    with pytest.raises(AssertionError, match="측정하지 못한 문서가 있다"):
        write_baseline(forged, path)
    assert not path.exists(), "거부했는데 파일이 생겼다"


def test_미측정_키가_없는_본문도_writer가_거부한다(tmp_path: Path) -> None:
    """**키의 부재는 0이 아니다** — 필드 하나를 지우는 것이 가드를 지나는 방법이 되면 안 된다.

    `effort`(G4)·지문 없는 본문에서 이미 같은 판단을 했다. 여기만 관대하면 그 자리가
    가장 싼 우회로가 된다.
    """
    path = tmp_path / "baseline.json"
    body = baseline_body(
        Fingerprint.of(DOCUMENTS, _context()), _measurement((36, 56), (17, 20)), _context()
    )
    del body["measurement"]["collected"]["unmeasured"]
    with pytest.raises(AssertionError, match="unmeasured.*가 없다"):
        write_baseline(body, path)

    without_measurement = {key: value for key, value in body.items() if key != "measurement"}
    with pytest.raises(AssertionError, match="`measurement`가 없다"):
        write_baseline(without_measurement, path)
    assert not path.exists()


def test_미측정_기준선_파일은_읽기_경계에서도_거부된다(tmp_path: Path) -> None:
    """**셋째 마디** — 이미 디스크에 있는 파일이 하한선이 되는 경로.

    커밋되는 하한선 파일은 사람이 열어 고칠 수 있고, 이 가드가 없던 시절의 파일도 있을 수
    있다. 읽기가 열려 있으면 그런 파일이 그대로 하한선이 된다.
    """
    path = tmp_path / "baseline.json"
    body = baseline_body(
        Fingerprint.of(DOCUMENTS, _context()), _measurement((16, 56), (16, 20)), _context()
    )
    body["measurement"]["collected"]["unmeasured"] = 36
    body["measurement"]["overall"]["unmeasured"] = 36
    path.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")

    assert stored_body(path) is not None, "전제 — JSON 으로는 멀쩡한 파일이다"
    assert load_baseline(path) is None, "미측정이 섞인 파일이 기준선으로 읽혔다"


def test_미측정_키가_없는_기준선_파일은_읽히지_않는다(tmp_path: Path) -> None:
    """**A-3 과 같은 자리** — 쓰기에서 요구한 것을 읽기가 만들어 주지 않는다.

    G6 는 `unmeasured` 키가 없는 본문을 거부한다. 그런데 `GroupMeasurement.unmeasured` 에
    기본값이 있으면 `load_baseline` 이 **키가 없는 디스크 파일을 `unmeasured=0` 인 정상
    기준선으로 복구**한다 — 쓰기에서 "키의 부재는 0이 아니다"라고 해 놓고 읽기가 그 구분을
    지우는 것이다. `effort` 가 정확히 그 형태로 열려 있었고 독립 검증(A-3)이 재현했다.

    **이 테스트가 없으면 그 기본값을 되돌려 놓아도 스위트가 초록이다**(실측: 기본값 `= 0`
    으로 되돌린 변이에서 150건 전부 통과). 필드를 필수로 두는 편집을 붙잡는 것이 여기다.
    """
    path = tmp_path / "baseline.json"
    body = baseline_body(
        Fingerprint.of(DOCUMENTS, _context()), _measurement((36, 56), (17, 20)), _context()
    )
    path.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
    assert load_baseline(path) is not None, "전제 — 전건을 잰 기준선은 정상적으로 읽힌다"

    del body["measurement"]["collected"]["unmeasured"]
    path.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
    assert stored_body(path) is not None, "전제 — JSON 으로는 멀쩡한 파일이다"
    assert load_baseline(path) is None, (
        "미측정 키가 없는 파일이 `unmeasured=0` 으로 복구됐다 — 읽기가 쓰기보다 관대하다"
    )


def test_미측정이_섞인_실행은_판정도_하지_못한다() -> None:
    """**기록 경로만 막으면 반쪽이다** — 평범한(비기록) 게이트 실행의 자리.

    부분 실패한 문서가 **원래도 실패하던 문서**라면 수치가 기준선과 같아 `유지`(비차단)로
    통과한다. 36건을 모델에 보내지도 못한 실행이 초록으로 끝나는 것이다. 기록을 막는 것과
    판정을 막는 것은 다른 사건이라 자리도 둘이다.
    """
    fingerprint = Fingerprint.of(DOCUMENTS, _context())
    baseline = _baseline(fingerprint, _measurement((16, 56), (16, 20)))
    judgement = compare(baseline, fingerprint, _사고_측정치())
    assert judgement.verdict is Verdict.UNMEASURED
    assert judgement.blocking is True
    # 재기록을 요구하지 않는다 — 요구해 봐야 `Baseline` 불변식이 그 기록을 거부한다.
    assert judgement.requires_record is False
    assert "실수집: 36/36건" in judgement.summary()


def test_전건을_잰_실행은_그대로_수치_판정을_받는다() -> None:
    """**대조군.** 미측정 검사가 정상 실행의 판정을 가로채지 않는다."""
    fingerprint = Fingerprint.of(DOCUMENTS, _context())
    baseline = _baseline(fingerprint, _measurement((36, 56), (17, 20)))
    assert compare(baseline, fingerprint, _measurement((36, 56), (17, 20))).verdict is Verdict.HELD
    assert (
        compare(baseline, fingerprint, _measurement((30, 56), (17, 20))).verdict
        is Verdict.REGRESSED
    )


def test_사고_실행의_수치는_어느_방향으로도_하한선이_되지_못한다(tmp_path: Path) -> None:
    """**리뷰어가 낸 두 행을 그대로 확인한다** — 사슬이 끊긴 뒤에도 성립하지 않는가.

    고치기 전 실측(2026-08-13):

        기준선 = 사고 실행(16/56 · 합성 16/20 · 실수집 0/36, 실수집 36건 미측정)
        2026-08-12 실측 31/56 (16/20·15/36)  → 하락  blocking=True   ← 정상 실행이 거짓 차단
        실수집 전멸 재발 0/36                 → 유지  blocking=False  ← 실수집 축 영구 개방

    두 행 모두 **그 기준선이 존재한다**는 전제 위에 선다. 이제 그 전제가 세 경계에서 전부
    성립하지 않으므로 두 행 자체가 없다. 마디를 하나씩 확인하는 이유는, 하나만 보면 뒤의
    마디가 다시 열렸을 때(예: 불변식을 지우고 writer 만 남길 때) 알아채지 못하기 때문이다.
    """
    path = tmp_path / "baseline.json"
    fingerprint = Fingerprint.of(DOCUMENTS, _context())
    사고 = _사고_측정치()

    # ① 사고 실행의 기준선은 조립되지 않는다.
    with pytest.raises(ValidationError, match="측정하지 못한 문서가 있다"):
        baseline_body(fingerprint, 사고, _context())

    # ② 손으로 조립해 writer 를 태워도 파일이 생기지 않는다.
    forged = baseline_body(fingerprint, _measurement((16, 56), (16, 20)), _context())
    forged["measurement"] = json.loads(사고.model_dump_json())
    with pytest.raises(AssertionError, match="측정하지 못한 문서가 있다"):
        write_baseline(forged, path)
    assert not path.exists()

    # ③ writer 까지 우회해 파일을 직접 써도 읽기에서 막힌다 — 두 행의 전제가 사라진다.
    path.write_text(json.dumps(forged, ensure_ascii=False), encoding="utf-8")
    assert load_baseline(path) is None

    # ④ 그래서 두 행이 성립하지 않는다. 정상 실행은 **거짓 차단되지 않고**(하한선이 없으므로
    #    "기준선 없음"으로 재기록을 요구할 뿐 하락으로 몰리지 않는다), 전멸 재발은
    #    **유지로 통과하지 않는다**(측정 미완으로 차단된다).
    정상 = _measurement((31, 56), (16, 20))
    거짓_차단 = compare(load_baseline(path), fingerprint, 정상)
    assert 거짓_차단.verdict not in {Verdict.REGRESSED, Verdict.HELD, Verdict.IMPROVED}, (
        "사고 수치가 정상 실행의 비교 대상이 됐다 — 수치 판정이 났다"
    )
    assert 거짓_차단.verdict is Verdict.ABSENT

    재발 = compare(load_baseline(path), fingerprint, _사고_측정치())
    assert 재발.verdict is Verdict.UNMEASURED, "실수집 전멸 재발이 하한선을 통과했다"
    assert 재발.blocking is True


def test_다른_producer의_수치가_하한선으로_채택되지_않는다(tmp_path: Path) -> None:
    """**A-1 재현 전체를 회귀로 고정한다** — 리더가 실행한 시나리오 그대로다.

    고치기 전 실측(2026-08-13):

        ctx_a = RunContext(provider="anthropic", model="model-a", …)   # 지문 쪽
        ctx_b = RunContext(provider="openai",    model="model-b", …)   # context 쪽
        body  = baseline_body(Fingerprint.of(DOCUMENTS, ctx_a), 낮은_수치, ctx_b)
        write_baseline(body, path=tmp)   # → 통과. 가드가 막지 않는다
        compare(load_baseline(tmp), Fingerprint.of(DOCUMENTS, ctx_a), 높은_수치)
        # → 판정 `개선`, 차단 False  ← model-b 의 낮은 수치가 model-a 의 하한선이 됐다

    이제 **첫 줄에서 끝난다.** 본문이 만들어지지 않으므로 writer 도 읽기도 판정도 없다.
    아래는 그 사슬의 각 마디가 하나씩 성립하지 않음을 확인한다 — 첫 마디만 보면 뒤의 마디가
    다시 열렸을 때(예: `Baseline` 불변식을 지우고 writer 만 남길 때) 알아채지 못한다.
    """
    path = tmp_path / "baseline.json"
    ctx_a = _context(provider="anthropic", observed_models=["model-a"], effort="low")
    ctx_b = _context(provider="openai", observed_models=["model-b"], effort="low")
    low, high = _measurement((10, 56), (5, 20)), _measurement((40, 56), (18, 20))
    fingerprint_a = Fingerprint.of(DOCUMENTS, ctx_a)

    # ① 본문 조립 — 여기서 끝난다.
    with pytest.raises(ValidationError, match="지문의 producer와 context가 갈렸다"):
        baseline_body(fingerprint_a, low, ctx_b)

    # ② 그 사슬을 우회해 손으로 본문을 만들어도 writer 가 막는다.
    forged = baseline_body(fingerprint_a, low, ctx_a)
    forged["context"] = json.loads(ctx_b.model_dump_json())
    with pytest.raises(AssertionError, match="지문의 producer와 context가 갈렸다"):
        write_baseline(forged, path)
    assert not path.exists()

    # ③ writer 까지 우회해 파일을 직접 써도 읽기에서 막힌다 — 판정에 도달하지 않는다.
    path.write_text(json.dumps(forged, ensure_ascii=False), encoding="utf-8")
    assert load_baseline(path) is None
    judgement = compare(load_baseline(path), fingerprint_a, high)
    assert judgement.verdict is not Verdict.IMPROVED, (
        "다른 producer 의 낮은 수치가 이 실행의 하한선으로 읽혔다"
    )
    assert judgement.blocking is True, "갈린 파일을 만난 실행이 차단되지 않았다"
