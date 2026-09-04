# P0-4 — 문단 단위 대응과 재변환 설계

- 작성 2026-09-04. 개정 2026-09-04(심사 반영 — 버전 순서·호출 예산·적용 규칙·에디터 형태 확정)
- 기준: `docs/master-plan.md` §4.1 P0-4·§3.2·§9, `DESIGN.md` §6.4·§6.5,
  `docs/kotlin-redevelopment-backlog.md` §1.3, `contracts/easy-doc-v1.yaml` 2.10.0
- 목표: 좌측 원본 / 우측 변환 결과의 **문단 단위 대응 하이라이트**, 직접 수정, **문단 단위 재변환**

## 0. 결정 게이트

아래 두 항목만 **사용자 확인 대기**다. 나머지는 모두 이 문서에서 **확정**이며 선택지로 남기지 않는다.

1. **재변환 호출 예산 기본값 — 사용자 확인 대기.** `easydoc.reconversion.call-budget` 기본값 **20회**(문서 1건당 LLM 호출 수). 비용 정책이라 숫자는 사용자가 정한다.
2. **master-plan §3.2 문구 개정 승인 — 사용자 확인 대기.** §4의 개정문을 그대로 반영할지.

**S1~S3는 이 게이트와 무관하게 착수한다. S4·S5는 두 항목이 확인되기 전에 시작하지 않는다** — 유료 호출의 상한을 정하는 일이라, 확인 없이 구현하면 되돌릴 대상이 코드가 아니라 나간 비용이다.

## 1. 현재 상태에서 이미 참인 것 (읽어서 확인)

이 계획의 절반은 새로 만드는 것이 아니라 **이미 있는 것에 이름을 붙이는 일**이다.

1. **원문은 이미 단위로 쪼개져 저장된다.** `ingest/ExtractedTextBuilder`가 블록(문단·표 셀·페이지)을 줄 단위로 trim 하고 빈 줄을 버린 뒤 `\n` 하나로 잇는다 — `documents.source_text_encrypted`의 **한 줄 = 원본 구조 단위 하나**이고, 문서는 등록 후 불변이라 그 줄 번호는 영구히 안정적이다.
2. **내보내기는 이미 그 줄 차례로 자리를 맞춘다.** `export/TextUnitWalk`가 추출기와 같은 순회로 DOM 노드를 모으고 `export/ReflectionPlan.planOf`가 **차례(ordinal)로만** 짝짓는다. 그 KDoc이 내용 유사도 정렬을 이미 기각해 뒀다 — 변환은 다시 쓰는 일이라 유사도가 가장 필요한 곳에서 가장 약하다.
3. **마스킹은 결정적이고 줄 수를 바꾸지 않는다** — `privacy/Masking.maskParts`에 난수가 없고 범주별 카운터가 문서 순서로 붙으며 치환 값에 개행이 없다. 그리고 **`easy_text`/`edited_text`는 평문 본문 하나뿐**이라 내보내기가 `exportParagraphs`로 `\n`·`\r\n`에서 쪼갠다. 검수 화면은 `<textarea>` 하나다.

## 2. 결정 1 — 구조 모델: 저장하지 않고 유도한다

- **원본 단위** = 저장된 추출 원문을 `\n`으로 쪼갠 줄. id = **0 기반 정수 색인** — 값이 실제로
  색인이므로 불투명 토큰을 만들지 않는다.
- **쉬운 글 단위** = `edited_text ?? easy_text`를 `\n`으로 쪼갠 줄. `split('\n')` ↔ `join('\n')`이
  **무손실 왕복**이라 화면을 단위 목록으로 바꿔도 저장되는 문자열이 한 글자도 달라지지 않는다.
