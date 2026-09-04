# P0-4 — 문단 단위 대응과 재변환 설계

- 작성: 2026-09-04. 상태: 설계안(구현 착수 전, 사용자 확인 대기 항목 §8)
- 기준: `docs/master-plan.md` §4.1 P0-4·§3.2 변환 호출 계약·§9, `DESIGN.md` §6.4·§6.5,
  `docs/kotlin-redevelopment-backlog.md` §1.3, `contracts/easy-doc-v1.yaml` 2.10.0
- 목표: 좌측 원본 / 우측 변환 결과의 **문단 단위 대응 하이라이트**, 직접 수정, **문단 단위 재변환**

## 1. 현재 상태에서 이미 참인 것 (읽어서 확인)

이 계획의 절반은 새로 만드는 것이 아니라 **이미 있는 것에 이름을 붙이는 일**이다.

1. **원문은 이미 단위로 쪼개져 저장된다.** `ingest/ExtractedTextBuilder`가 블록(문단·표 셀·
   페이지)을 줄 단위로 trim 하고 빈 줄을 버린 뒤 `\n` 하나로 잇는다. 즉
   `documents.source_text_encrypted`의 **한 줄 = 원본 구조 단위 하나**다. 문서는 등록 후
   불변이라 그 줄 번호는 영구히 안정적이다.
2. **내보내기는 이미 그 줄 차례로 자리를 맞춘다.** `export/TextUnitWalk`가 추출기와 같은 순회로
   DOM 노드를 모으고, `export/ReflectionPlan.planOf`가 **차례(ordinal)로만** 짝짓는다. 그
   KDoc이 내용 유사도 정렬을 이미 기각해 뒀다 — 쉬운 글 변환은 문장을 다시 쓰는 일이라
   유사도가 가장 필요한 자리에서 가장 못 미덥다.
3. **마스킹은 결정적이고 줄 수를 바꾸지 않는다.** `privacy/Masking.maskParts`에 난수가 없고
   범주별 카운터가 문서 순서로 붙으며, 치환 값에 개행이 없다.
4. **`easy_text`/`edited_text`는 평문 본문 하나뿐**이고, 내보내기는 `exportParagraphs`로
   `\n`·`\r\n`에서 쪼갠다. 검수 화면은 `<textarea>` 하나다.

## 2. 결정 1 — 구조 모델: **저장하지 않고 유도한다**

- **원본 단위** = 저장된 추출 원문을 `\n`으로 쪼갠 줄. id = **0 기반 정수 색인**(`source_unit_index`).
  불투명 토큰을 만들지 않는다 — 값이 실제로 색인이므로 색인이라고 적는 것이 사실이다.
- **쉬운 글 단위** = `edited_text ?? easy_text`를 `\n`으로 쪼갠 줄. `split('\n')` ↔ `join('\n')`이
  **무손실 왕복**이라, 화면을 단위 목록으로 바꿔도 저장되는 문자열이 한 글자도 달라지지 않는다.
- **`conversion_segments` 표를 만들지 않고, 새 암호문 열도 만들지 않는다.** 대응은 (원문, 본문)의
  순수 함수이고 둘은 이미 저장돼 있다. 저장하면 ⑴ 검수 저장마다 즉시 낡는 두 번째 진실이 생기고
  ⑵ `EncryptedField` 인구조사·`EnvelopeColumnWriteGuardTest`·키 교체 배치(`EnvelopeRotation`)가
  모두 같은 변경 단위에 딸려 온다. 얻는 것이 없다.
- **기존 blob-only 행에 backfill이 필요 없다.** 유도값이라 옛 변환도 그대로 대응이 나온다.
- **프롬프트에 마커를 넣지 않는다.** 기각 사유 넷: ⑴ `core/easyread/Prompts.kt`는 실측 튜닝된
  큐레이션 데이터이고 지나가다 손대지 않는다는 규약이 파일 머리에 있다 ⑵ 프롬프트가 **줄 나누기를
  적극 지시**하므로(나열은 한 줄에 하나) 1:N이 정상이고 마커로도 1:1이 되지 않는다 ⑶ 마커가
  `easy_text`에 남으면 제거 단계가 하중을 받고, 버그 한 번이 `[[S12]]`를 배포 문서에 싣는다
  ⑷ 마커 유실을 고칠 3번째 호출 자리가 「최대 2회」에 없다.

