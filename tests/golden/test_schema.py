"""골든셋 문서 자체를 기계적으로 검증한다 (LLM 호출 없음, 기본 실행 포함).

여기서 걸러지지 않은 문서 결함은 LLM 평가 단계에서 원인 불명의 실패로 나타난다.
특히 required_facts에 마스킹 대상 패턴이 섞이면 팩트 보존 검사가 영구히 실패한다.
실패 출력에는 문서 id와 위반 리터럴만 남기고 본문은 남기지 않는다.
"""

import re

import pytest
from pydantic import ValidationError

from app.easyread.goldenset import GoldenDocument, load_documents
from app.easyread.style_rules import (
    DIFFICULT_WORD_REPLACEMENTS,
    PROMPT_ONLY_WORDS,
    check_style,
    find_difficult_words,
)
from app.privacy.masking import MaskCategory, mask_text
from tests.golden import DOCUMENTS_DIR

MIN_DOCUMENTS = 20
MIN_DIFFICULT_WORDS = 2
MIN_FACTS = 3
MAX_FACTS = 6
MIN_SOURCE_CHARS = 500
MAX_SOURCE_CHARS = 1500

# 카테고리 통제 어휘 — 자유 입력이면 "주거 공고문"/"주거 안내문"처럼 축이 갈라져
# 주제 분포 집계가 깨진다. 새 주제를 넣을 때 이 목록을 함께 갱신한다.
ALLOWED_CATEGORIES = frozenset(
    {
        "복지 안내문",
        "보건 안내문",
        "행정 안내문",
        "주거 안내문",
        "재난 안내문",
        "문화 안내문",
        "교육 안내문",
        "고용 안내문",
        "생활요금 안내문",
    }
)

DOCUMENTS: list[GoldenDocument] = load_documents(DOCUMENTS_DIR)


def test_문서가_최소_수량_이상_로드된다() -> None:
    assert len(DOCUMENTS) >= MIN_DOCUMENTS


def test_id가_중복되지_않는다() -> None:
    ids = [document.id for document in DOCUMENTS]
    assert len(set(ids)) == len(ids)


def test_문서가_서로_다르다() -> None:
    """템플릿 복붙이면 평가 변별력이 없다 — 제목·본문이 모두 달라야 한다."""
    assert len({document.title for document in DOCUMENTS}) == len(DOCUMENTS)
    assert len({document.source_text for document in DOCUMENTS}) == len(DOCUMENTS)


def test_모두_합성_문서로_표시된다() -> None:
    """실제 수집 문서로 교체하면 synthetic을 false로 바꾸고 이 기준을 조정한다."""
    assert [document.id for document in DOCUMENTS if not document.synthetic] == []


def _payload(**overrides: object) -> dict[str, object]:
    """스키마 불변식 검사용 최소 문서. 파일로 만들지 않아 평가셋에 섞이지 않는다."""
    payload: dict[str, object] = {
        "id": "999",
        "title": "검증용 문서",
        "category": "복지 안내문",
        "synthetic": True,
        "source_text": "검증용 본문입니다.",
        "required_facts": [],
    }
    payload.update(overrides)
    return payload


def test_수집_문서는_출처_없이_만들_수_없다() -> None:
    """synthetic=false인데 source가 없으면 공공누리 유형·수집 시점 근거가 사라진다."""
    with pytest.raises(ValidationError):
        GoldenDocument.model_validate(_payload(synthetic=False))


def test_출처가_있으면_수집_문서를_만들_수_있다() -> None:
    document = GoldenDocument.model_validate(
        _payload(
            synthetic=False,
            source={
                "url": "https://example.go.kr/notice/1",
                "organization": "○○구청",
                "license": "공공누리 제1유형",
                "collected_at": "2026-08-06",
            },
        )
    )
    assert document.source is not None
    assert document.source.license == "공공누리 제1유형"


def test_합성_문서는_출처가_없어도_된다() -> None:
    """기존 합성 20건이 이 규칙에 걸리지 않아야 한다."""
    assert GoldenDocument.model_validate(_payload()).source is None


def test_필수_문자열_필드가_비어_있지_않다() -> None:
    empty = [
        document.id
        for document in DOCUMENTS
        if not (document.title.strip() and document.category.strip())
    ]
    assert empty == []


def test_본문_길이가_기준_범위_안이다() -> None:
    """너무 짧으면 변환 난도가 없고, 너무 길면 토큰 한도·지연 측정이 왜곡된다."""
    out_of_range = [
        document.id
        for document in DOCUMENTS
        if not MIN_SOURCE_CHARS <= len(document.source_text) <= MAX_SOURCE_CHARS
    ]
    assert out_of_range == []


def test_어려운_표현이_문서마다_충분히_들어_있다() -> None:
    """PROMPT_ONLY_WORDS(상기·하기 등)는 채점 제외라 개수에 포함되지 않는다."""
    insufficient = [
        document.id
        for document in DOCUMENTS
        if len(find_difficult_words(document.source_text)) < MIN_DIFFICULT_WORDS
    ]
    assert insufficient == []


def test_합성_개인정보가_문서마다_들어_있다() -> None:
    """마스킹 파이프라인이 실제로 동작하는지 평가하려면 문서에 개인정보가 있어야 한다."""
    without_pii = [
        document.id for document in DOCUMENTS if not mask_text(document.source_text).items
    ]
    assert without_pii == []


