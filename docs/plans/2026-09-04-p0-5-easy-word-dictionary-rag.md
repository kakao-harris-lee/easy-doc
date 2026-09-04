# P0-5 — 쉬운 말 사전: 검수 화면 대체어 추천

- 작성일: 2026-09-04
- 상태: 계획(미착수). 사용자 결정 대기 항목은 §7
- 기준: `docs/master-plan.md` §4.1 P0-5·§9 단계 2, `DESIGN.md` §6.4, `contracts/easy-doc-v1.yaml`
- 원칙: 되돌릴 수 있는 수직 조각, TDD, 계층 경계(core → application → infrastructure → api/프런트)
- 범위 밖: 문단 단위 대응·재변환(P0-4 계획 문서 소관), 프롬프트 주입 경로 변경, 어드민 사전 편집

## 1. 시작 상태 (코드 확인 2026-09-04, `bc095e4`)

이미 있는 것 — **이 기능의 검색 엔진은 사실상 구현돼 있다.**

- `core/dictionary/DictionaryIndex.kt` — 표면형 트라이 + 최장일치 + 조사 경계 + 로마자·숫자 경계 + 표면형 소유권. `findAll(text): List<DictionaryMatch>`가 공개돼 있고 매치마다 `start`/`end`/`surface`/`entry`를 준다.
- `core/dictionary/DictionaryEntry.kt` — `term`·`easyTerm`·`strategy`(substitute/gloss/keep)·`risk`(high/low/none)·`priority`·`definition`·`caution`·`tags`·`examples`. 팝업이 보여줄 정보가 그대로 다 있다.
- `infrastructure/dictionary/DictionaryIndexJsonReader.kt` — 커밋된 사본 `resources/dictionary/easy_dict.index.json`(1.5MB·엔트리 2,179·표면형 40,189·`schema_version` 1.0.0)을 읽는다. Gradle `checkDictionaryIndex`가 `dictionary/dist` 정본과 바이트 동일성을 매 빌드 검사한다.
- `infrastructure/dictionary/DictionaryReferenceContextTest` — 고정 56건에 대해 프롬프트 컨텍스트 문자열을 Python 참조 구현과 대조한다. 정책 기본값도 함께 고정한다(`DictionaryProperties().policy() == REFERENCE_POLICY`).
- `compose.yml` — 이미 `pgvector/pgvector:pg16`. 벡터 컬럼은 없고, Flyway는 `V9`까지.
- `core/easyread/StyleRules.kt` — `DIFFICULT_WORD`의 복합어 오탐 결함은 `43eef53`·`dd58131`·`fbc7efa`로 해소됐다(낱말 경계 + 괄호 뜻풀이 판정).

없는 것 — 이 계획이 만들 것.

- 사전을 **문서 변환이 아니라 사람의 조회**에 쓰는 경로. `DictionaryIndex`는 `@Profile("worker")`인 `ConversionWorkerConfiguration`에서만 적재된다. **API 프로세스는 색인을 읽지 않는다.**
- 조회 HTTP 계약, 프런트 팝업, 그리고 이 기능의 유용성을 무료로 재는 장치.

## 2. 목표

검수 화면에서 담당자가 어려운 말을 지목하면, 그 말에 대한 사전 지침(쉬운 표현·뜻·주의·치환 가능 여부)을 팝업으로 보여주고, 안전한 경우에만 편집기 본문을 바꿔 준다.

## 3. 결정과 근거

### 3.1 검색 방식 — 어휘 우선(색인 조회). MVP에 pgvector를 도입하지 않는다

**권고: (a) 어휘 우선.** 이유는 셋이다.

