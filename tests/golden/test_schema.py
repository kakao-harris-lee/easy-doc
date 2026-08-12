"""골든셋 문서 자체를 기계적으로 검증한다 (LLM 호출 없음, 기본 실행 포함).

여기서 걸러지지 않은 문서 결함은 LLM 평가 단계에서 원인 불명의 실패로 나타난다.
특히 required_facts에 마스킹 대상 패턴이 섞이면 팩트 보존 검사가 영구히 실패한다.
실패 출력에는 문서 id와 위반 리터럴만 남기고 본문은 남기지 않는다.

**혼합 코퍼스**: 합성 문서(우리가 만든 것)와 실수집 문서(공공기관 배포본)가 함께 있다.
기준이 갈리는 것은 우리가 통제할 수 있는 속성뿐이다 — 본문 길이와 개인정보 포함 여부는
합성 문서에서만 우리 마음대로 정할 수 있으므로 출처별로 다른 기준을 쓰고, 팩트 개수·
카테고리·어려운 표현처럼 **평가의 변별력을 좌우하는 기준은 출처와 무관하게 같다**.
실수집이라는 이유로 느슨해지면 통과율이 코퍼스 구성에 따라 움직인다.
"""

import pytest
from pydantic import ValidationError

from app.easyread.goldenset import GoldenDocument, load_documents
from app.easyread.style_rules import (
    DIFFICULT_WORD_REPLACEMENTS,
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
# 합성 문서 상한 — 우리가 길이를 정해 쓰므로 좁게 유지한다.
MAX_SYNTHETIC_SOURCE_CHARS = 1500
# 실수집 문서 상한. 실제 공공 안내문은 2~4천 자가 예사라 합성 기준을 그대로 들이대면
# 코퍼스가 "짧은 문서만 모은 셋"으로 편향된다. 대신 변환 상한을 넘길 수는 없다 —
# 정책 원본은 app/services/documents.py의 MAX_CONVERTIBLE_CHARS(4,000)이고, 그 위는
# 변환 자체가 거부되어 평가가 실력과 무관하게 하드 실패한다.
# 여기서 import하지 않는 이유는 문서 파일만 보는 검사 층에 서비스(DB·큐) 계층을
# 끌어오지 않기 위해서다(scripts/collect_golden.py도 같은 규칙). 상한 정책이 바뀌면
# 두 곳을 함께 고칠 것.
MAX_COLLECTED_SOURCE_CHARS = 4000

# 카테고리 통제 어휘 — 자유 입력이면 "주거 공고문"/"주거 안내문"처럼 축이 갈라져
# 주제 분포 집계가 깨진다. 새 주제를 넣을 때 이 목록을 함께 갱신한다.
# "공고문"은 안내문과 문체 축이 다르다(고시·공고체). 실수집본에 공고·공모가 많아
# 별도 값으로 둔다 — "공고"가 아니라 "공고문"으로 통일한다.
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
        "공고문",
    }
)

# 마스킹 대상을 품은 합성 문서의 **정확한 분포**. 2026-08-12 범주 축소(5종 → 2종)
# 전에는 합성 20건이 전부 여기 들어 있었다(마스킹 항목 31건). 전화번호·이메일이 범위에서
# 빠지면서 2건만 남았고, 그 사실이 어떤 검사도 깨지 않은 채 지나갔다. 그래서 **줄어든
# 결과 자체를 기대값으로 적어** 다음에 같은 일이 조용히 일어나지 못하게 한다.
PII_BEARING_SYNTHETIC_DOCUMENTS: dict[str, frozenset[MaskCategory]] = {
    "003": frozenset({MaskCategory.RRN}),  # 주민등록-사실조사
    "011": frozenset({MaskCategory.CARD}),  # 문화누리카드
}

# 문서별 마스킹 경로 확인용 가짜 값. 저장하지 않고 사본에만 끼워 넣는다.
MASKING_PROBES: tuple[tuple[MaskCategory, str], ...] = (
    (MaskCategory.RRN, "900101-1234567"),
    (MaskCategory.CARD, "1234-5678-9012-3456"),
)

DOCUMENTS: list[GoldenDocument] = load_documents(DOCUMENTS_DIR)


