# P0-5 — 쉬운 말 사전: 검수 화면 대체어 추천

- 작성일: 2026-09-04 (Codex 계획 심사 반영 개정)
- 상태: 계획(미착수). 사용자 확인 대기 항목은 §7
- 기준: `docs/master-plan.md` §4.1 P0-5·§9 단계 2, `DESIGN.md` §6.4, `contracts/easy-doc-v1.yaml`
- 원칙: 되돌릴 수 있는 수직 조각, TDD, 계층 경계(core → application → infrastructure → api/프런트)
- 범위 밖: 문단 단위 대응·재변환(`2026-09-04-p0-4-paragraph-mapping-reconversion.md`), 프롬프트 주입 경로 변경, 어드민 사전 편집

## 1. 시작 상태 (코드 확인 2026-09-04, `bc095e4`)

이미 있는 것 — **이 기능의 검색 엔진은 사실상 구현돼 있다.**

- `core/dictionary/DictionaryIndex.kt` — 표면형 트라이 + 최장일치 + 조사 경계 + 로마자·숫자 경계 + 표면형 소유권. `findAll(text): List<DictionaryMatch>`가 공개돼 있고 매치마다 `start`/`end`/`surface`/`entry`를 준다.
- `core/dictionary/DictionaryEntry.kt` — `term`·`easyTerm`·`strategy`·`risk`·`priority`·`definition`·`caution`·`tags`·`examples`. 팝업이 보여줄 정보가 그대로 다 있다.
- `infrastructure/dictionary/DictionaryIndexJsonReader.kt` — 커밋된 사본 `resources/dictionary/easy_dict.index.json`을 읽는다. Gradle `checkDictionaryIndex`가 `dictionary/dist` 정본과 바이트 동일성을 매 빌드 검사한다.
- **색인 실측(2026-09-04, 커밋된 사본 직접 계수)**: 엔트리 2,179 · 표면형 40,189 · `schema_version` 1.0.0 · 전략 `substitute` 1,525 / `gloss` 607 / `keep` 47 · 위험도 `none` 1,526 / `low` 553 / `high` 100.
- `DictionaryReferenceContextTest` — 고정 56건의 프롬프트 컨텍스트를 Python 참조 구현과 대조하고, 정책 기본값도 함께 고정한다.
- `compose.yml`은 이미 `pgvector/pgvector:pg16`. 벡터 컬럼은 없고 Flyway는 `V9`까지.
- `core/easyread/StyleRules.kt`의 `DIFFICULT_WORD` 복합어 오탐은 `43eef53`·`dd58131`·`fbc7efa`로 해소됐다.

없는 것 — 이 계획이 만들 것.

- 사전을 **문서 변환이 아니라 사람의 조회**에 쓰는 경로. `DictionaryIndex`는 `@Profile("worker")`인 `ConversionWorkerConfiguration`에서만 적재된다 — **API 프로세스는 색인을 읽지 않는다.**
- 조회 HTTP 계약, 프런트 팝업, 유용성을 무료로 재는 고정 픽스처.

## 2. 목표

검수 화면에서 담당자가 어려운 말을 지목하면 그 말의 사전 지침(쉬운 표현·뜻·주의·치환 가능 여부)을 팝업으로 보여주고, 안전한 경우에만 편집기 본문을 바꿔 준다.

## 3. 결정과 근거

### 3.1 검색 방식 — 어휘 우선(색인 조회). MVP에 pgvector를 도입하지 않는다

**결정: (a) 어휘 우선.** 이유는 셋이다.