def test_마스킹_범주_전체가_골든셋에_등장한다() -> None:
    """한 범주라도 빠지면 그 패턴의 회귀를 골든셋 평가가 잡지 못한다."""
    covered = {
        item.category for document in DOCUMENTS for item in mask_text(document.source_text).items
    }
    assert covered == set(MaskCategory)


def test_카테고리가_통제_어휘_안이다() -> None:
    unknown = sorted({document.category for document in DOCUMENTS} - ALLOWED_CATEGORIES)
    assert unknown == []


def test_원문은_모두_변환이_필요한_상태다() -> None:
    """이미 쉬운 글인 문서가 섞이면 통과율이 실력과 무관하게 올라간다."""
    already_easy = [
        document.id for document in DOCUMENTS if check_style(document.source_text).passed
    ]
    assert already_easy == []


def test_required_facts_개수가_기준_범위_안이다() -> None:
    out_of_range = [
        document.id
        for document in DOCUMENTS
        if not MIN_FACTS <= len(document.required_facts) <= MAX_FACTS
    ]
    assert out_of_range == []


def test_required_facts의_canonical이_원문에_실제로_존재한다() -> None:
    """원문에 없는 리터럴은 변환문에서도 찾을 수 없어 평가가 항상 실패한다.

    accept(허용 변형)는 변환문에서만 나오는 표기이므로 원문 존재를 요구하지 않는다.
    """
    violations = [
        (document.id, fact.canonical)
        for document in DOCUMENTS
        for fact in document.required_facts
        if fact.canonical not in document.source_text
    ]
    assert violations == []


def test_required_facts에_마스킹_대상_패턴이_없다() -> None:
    """전화·이메일·계좌 등은 플레이스홀더로 치환되므로 팩트로 쓸 수 없다.

    accept 변형도 같은 이유로 검사한다.
    """
    violations = [
        (document.id, literal)
        for document in DOCUMENTS
        for fact in document.required_facts
        for literal in (fact.canonical, *fact.accept)
        if mask_text(literal).items
    ]
    assert violations == []


def test_required_facts가_마스킹_후에도_원문에_남는다() -> None:
    """개인정보 바로 옆 리터럴이 마스킹 구간에 삼켜지지 않는지 확인한다."""
    violations = [
        (document.id, fact.canonical)
        for document in DOCUMENTS
        for fact in document.required_facts
        if fact.canonical not in mask_text(document.source_text).masked_text
    ]
    assert violations == []


def test_required_facts에_치환_대상_표현이_들어_있지_않다() -> None:
    """팩트 안에 치환 키가 있으면 모델이 그 자리를 바꿔 팩트가 통째로 사라진다.

    실제로 "아이행복카드"가 "이행"을 품고 있어 사전 확충 때 걸러 냈다. 사전을 넓힐
    때마다 이 검사가 같은 사고를 미리 잡는다(문서 쪽이 아니라 사전 쪽을 고칠 것).
    """
    violations = [
        (document.id, literal, word)
        for document in DOCUMENTS
        for fact in document.required_facts
        for literal in (fact.canonical, *fact.accept)
        for word in DIFFICULT_WORD_REPLACEMENTS
        if word in literal
    ]
    assert violations == []


def test_채점_대상_표현이_더_긴_낱말_안에_박혀_있지_않다() -> None:
    """복합어·제도 용어 안에 박힌 키는 모델이 옳게 써도 위반으로 세어진다.

    실측 사례: "소득인정액"의 '정액', "정부양곡"의 '부양', "특별지원"의 '별지'.
    셋 다 골든셋 LLM 평가에서 통과율을 0.15 떨어뜨렸다. 앞 글자가 한글이면 더 긴
    낱말의 일부라는 뜻이다(뒤에 붙는 조사·어미는 정상이므로 보지 않는다).
    문맥 판단이 필요한 낱말은 PROMPT_ONLY_WORDS로 내려 채점에서 빼는 것이 답이고,
    문서를 고치는 것이 아니다.
    """
    scored = [word for word in DIFFICULT_WORD_REPLACEMENTS if word not in PROMPT_ONLY_WORDS]
    violations: list[tuple[str, str, str]] = []
    for document in DOCUMENTS:
        for word in scored:
            for match in re.finditer(re.escape(word), document.source_text):
                start = match.start()
                if start == 0 or not re.match(r"[가-힣]", document.source_text[start - 1]):
                    continue
                wider = re.search(
                    r"[가-힣]*" + re.escape(word), document.source_text[: match.end()]
                )
                assert wider is not None
                # "미제출"처럼 넓은 쪽도 사전 키면 의도한 중첩이다(사전 큐레이션 규칙 3).
                if wider.group() not in DIFFICULT_WORD_REPLACEMENTS:
                    violations.append((document.id, word, wider.group()))
    assert violations == []


def test_허용_변형이_canonical과_다르다() -> None:
    """canonical을 그대로 accept에 넣으면 판정이 느슨해진 것처럼 보여 오해를 부른다."""
    violations = [
        (document.id, fact.canonical)
        for document in DOCUMENTS
        for fact in document.required_facts
        if fact.canonical in fact.accept or len(set(fact.accept)) != len(fact.accept)
    ]
    assert violations == []