def _with_probe(source_text: str, probe: str) -> str:
    """본문 한가운데(첫 줄바꿈 뒤)에 가짜 개인정보를 끼운 사본을 만든다.

    끝에 덧붙이면 앞뒤 문맥이 없어 경계 판정을 거의 시험하지 못한다. 문단 사이에 넣어야
    실제 안내문에서 개인정보가 나타나는 자리와 같은 조건이 된다.
    """
    cut = source_text.find("\n")
    head, tail = (
        (source_text[: cut + 1], source_text[cut + 1 :]) if cut != -1 else (source_text, "")
    )
    return f"{head}담당자 확인용 {probe} 기재.\n{tail}"


def test_문서가_최소_수량_이상_로드된다() -> None:
    assert len(DOCUMENTS) >= MIN_DOCUMENTS


def test_id가_중복되지_않는다() -> None:
    ids = [document.id for document in DOCUMENTS]
    assert len(set(ids)) == len(ids)


def test_문서가_서로_다르다() -> None:
    """템플릿 복붙이면 평가 변별력이 없다 — 제목·본문이 모두 달라야 한다."""
    assert len({document.title for document in DOCUMENTS}) == len(DOCUMENTS)
    assert len({document.source_text for document in DOCUMENTS}) == len(DOCUMENTS)


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
    """너무 짧으면 변환 난도가 없고, 너무 길면 토큰 한도·지연 측정이 왜곡된다.

    상한만 출처에 따라 갈린다 — 길이는 합성 문서에서만 우리가 정할 수 있다.
    """
    out_of_range = [
        document.id
        for document in DOCUMENTS
        if not MIN_SOURCE_CHARS
        <= len(document.source_text)
        <= (MAX_SYNTHETIC_SOURCE_CHARS if document.synthetic else MAX_COLLECTED_SOURCE_CHARS)
    ]
    assert out_of_range == []


def test_어려운_표현이_문서마다_충분히_들어_있다() -> None:
    """출처와 무관한 기준 — 어려운 표현이 없는 문서는 변환할 것이 없어 변별력이 없다.

    PROMPT_ONLY_WORDS(상기·하기 등)는 채점 제외라 개수에 포함되지 않는다.
    """
    insufficient = [
        document.id
        for document in DOCUMENTS
        if len(find_difficult_words(document.source_text)) < MIN_DIFFICULT_WORDS
    ]
    assert insufficient == []


def test_합성_문서에_마스킹_범주_전체가_들어_있다() -> None:
    """마스킹 파이프라인 회귀를 잡으려면 개인정보가 든 문서가 필요하다 — 합성 문서의 몫이다.

    2026-08-12 범주 축소(5종 → 2종) 전에는 검사가 둘이었다.

    - **A**: 합성 문서는 **하나하나가** 개인정보를 품는다
    - **B**: 골든셋 **전체**의 범주 합집합이 전 범주를 덮는다

    지금 이 검사는 **C**(합성 집합의 범주 합집합 = 전 범주)다. 합성 집합은 골든셋 전체의
    부분집합이므로 **C ⟹ B**이고, 그 방향으로는 옛 검사보다 강화됐다. **그러나 A는
    대체 없이 사라졌다** — 축소 전 합성 20건 중 18건은 전화번호·이메일로 A를 채우고
    있었고 그 두 범주가 빠지면서 마스킹 대상을 품은 합성 문서가 `003`·`011` 둘만 남았다
    (마스킹 항목 31건 → 2건, PII 보유 합성문서 20/20 → 2/20 실측).

    A를 원형 그대로 되살릴 수는 없다. 18개 문서에 주민등록번호를 심으면 안내문으로서
    부자연스러워지고 `source_text`가 바뀌어 팩트 보존·골든셋 통과율까지 함께 움직인다.
    **그래서 A를 복원하는 대신 잃은 것을 명시적으로 만들었다** — 아래 두 검사가 그 몫이다.

    - `test_마스킹_대상을_품은_합성_문서가_명시된_2건뿐이다`: 축소 결과를 값으로 못박아
      "조용히 사라진 상태"를 없앤다
    - `test_합성_문서마다_2종_마스킹_경로가_실제로_돈다`: 파일을 건드리지 않는 주입본으로
      **문서별** 보장을 되살린다

    실수집 문서는 마스킹을 마친 뒤 편입되므로 0건이 정상이고, 여기서 요구하면 편입
    절차(마스킹 선행)를 지킨 문서가 오히려 걸린다. 플레이스홀더가 남아 있는지도
    검사하지 않는다 — 애초에 개인정보가 없는 공개 배포용 안내문이 흔하다.

    한 범주라도 빠지면 그 패턴의 회귀를 골든셋 평가가 잡지 못한다.
    """
    covered = {
        item.category
        for document in DOCUMENTS
        if document.synthetic
        for item in mask_text(document.source_text).items
    }
    assert covered == set(MaskCategory)