- **`conversion_segments` 표를 만들지 않고 새 암호문 열도 만들지 않는다.** 대응은 (원문, 본문)의
  순수 함수이고 둘은 이미 저장돼 있다. 저장하면 ⑴ 검수 저장마다 즉시 낡는 두 번째 진실이 생기고
  ⑵ `EncryptedField` 인구조사·`EnvelopeColumnWriteGuardTest`·키 교체 배치가 같은 변경 단위에 딸려
  온다. **기존 blob-only 행에 backfill도 필요 없다.**
- **프롬프트에 마커를 넣지 않는다.** ⑴ `Prompts.kt`는 실측 튜닝된 큐레이션 데이터이고 지나가다
  손대지 않는다는 규약이 파일 머리에 있다 ⑵ 프롬프트가 **줄 나누기를 적극 지시**하므로 1:N이
  정상이고 마커로도 1:1이 되지 않는다 ⑶ 마커가 `easy_text`에 남으면 버그 한 번이 `[[S12]]`를 배포
  문서에 싣는다 ⑷ 마커 유실을 고칠 3번째 호출 자리가 「최대 2회」에 없다.

### 정렬 알고리즘 — patience 방식 앵커 + 차례 보간

차례만 쓰면 대응이 **대부분의 문서에서 틀린다**(문단을 여러 줄로 나누는 것이 규칙상 정상이라 그 뒤가
전부 밀린다). 그래서 다시 쓰기를 **견뎌 살아남는 토큰**만 앵커로 쓴다.

1. 앵커 = ⑴ 마스킹 자리표시자(`[[…]]` — 개수 보존이 프롬프트 규칙이자 검사 대상) ⑵
   `core/easyread/FactPreservation`의 사실 추출 결과(숫자·날짜·시각·금액·백분율·연락처·URL).
   파이프라인이 보정 호출로 **보존을 강제**하는 대상이라 남아 있을 확률이 가장 높다.
   `extractFacts`를 `private` → `internal`로 넓혀 같은 모듈에서 재사용한다(공개 API 확대 아님).
2. 원본·본문 **양쪽에서 유일한** 앵커만 tie point 후보로 쓴다(patience diff의 unique-line 발상).
3. 후보를 쉬운 글 색인 순으로 정렬하고 **원본 색인이 비감소(non-decreasing)인 최장 부분수열**을
   고른다 — 강증가가 아닌 것이 1:N을 허용하는 자리다. 동점이면 **쉬운 글 색인이 작은 쪽**을 남긴다
   (결정성). O(k log k)이므로 셀 예산이 필요 없다.
4. tie point 사이 구간은 비례 배분한다: 구간의 쉬운 글 단위 `m`개, 원본 단위 `n`개일 때 구간 내
   `j`번째 쉬운 글 단위 → 구간 내 `floor(j * n / m)`번째 원본 단위.
5. 앵커로 묶인 단위는 `high`, 보간된 단위는 `low`. 앵커가 없으면 전부 `low`로 떨어져 순수 차례
   정렬과 같아지고, 그 사실을 숨기지 않는다.

**화면은 `high`만 대응으로 주장하고 `low`는 「대응을 확인하지 못했습니다」로 표시한다.** 실제
(원문, 변환문) 쌍 코퍼스가 없어(`data/golden/conversions/*.txt`는 사실 목록이지 변환 결과가 아니다)
정렬 정확도를 무료로 측정할 수 없다 — 정확도를 약속하는 대신 **모르는 것을 모른다고 말하는 모양**
으로 설계한다.

## 3. 결정 2 — 대응 데이터 모양과 계약 버전 순서

`ConversionResponse`에 필드 하나를 더한다. 완료 전이거나 본문이 없으면 `null`이고, 별도 사유 필드를
두지 않는다 — `status`와 `easy_text`가 이미 사유를 말한다.

```yaml
segment_map:                      # object | null
  source_unit_count: 12
  easy_unit_count: 15
  units:                          # easy 단위 기준, easy_unit_count 개, 색인 순서 그대로
    - easy_unit_index: 0
      source_unit_indexes: [0]    # 0개 이상. 빈 배열 = 대응을 찾지 못했다
      confidence: high            # high | low
  compliant_source_units: [3, 7]  # 스타일 게이트를 이미 통과한 원본 단위 (S4에서 추가)
```