1. **필요한 답을 이미 정확히 준다.** 사전에 있는 말은 트라이 최장일치가 활용형·조사까지 처리해 0ms에 찾고, 전략·위험도·주의까지 함께 준다.
2. **임베딩 최근접은 이 도메인에서 위험한 답을 만든다.** 미등재어를 벡터로 찾으면 「의미가 가까운 다른 표제어」가 나오고, 그것을 대체어로 제시하면 `과태료 → 벌금` 사고의 재발이다. 사전이 `replace_strategy`·`risk_level`로 막아 둔 것을 검색 계층이 되살리는 셈이다(`dictionary/DESIGN.md` §2.1).
3. **외부 호출이 없으므로 마스킹·국외이전 경계를 건드리지 않는다.** 임베딩을 넣는 순간 벤더가 하나 늘고(Anthropic은 임베딩 API가 없다) 클릭마다 문서 조각이 국외로 나간다.

**P0-5의 「pgvector 기반」 해석.** 그 표현은 2026-08 시점의 구현 스케치이고, 사용자에게 약속한 것은 「어려운 용어를 지목하면 대체어를 추천한다」다. 인프라가 이미 pgvector 이미지라 나중에 벡터를 켜는 것은 마이그레이션 한 장이지 DB 교체가 아니다 — 지금 미루는 데 구조적 비용이 없다. **이 재해석 자체는 §7 결정 게이트의 사용자 확인 대기 항목이다.**

**미등재어 2차 후보(무료·비벡터).** 정확·활용형 일치가 없으면 같은 색인으로 **복합어 부분 일치**를 찾아 「이 말 안에 든 아는 말」을 설명으로만 제시한다. 대체어 버튼은 주지 않는다. 그것도 없으면 후보 0건을 정직하게 돌려준다.

**임베딩 착수 트리거(지금 확정).** 실제 파일럿 조회 **200건 이상**을 모았을 때 **무결과율이 0.30을 넘을 때만** 조각 7을 검토 대상으로 올린다. 그 시점의 착수 판단은 사용자 결정이다(§7). 그 조건이 서기 전에는 착수하지 않는다.

### 3.2 데이터 — 새 표도, 새 적재 경로도 만들지 않는다

**결정: 조회는 API 프로세스가 메모리에 올린 같은 `DictionaryIndex`를 쓴다. `dictionary_entries` 표와 Flyway 마이그레이션은 MVP에 없다.**

- 원천·라이선스·갱신 주기 질문이 새로 생기지 않는다. 조회가 읽는 것은 이미 커밋돼 있고 게이트가 정본과 대조하는 사본 하나다.
- 표로 옮기면 **매칭 구현이 둘이 된다.** 트라이 경계 규칙을 SQL로 다시 쓰면 `DictionaryReferenceContextTest`가 지키는 참조 동등성 밖에 두 번째 매칭기가 생기고, 둘이 갈리면 조회와 프롬프트가 다른 말을 한다.
- 비용: API 프로세스가 1.5MB JSON을 기동 때 한 번 읽는다. `easydoc.dictionary.lookup.enabled=false`면 읽지 않는다.
- **골든 고정 56건은 영향받지 않는다.** 조회는 `findAll`만 쓰고 `buildPromptContext`·`DictionaryContextLines`·`DictionaryPromptContext`·`DictionaryProperties` 기본값을 한 글자도 건드리지 않는다.
- **출처 표기.** `index.json`은 엔트리별 원천·라이선스를 담지 않는다(wire 키 `t/e/d/s/r/p/g/c/x`). 팝업은 **사전 단위 표기**(사전 이름 + 라이선스 요약 + `schema_version`)와 `tags`를 보여준다. 엔트리별 출처는 색인 스키마 1.1.0이 필요한 별 작업이며 이 계획 밖이다.

### 3.3 임베딩 provider — MVP에서는 만들지 않는다(조각 7 설계만)

- **`LlmProvider`에 얹지 않는다.** 반환이 `LlmCompletion`이 아니라 벡터라 그 포트를 오염시킨다. 별 포트 `core/llm/EmbeddingProvider.kt`를 둔다.
- **Anthropic은 임베딩 API가 없다.** 제품 기본 provider가 anthropic이므로 임베딩은 필연적으로 두 번째 벤더다(OpenAI `text-embedding-3-small` 권고). 그 벤더의 no-training·국외이전 약관 확인이 master-plan §3.1·§3.2에 따라 선행 조건이 된다.
- 단가는 `BigDecimal` 구성값, 미설정은 `null`. 관측은 `MetricsLlmProviderDecorator`를 거울처럼 따르는 decorator로 하고 질의어·본문은 로그에 넣지 않는다. 엔트리 임베딩은 적재 시 일괄, 질의는 텍스트 해시로 캐시한다. 단위 테스트는 `FakeEmbeddingProvider`만 쓴다.