def test_마스킹_대상을_품은_합성_문서가_명시된_2건뿐이다() -> None:
    """축소로 잃은 것을 값으로 못박는다 — 위 검사(C)가 삼킨 A의 소실을 눈에 보이게 한다.

    C는 "합집합이 전 범주를 덮는가"만 묻기 때문에, 마스킹 대상을 품은 문서가 20건이든
    2건이든 똑같이 통과한다. 그래서 축소가 통과 상태를 바꾸지 않은 채 지나갔다.
    여기서는 **분포 자체**를 고정한다. 이 검사가 깨지는 두 방향이 모두 알림이다.

    - 줄어드는 쪽: `003`·`011`에서 개인정보가 빠지면 골든셋에 남은 마지막 실사용 표본이
      사라진다. C만으로는 한 범주가 통째로 빠져야 겨우 걸린다
    - 늘어나는 쪽: 새 문서가 개인정보를 품고 들어오면 편입 절차(마스킹 선행)를 밟았는지
      확인해야 한다. 조용히 늘지 못하게 막는다

    기대값을 고칠 때는 왜 분포가 달라졌는지를 커밋 메시지에 남길 것.
    """
    found: dict[str, frozenset[MaskCategory]] = {}
    for document in DOCUMENTS:
        if not document.synthetic:
            continue
        categories = frozenset(item.category for item in mask_text(document.source_text).items)
        if categories:
            found[document.id] = categories
    assert found == PII_BEARING_SYNTHETIC_DOCUMENTS


def test_합성_문서마다_2종_마스킹_경로가_실제로_돈다() -> None:
    """옛 검사 A의 **문서별** 보장을 파일을 건드리지 않고 되살린다.

    `source_text`를 실제로 고치면 안 된다 — 본문이 바뀌면 required_facts·어려운 표현·
    골든셋 통과율이 함께 움직여, 마스킹 회귀를 잡으려다 평가 기준을 흔든다. 대신 문서
    문맥 한가운데에 가짜 2종을 끼워 넣은 **사본**을 만들어 문서마다 확인한다.

    A가 실제로 지켜 주던 것은 "각 문서의 문맥에서 마스킹이 돈다"이지 "파일에 개인정보가
    저장돼 있다"가 아니었다. 주입본은 그 성질을 그대로 유지하면서 저장은 하지 않는다.
    복원(`restores_input`)까지 함께 보는 이유는 마스킹이 문맥을 과잉 잠식하면 내보내기가
    잘못된 원문을 꽂기 때문이다.
    """
    failures: list[tuple[str, str, str]] = []
    for document in DOCUMENTS:
        if not document.synthetic:
            continue
        for category, probe in MASKING_PROBES:
            text = _with_probe(document.source_text, probe)
            result = mask_text(text)
            if probe in result.masked_text:
                failures.append((document.id, category.value, "미마스킹"))
                continue
            if category not in {item.category for item in result.items}:
                failures.append((document.id, category.value, "범주 오판정"))
            restored = result.masked_text
            for item in result.items:
                restored = restored.replace(item.placeholder, item.original.get_secret_value(), 1)
            if restored != text:
                failures.append((document.id, category.value, "복원 실패"))
    assert failures == []


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
    """주민등록번호·카드번호는 플레이스홀더로 치환되므로 팩트로 쓸 수 없다.

    2026-08-12 축소로 전화번호·이메일·계좌번호는 이 검사에 걸리지 않는다. 본문에서도
    가려지지 않으므로 팩트로 남아도 보존 검사는 깨지지 않는다 — 검사가 느슨해진 것이
    아니라 치환되는 대상 자체가 줄었다. accept 변형도 같은 이유로 함께 검사한다.
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


def test_허용_변형이_canonical과_다르다() -> None:
    """canonical을 그대로 accept에 넣으면 판정이 느슨해진 것처럼 보여 오해를 부른다."""
    violations = [
        (document.id, fact.canonical)
        for document in DOCUMENTS
        for fact in document.required_facts
        if fact.canonical in fact.accept or len(set(fact.accept)) != len(fact.accept)
    ]
    assert violations == []