1. **필요한 답을 이미 정확히 준다.** 사전에 있는 말은 트라이 최장일치가 활용형·조사까지 처리해 0ms에 찾고, 전략·위험도·주의까지 함께 준다. 임베딩이 이보다 나은 답을 내는 구간이 없다.
2. **임베딩 최근접은 이 도메인에서 위험한 답을 만든다.** 사전에 없는 말을 벡터로 찾으면 「의미가 가까운 다른 표제어」가 나오는데, 그것을 대체어로 제시하면 `과태료 → 벌금` 사고의 재발이다. 사전 설계가 `replace_strategy`·`risk_level`로 막아 둔 것을 검색 계층이 되살리는 셈이다(`dictionary/DESIGN.md` §2.1).
3. **외부 호출이 없으므로 마스킹·국외이전 경계를 건드리지 않는다.** 조회가 프로세스 안에서 끝나면 master-plan §3.1·§3.2의 no-training·국외이전 고지 검토가 이 기능에 붙지 않는다. 임베딩을 넣는 순간 **벤더 하나가 더 늘고**(Anthropic은 임베딩 API가 없다) 클릭마다 문서 조각이 국외로 나간다.

**P0-5의 「pgvector 기반」은 무엇으로 읽는가.** 그 표현은 2026-08 시점의 구현 스케치이고, 사용자에게 약속한 것은 「어려운 용어를 지목하면 대체어를 추천한다」다. 인프라는 이미 pgvector 이미지라 나중에 벡터를 켜는 것은 마이그레이션 한 장이지 DB 교체가 아니다 — 지금 미루는 데 구조적 비용이 없다. 계획은 이 해석을 backlog에 명시하고 넘어간다.

**미등재어 2차 후보(무료·비벡터).** 정확·활용형 일치가 없으면 같은 색인으로 **복합어 부분 일치**를 찾아 「이 말 안에 든 아는 말」을 설명으로만 제시한다(`중증질환자` → `중증질환`). 대체어 버튼은 주지 않는다. 이것으로도 답이 없으면 후보 0건을 정직하게 돌려준다.

**되돌릴 수 있는 상향 경로(§4 슬라이스 6, 착수 조건부).** §3.6 측정에서 ⑴ 무결과 비율이 높고 ⑵ 그 미스가 복합어·고유명사가 아니라 **사전이 의미로는 덮는데 어휘로 놓치는 말**로 확인되면, `EmbeddingProvider` 포트 + `dictionary_entries.embedding vector(1536)` + HNSW 색인을 별 변경 단위로 추가한다. 그 조건이 확인되기 전에는 착수하지 않는다.

### 3.2 데이터 — 새 표도, 새 적재 경로도 만들지 않는다

**권고: 조회는 API 프로세스가 메모리에 올린 같은 `DictionaryIndex`를 쓴다. `dictionary_entries` 표와 Flyway 마이그레이션은 MVP에 없다.**

- 원천·라이선스·갱신 주기 질문이 새로 생기지 않는다. 조회가 읽는 것은 이미 커밋돼 있고 이미 게이트가 정본과 대조하는 사본 하나다.
- 표로 옮기면 **매칭 구현이 둘이 된다.** 트라이 경계 규칙을 SQL로 다시 쓰는 순간 `DictionaryReferenceContextTest`가 지키는 참조 동등성 밖에 두 번째 매칭기가 생기고, 둘이 갈리면 조회와 프롬프트가 다른 말을 한다.
- 비용: API 프로세스가 1.5MB JSON을 기동 때 한 번 읽는다(트라이 힙 수십 MB 추정 — 슬라이스 2에서 실측한다). `easydoc.dictionary.lookup.enabled=false`면 읽지 않는다.
- **골든 고정 56건은 영향받지 않는다.** 조회는 `findAll`만 쓰고 `buildPromptContext`·`DictionaryContextLines`·`DictionaryPromptContext`·`DictionaryProperties` 기본값을 **한 글자도 건드리지 않는다.** 슬라이스마다 이 테스트가 그대로 통과하는 것을 완료 조건에 둔다.
- **출처 표기의 한계(정직하게 적는다).** `index.json`은 엔트리별 원천·라이선스를 담지 않는다(wire 키 `t/e/d/s/r/p/g/c/x`). MVP 팝업은 엔트리별 출처가 아니라 **사전 단위 표기**(사전 이름 + 라이선스 요약 + `schema_version`)와 `tags`(도메인)를 보여준다. 엔트리별 출처는 색인 스키마 1.1.0이 필요하다 — §7 사용자 결정.