### 정렬 알고리즘 — patience 방식 앵커 + 차례 보간

차례만 쓰면 대응이 **대부분의 문서에서 틀린다**(모델이 한 문단을 여러 줄로 나누는 것이 규칙상
정상이므로 그 뒤 전부가 한 칸씩 밀린다). 그래서 다시 쓰기를 **견뎌 살아남는 토큰**만 앵커로 쓴다.

1. 앵커 = ⑴ 마스킹 자리표시자(`[[…]]` — 개수 보존이 프롬프트 규칙이자 검사 대상) ⑵
   `core/easyread/FactPreservation`의 사실 추출 결과(숫자·날짜·시각·금액·백분율·연락처·URL).
   이 값들은 파이프라인이 보정 호출로 **보존을 강제**하는 대상이라 남아 있을 확률이 가장 높다.
   `extractFacts`를 `private` → `internal`로 넓혀 같은 모듈에서 재사용한다(공개 API 확대 아님).
2. 원본·본문 **양쪽에서 유일한** 앵커만 tie point 후보로 쓴다(patience diff의 unique-line 발상).
   그 후보들의 최장 증가 부분수열(LIS)이 단조 대응이 된다 — O(k log k), 셀 예산이 필요 없다.
3. tie point 사이의 단위는 차례로 비례 배분한다.
4. 결과의 confidence: 앵커로 묶인 단위는 `high`, 보간된 단위는 `low`.
5. 앵커가 하나도 없으면 전부 `low`로 떨어진다 — 순수 차례 정렬과 같아지고, 그 사실을 숨기지 않는다.

**화면은 `high`만 대응으로 주장한다.** `low`는 「대응을 확인하지 못했습니다」로 표시한다. 실제
(원문, 변환문) 쌍 코퍼스가 없어(`data/golden/conversions/*.txt`는 사실 목록이지 변환 결과가
아니다) 정렬 정확도를 무료로 측정할 수 없다 — 그래서 정확도를 약속하는 대신 **모르는 것을 모른다고
말하는 모양**으로 설계한다.

## 3. 결정 2 — 대응 데이터 모양 (`readConversion`, snake_case)

`ConversionResponse`에 필드 하나를 더한다. 완료 전이거나 본문이 없으면 `null`이다(다른 결과
필드와 같은 규칙이라 별도 사유 필드를 두지 않는다 — `status`와 `easy_text`가 이미 사유를 말한다).

```yaml
segment_map:                     # object | null
  source_unit_count: 12
  easy_unit_count: 15
  units:                         # easy 단위 기준, easy_unit_count 개, 색인 순서 그대로
    - easy_unit_index: 0
      source_unit_indices: [0]   # 0개 이상. 빈 배열 = 대응을 찾지 못했다
      confidence: high           # high | low
```

- 본문(원문·쉬운 글 텍스트)은 **싣지 않는다.** 원문은 화면이 이미
  `GET /documents/{document_id}/source`로 받고, 본문은 같은 응답에 있다. 같은 문자열을 두 자리에
  두면 갈릴 수 있다.
- 대응의 역방향(원본 → 쉬운 글)은 클라이언트가 묶어서 만든다. `source_unit_count`와의 차집합이
  「모델이 빠뜨린 원본 단위」다.
- **편집 중 id 유지:** 서버 지도는 **저장된 본문**을 설명한다. 화면은 불러올 때 단위마다 지역 키를
  발급해 `easy_unit_index`와 묶고, 단위 **안**의 타이핑은 지도를 유지한다. 사용자가 단위를
  나누거나 합치면(단위 수 변경) 그 단위의 대응만 버리고 「저장하면 다시 계산합니다」로 표시한다.
