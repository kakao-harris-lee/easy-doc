# 참조 출력 픽스처

`dictionary/src/easydict/lookup.py`(참조 구현)의 `build_prompt_context()` 출력이다.
골든 코퍼스 58건(`data/golden/documents/*.json`)의 `source_text`마다 한 건씩,
파일명은 문서 id다. 이 중 이식본↔참조 구현 대조에 실제로 쓰이는 것은 고정
52건뿐이다(`DictionaryReferenceContextTest`의 `FROZEN_REFERENCE_DOCUMENT_IDS`,
2026-09-04 사용자 결정으로 동결) — 나머지 6건(`022`·`023`·`047`·`105`·`106`·
`107`, 2026-09-02 이후 승격)은 대조 대상이 아니지만 픽스처는 여기 함께
보관한다.

Kotlin 이식본 테스트가 같은 원문을 자기 구현에 넣고 이 파일과 문자열이 같은지
본다. 이식에서 경계 규칙 하나만 빠져도 문서가 조용히 훼손되는데
(`dictionary/DESIGN.md` §6.7: `CCTV`에서 `CT`가 매칭돼 `C전류 변성기V`가 되는
종류) 사람 눈으로는 안 보이기 때문이다.

58건 중 37건에 잘림 안내줄이 붙는다 — 예산 규칙(`max_terms`/`max_chars`/
`max_chars_ratio`/`min_substitute`)이 코너 케이스가 아니라 과반에서 실제로
도는 경로다.

매칭이 0건이어도 빈 문자열이 아니라 섹션 제목만 있는 208자 골격이 나온다
(2026-09-09 GLOSS 구역 안내 줄 추가로 130자에서 늘었다). 이 안내 줄의 길이는
**문자 예산 판정에서는 빼는데**(같은 날 사용자 결정 — 지시문이지 사전이 찾아낸
낱말 정보가 아니므로, 낱말 항목이 밀려나는 대가를 받아들이지 않는다.
`DictionaryContextLines.kt`의 `budgetedCharCount()`/`lookup.py`의
`_budgeted_char_count()` 참고) 렌더링 자체는 그대로 포함하므로, 위 잘림 안내
건수(37건)는 안내 줄 추가 전과 같다. 이 코퍼스에는 매칭 0건 문서가 없으므로
(최소 8건) 그 경계는 여기서 덮이지 않는다 — core 단위 테스트가 따로 잡는다.

## 뽑은 파라미터

`dictionary/docs/easy-doc-integration.md` §4의 권장값이며 제품이 쓰는 값과 같다.

```
max_terms=40, max_chars=4000, max_chars_ratio=1.0,
min_substitute=5, max_examples=3, gloss_style="sentence"
```

## 재생성

사전을 다시 빌드했거나 위 파라미터를 바꿨으면 저장소 루트에서 아래를 돌리고
diff를 확인한다. **diff가 났다면 참조 구현의 출력이 달라진 것이므로, 픽스처를
덮어쓰기 전에 왜 달라졌는지부터 본다.**

```bash
PYTHONPATH=dictionary/src python3 - <<'PY'
import json, pathlib
from easydict.lookup import EasyDict

out = pathlib.Path("backend-kotlin/infrastructure/src/test/resources/dictionary/reference")
ed = EasyDict.from_index_json("dictionary/dist/easy_dict.index.json")
for path in sorted(pathlib.Path("data/golden/documents").glob("*.json")):
    doc = json.loads(path.read_text(encoding="utf-8"))
    context = ed.build_prompt_context(
        doc["source_text"],
        max_terms=40, max_chars=4000, max_chars_ratio=1.0,
        min_substitute=5, max_examples=3, gloss_style="sentence",
    )
    (out / f"{doc['id']}.txt").write_text(context, encoding="utf-8")
PY
```