### 3.4 API — `POST /dictionary/lookup` 하나 (계약 2.11.0, 마이그레이션 없음)

`GET /conversions/{id}/terms`를 **버린다.** 담당자는 편집 중이고 서버에 저장된 본문은 이미 낡았다 — 방금 고친 문장의 낱말을 조회하면 없는 것으로 나온다.

**위치 계약(구속력 있는 한 문장).** 조회 wire 계약이 받는 것은 **선택된 문자열 하나뿐**이며(`{ "text": string }`, 위치 성격의 필드는 요청에도 응답에도 없다), `easy_unit_index`·`start`·`end` 같은 좌표는 P0-4 편집기의 **클라이언트 전용 상태**다 — P0-4 계획 §10도 「사전 팝업은 `segment_map` 없이도 성립한다」로 같은 경계를 적고 있다.

**계약 버전 순서(레인 간 확정).** P0-5 조회 API가 **2.11.0**, P0-4가 `segment_map`으로 2.12.0, 재변환으로 2.13.0 + `V10`을 쓴다. 조건부 조각 7은 **번호를 예약하지 않는다** — 착수 시점에 `contracts/easy-doc-v1.yaml`의 현재 `version`과 `db/migration/` 디렉터리를 실제로 확인해 그때의 다음 빈 번호를 배정한다.

- 요청: `{ "text": "<지목한 문자열>" }`. 인증 필수. `x-input-limits.max_term_query_chars`(100) 이하, 제어문자 제거 후 비면 422.
- 처리: 질의 문자열에 `findAll`을 돌린다 — 브라우저가 한글을 어절 단위로 선택하므로(`과태료를`) 정확 일치 조회가 아니라 매칭기를 태우는 것이 맞다. 조사(`를`·`을` 등)는 색인의 `josa` 목록이 경계로 처리한다.
- 응답(snake_case): `{ "query", "candidates": [...], "dictionary": { "name", "license", "schema_version" } }`. 후보는 `term`·`easy_term`·`strategy`·`risk`·`definition`·`caution`·`tags`·`examples[{before,after}]`·`match_kind`·`applicable`.
- **`score`(float)를 두지 않는다.** 어휘 일치에 연속 점수는 뜻이 없고, 있으면 다음 사람이 임의 임계값을 건다. `match_kind` enum(`exact`|`inflected`|`compound_part`)을 값으로 두고 순서는 서버가 정한다.
- `match_kind` 정의: `exact` = 매치 표면형이 표제어와 같고 남는 것이 조사뿐 · `inflected` = 표면형이 표제어와 다름(`DictionaryMatch.isInflected`) · `compound_part` = 매치가 질의의 일부만 덮고 남은 부분이 조사가 아님.
- `applicable`은 치환 버튼을 줄지의 단일 출처 — `strategy == substitute`일 때만 참. gloss는 원어를 남겨야 하고 keep은 손대면 안 된다. 색인 실측상 전략의 70%가 `substitute`라 이 버튼이 뜨는 빈도는 낮지 않다.
- 상태 코드: 200(후보 0건도 200), 401, 415, 422, 429(`RateLimitedException` → 기존 `GlobalExceptionHandler`가 `Retry-After`를 싣는다), 500/503. **404를 쓰지 않는다** — 은폐할 소유 자원이 없고 「없음」은 빈 후보 목록이다.
- 헤더: `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`.
- 남용 한도: 사용자별 분당 60을 `easydoc.dictionary.lookup.rate-limit-per-minute` 구성값으로 받는다. 프로세스 내 카운터라 인스턴스별 한도이며 계약에 그렇게 적는다 — 사전 전량 긁기를 늦추는 것이 목적이다.