- 배열 이름은 `_indexes`로 통일한다 — 재변환 요청의 `easy_unit_indexes`와 같은 어형이어야 프런트가
  두 이름을 헷갈리지 않는다.
- 본문(원문·쉬운 글 텍스트)은 **싣지 않는다.** 원문은 화면이 이미
  `GET /documents/{document_id}/source`로 받고 본문은 같은 응답에 있다. 역방향(원본 → 쉬운 글)과
  「모델이 빠뜨린 원본 단위」는 클라이언트가 묶어서 만든다.
- **`updateConversion` 요청은 바뀌지 않는다** — 지금처럼 `{ edited_text }` 하나이고 값은 단위를
  `\n`으로 이은 문자열이다. 제어문자 제거·20,000자 판정이 그대로 적용되고 응답이 GET과 같은
  스키마라 저장 직후 새 지도가 함께 온다.

### 계약 버전 순서 (P0-5 레인과 공유 — 확정)

**⑴ P0-5 사전 조회 API = 2.11.0(마이그레이션 없음) → ⑵ P0-4 `segment_map`(S2) = 2.12.0
(마이그레이션 없음) → ⑶ P0-4 재변환(S4) = 2.13.0 + Flyway V10.**

- P0-5의 **조건부 프롬프트 주입**은 「착수 시점의 다음 빈 버전」을 가져간다. 번호를 예약하지 않는다.
- **S1·S2는 P0-5 S1~S3와 병행해도 된다** — 만지는 파일이 겹치지 않는다(P0-4는
  `core/segment`·`ConversionQueryService`·`ConversionDtos`, P0-5는 `core/dictionary` 계열).
  겹치는 파일은 `contracts/easy-doc-v1.yaml`과 `frontend/src/api/types.ts` 둘뿐이고 직렬이다.
- **S4는 P0-5의 계약 bump이 들어온 뒤에 번호를 잡는다**(또는 그 반대 — 먼저 머지되는 쪽이 그
  번호를 가져간다). 실행자는 착수 직전에 `grep -n '^  version:' contracts/easy-doc-v1.yaml`(info)
  과 `grep -n -m1 '^  - version:' …`(x-changelog 최상단)이 일치하는지 보고 **다음 minor**를 쓴다.

## 4. 결정 3 — 문단 재변환

`POST /conversions/{conversion_id}/segments/{source_unit_index}/reconvert`, 요청 본문
`{ easy_unit_indexes: [int], easy_text_fingerprint: string }`. `source_unit_index`는 경로가 지고
본문이 되풀이하지 않는다(요청 전체로 보면 세 값이 함께 간다). `easy_unit_indexes`는 **클라이언트가
지금 그 원본 단위에 대응시키고 있는 쉬운 글 단위**이고 빈 배열일 수 있다. `easy_text_fingerprint`는
**에디터 현재 본문의 SHA-256 16진 문자열**이다.

- **응답은 후보 텍스트뿐이고 변환 본문에는 아무것도 쓰지 않는다.**
  `{ source_unit_index, easy_unit_indexes, easy_text_fingerprint, candidate_text|null, adopted,
  reject_reason|null, style_issue_count, llm_calls, remaining_call_budget }` — 지문과 색인 배열은
  **받은 값을 그대로 되울린다.** 서버는 그 값으로 아무 판정도 하지 않고, 응답이 어느 편집 상태에
  대한 것인지 클라이언트가 알아보게 하는 것이 전부다.
