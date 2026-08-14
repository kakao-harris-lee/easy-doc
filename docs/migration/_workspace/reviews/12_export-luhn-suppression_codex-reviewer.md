# 게이트 12 — codex 독립 리뷰 (`12_export-luhn-suppression`)

작성: `codex-reviewer` · **1회차** · 실행 2026-08-14 17:17–17:28 KST (11분 5초)

> **§3은 codex 원문 무편집이다.** Claude의 판정·심각도 조정·중복 병합·표현 손질이 들어가 있지 않다.
> 정리처럼 보이는 서술은 전부 §4로 분리했고, §4에서도 옳고 그름은 판정하지 않는다.
> 종합·판정은 `migration-reviewer`(2차 교차 종합)와 리더의 몫이다.

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 어간 | `12_export-luhn-suppression` (**리더 지정값 그대로**. 임의 슬러그를 만들지 않았다) |
| 대상 리비전 (요청) | `cd23aec..516c0e9` |
| **HEAD 실측** | `516c0e93b5d62aa33148491c5e6fe9358e934e3c` — `516c0e9`와 **동일**(대조 완료) |
| **merge-base 실측** | `cd23aecae1ccb0540530213014b8cf807417b050` = `cd23aec` 자신 → `--base cd23aec`가 요청 범위와 **정확히 일치** |
| 작업 트리 | 추적 대상 변경 0건. untracked 3건(`.playwright-mcp/`, `.doc` 2개)은 branch 모드라 대상 아님 |
| 모드 | `adversarial` (위험 영역: 마스킹 파이프라인 · 내보내기 · 게이트 장치) |
| scope / base | `auto`(미지정 — `--base`가 우선) / `cd23aec` |
| 헬퍼 | `~/.claude/plugins/cache/openai-codex/codex/**1.0.6**/scripts/codex-companion.mjs` (plugins cache, 최신 버전 자동 선택) |
| codex CLI | `codex-cli 0.147.0` |
| **스크립트 종료 코드** | **`0`** — 리뷰 근거로 유효 (`5`·`7` 아님) |
| job id | `review-mssocgfq-fgvpvb` |
| codex session ID | `019fff59-2bf8-7e53-9591-abaf98e5fcca` (재조회: `codex resume 019fff59-2bf8-7e53-9591-abaf98e5fcca`) |
| 소요 | 11분 5초 |

**실행 명령 (인자 그대로)**

```
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base cd23aec "<focus 전문 — §2>"
```

**헬퍼 실명령**

```
node ~/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
  adversarial-review --base cd23aec <focus>
```

**스크립트 stderr 대상 판정 두 줄 (무편집)**

```
codex-review: 리뷰 대상 = branch diff vs cd23aec
codex-review: 대상 판정 = non-empty (merge-base=cd23aecae1cc, 변경 파일 34개 (branch 모드는 커밋된 변경만 센다))
```

### 1.1 제공한 맥락

focus text 안에 **단정문(채점 기준)으로만** 주입했다. 계획 문서 본문이나 다른 산출물은 넘기지 않았다.

- 재개발 전환 전제(판정 기준 = 요구사항 충족, Python은 정답 아님), 마스킹 선행 불변식, 마스킹 범주 2종
- 축별 대상 파일 경로 — Kotlin `core` 2개, 하네스 스크립트 2개, 테스트 4개, fixture·하한 파일 4개, CI 워크플로
- 축별 "지켜야 하는 조건"과 "깨지면 무엇이 새는가"

### 1.2 의도적으로 넘기지 않은 것 (독립성 보존)

1. `07_privacy-gate_masking-verdicts.md` §4-novies·§4-decies의 **판정 결과**
2. `reviews/11_suppression-and-domains_cross.md` §10의 지적 목록과 직전 회차 두 리뷰의 결론
3. 리더가 알린 **현재 상태 실측**(8/8 exit 0 · 성질 152건 · 단언 584개 · 불충족 0 · 갈림 36)