### 3.5 프런트 — 선택 기반 팝업

- 결과 패널은 순수 `<textarea>`(`ReviewEditor.tsx:623`)다. **textarea 안에는 밑줄을 그을 수 없다** — 「클릭 가능한 용어」 표시가 원리적으로 불가능하다. 그래서 트리거는 **선택**이며, 한글 어절은 더블클릭으로 브라우저가 통째로 선택하므로 「낱말을 두 번 누르면 팝업」이 P0-5 문구의 「클릭하면 팝업」에 대응한다.
- 선택이 생기면 근처에 「쉬운 말 찾기」 버튼(44px 이상)을 띄우고, 키보드 단축키도 같은 동작을 준다.
- 팝업은 `role="dialog"`·초점 가둠·Esc 닫기·닫을 때 편집기로 초점 복귀. 후보는 버튼 목록, 결과 개수는 `aria-live`로 알린다. `prefers-reduced-motion`을 지킨다.
- 적용은 `applicable`인 후보에만. `[selectionStart, selectionEnd)`를 `easy_term`으로 갈고 캐럿을 삽입 끝으로 두고 `dirty`를 켠다. gloss·keep 후보는 설명만 보여주고 복사 버튼을 준다.
- 원문 패널(읽기 전용)에서도 조회는 되고 적용 버튼은 없다.

### 3.6 유용성 측정 — 고정 픽스처, 무료, 임계값 확정

첫 실행 **전에** 픽스처를 등록한다: `backend-kotlin/core/src/test/resources/dictionary/lookup-fixture.json`, **50건 이상**, 항목마다 `query`·`expected_entry_id`(무결과 기대는 `null`)·`expected_match_kind`·`expected_applicable`.

출처 구성:

- 골든 변환 63건(공개 문서)에서 나온 `DIFFICULT_WORD` 잔존어 — 담당자가 누를 만한 말의 실물 표본.
- 손으로 쓴 케이스: `compound_part`, 활용형, 조사 경계, 로마자·숫자 경계, 그리고 **의도적 음성 케이스**.
- 색인에서 미리 확인한 값(2026-09-04): `구비서류` → id 2165 · substitute · `준비할 서류` · applicable 참 / `과태료를` → id 2142 · gloss · risk high · applicable 거짓 / `급여` → id 2141 · gloss · risk high / `시행령` → id 1775(표제어 자체가 엔트리다 — 짧은 표제어가 최장일치를 이기지 않는지 보는 케이스) / `게시판`·`저소득가구`·`고령운전자` → 표면형 없음(각각 무결과·`compound_part` 기대).

지표는 **따로 판정**하며 임계값은 지금 확정한다.

| 지표 | 임계값 |
|---|---|
| top-1 엔트리 정확도 | ≥ 0.90 |
| 위험한 applicable 비율(기대 거짓인데 참) | = 0 |
| 양성 케이스 무결과율 | ≤ 0.10 |

- 이 셋은 `infrastructure`가 아니라 픽스처와 같은 모듈(`core`) 테스트에서 판정한다. 새 검증 하네스를 만들지 않는다.
- **파일럿에서 질의어를 수집하지 않는다.** 사용자 문서의 낱말은 본문이고 본문은 로그·메트릭에 넣지 않는다. 운영에서 남기는 것은 개수와 `match_kind` 분포뿐(디버그 로그)이며, §3.1의 무결과율 트리거는 그 개수 집계로 판정한다.
- 조회 전용 피드백 항목은 만들지 않는다 — `conversion_feedback` 스키마 변경이라 이 계획 범위 밖이다.

## 4. 작업 순서