### 3.3 임베딩 provider — MVP에서는 만들지 않는다(슬라이스 6 설계만)

- **`LlmProvider`에 얹지 않는다.** 반환이 `LlmCompletion`이 아니라 벡터라 그 포트를 오염시킨다. 별 포트 `core/llm/EmbeddingProvider.kt`를 둔다.
- **Anthropic은 임베딩 API가 없다.** 제품 기본 provider가 anthropic이므로 임베딩은 필연적으로 두 번째 벤더다(OpenAI `text-embedding-3-small` 권고, 대안 Voyage). 그 벤더의 no-training·국외이전 약관 확인이 §3.1·§3.2에 따라 선행 조건이 된다.
- 단가는 `BigDecimal` 구성값, 미설정은 `null`(0달러가 아니다). 관측은 `MetricsLlmProviderDecorator`를 거울처럼 따르는 decorator로 하고 질의어·본문은 로그에 넣지 않는다.
- 엔트리 임베딩은 **적재 시 일괄**, 조회 질의는 **텍스트 해시로 캐시**한다. 단위 테스트는 `FakeEmbeddingProvider`만 쓰고 실제 API를 부르지 않는다. 실제 호출과 일괄 임베딩 실행은 CLAUDE.md 모델·비용 정책에 따라 사용자 승인이 있을 때만 한다.

### 3.4 API — `POST /dictionary/lookup` 하나 (계약 2.11.0)

`GET /conversions/{id}/terms`를 **버린다.** 담당자는 편집 중이고 서버에 저장된 본문은 이미 낡았다 — 방금 고친 문장의 낱말을 조회하면 없는 것으로 나온다. 위치(offset) 기반 계약은 편집으로 즉시 무효가 되고, 그 위치의 출처는 P0-4 소관이라 여기서 정할 것도 아니다.

- 요청: `{ "text": "<지목한 문자열>" }`. 인증 필수. `text`는 `x-input-limits.max_term_query_chars`(권고 100) 이하, 제어문자 제거 후 비면 422.
- 처리: 질의 문자열에 `findAll`을 돌린다 — 브라우저가 한글을 어절 단위로 선택하므로(`과태료를`) 정확 일치 조회가 아니라 매칭기를 태우는 것이 맞다.
- 응답(snake_case): `{ "query", "candidates": [...], "dictionary": { "name", "license", "schema_version" } }`. 후보 항목은 `term`·`easy_term`·`strategy`·`risk`·`definition`·`caution`·`tags`·`examples[{before,after}]`·`match_kind`·`applicable`.
- **`score`(float)를 두지 않는다.** 어휘 일치에 연속 점수는 뜻이 없고, 있으면 다음 사람이 임의 임계값을 건다. 대신 `match_kind` enum(`exact`|`inflected`|`compound_part`)을 값으로 두고 순서는 서버가 정한다(CLAUDE.md 「도메인 의미가 있는 값은 enum」).
- `applicable`(boolean)은 **치환 버튼을 줄지**의 단일 출처다 — `strategy == substitute`일 때만 참. gloss는 원어를 남겨야 하고 keep은 손대면 안 된다.
- 상태 코드: 200(후보 0건도 200 — 질의는 유효했다), 401, 415, 422, 429(`RateLimitedException` → 기존 `GlobalExceptionHandler`가 `Retry-After`를 싣는다), 500/503. **404를 쓰지 않는다** — 소유권 있는 자원이 아니라 은폐할 것이 없고, 「없음」은 빈 후보 목록이다.
- 헤더는 다른 인증 응답과 같다: `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`.
- 남용 한도: 사용자별 분당 상한(권고 60)을 `easydoc.dictionary.lookup.rate-limit-per-minute`로 받는다. 프로세스 내 카운터라 인스턴스가 늘면 인스턴스별 한도다 — 계약에 그렇게 적는다(사전 전량 긁기를 늦추는 것이 목적이고, 정확한 전역 한도가 목적이 아니다).