셋 다 주면 "우리가 이미 내린 결론"을 확인시키는 유도가 되어 독립 관점이 회수된다. **조건(채점 기준)만 주고
판단은 codex가 하게 뒀다.** 다만 조건 자체는 위 정본 문서들에서 문언 그대로 가져왔다 — 이것은 유도가 아니라 채점 기준이다.

### 1.3 민감 데이터

프롬프트에 사용자 문서 본문·실제 암호문·키·개인정보를 싣지 않았다. 카드번호·주민등록번호는 전부 합성 값이고
저장소 코드·fixture 안에만 있으며 **프롬프트에 값을 옮기지 않았다.** (`privacy-gate` 감사 대상 항목)

### 1.4 회차 관계

게이트 11과 표면이 일부 겹치나(억제 계약), 그 사이 대상 코드가 크게 바뀌었으므로 `재호출 지침`에 따라
**이전 회차의 해소 여부를 묻지 않고 새 리뷰로 취급**했다. 이전 리뷰 파일은 덮어쓰지 않았다.

---

## 2. 전달한 프롬프트 전문

```
이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot로 재개발하는 중이다. 판정 기준은 "Python과 같은 값이 나오는가"가 아니라 요구사항·정책을 충족하는가다(Python은 폐기 대상이며 정답이 아니다). 예외는 정책 불변식 하나 — 사용자 문서 텍스트는 마스킹 파이프라인을 통과한 뒤에만 LLM으로 전달된다. 마스킹 범주는 주민등록번호(외국인등록번호 포함)·카드번호 2종뿐이다.

아래 넷을 축으로 위반·누락·경계 결함을 찾아라. 각 축의 "조건"은 지켜져야 하는 요구사항이다.

[1] CARD Luhn accept 훅의 방향 정확성 — backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt (acceptsLuhn, MaskPattern.accept, 매치 필터링 경로)
조건: Luhn 도입은 재현율을 낮추면 안 된다. 유효한 카드번호는 구분자 변형(하이픈·공백·혼합·무구분)과 전각/아라비아-인도 숫자 표기에서도 여전히 마스킹돼야 한다.
찾아라: (a) Luhn 계수를 구분자 제거 전 문자열에 하거나 코드포인트 뺄셈(c - '0')으로 하는 자리 — 전각 숫자에서 값이 음수가 되어 Luhn이 조용히 틀린 답을 낸다. (b) 자리 가중(뒤에서 짝수 번째를 두 배)의 인덱스 방향이 뒤집혔거나, 길이가 16이 아닐 때·홀수일 때의 처리. (c) accept가 거부한 매치가 그 구간을 점유해 뒤이은 다른 패턴(RRN 등)이나 같은 패턴의 다음 후보가 그 자리를 판정할 기회를 잃는 경로 — 거부는 자리를 비워 줘야 한다. (d) 거부된 매치가 자리표시자 채번·복원표·미해결 자리표시자 보고에 흔적을 남기는지. (e) 이 배치에서 fixture·테스트의 합성 카드번호 19건을 Luhn 유효 값으로 교체했는데, 그 교체로 원래 잡아야 할 성질을 검사하지 않게 된 케이스가 있는지. Luhn 도입 전후를 가르는 음성 케이스(keeps-card-luhn-invalid)가 단 하나라면 그것이 지워지거나 완화될 때 무엇이 남는지도 답하라.
깨지면: 실제 카드번호가 마스킹 없이 LLM과 내보내기 문서로 나간다.

[2] 지문 기반 억제 계약의 잔여 표면 — .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py, tests/test_privacy_scanner.py
조건: 억제 표기(# privacy-allow: RULE @8자지문)는 (path, call_ref, rule_id, content_digest) 넷이 모두 일치하는 적중 하나만 억제한다. 형식 진술은 |suppressed_by(marker)| <= 1 이다. 구간·줄·영역을 억제하지 않는다. call_ref 또는 content_digest를 산출할 수 없는 적중은 억제 대상이 아니다(닫힘 규칙). 지문 재생성 도구 --update-markers는 CI에서 실행될 수 없어야 하고, 사유 문구를 건드리지 않아야 한다.
찾아라: (a) 8자(32비트) 절단 지문의 충돌로 다른 내용의 호출이 억제되는 경로와 그 확률적 표면. (b) 같은 파일에서 (call_ref, rule_id, content_digest)가 동일한 호출이 둘 이상일 때 표기 하나가 둘을 누르는지 — 상한 1이 실제로 강제되는지 코드에서 짚어라. (c) --update-markers의 CI 금지 이중 장치를 우회하는 경로(환경변수 위조, 로컬 실행 후 커밋, 래퍼·훅 경유, 함수 직접 호출). (d) call_ref가 순서 의존이라 논리 줄 안 호출 순서를 바꾸면 표기가 다른 호출로 옮겨 붙는지. (e) 표기 문법이 틀렸을 때(지문 누락·형식 오류) 결과가 "억제 없음"이 아니라 "억제 성공" 또는 "적중 없음"으로 읽히는 경로. (f) 로그 호출 탐지 정규식이 닿지 않는 호출 형태(LoggerFactory 체인, 정적 import getLogger, 명명 수신자)와, 그 도달 정의가 스캐너가 아니라 테스트 파일에 재구현돼 두 정의가 갈릴 수 있는 구조.
깨지면: 평문 개인정보를 로그로 내보내는 호출이 검사 없이 통과한다.

[3] export 단언의 경계 판정과 복원 세 성질 — backend-kotlin/core/src/main/kotlin/kr/easydoc/core/easyread/Export.kt, backend-kotlin/core/src/test/kotlin/kr/easydoc/core/easyread/ExportTest.kt, backend-kotlin/core/src/test/kotlin/kr/easydoc/core/ExportParityTest.kt, parity/fixtures/export/export.json
조건: 파일명 정제는 경로 구분자·따옴표·제어문자·콜론·파이프를 남기지 않고 길이 상한을 넘지 않는다. Content-Disposition은 RFC 5987 filename*=UTF-8'' 로 퍼센트 인코딩되어 헤더 전체가 US-ASCII 안에 있어야 한다. 자리표시자 복원은 (i) 모르는 자리표시자를 그대로 보존하고 (ii) 치환을 정확히 1회만 돌며 (iii) 등록된 것만 원문으로 되돌린다.
찾아라: (a) 단언을 "값이 아니라 경계"로 세운 결과 정제 규칙이 깨져도 통과하는 조합 — 금지 문자를 제거하는 대신 다른 금지 문자로 치환하거나, 확장자를 줄기로 밀어 넣거나, 길이 상한을 코드포인트가 아닌 UTF-16 단위·바이트로 재어 깨진 서로게이트 쌍이나 결합 문자가 잘려 남는 경로. (b) 퍼센트 인코딩 대상 문자 집합(isAsciiUnreserved)이 RFC 5987 attr-char와 어긋나 인코딩되지 않은 문자가 헤더에 남거나, 반대로 과도 인코딩으로 파일명이 훼손되는 자리. (c) 자리표시자 치환이 복원값 안의 자리표시자 모양 문자열을 다시 치환하는 경로(사용자 본문이 개인정보로 바뀌는 주입 경로)와, 치환 문자열에서 달러 기호·역슬래시가 특수 해석되는 자리. (d) 파일명 정제 결과가 빈 문자열·점만·예약어가 되는 경로. (e) 복원 구현을 export에 복제하지 않고 마스킹 쪽 restoreForExport 하나로 둔 결정이 실제로 지켜지는지, 아니면 export 경로에 두 번째 복원 로직이 들어와 있는지.
깨지면: 자리표시자가 남은 채 문서가 기관 밖으로 나가거나 다운로드 응답 헤더가 아예 전송되지 않는다.

[4] 선언한 범위 대 실제 도달 — .claude/skills/python-kotlin-parity/scripts/compare_parity.py, .../dump_parity_fixtures.py, .github/parity-case-floor.txt, .github/parity-declared-floor.txt, backend-kotlin/parity-domains.txt, backend-kotlin/core/src/test/kotlin/kr/easydoc/core/ParityDeclarationSyncTest.kt, .github/workflows/ci.yml
조건: 8개 도메인이 전부 선언되면 비교기는 부분 게이트가 아니라 전체 게이트로 돌고, 통과 조건은 종료 코드 0이며 종료 코드 3은 실패다(부분 게이트 사면이 소멸한다). 선언과 정본 상태의 어긋남은 양방향으로 잡혀야 한다 — 선언했는데 생산자가 없는 경우와 생산자가 있는데 선언이 없는 경우 둘 다.
찾아라: (a) 이 게이트가 어디서 도는가 — CI 잡에 실제로 배선돼 있는지 워크플로 파일에서 실행 경로를 짚고, 배선돼 있다면 실패 종료 코드가 잡 실패로 전파되는지 확인하라. 로컬에서만 도는 게이트면 그렇게 답하라. (b) exit 3을 여전히 성공으로 읽는 호출부·조건문·CI 스텝이 남아 있는지. (c) 비교기가 자기가 생성한 fixture를 자기 선언과 대조하는 자기 참조 구조가 남았는지 — 기준이 검사 대상 자신에게서 나오면 그 검사는 아무것도 재지 않는다. (d) 하한 파일(parity-case-floor·parity-declared-floor)이 빈 선언·placeholder·0건에서 통과하는지. (e) ParityDeclarationSyncTest가 한 방향만 잡는지. (f) 성공·실패 판정이 대리 지표로 이뤄지는 자리 — 지적 건수를 변경 여부로, 종료 코드 0을 "검토했다"로, 테스트 통과를 "그 경로가 실제로 돌았다"로 바꿔 읽는 곳. (g) 이 배치가 도입한 장치들(CARD Luhn accept, 지문 억제 계약, export 단언 117개, 전체 게이트 전환) 중 떼어내도 아무 테스트가 깨지지 않는 것이 있으면 지목하라.
깨지면: 게이트가 초록으로 보이면서 실제로는 아무것도 검증하지 않는다.

Python 원본과 값이 다르다는 사실 자체는 결함이 아니다. 요구사항·정책 위반과 검증 공백만 지적하라.
```