**조각 1 — 계약 (S, `contracts/`).** 2.11.0: `POST /dictionary/lookup`, 요청·응답 스키마, `x-input-limits.max_term_query_chars: 100`, 422·429 예시. 실패하는 `api/src/test/.../DictionaryLookupContractTest.kt` 골격을 같은 조각에 둔다.
합격: 계약 테스트가 「경로 없음」으로 실패한다. `version`이 2.11.0이고 P0-4 계획이 쓰는 2.12.0·2.13.0과 충돌하지 않는다.

**조각 2 — 후보 산출 (M, `core`).** 새 파일 `core/src/main/kotlin/kr/easydoc/core/dictionary/TermLookup.kt`: `TermQuery`(정제·상한), `TermCandidate`, `TermMatchKind`, 순서 규칙, `applicable` 판정. 테스트 `core/src/test/.../TermLookupTest.kt` 선행. **기존 dictionary 파일은 수정하지 않는다.**
합격(입력 → 기대):

- `"구비서류"` → 후보 1건, id 2165, `substitute`, `applicable=true`, `exact`
- `"과태료를"` → id 2142, `gloss`, `risk=high`, `applicable=false`, `exact`(조사는 매치 밖)
- `"시행령"` → id 1775 단독. `시행`으로 시작하는 짧은 후보가 앞서지 않는다
- `"게시판"` → 후보 0건 (예외 아님, 빈 목록)
- `"저소득가구"` → `compound_part` + `applicable=false`
- `""`·제어문자만 → `TermQuery` 생성 거절
- `DictionaryReferenceContextTest` 고정 56건이 손대지 않은 채로 통과

**조각 3 — 유스케이스와 배선 (M, `application`+`infrastructure`).** `application/dictionary/TermCandidateSource.kt`(포트)·`TermLookupService.kt`, `infrastructure/dictionary/IndexedTermCandidateSource.kt`·`DictionaryLookupProperties.kt`(`enabled`·`max-query-chars`·`rate-limit-per-minute`). 색인 적재를 `infrastructure/dictionary/DictionaryConfiguration.kt`로 뽑고 `ConversionWorkerConfiguration`이 그 빈을 받게 고친다.
합격: `easydoc.dictionary.enabled=false`인 worker가 색인을 **읽지 않는다**(기존 불변식 유지). `easydoc.dictionary.lookup.enabled=false`인 api도 읽지 않는다. 조회를 켠 api 기동에서 색인 적재 시간과 힙 증가를 한 번 실측해 이 문서에 적는다.

**조각 4 — HTTP (S, `api`).** `api/dictionary/DictionaryLookupController.kt`·`DictionaryLookupDtos.kt`, 사용자별 분당 한도, `application.yml`에 `easydoc.dictionary.lookup.*`.
합격: 토큰 없이 401 · `text/plain`으로 415 · 101자로 422 · 61번째 요청에 429 + `Retry-After` · 200 응답에 두 헤더. `DictionaryLookupReachTest`가 실경로를 고정한다.

**조각 5 — 팝업 (M, `frontend`).** `api/types.ts`·`client.ts`에 타입·호출, `components/TermLookupPopover.tsx`(+`.test.tsx`), `ReviewEditor.tsx`에 트리거·적용 배선.
합격: `구비서류`를 선택 → 팝업에 `준비할 서류`와 적용 버튼 · 적용 후 textarea 값이 바뀌고 저장 상태가 `저장 안 됨` · `과태료`는 적용 버튼 없이 설명만 · Esc로 닫고 초점이 textarea로 돌아온다 · `npm run check`·`npm run test -- --run`·`npm run build` 통과.

**조각 Q — 픽스처 측정 (S, `core` 테스트).** `lookup-fixture.json`(50건 이상)과 `TermLookupFixtureTest.kt`. 조각 2 직후에 둔다.
합격: §3.6 세 임계값을 각각 단언하고 분포를 테스트 출력에 찍는다. 픽스처가 50건 미만이면 실패한다.

**조각 6 — e2e·문서 (S, 루트).** `frontend/e2e/`에 조회 흐름 1건(`fake` provider의 결정적 결과문에 표제어를 심는다), backlog §1 사전 행과 master-plan §4.1 P0-5 상태 갱신(pgvector 재해석 기록 포함).