### 3.5 프런트 — 선택 기반 팝업. P0-4에 의존하지 않는다

**P0-4 경계 가정(명시):** P0-5는 문단 id·문자 offset·서버가 미리 표시한 용어 구간을 **쓰지 않는다.** 입력은 사용자가 고른 문자열 하나뿐이다. P0-4가 구조 있는 편집기를 만들면 호출부 위치만 옮기고 계약은 그대로다.

- 오늘 결과 패널은 순수 `<textarea>`(`ReviewEditor.tsx:623`)다. **textarea 안에는 밑줄을 그을 수 없다** — 「클릭 가능한 용어」 표시가 원리적으로 불가능하다. 그래서 트리거는 **선택**이다. 한글 어절은 더블클릭으로 브라우저가 통째로 선택하므로 「낱말을 두 번 누르면 팝업」이 P0-5 문구의 「클릭하면 팝업」에 사실상 대응한다.
- 선택이 생기면 선택 근처에 「쉬운 말 찾기」 버튼(44px 이상)을 띄우고, 누르면 팝업이 뜬다. 키보드 단축키도 같은 동작을 준다.
- 팝업은 `role="dialog"`·초점 가둠·Esc 닫기·닫을 때 편집기로 초점 복귀. 후보는 버튼 목록, 결과 개수는 `aria-live`로 알린다. `prefers-reduced-motion`을 지킨다.
- 적용은 `applicable`인 후보에만. `[selectionStart, selectionEnd)`를 `easy_term`으로 갈고 캐럿을 삽입 끝으로 두고 `dirty`를 켠다. gloss·keep 후보는 설명만 보여주고 복사 버튼을 준다.
- 원문 패널(읽기 전용)에서도 조회는 되고 적용 버튼은 없다.
- 테스트: 팝업 단위 테스트(선택 → 요청 → 후보 렌더 → 적용 → dirty), api 클라이언트 테스트, Playwright 1건(`fake` provider의 결정적 결과문에서 알려진 표제어를 골라 팝업 → 적용 → 저장까지).

### 3.6 유용성 측정 — 무료, 새 하네스 없이

`infrastructure/src/test`의 기존 골든 레인 안에 **테스트 하나**를 더한다(새 검증 하네스를 만들지 않는다는 CLAUDE.md 규칙 준수).

- 입력은 이미 커밋된 골든 변환 스냅샷이다. 각 문서에서 `findDifficultWords`가 세는 `DIFFICULT_WORD` 잔존어를 「담당자가 누를 만한 말」의 대리 지표로 삼아, 그중 조회가 후보 1건 이상을 내는 비율(**답변 가능률**)을 잰다. 하한선을 단언하고(초기 권고 0.90, 첫 실측 뒤 확정) 분포를 테스트 출력에 찍는다.
- 함께 찍는 것: 문서별 `findAll` 매치 수, `match_kind` 분포, 무결과 낱말 목록(**골든 문서는 공개 문서라 이 목록은 로그에 남겨도 된다**).
- **파일럿에서 질의어를 수집하지 않는다.** 사용자 문서의 낱말은 본문이고, 로그·메트릭에 본문을 넣지 않는다는 규칙이 우선이다. 운영에서 남기는 것은 개수와 `match_kind` 분포뿐이며(디버그 로그), 무결과 낱말의 실제 목록은 위 골든 오프라인 측정에서만 얻는다.
- 기존 피드백 폼(`PUT /conversions/{id}/feedback`)은 그대로 둔다 — 이 기능 전용 항목을 추가하려면 `conversion_feedback` 스키마 변경이라 별 결정이다(§7).

## 4. 작업 순서