- **클라이언트 적용 규칙 — 자동 교체는 어떤 경우에도 하지 않는다.** ⑴ 응답 도착 시점의 에디터 본문
  지문이 **여전히 같고** ⑵ 그 대응이 `high`이고 ⑶ `easy_unit_indexes`가 **정확히 한 단위**일 때만
  「바꾸기」를 제시해 그 단위를 갈아 끼운다. 나머지 전부(1:N, N:1, `low`, 빈 배열, 지문 불일치)는
  후보를 **카드**로 보여주고 「이 위치에 넣기」로 캐럿 자리에 삽입한다.
- **동기 처리.** 단위 하나는 짧아 호출이 초 단위이고, 큐로 보내면 새 작업 종류·상태·폴링 계약이
  전부 딸려 온다. `LlmProviderConfiguration`은 프로필 게이트가 없어 api에 이미 `LlmProvider` 빈이
  있다 — `ConvertDocumentUseCase`만 worker 프로필 밖으로 조립하면 된다. 외부 호출은 트랜잭션
  밖이고 동시 실행은 `easydoc.conversion.reconvert-concurrency`로 제한한다.
- **입력은 「문서 전체를 마스킹한 뒤의 n번째 줄」이다.** `maskText(원문 전체)`가 결정적이고 줄 수를
  바꾸지 않으므로 잘라 낸 단위의 자리표시자 번호가 저장된 대응표와 **구성상 일치한다.** 단위만 따로
  마스킹하면 `[[주민등록번호1]]`이 문서의 `[[주민등록번호3]]`과 어긋나 끼워 넣는 순간 자리표시자
  집합이 깨지고 내보내기가 409로 막힌다. 그래서 `ConvertDocumentUseCase`에 **이미 마스킹된 텍스트를
  받는 진입점**을 추가하고(타입이 `MaskedText`라 경계는 그대로) 그 뒤 경로는 한 줄도 바꾸지 않는다.
- **채택 게이트는 사실·자리표시자만 본다.** 후보가 원본 단위의 사실을 빠뜨렸거나 자리표시자를
  잃으면 `adopted: false`와 사유를 낸다(`decideRepairAdoption`을 단위 규모로 재사용). 문체 위반
  수는 숫자로 **보고**만 한다 — 사용자가 손댄 현재 텍스트를 서버가 모르므로 비교 기준이 없다.

### 비용 상한은 요청이 아니라 **LLM 호출 수**로 센다

`easydoc.reconversion.call-budget`(기본 20회 — §0 게이트 1). 재변환 1회가 보정 여부에 따라 1회
또는 2회를 쓰므로, 요청 수로 세면 같은 예산이 실제로 2배까지 벌어진다.

- **예약(호출 전, 트랜잭션 1 — 커밋하고 나간다):**
  `UPDATE conversions SET reconversion_calls = reconversion_calls + 2,
   reconverted_units = reconverted_units || to_jsonb(:unit)
   WHERE id = :id AND <소유 술어> AND reconversion_calls + 2 <= :budget RETURNING reconversion_calls`
  0행이면 **429**. 소유 술어를 같은 문장이 진다(`OwnershipPredicateGuardTest`).
- **정산(호출 후, 트랜잭션 2):** 실제 사용량 `usage.llmCalls`(0·1·2)만큼만 남기고
  `2 - usage.llmCalls`를 되돌린다. 보정을 부르지 않았으면 1회가 환불되고, 첫 호출이 provider
  오류로 실패해도 같은 규칙이다. **실패 방향은 보수적이다** — 예약 커밋 뒤 프로세스가 죽으면
  예약이 남아 최대 2회를 더 쓴 것으로 세고, 예산을 **덜 세는 일은 없다.**
- **429는 공유 컴포넌트를 쓰지 않는다.** `components/responses/TooManyRequests`는 60초 재발송
  쿨다운 전용이고 `Retry-After`를 필수로 요구하며 「이 컴포넌트를 선언하는 오퍼레이션은 하나뿐」
  이라고 스스로 적어 뒀다. 재변환에는 쿨다운도 재시도 시각도 없다 — 이 오퍼레이션 전용 429에
  `{ detail, remaining_call_budget }` 스키마를 새로 둔다.