**조각 7 — 임베딩 (L, 조건부).** §3.1 트리거가 서고 사용자가 착수를 승인할 때만. 변경 단위: `EmbeddingProvider` 포트 + 어댑터 + 관측 decorator, 엔트리 표와 `embedding vector(1536)` + HNSW 마이그레이션, `load-dictionary` 프로필, 질의 캐시, `match_kind`에 `semantic` 추가. **계약·마이그레이션 번호는 착수 시점에 확인해 배정한다.**

## 5. 완료 정의

- [ ] `cd backend-kotlin && ./gradlew build` 통과 (ktlint·detekt·모듈 경계·`checkDictionaryIndex` 포함)
- [ ] `DictionaryReferenceContextTest` 고정 56건이 손대지 않은 채로 통과
- [ ] `cd frontend && npm run check && npm run test -- --run && npm run build` 통과
- [ ] `docker compose config`와 CI 프로필 config 통과
- [ ] 계약·Kotlin DTO/컨트롤러·프런트 타입·호출부가 한 변경 단위로 검증됨
- [ ] Playwright 조회 흐름 통과, 픽스처 50건 이상이 세 임계값을 만족
- [ ] 유료 외부 호출 0건 — 이 범위에는 외부 API 호출이 없다

## 6. 리스크

1. **발견성.** textarea에서는 용어를 표시할 수 없어 「선택 후 조회」를 모르는 사용자는 기능을 못 쓴다. 완화: 결과 패널 라벨 옆 상시 안내 한 줄 + 더블클릭 동작. 근본 해결은 구조 있는 편집기(P0-4)를 기다린다.
2. **API 프로세스 무게.** 색인 적재로 기동이 느려지거나 힙이 커질 수 있다. 완화: 조각 3에서 실측, `enabled` 스위치, 필요하면 지연 적재.
3. **후보 0건의 체감.** 엔트리 2,179건은 실무 문서를 다 덮지 않는다. 완화: 빈 결과를 「사전에 없는 말입니다」로 정직하게 말하고 복합어 부분 일치를 함께 보여준다. 벡터로 얼버무리지 않는다.
4. **픽스처 편향.** 골든 변환에서 뽑은 질의는 `DIFFICULT_WORD` 목록이 걸러낸 말에 쏠린다 — 사전에는 있지만 그 규칙이 모르는 말은 표본에 덜 들어온다. 완화: 손으로 쓴 케이스를 픽스처의 절반 이상으로 두고 그 비율을 픽스처 안에 적는다.
5. **사전 갱신과 조회의 결합.** 색인이 갱신되면 조회 결과도 픽스처 기대값도 바뀐다. 기존 `checkDictionaryIndex`와 `schema_version` 단언이 그대로 이 경로의 방어선이다 — 새 장치를 만들지 않는다.

## 7. 결정 게이트

- **[확정(2026-09-05, 사용자)] pgvector 재해석.** P0-5의 「pgvector 기반」을 「어려운 용어 지목 → 대체어 추천」이라는 요구로 읽고 벡터는 §3.1 트리거가 설 때만 착수한다 — P0-5는 어휘 조회 MVP로 전달되며 임베딩은 §3.1 트리거(파일럿 조회 200건 이상·무결과율 0.30 초과·사용자 승인)가 설 때만 조건부로 착수한다. **이 확정으로 조각 1(계약 2.11.0)이 착수 가능해졌다.**
- **[트리거 발화 시 사용자 결정] 조각 7 착수.** 파일럿 조회 200건 이상에서 무결과율 0.30 초과가 확인되면 임베딩 착수 여부와 벤더·비용 승인을 사용자가 정한다.

그 밖의 항목은 이 문서에서 결정됐다: 계약 2.11.0·마이그레이션 없음, 위치 계약(문자열만), 임계값 셋(0.90 / 0 / 0.10), 분당 60 구성값, 사전 단위 출처 표기, 조회 전용 피드백 항목 없음.