---

## 3. codex 원문 출력 — **무편집**

아래는 `codex_out_12.txt` 전문이다. 한 글자도 고치거나 지우거나 덧붙이지 않았다. 잘림 없음.

```
# Codex Adversarial Review

Target: branch diff against cd23aec
Verdict: needs-attention

No-ship: 현재 parity 전체 비교와 privacy 스캐너가 모두 exit 0이지만, 실제 카드 누락과 개인정보 로그 게이트 우회가 가능한 반례가 남아 있다.

Findings:
- [high] 거부된 CARD 매치가 겹치는 유효 카드 후보를 삼킨다 (backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt:691-699)
  candidateSpans는 findAll이 비중첩 매치를 결정한 뒤 accept를 필터링한다. 따라서 거부된 매치도 정규식 탐색 커서를 끝까지 전진시킨다. `0000-4111-1111-1111-1111`에서는 첫 후보 `0000-4111-1111-1111`만 반환되어 Luhn 거부되고, 위치 5의 유효한 `4111-1111-1111-1111`은 다시 검사되지 않는다. 원문·view 경로 모두 같은 구조라 실제 카드 부분이 평문으로 남는다. 현재 테스트는 거부 후보 뒤의 비중첩 RRN만 검사해 이 경계를 놓친다.
  Recommendation: 거부 시 다음 코드포인트부터 겹침 탐색을 재개하는 루프로 바꾸고, 거부 후보 내부에서 시작하는 유효 CARD/RRN 후보가 마스킹되며 거부 후보는 항목·채번·복원표에 남지 않는 회귀 테스트를 추가한다.
- [high] 억제 표기가 call_ref와 결속되지 않는다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:1062-1072)
  _marker_touches는 rule_id, 8자리 digest, 물리 줄만 비교하고 call_ref는 전혀 비교하지 않는다. Marker에도 승인 당시 call_ref가 없다. 읽기 전용 합성 probe에서도 동일 digest의 `receiver_a`와 `receiver_b` 적중이 각각 같은 표기로 억제됐다. 따라서 수신자·메서드가 바뀌어 call_ref가 달라져도 표기가 이동하며, 다른 인자 내용이 같은 32비트 SHA-256 접두사를 갖도록 교체되면 단일 적중이므로 상한-1 방어도 발화하지 않는다.
  Recommendation: 표기에 call_ref와 content digest를 모두 결속하거나 둘을 포함한 충돌 저항성 지문을 저장한다. 32비트 접두사 대신 최소 128비트 또는 전체 digest를 사용하고, 같은 digest지만 다른 call_ref인 교체가 억제되지 않는 테스트를 추가한다.
- [high] 실제 로그 호출 형태가 스캐너와 도달 검사 양쪽에서 빠진다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:204-233)
  LOG_CALL은 이름이 정확히 log/logger인 괄호 호출만 인식한다. LoggerFactory 체인, 정적 import getLogger, kotlin-logging 람다, SLF4J fluent 호출은 탐지되지 않으며 테스트는 이를 strict xfail로 정상화한다. 저장소에도 ContractErrorReportValve.kt:104-106의 LoggerFactory 체인이 이미 존재하지만 전체 스캐너는 이를 보고하지 않고 exit 0이었다. 그 인자를 exception.message나 문서 본문으로 바꿔도 LOG-BODY 게이트가 발화하지 않는다.
  Recommendation: 체인·정적 import·람다·fluent 호출을 호출 단위로 인식하도록 탐지기와 독립 도달 정의를 확장하고, 현재 xfail을 모두 통과 단언으로 전환한다. 실제 ContractErrorReportValve 호출에 민감한 인자를 주입하는 음성 변이도 CI에서 실패시킨다.
- [high] 새 canonical producer가 생기면 전체 게이트가 다시 부분 성공으로 후퇴한다 (backend-kotlin/core/src/test/kotlin/kr/easydoc/core/ParityDeclarationSyncTest.kt:152-168)
  ParityDeclarationSyncTest는 BUILDERS가 아니라 커밋된 fixture 디렉터리만 읽고, 대응 JSON이 없으면 mapNotNull로 조용히 건너뛴다. 따라서 BUILDERS에 새 ready producer를 추가하면서 fixture·Kotlin producer·선언을 빠뜨리면 이 테스트는 보지 못한다. CI는 canonical_count 증가를 허용하고 declared_count와 달라지면 기존 도메인만 --only-domain으로 비교한 뒤 exit 3을 성공으로 바꾼다. 즉 8/8에서 사라졌다고 선언한 부분 게이트 사면이 생산자 추가 한 번으로 되살아난다.
  Recommendation: BUILDERS의 기계 판독 가능한 전체 도메인·상태를 fixture와 독립적으로 읽어 선언, fixture, Kotlin actual과 양방향 정확 일치를 강제한다. 8/8 전환 이후에는 CI의 exit-3 성공 분기를 제거하고 새 도메인도 선언·fixture·producer가 모두 준비되기 전까지 실패시킨다.
- [medium] update_markers의 CI 금지는 직접 호출과 래퍼를 막지 못한다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:1430-1444)
  CI 검사는 main의 CLI 분기에만 있고 update_markers 함수 자체에는 없다. 따라서 모듈을 import한 래퍼나 함수 직접 호출은 CI 환경에서도 지문을 갱신할 수 있다. 워크플로 방어도 yml 안의 리터럴 `--update-markers`만 검색하므로 래퍼·로컬 action·python -c 호출을 보지 못하며, 환경변수를 지운 CLI 호출도 main 검사를 우회한다. 갱신 후 같은 실행에서 스캔하면 변경된 호출이 기존 사유로 다시 억제된다.
  Recommendation: update_markers 내부에서도 CI 실행을 거부하고, CI에서는 읽기 전용 체크아웃 또는 갱신 전후 git diff 불변을 강제한다. 워크플로뿐 아니라 호출 가능한 래퍼·로컬 action 전체를 검사하는 회귀 테스트를 추가한다.
- [medium] 파일명 정제가 전체 제어문자와 Unicode 절단 경계를 보장하지 않는다 (backend-kotlin/core/src/main/kotlin/kr/easydoc/core/easyread/Export.kt:54-104)
  금지 정규식은 C0와 DEL만 제거해 C1 제어문자 U+0080–U+009F를 그대로 남긴다. 또한 take(80)는 코드포인트가 아니라 UTF-16 코드 유닛을 자르므로 BMP 문자 79개 뒤의 보충평면 문자에서 상위 서로게이트만 남길 수 있다. 이후 UTF-8 변환은 문자를 대체해 다운로드 이름을 훼손하지만, fixture는 U+0000/U+001F/U+007F와 BMP 한글 길이만 검사하므로 117개 단언이 모두 통과한다.
  Recommendation: Character.isISOControl 또는 Unicode Cc 범주로 제어문자를 제거하고, 코드포인트 또는 grapheme 단위로 상한을 적용한다. C1, 보충평면 경계, 결합문자 및 Windows 예약 basename을 포함한 테스트를 추가한다.

Next steps:
- 위 반례들을 음성 변이 테스트로 고정한 뒤 Kotlin 테스트, privacy scanner, parity 전체 비교를 다시 실행한다.
- 특히 scanner와 parity CI가 반례에서 비정상 종료하는 증거가 생기기 전까지 병합을 차단한다.
```