- **멱등하지 않다** — 매 호출이 새 결과를 만든다. 클라이언트는 자동 재시도하지 않고 비용 방벽은
  예약 하나다. 계약에 그 사실을 적는다.
- `conversions.reconverted_units`(V10, **평문** jsonb — 색인만 담으므로 `missing_placeholders`와
  같은 판단)는 **요청 기록**이지 채택 기록이 아니다(채택은 클라이언트가 하므로 서버가 볼 수 없다).

**master-plan §3.2 개정문(그대로 반영 — §0 게이트 2):**

> 자동 변환 1건 = LLM 호출 최대 2회(변경 없음). 사용자 재변환은 문서당 호출 예산(기본 20회,
> 구성값) 안에서만 실행되며, 예산은 요청이 아니라 실제 호출 수로 예약·정산한다.

## 5. 결정 4 — 내보내기(§6.5)와 품질 항목(backlog §1.3)의 경계

- **내보내기는 이번에 바꾸지 않는다.** 지도는 `planOf`를 개선할 여지를 만들지만(1:N을 알면 원본
  문단 하나에 여러 줄을 넣어 `partial`이던 문서가 `available`이 된다) 그것은 **이미 출시된 기능의
  동작 변경**이고 DOCX의 문단 안 줄바꿈은 `w:br` 삽입이 필요해 `TextUnit.rewrite`를 고쳐야 한다.
  회귀 가드는 §6 G다. 단위 정의를 내보내기 순회와 같은 차례로 맞춰 두었으므로 나중에
  `planOf(units, lines, segmentMap)`으로 바꾸는 것이 후속이다.
- **표·목록 구조 보존: 범위 밖.** 구조를 늘리면 단위 모델이 함께 바뀐다. 이번에는 단위를 「추출
  원문의 한 줄」로 **고정**하고 더 풍부한 타입을 만들지 않는다.
- **이미 쉬운 글인 입력의 저변경 보장: 재변환 자리에서만 지킨다.** 자동 변환을 단위별로 쪼개
  「통과한 문단은 건드리지 않기」를 하지 않는다(호출 상한과 프롬프트 설계가 문서 전체를 전제한다).
  대신 `segment_map.compliant_source_units`로 화면이 **호출 전에** 「이 문단은 이미 규칙을
  통과합니다 — 다시 쓰면 나빠질 수 있습니다」로 경고한다. LLM 비용이 0인 경고다. `checkStyle`을
  조회마다 원문 전체에 돌리는 CPU가 새로 생기므로 S4에서 20,000자 입력으로 실측해 기록한다.

## 6. 슬라이스와 수용 기준

**S1 — core 단위 분할과 정렬 (M, 백엔드만, 계약 변경 없음)**
- 파일: `core/segment/{SourceUnits,SegmentAlignment}.kt`,
  `core/easyread/FactPreservation.kt`(`extractFacts` → `internal`), 각 테스트.
- 수용 기준(입력 → 기대):
  - **A1 왕복:** `""`, `"a"`, `"a\n"`, `"a\n\nb"`, `"a\r\nb"`, `"\n"` → `join(split(x)) == x`.
  - **A2 앵커 없음:** 원본 2단위 / 쉬운 글 3단위, 공통 사실 0개 → 세 단위 모두 `low`,
    `source_unit_indexes` = `[0]`, `[0]`, `[1]`(§2 보간식 `floor(j*n/m)`).
  - **A3 1:N 분할:** 원본 0에 `3월 2일`·`3월 31일`, 원본 1에 `주민센터`. 쉬운 글 0·1이 두 날짜를,
    2가 `주민센터`를 나눠 가짐 → 세 단위 모두 `high`, `[0]`, `[0]`, `[1]`.
  - **A4 N:1 병합:** 원본 0에 `3월 2일`, 원본 1에 `주민센터`, 쉬운 글 0이 둘을 다 담음 →
    쉬운 글 0 = `[0, 1]`, `high`.
  - **A5 순서 역전:** 앵커 매칭이 `(원본2,쉬운0)`·`(원본1,쉬운1)`·`(원본0,쉬운2)` → 비감소
    부분수열 길이 1이므로 **정확히 한 단위만 `high`**, 나머지는 `low` 보간.
  - **A6 전사성·결정성:** 모든 쉬운 글 단위가 `units`에 **정확히 한 번** 나오고, 같은 입력을 두 번
    정렬하면 결과가 동일하다.