- **`updateConversion` 요청은 바뀌지 않는다.** 지금처럼 `{ edited_text }` 하나이고 값은 단위를
  `\n`으로 이은 문자열이다. 제어문자 제거·20,000자 판정이 그대로 적용된다. 응답이 GET과 같은
  스키마이므로 저장 직후 새 지도가 함께 온다.

## 4. 결정 3 — 문단 재변환

`POST /conversions/{conversion_id}/segments/{source_unit_index}/reconvert` (요청 본문 없음)

- **동기 처리.** 큐를 태우지 않는다: 단위 하나는 짧아 호출이 초 단위이고, 큐로 보내면 새 작업
  종류·상태·폴링 계약이 전부 딸려 온다. `LlmProviderConfiguration`은 프로필 게이트가 없어 api
  프로세스에 이미 `LlmProvider` 빈이 있다 — `ConvertDocumentUseCase`만 worker 프로필 밖으로
  조립하면 된다. 외부 호출은 트랜잭션 밖이고, 동시 실행은
  `easydoc.conversion.reconvert-concurrency`로 제한한다(`ConcurrencyLimitedTextExtractor` 선례).
- **입력은 「문서 전체를 마스킹한 뒤의 n번째 줄」이다.** `maskText(원문 전체)`가 결정적이고 줄 수를
  바꾸지 않으므로, 이렇게 잘라 낸 단위의 자리표시자 번호가 저장된 대응표와 **구성상 일치한다.**
  단위만 따로 마스킹하면 `[[주민등록번호1]]`이 문서의 `[[주민등록번호3]]`과 어긋나 본문에 끼워
  넣는 순간 자리표시자 집합이 깨지고 내보내기가 409로 막힌다. 그래서
  `ConvertDocumentUseCase`에 **이미 마스킹된 텍스트를 받는 진입점**을 추가하고(타입이 `MaskedText`라
  경계는 그대로다) 그 뒤 경로는 기존 것을 한 줄도 바꾸지 않고 쓴다.
- **호출 계약:** 문단 재변환 1회 = **재변환 1 + 조건부 보정 1 = 최대 2회**, 루프 없음 — 자동 변환과
  같은 모양이다. §3.2 문구 개정 제안: 「문서 변환 1건 = 최대 2회」를 **「자동 변환 1회 = 최대 2회」**로
  좁히고, 「사용자가 요청한 문단 재변환 1회 = 최대 2회, 문서 1건의 재변환 총 호출은 구성값 상한」을
  나란히 적는다. 크레딧은 나중이며 이 계획은 크레딧을 만들지 않는다.
- **정액 방벽(TOCTOU 없음):** `conversions.reconverted_units jsonb NOT NULL DEFAULT '[]'`(V10,
  **평문** — 담기는 것이 색인뿐이라 `missing_placeholders`와 같은 판단)에 LLM 호출 **전에** 단일
  UPDATE로 색인을 append 한다. `WHERE jsonb_array_length(reconverted_units) < :cap` 이 상한을
  원자적으로 지키고, 0행이면 **429**(계약에 `TooManyRequests` 응답이 이미 있다). 소유 술어를 같은
  문장이 진다(`OwnershipPredicateGuardTest`). 암호문 열을 건드리지 않으므로 봉투 인구조사와 무관하다.
  이 열은 **요청 기록**이지 채택 기록이 아니다 — 채택은 클라이언트가 하므로 서버가 볼 수 없다(§8).
- **멱등하지 않다.** 매 호출이 새 결과를 만든다. 클라이언트는 자동 재시도하지 않고, 비용 방벽은
  위 상한 하나다. 이 사실을 계약에 적는다.