| # | 조각 | 계층 | 크기 |
|---|---|---|---|
| 1 | 계약 | `contracts/` | S |
| 2 | 후보 산출 도메인 | `core` | M |
| 3 | 유스케이스·색인 배선 | `application`+`infrastructure` | M |
| 4 | HTTP 경로 | `api` | S |
| 5 | 팝업 | `frontend` | M |
| 6 | e2e·문서 | 루트 | S |
| Q | 답변 가능률 측정 | `infrastructure` 테스트 | S |
| 7 | 임베딩(조건부·설계만) | 전 계층 | L |

**조각 1 — 계약(S).** `contracts/easy-doc-v1.yaml` 2.11.0: `POST /dictionary/lookup`, 요청·응답 스키마, `x-input-limits.max_term_query_chars`, 422·429 예시. 실패하는 계약 테스트 골격(`api/src/test/.../DictionaryLookupContractTest.kt`)을 같은 조각에 둔다. 완료 조건: 계약 테스트가 「경로 없음」으로 실패한다.

**조각 2 — 후보 산출(M).** 새 파일 `core/src/main/kotlin/kr/easydoc/core/dictionary/TermLookup.kt`: `TermQuery`(정제·상한), `TermCandidate`, `TermMatchKind`, 정확 → 활용형 → 복합어 부분 순서 규칙, `applicable` 판정. 테스트 `core/src/test/.../TermLookupTest.kt` 선행. 완료 조건: `과태료를`이 `과태료`(gloss·applicable=false)를 내고, `중증질환자`가 `compound_part`만 내고, `DictionaryReferenceContextTest`가 그대로 통과한다. **기존 dictionary 파일은 수정하지 않는다.**

**조각 3 — 유스케이스와 배선(M).** `application/dictionary/TermCandidateSource.kt`(포트)·`TermLookupService.kt`(상한·미사용 시 빈 결과), `infrastructure/dictionary/IndexedTermCandidateSource.kt`, `DictionaryLookupProperties.kt`(`enabled`·`max-query-chars`·`rate-limit-per-minute`). 색인 적재를 `infrastructure/dictionary/DictionaryConfiguration.kt`로 뽑고 `ConversionWorkerConfiguration`이 그 빈을 받게 고친다. 완료 조건: 「주입을 껐으면 색인을 읽지도 않는다」 불변식이 worker에서 유지되고, 조회를 껐으면 API도 읽지 않는다. 기동 시 색인 적재 시간·힙 증가를 한 번 실측해 계획에 적는다.

**조각 4 — HTTP(S).** `api/dictionary/DictionaryLookupController.kt`·`DictionaryLookupDtos.kt`, 사용자별 분당 한도(`RateLimitedException` 재사용), `api/src/main/resources/application.yml`에 `easydoc.dictionary.lookup.*`. 완료 조건: 계약 테스트와 실경로 테스트(`DictionaryLookupReachTest`)가 200·401·415·422·429와 두 헤더를 고정한다.

**조각 5 — 팝업(M).** `frontend/src/api/types.ts`·`client.ts`에 타입·호출 추가, `frontend/src/components/TermLookupPopover.tsx`(+`.test.tsx`), `ReviewEditor.tsx`에 트리거·적용 배선. 완료 조건: `npm run check`·`npm run test -- --run`·`npm run build` 통과, 팝업 접근성 단언(dialog·초점 복귀·Esc·aria-live) 포함.

**조각 6 — e2e·문서(S).** `frontend/e2e/`에 조회 흐름 1건, `docs/kotlin-redevelopment-backlog.md` §1 표의 사전 행과 master-plan §4.1 P0-5 상태 갱신(「pgvector는 미도입 — 해석 기록」 포함).

**조각 Q — 측정(S).** 조각 2 뒤 언제든. `infrastructure/src/test/.../TermLookupCoverageTest.kt`. 하한선 숫자는 첫 실측을 보고 정한다.