**원문 구획 끝.** 위 블록 안에는 Claude가 쓴 문장이 한 줄도 없다.

---

## 4. 정리(가공) — 목록화만. 판정하지 않는다

이 구획은 §3을 **찾아보기 쉽게 표로 옮긴 것**이다. 심각도는 **codex가 스스로 붙인 라벨**을 그대로 옮겼고
Claude가 조정하지 않았다. 중복 병합·표현 손질·기각·오탐 주석을 넣지 않았다.

### 4.1 지적 목록 (codex 라벨 그대로)

| # | codex 라벨 | 제목 (원문 그대로) | codex가 댄 근거 위치 (그대로 옮김) | 대응 focus 축 |
|---|---|---|---|---|
| C-1 | `high` | 거부된 CARD 매치가 겹치는 유효 카드 후보를 삼킨다 | `Masking.kt:691-699` | ①(c)(d) |
| C-2 | `high` | 억제 표기가 call_ref와 결속되지 않는다 | `scan_privacy_invariants.py:1062-1072` | ②(a)(b)(d) |
| C-3 | `high` | 실제 로그 호출 형태가 스캐너와 도달 검사 양쪽에서 빠진다 | `scan_privacy_invariants.py:204-233`, `ContractErrorReportValve.kt:104-106` | ②(f) |
| C-4 | `high` | 새 canonical producer가 생기면 전체 게이트가 다시 부분 성공으로 후퇴한다 | `ParityDeclarationSyncTest.kt:152-168` | ④(a)(b)(e) |
| C-5 | `medium` | update_markers의 CI 금지는 직접 호출과 래퍼를 막지 못한다 | `scan_privacy_invariants.py:1430-1444` | ②(c) |
| C-6 | `medium` | 파일명 정제가 전체 제어문자와 Unicode 절단 경계를 보장하지 않는다 | `Export.kt:54-104` | ③(a) |