- **채택 게이트는 사실·자리표시자만 본다.** 후보가 원본 단위의 사실을 빠뜨렸거나 자리표시자를
  잃으면 거절하고 `adopted: false`로 사유를 낸다(`decideRepairAdoption`과 같은 판정을 단위 규모로
  재사용). 문체 위반 수는 「나빠졌는가」로 쓰지 않고 **숫자로 보고**만 한다 — 사용자가 손댄 현재
  텍스트를 서버가 모르므로 비교 기준이 없다.
- **동시성:** 아무것도 저장하지 않으므로 쓰기 충돌이 없다. 기존 PUT의 행 잠금·봉투 CAS가 그대로
  최종 저장을 지킨다.
- 응답: `{ source_unit_index, easy_text|null, adopted, reject_reason|null, style_issue_count,
  llm_calls, remaining_reconversions }`.

## 5. 결정 4 — 내보내기(§6.5)와의 관계: **이번엔 바꾸지 않는다**

대응 지도는 `planOf`를 개선할 여지를 만든다 — 1:N을 알면 「원본 문단 하나에 여러 줄」을 제자리에
넣을 수 있어 `partial`이던 문서가 `available`이 된다. 그러나 그것은 **이미 출시된 기능의 동작 변경**
이고, DOCX에서 한 문단 안 줄바꿈은 `w:br` 삽입이 필요해 `TextUnit.rewrite`를 고쳐야 한다.

- **이번 범위: 변하지 않는다.** 슬라이스마다 「`format_preservation` 판정이 하나도 달라지지 않았다」를
  회귀 테스트로 못 박는다.
- **설계 접점만 남긴다:** 지도의 단위 정의를 내보내기 순회와 **같은 차례**로 맞춰 두었으므로,
  나중에 `planOf(units, lines)`를 `planOf(units, lines, segmentMap)`으로 바꾸는 것이 후속 슬라이스다.

## 6. 결정 5 — 품질 항목(backlog §1.3)

- **표·목록 구조 보존: 범위 밖.** 구조를 늘리면 단위 모델이 함께 바뀐다. 이번에는 단위를 「추출 원문의
  한 줄」로 **고정**하고 더 풍부한 타입을 만들지 않는다.
- **이미 쉬운 글인 입력의 저변경 보장: 부분 채택.** 자동 변환을 단위별로 쪼개 「통과한 문단은 건드리지
  않기」를 하지 않는다(호출 상한과 프롬프트 설계가 문서 전체를 전제한다). 대신 **재변환 자리에서**
  값싸게 지킨다: `segment_map.compliant_source_units`(스타일 게이트를 이미 통과한 원본 단위 색인)를
  실어, 화면이 **호출 전에** 「이 문단은 이미 규칙을 통과합니다 — 다시 쓰면 나빠질 수 있습니다」로
  경고한다. LLM 비용이 0인 경고다. `checkStyle`을 조회마다 원문 전체에 돌리는 CPU가 새로 생기므로
  슬라이스 4에서 20,000자 입력으로 실측하고 결과를 기록한다.

## 7. 슬라이스 (순서대로, 되돌릴 수 있는 단위)

**S1 — core 단위 분할과 정렬 (M, 백엔드만, 계약 변경 없음)**
- 파일: `core/segment/SourceUnits.kt`(split/join), `core/segment/SegmentAlignment.kt`,
  `core/easyread/FactPreservation.kt`(`extractFacts`를 `internal`로), 테스트 3종.
- TDD 순서: ⑴ `join(split(x)) == x`(빈 줄·`\r\n`·끝 개행 포함) ⑵ 앵커 LIS가 1:N·N:1·삭제·삽입
  케이스에서 단조 대응을 낸다 ⑶ 앵커 0개면 차례 정렬과 같고 전부 `low`다 ⑷ 모든 easy 단위가 결과에
  정확히 한 번 나온다(전사성).
- 수용: `./gradlew :core:test` 통과. **`Prompts.kt` 문자열을 한 글자도 바꾸지 않는다**(따라서
  `PromptTextSnapshotTest`·골든 기준선 무영향).