- **A7 순회 대조(값어치 높음):** DOCX·HWPX fixture마다 `split(추출 원문).size == TextUnitWalk 단위
  수`. 어긋나면 **오늘의 내보내기 정렬에 잠재 결함이 있다는 신호**다.
- **`Prompts.kt` 문자열을 한 글자도 바꾸지 않는다** → `PromptTextSnapshotTest`·골든 기준선 무영향.

**S2 — `readConversion`에 `segment_map` (M, 계약 2.12.0, 마이그레이션 없음)**
- 파일: `contracts/easy-doc-v1.yaml`, `core/document/ConversionView.kt`,
  `application/document/ConversionQueryService.kt`(+`DocumentRepository.findOwnedSource` 협력자),
  `api/document/ConversionDtos.kt`, `frontend/src/api/types.ts`, Kotlin 계약 테스트.
- 수용 기준: `pending`·`processing`·`failed` → `segment_map: null`(폴링 중 비용 0). `done` →
  `easy_unit_count == split(edited_text ?? easy_text).size`. 남의 변환 404 판정 불변.
- **G 내보내기 회귀 가드:** 기존 `ConversionQueryServiceTest`·`ConversionExportContractTest`의 모든
  케이스에서 `format_preservation.status`와 `details`가 **바뀌지 않는다.**

**S3 — 검수 화면의 단위 대응 (M, 프런트만)**
- 파일: `frontend/src/components/{ReviewEditor,SourceTextPanel}.tsx`, 새
  `SegmentedResultEditor.tsx`, 각 테스트, `frontend/src/a11y.test.tsx`.
- 원문 패널은 읽기 전용 단위 목록(클릭 가능), 결과 패널은 **쉬운 글 단위마다 `<textarea>` 하나**.
  **단위 수가 200을 넘으면 지금의 단일 textarea로 내려앉고 배너가 사유를 적으며 재변환을 제공하지
  않는다**(200은 구성 상수로 두지만 값은 이 문서가 정한다). 파일럿 표본은 전부 3,076자 이하
  (≈50단위)라 일상 경로는 목록 쪽이다.
- 단위 나누기·합치기: 단위 안에서 **Enter → 분할**(뒤 단위 번호가 밀린다), **맨 앞에서 Backspace →
  앞 단위와 병합**. 지도는 이은 본문에서 **클라이언트가 다시 계산**하고 왕복은 무손실로 남는다.
- 수용 기준: 저장 문자열이 오늘과 동일(`join('\n')` 왕복). `low` 대응은 하이라이트하지 않고 「대응
  확인 불가」로 표시. 201단위 입력 → 내려앉기 배너와 재변환 버튼 없음.
- 문서: `DESIGN.md` §6.4에 「결과 패널은 단위 목록」·내려앉기 규칙·분할/병합 키를 개정으로 추가.

**S4 — 재변환 엔드포인트 (M, 계약 2.13.0 + V10) — §0 게이트 확인 후 착수**
- 파일: `V10__conversion_reconversion_budget.sql`(`reconversion_calls integer NOT NULL DEFAULT 0`
  + CHECK ≥ 0, `reconverted_units jsonb NOT NULL DEFAULT '[]'`),
  `application/conversion/ConvertDocumentUseCase.kt`(마스킹된 입력 진입점), 새
  `application/conversion/ReconvertSegmentUseCase.kt`,
  `infrastructure/document/JdbcConversionRepository.kt`(예약·정산 UPDATE), api 프로필 조립,
  `api/document/…Controller/Dtos`, `contracts/easy-doc-v1.yaml`, `frontend/src/api/{types,client}.ts`.