**전체 판정**: `Verdict: needs-attention` / `No-ship` 한 줄이 붙었다 (§3 4·6행).

### 4.2 codex가 **지적을 내지 않은** 축·하위 질문

§7 규칙에 따라 **그대로 기록한다. Claude가 대신 지적을 만들어 채우지 않았다.** 아래는 "문제 없음"이라는
판정이 아니라 "이번 회차 codex 출력에 해당 항목에 대한 서술이 없다"는 사실 기록이다.

| 축 | 물었으나 codex 출력에 서술이 없는 하위 질문 |
|---|---|
| ① Luhn | (a) `Character.digit` 계수 단위·전각/아라비아-인도 숫자, (b) 자리 가중 인덱스 방향·길이 16 검사, (e) fixture 카드값 19건 교체의 부작용, `keeps-card-luhn-invalid`가 유일 케이스일 때의 함의 |
| ② 억제 | (e) 표기 문법 오류가 "억제 성공"으로 읽히는 경로 |
| ③ export | (b) `isAsciiUnreserved` 대 RFC 5987 `attr-char`, (c) 복원 재치환·주입 경로·치환 문자열 `$`/`\` 특수 해석, (e) 복원 로직 두 벌 여부 |
| ④ 게이트 | (c) 비교기 자기 참조 구조, (d) 하한 파일이 빈 선언에서 통과하는지, (f) 대리 지표, (g) 떼어도 안 깨지는 장치 지목 |

※ ②(a) 8자 지문 충돌과 ②(b) 상한 1은 C-2 본문 안에서 함께 다뤄졌고, ①(c)와 ①(d)는 C-1 본문과
Recommendation에서 함께 다뤄졌다. ③(d)의 "예약어"는 C-6 Recommendation에만 등장한다(본문 지적은 아니다).

### 4.3 전제 확인이 필요한 서술

codex 출력을 삭제하지 않고 원문에 그대로 뒀다. 아래는 **`migration-reviewer`가 판단할 대상**이며
여기서 옳고 그름을 정하지 않는다.

- C-3의 "테스트는 이를 strict xfail로 정상화한다" 및 C-4의 "CI는 … exit 3을 성공으로 바꾼다"는
  **이 배치에서 바뀐 파일에 대한 동작 주장**이다. 실행으로 대조할 수 있는 진술이므로 교차 종합에서 확인 대상이다.
- C-2의 "읽기 전용 합성 probe에서도 … 각각 같은 표기로 억제됐다"는 codex가 **직접 실행한 결과**라고
  적은 것이다. stderr 로그에 codex가 `python -c`로 Luhn 계산과 스캐너 probe를 돌린 기록이 있다.
- C-1의 반례 문자열 `0000-4111-1111-1111-1111`은 codex가 만든 합성 값이다(실제 카드 아님).

### 4.4 codex가 실제로 읽은 것 (stderr 근거)

대상 판정이 "34개 파일"이었고, stderr 명령 로그상 codex는 최소 다음을 직접 열었다 —
`Masking.kt`, `MaskingTest.kt`, `Export.kt`, `ExportTest.kt`, `ParityDeclarationSyncTest.kt`,
`scan_privacy_invariants.py`, `tests/test_privacy_scanner.py`, `compare_parity.py`,
`dump_parity_fixtures.py`, `ContractErrorReportValve.kt`, `.github/workflows/ci.yml`,
`parity/fixtures/*`. 또한 `python -c`로 **Luhn 체크섬을 직접 계산**했고, `git status`·`git diff --check`·
`git rev-parse --short=12 HEAD`로 대상 리비전을 스스로 확인했다.

---

## 5. 미실행·실패 항목

**없다.** 이 회차에서 실패·재시도·잘림은 발생하지 않았다.

| 점검 | 결과 |
|---|---|
| 스크립트 종료 코드 | `0` — 리뷰 근거로 유효 |
| 대상 판정 | `non-empty` (34개 파일). exit 7 아님 |
| 출력 비었음(exit 5) | 해당 없음 |
| 재시도 | 불필요 (1회 실행 성공) |
| 출력 잘림 | 없음 — `Next steps:` 블록까지 온전 |
| ⚠ codex 리뷰 누락 | **해당 없음.** 독립 관점이 확보된 상태다 |

---

## 6. 수신

- **→ `migration-reviewer`**: 이 파일 경로를 그대로 전달한다. 요약본이 아니라 **§3 원문**이 입력이다.
  2차 교차 종합(`12_export-luhn-suppression_cross.md`)은 같은 어간으로 이 파일을 찾는다.
- **→ 리더**: codex의 견해는 `needs-attention` / `No-ship`이다. **이 에이전트는 판정을 붙이지 않는다** —
  Phase 2 종료 조건 충족 여부의 판정은 교차 종합과 리더의 몫이다.

이 에이전트는 코드를 수정하지 않았고, codex 지적의 옳고 그름을 판정하지 않았으며, 심각도를 조정하거나
지적을 병합·삭제하지 않았다.
