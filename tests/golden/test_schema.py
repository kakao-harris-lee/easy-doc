"""골든셋 문서 자체를 기계적으로 검증한다 (LLM 호출 없음, 기본 실행 포함).

여기서 걸러지지 않은 문서 결함은 LLM 평가 단계에서 원인 불명의 실패로 나타난다.
특히 required_facts에 마스킹 대상 패턴이 섞이면 팩트 보존 검사가 영구히 실패한다.
실패 출력에는 문서 id와 위반 리터럴만 남기고 본문은 남기지 않는다.
"""

from app.easyread.goldenset import GoldenDocument, load_documents
from app.easyread.style_rules import check_style, find_difficult_words
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
    """PROMPT_ONLY_WORDS(상기·하기)는 채점 제외라 개수에 포함되지 않는다."""
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


def test_허용_변형이_canonical과_다르다() -> None:
    """canonical을 그대로 accept에 넣으면 판정이 느슨해진 것처럼 보여 오해를 부른다."""
    violations = [
        (document.id, fact.canonical)
        for document in DOCUMENTS
        for fact in document.required_facts
        if fact.canonical in fact.accept or len(set(fact.accept)) != len(fact.accept)
    ]
    assert violations == []