- 수용 기준(입력 → 기대): 예산 잔량 1 → **429 + `remaining_call_budget: 1`이고 LLM 호출 0회** /
  남의 변환 → 404 / 완료 전 → 409 / 색인 범위 밖 → 422 / 보정 불필요 응답 → `llm_calls: 1`이고
  `reconversion_calls` 증가분이 **1** / 자리표시자 있는 단위 → 후보의 자리표시자 번호가 문서
  대응표와 같음 / 사실 유실 후보 → `adopted: false` / 지문·색인 배열이 요청과 **동일하게 되울림**.
- 실제 유료 호출 없이 fake provider로 검증. `./gradlew build` 통과. §5 CPU 실측 기록.

**S5 — 재변환 UI (S, 프런트만) — §0 게이트 확인 후 착수**
- 단위별 버튼, 이미 통과한 단위 경고, 남은 호출 예산 표시, 429·거절 사유 문구.
- 수용 기준: **지문 불일치 응답은 어떤 단위도 교체하지 않는다**(카드 + 「이 위치에 넣기」만).
  `high` + 단일 단위에서만 「바꾸기」가 보인다. **StrictMode 이중 렌더와 버튼 연속 두 번 클릭이
  후보를 두 번 삽입하지 않는다.**

**S6 — 내보내기가 지도를 소비 (L, 후속·범위 밖)** — `planOf` 확장, `w:br` 다중 줄 반영,
`format_preservation` 어휘 재검토. 접점만 남기고 하지 않는다.

## 7. 리스크

1. **정렬 정확도를 무료로 측정할 수 없다** — 실제 (원문, 변환문) 쌍 코퍼스가 없다. 완화는 손으로 만든 fixture(§6 A2~A5)와 confidence 노출이고, 유료 실측은 게이트 ⓪의 승인 규칙을 따른다.
2. **추출 줄 수와 내보내기 단위 수의 잠재 불일치**(예: `w:t` 안의 개행). §6 A7이 드러낸다.
3. **api 프로세스가 유료 외부 호출을 하게 된다** — 타임아웃·동시 실행 제한이 있어도 사용자 요청 스레드에서 나가는 첫 LLM 호출이다.
4. **수정률 KPI 오염** — 채택된 후보가 `edited_text`에 사람 편집처럼 섞인다. `reconverted_units`로 지표를 한정하되 **채택 여부는 모른다.**
5. **CPU:** 조회마다 원문 복호화 + 정렬 + (S4부터) 원문 `checkStyle`. 20,000자로 실측해 기록한다.

## 8. 범위 밖

표·목록 구조 보존, 레이아웃 인지 PDF 추출, 내보내기의 지도 소비(S6), 크레딧·결제, RAG 사전
팝업(P0-5), 자동 변환의 단위별 분할, 실제 유료 LLM 측정.

## 9. P0-5(사전 팝업) 레인과의 경계

- **P0-5의 HTTP 계약에는 좌표가 들어가지 않는다.** 사전 조회는 **선택된 문자열만** 받는다.
  `easy_unit_index`나 UTF-16 오프셋을 wire에 실으라는 요구는 **철회한다** — 좌표는 P0-4 에디터
  **안의 클라이언트 상태**이고 서버가 알 이유가 없다.
- 두 레인이 공유하는 파일은 계약과 `frontend/src/api/types.ts` 둘뿐이고 버전 순서는 §3이 정한다.
- 사전 팝업이 「원본의 어느 문단에서 온 용어인지」를 말하려 하면 그 대응이 `high`일 때만 주장한다.