- 추가 회귀(높은 값어치): DOCX/HWPX fixture마다 `split(추출 원문).size == TextUnitWalk 단위 수`.
  어긋나면 **오늘의 내보내기 정렬에 잠재 결함이 있다는 신호**다.

**S2 — `readConversion`에 `segment_map` (M, 계약 2.11.0)**
- 파일: `contracts/easy-doc-v1.yaml`, `core/document/ConversionView.kt`,
  `application/document/ConversionQueryService.kt`(+`DocumentRepository.findOwnedSource` 협력자),
  `api/document/ConversionDtos.kt`, `frontend/src/api/types.ts`, Kotlin 계약 테스트.
- 완료 전·본문 없음이면 `null`. 폴링 중에는 계산하지 않으므로 대기 화면에 비용이 없다.
- 수용: 계약 테스트가 필드 존재·`null` 갈래를 고정. **`format_preservation` 결과 불변** 회귀.

**S3 — 검수 화면의 단위 대응 (M, 프런트만)**
- 파일: `frontend/src/components/ReviewEditor.tsx`, `SourceTextPanel.tsx`,
  새 `SegmentedResultEditor.tsx`, 각 테스트, `frontend/src/a11y.test.tsx`.
- 원문 패널: 읽기 전용 단위 목록(클릭 가능). 결과 패널: 단위별 `<textarea>` 목록.
  **단위 수가 임계값(구성 상수, 권고 200)을 넘으면 지금의 단일 textarea로 명시적으로 내려앉고 그
  사유를 화면에 적는다** — 20,000자 상한에서 단위가 수백 개가 될 수 있고, 수백 개 편집 필드는 DOM
  무게와 낭독 순서를 망친다. 파일럿 표본은 전부 3,076자 이하(≈50단위)라 일상 경로는 목록 쪽이다.
- 저장 문자열이 오늘과 동일함을 테스트로 못 박는다(`join('\n')` 왕복).
- `low` 대응은 하이라이트하지 않고 「대응 확인 불가」로 표시(§9 상태 어휘).
- 문서: `DESIGN.md` §6.4에 「결과 패널은 단위 목록」과 내려앉기 규칙을 개정으로 추가.

**S4 — 재변환 엔드포인트 (M, 계약 2.12.0 + V10)**
- 파일: `V10__conversion_reconverted_units.sql`, `application/conversion/ConvertDocumentUseCase.kt`
  (마스킹된 입력 진입점), 새 `application/conversion/ReconvertSegmentUseCase.kt`,
  `infrastructure/document/JdbcConversionRepository.kt`(원자적 정액 UPDATE),
  `infrastructure/queue|llm` 조립(api 프로필에서 use case 노출), `api/document/…Controller/Dtos`,
  `contracts/easy-doc-v1.yaml`, `frontend/src/api/{types,client}.ts`.
- TDD 순서: ⑴ 정액 초과가 429이고 **LLM을 부르지 않는다** ⑵ 남의 변환은 404 ⑶ 완료 전은 409
  ⑷ 색인 범위 밖은 422 ⑸ 자리표시자 번호가 문서 대응표와 같다 ⑹ 사실 유실 후보는 `adopted: false`
  ⑺ 호출 수가 단위당 최대 2회.
- 수용: `./gradlew build` 통과, 실제 유료 호출 없이 fake provider로 검증.

**S5 — 재변환 UI (S, 프런트만)** — 단위별 버튼, 이미 통과한 단위 경고, 남은 횟수 표시, 429/거절
사유 문구, 후보를 미저장 변경으로 넣기(저장은 기존 PUT).

**S6 — 내보내기가 지도를 소비 (L, 후속·범위 밖)** — `planOf` 확장, `w:br` 다중 줄 반영,
`format_preservation` 어휘 재검토. 이 계획은 접점만 남기고 하지 않는다.

## 8. 리스크와 열린 결정