**조각 7 — 임베딩(L·조건부).** 조각 Q의 결과가 §3.1의 두 조건을 만족할 때만 착수. 그때의 변경 단위: `EmbeddingProvider` 포트 + 어댑터 + 관측 decorator, Flyway `V10__dictionary_entries.sql`(엔트리 표 + `embedding vector(1536)` + HNSW), 적재 경로(`load-dictionary` 프로필 — `migrate`·`rotate-keys`와 같은 형태), 질의 캐시, 계약 2.12.0(`match_kind`에 `semantic` 추가). **착수 전 사용자 비용·범위 승인이 별도로 필요하다.**

## 5. 완료 정의

- [ ] `cd backend-kotlin && ./gradlew build` 통과 (ktlint·detekt·모듈 경계·`checkDictionaryIndex` 포함)
- [ ] `DictionaryReferenceContextTest` 고정 56건이 손대지 않은 채로 통과
- [ ] `cd frontend && npm run check && npm run test -- --run && npm run build` 통과
- [ ] `docker compose config`와 CI 프로필 config 통과
- [ ] 계약·Kotlin DTO/컨트롤러·프런트 타입·호출부가 한 변경 단위로 검증됨
- [ ] Playwright 조회 흐름 통과
- [ ] 답변 가능률 실측값이 계획에 기록됨
- [ ] 유료 외부 호출 0건 — 이 범위에는 외부 API 호출이 없다

## 6. 리스크

1. **발견성.** textarea에서는 용어를 표시할 수 없어 「선택 후 조회」를 모르는 사용자는 기능을 못 쓴다. 완화: 결과 패널 라벨 옆 상시 안내 한 줄 + 더블클릭 동작. 근본 해결은 구조 있는 편집기(P0-4)를 기다린다.
2. **API 프로세스 무게.** 색인 적재로 기동이 느려지거나 힙이 커질 수 있다. 완화: 조각 3에서 실측, `enabled` 스위치, 필요하면 지연 적재.
3. **후보 0건의 체감.** 사전 2,179건은 실무 문서를 다 덮지 않는다. 완화: 빈 결과를 「사전에 없는 말입니다」로 정직하게 말하고 복합어 부분 일치를 함께 보여준다. 벡터로 얼버무리지 않는다.
4. **gloss 다수.** 표제어 대부분이 `status=review`라 `substitute`를 받지 못한다(`dictionary/docs/easy-doc-integration.md` §6) — 치환 버튼이 뜨는 후보가 예상보다 적을 수 있다. 조각 Q가 `strategy` 분포를 함께 찍어 이 비율을 먼저 드러낸다.
5. **사전 갱신과 조회의 결합.** 색인이 갱신되면 조회 결과도 바뀐다. 기존 `checkDictionaryIndex`와 `schema_version` 단언이 그대로 이 경로의 방어선이다 — 새 장치를 만들지 않는다.

## 7. 사용자 결정이 필요한 것

1. **pgvector 미도입 해석.** P0-5의 「pgvector 기반」을 「어려운 용어 지목 → 대체어 추천」이라는 요구로 읽고, 벡터는 §3.1 조건이 확인될 때만 착수한다 — 이 해석을 확정할지.
2. **엔트리별 출처 표기.** 지금 색인에는 원천·라이선스가 없다. MVP는 사전 단위 표기로 가고, 엔트리별 출처가 B2G 납품에 필요하면 `dictionary/` 색인 스키마 1.1.0(원천 코드 추가)이 별 작업으로 붙는다. 필요 여부를 정할지.
3. **답변 가능률 하한선.** 조각 Q의 단언값(권고 0.90)을 첫 실측 뒤 확정한다 — 그 숫자를 누가 정할지.
4. **조회 전용 피드백 항목.** 팝업 사용·적용 여부를 파일럿 판정에 쓰려면 `conversion_feedback` 스키마 변경이다. MVP에서는 빼는 것을 권고한다.
5. **분당 상한 60.** 남용 방지용 초기값이며 근거 있는 실측값이 아니다. 운영 중 조정 가능한 구성값으로 두는 것까지가 이 계획의 약속이다.