리스크
1. **정렬 정확도를 무료로 측정할 수 없다.** 실제 (원문, 변환문) 쌍 코퍼스가 없다. 완화: 손으로 만든
   정렬 fixture(프롬프트가 문서화한 실패/정상 모드에서 파생)와 confidence 노출. 유료 실측은 게이트 ⓪과
   같은 승인 규칙을 따른다.
2. **추출 줄 수와 내보내기 단위 수의 잠재 불일치**(예: `w:t` 안의 개행). S1의 fixture 대조가 드러낸다.
3. **api 프로세스가 유료 외부 호출을 하게 된다.** 타임아웃은 provider가 이미 가지고 있고 동시 실행은
   제한하지만, 사용자 요청 스레드에서 나가는 첫 LLM 호출이라는 사실은 남는다.
4. **수정률 KPI 오염.** 채택된 재변환 결과는 `edited_text`에 사람 편집처럼 섞인다. `reconverted_units`가
   「이 변환은 재변환을 N회 요청했다」를 기록해 지표를 한정할 수 있게 하되, **채택 여부는 모른다.**
5. **CPU:** 조회마다 원문 복호화 + 정렬 + (S4부터) 원문 `checkStyle`. 20,000자로 실측해 기록한다.

사용자 확인이 필요한 열린 결정 (**미결**)
- **(a) 문서당 재변환 상한 값.** 권고: `easydoc.conversion.max-segment-reconversions` 기본 **10**.
  단위 호출은 문서 호출보다 훨씬 싸지만 상한 자체는 비용 정책이라 숫자를 사용자가 정해야 한다.
- **(b) 채택 여부를 저장할 것인가.** 저장하려면 `updateConversion` 요청에 「이 단위는 재변환 결과를
  채택했다」 필드가 필요하다(계약 변경). 권고: MVP에서는 하지 않고 요청 기록만 남긴다.
- **(c) §3.2 「최대 2회」 문구 개정안**(위 §4)을 master-plan에 반영할지, 그리고 크레딧 설계(§5)에
  재변환을 어떻게 넣을지.
- **(d) 결과 패널의 시각 형태** — 단위별 textarea 목록(권고) vs. 단일 편집기 + 오버레이.
  후자는 폰트 메트릭·스크롤 동기화에 민감하고 캐럿 기반 단위 판정이 필요하다.
- **(e) 단위 목록 내려앉기 임계값**(권고 200)과, 넘겼을 때 재변환 버튼을 아예 감출지 캐럿 단위에만
  붙일지.

## 9. 범위 밖

표·목록 구조 보존, 레이아웃 인지 PDF 추출, 내보내기의 지도 소비(S6), 크레딧·결제, RAG 사전 팝업
(P0-5 — 별도 계획), 자동 변환의 단위별 분할, 실제 유료 LLM 측정.

## 10. P0-5(사전 팝업) 레인이 의지해도 되는 구조

- **쉬운 글 본문의 주소 체계**는 `easy_unit_index` = `(edited_text ?? easy_text).split('\n')`의
  0 기반 색인이다. 이 색인은 서버가 세션 중에 다시 매기지 않고, 조회와 PUT 응답이 같은 규칙으로 낸다.
- 용어 위치는 **단위 안의 상대 좌표**로 표현하라: `{ easy_unit_index, start, end }`.
  전체 본문 기준 절대 좌표를 쓰면 다른 단위를 고치는 것만으로 무효가 된다.
- `start`/`end`는 **UTF-16 코드 단위** 오프셋으로 정의하라 — 브라우저 `textarea`/Selection이 쓰는
  단위와 같아야 한다. 저장소 길이 상한은 코드 포인트로 재므로(`charCountOf`) 두 축을 섞지 말고
  계약 문구에 어느 쪽인지 적어야 한다.
- 사전 팝업은 `segment_map` 없이도 성립한다 — 위 세 값만 쓰면 이 계획의 슬라이스 순서와 무관하게
  병행 구현할 수 있다. 반대로 `segment_map.units[].confidence`가 `low`인 단위에서는 「원본의 어느
  문단에서 온 용어인지」를 주장하지 말아야 한다.
