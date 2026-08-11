# focus text 라이브러리

`codex-review.sh adversarial`에 붙일 focus 문장 모음. **그대로 복사하지 말고** 실제로 바뀐 파일과 Phase에 맞게 골라 3~5개 축으로 조합하라. 열 개를 한 번에 넣으면 codex가 전부 얕게 본다.

각 문장은 (1) 불변식을 단정문으로, (2) 대조할 Python 원본 경로, (3) 위반 시 결과 순으로 되어 있다. 이 세 요소가 codex의 심각도 판단을 바꾸므로 임의로 줄이지 마라.

아래 Python 경로는 2026-08-11 기준 `main`에 실제로 존재하는 파일이다. 원본 구조가 바뀌면 경로를 먼저 확인하고 고쳐 쓴다 — 없는 경로를 주면 codex가 추측으로 메운다.

---

## Phase 0 / 3 — 계약·인증

```
"이 저장소의 HTTP 계약은 v1으로 동결되어 있다: JSON 필드는 snake_case, 오류 본문은 {"detail": ...}, 상태 코드는 가입 201·업로드 202·삭제 204·입력 오류 422·충돌 409·인증 401·소유권 은닉 404다. Python 원본 app/api/auth.py, app/api/documents.py, app/api/workspaces.py, app/api/errors.py, app/exceptions.py와 대조해 Kotlin 구현이 다른 상태 코드나 다른 본문 형태를 내는 경로를 찾아라. Spring 기본 ProblemDetail이 전역 예외 매퍼를 우회해 그대로 나가는 경로도 포함하라. React가 이미 이 형태에 의존하므로 어긋나면 화면이 조용히 깨진다."
```

```
"인증 계약: Authorization: Bearer, JWT HS256, sub·exp·typ의 의미가 Python 발급 토큰과 호환되어야 한다. app/services/auth.py, app/api/deps.py와 대조해 클레임 이름·타입·만료 해석·서명 검증 순서가 다른 지점을 찾아라. Argon2 PHC 문자열은 그대로 검증되어야 하고, 재해시는 로그인 성공 시에만 일어나야 한다. 호환이 깨지면 기존 사용자가 전원 로그인 불가가 된다."
```

```
"접근 통제 계약: 다른 사용자의 자원은 403이 아니라 404다(자원 존재 자체를 숨긴다). 소유권 검사가 조회 뒤에 오거나, 예외 매퍼가 403/401을 흘리거나, 오류 메시지·응답 시간·헤더 차이로 자원 존재가 드러나는 경로를 찾아라. 이것은 스타일이 아니라 보안 계약이며 절체 즉시 중단 기준에 포함된다."
```

## Phase 2 — 순수 도메인 parity

```
"이 Kotlin 코드는 Python 원본 app/privacy/masking.py의 동작을 그대로 재현해야 한다. 경계값(빈 문자열, 공백만, null, 매우 긴 입력, 유니코드 결합 문자, 전각 문자, 개행 혼재)에서 두 구현이 다른 결과를 내는 지점을 찾아라. 특히 정규식 의미 차이(\s, \b, 유니코드 클래스, greedy 매칭), 치환 순서, 겹치는 매치 처리, 자리표시자 번호 부여 규칙을 대조하라. 마스킹이 하나라도 새면 원문이 외부 LLM으로 나간다."
```

```
"스타일 규칙과 프롬프트 렌더링은 app/easyread/style_rules.py, app/easyread/prompts.py, app/easyread/postprocess.py와 정규화 동등해야 한다. 문장 분리 기준, 길이 계산 단위(문자 수 대 바이트 수), 금지 표현 목록, 어려운 말 사전 적용 순서, 보정 채택 판정, 자리표시자 보존 검사가 갈라지는 지점을 찾아라. 품질 개선을 섞지 않고 동등성만 본다 — 더 나은 규칙을 제안하는 것이 아니라 다른 규칙을 찾는 것이 목표다."
```

## Phase 4 — 암호화·문서

```
"이 변경은 Python cryptography의 Fernet 토큰과 호환되어야 한다. app/privacy/crypto.py와 대조해 버전 바이트, timestamp, IV, 암호문, HMAC의 배치와 검증 순서를 확인하고, HMAC 검증을 복호화 뒤로 미루거나 변조 토큰을 거부하지 않는 지점, base64 변형(urlsafe/padding) 차이, 키 파생·키 버전 처리 차이를 찾아라. 호환이 깨지면 기존 사용자 문서를 영구히 읽을 수 없다."
```

```
"문서 추출은 app/ingest/extractors.py와 동등해야 한다. 입력 10MB 상한, 지원 형식 docx·pdf·hwpx, 텍스트 없는 PDF 거절, 제어문자 제거, DTD·외부 엔터티 차단, zip bomb(압축 해제 예산) 제한이 우회되는 경로를 찾아라. 특히 XML 파서 설정이 기본값이라 외부 엔터티를 허용하는지, ZIP 엔트리 경로 순회(zip slip)를 막는지, 상한 검사가 전체 읽기 이후에 오는지 확인하라. 상한 검사가 늦으면 검사 자체가 자원 고갈을 막지 못한다."
```

```
"내보내기는 app/easyread/export.py, app/easyread/hwpx.py와 동등해야 한다. 파일명 생성과 RFC 5987 Content-Disposition 인코딩이 한글·공백·따옴표·개행이 든 이름에서 원본과 같은 결과를 내는지, TXT 출력에 BOM이 붙거나 제어문자가 남는지, HWPX가 mimetype 엔트리 위치·ZIP 엔트리 순서 조건을 지키는지 확인하라. 생성한 HWPX를 자체 추출기로 다시 읽어 본문이 일치해야 한다."
```

## Phase 5 — worker·LLM

```
"작업 큐 불변식: 문서·변환·작업 행은 같은 DB 트랜잭션에서 저장되어야 하고(커밋 성공 후 큐 등록 실패라는 간극이 없어야 한다), worker는 FOR UPDATE SKIP LOCKED로 작업을 가져가며 lease 만료 시에만 재처리한다. app/workers/tasks.py, app/services/conversion.py와 대조해 이중 완료, 작업 영구 유실, pending 무한 체류가 가능한 경합 조건을 찾아라. lease 갱신 실패와 프로세스 강제 종료 시나리오를 반드시 포함하라."
```

```
"제품 계약: 문서 한 건당 LLM 호출은 변환 1회 + 조건부 보정 1회로 최대 2회다. LLM SDK 자체 재시도와 worker 재시도가 겹쳐 이 상한을 넘기는 경로, 이미 완료된 변환을 재처리할 때 idempotency 검사를 건너뛰는 경로를 찾아라. 재시도 책임은 한 계층만 가져야 한다. app/llm/provider.py, app/llm/openai_provider.py, app/llm/anthropic_provider.py와 대조하라."
```

```
"실패 분류 계약: 도메인 실패·응답 절단·provider 설정 오류는 failed로 확정하고 자동 재시도하지 않으며, DB·일시 네트워크 오류만 제한된 횟수와 backoff로 재시도한다. 이 분류가 뒤바뀌어 설정 오류를 무한 재시도하거나 일시 오류를 즉시 failed로 확정하는 지점을 찾아라. 로그에는 conversion id·상태·시도 횟수·failure code만 남아야 하고 본문·개인정보가 있으면 안 된다."
```

```
"보존 파기는 매일 04:00 KST에 500건 단위로 수행되고, 다중 worker 중복 실행을 PostgreSQL advisory lock으로 막아야 한다. app/workers/purge.py와 대조해 타임존 처리(UTC/KST 혼동), 배치 커밋 경계, lock 미획득 시 조용한 통과, 삭제 범위(문서와 변환을 함께 파기하되 문서가 든 작업 공간과 마지막 작업 공간은 삭제하지 않음)가 어긋나는 지점을 찾아라."
```

## Phase 6 — React 통합

```
"프런트엔드는 백엔드가 내려주는 snake_case를 그대로 쓰고, 401 처리·네트워크 오류·파일 다운로드 래퍼 동작이 기존과 같아야 한다. frontend/src/api/client.ts, frontend/src/api/types.ts와 대조해 생성 타입 교체 후 필드 이름·optional 여부·enum 값(pending|processing|done|failed)이 달라진 지점, Content-Disposition에서 파일명을 못 읽게 되는 경로, 폴링 중 상태 전이 처리가 바뀐 지점을 찾아라."
```

## Phase 7 — 절체 직전 종합

```
"절체 즉시 중단 기준을 하나씩 검증하라: (1) 기존 Fernet 문서 복호화 실패, (2) 다른 사용자 데이터 노출 또는 404 소유권 규칙 위반, (3) 마스킹 전 본문이 LLM이나 로그로 전송됨, (4) 중복 LLM 호출·작업 영구 유실·pending 무한 체류, (5) 문서 추출·내보내기 주요 fixture 불일치, (6) 최대 2회 호출 계약 위반. 각 항목에 대해 '현재 코드에서 이것이 일어날 수 있는 구체적 경로'를 찾고, 없으면 무엇이 그것을 막고 있는지 코드 근거를 대라. 막는 근거를 못 대는 항목은 위반 가능으로 보고하라."
```

---

## 조합 규칙

- **Phase 종료 판정**: 해당 Phase 블록 전체 + Phase 7 종합 문장 중 관련 항목
- **커밋 전 점검**: 바뀐 파일이 속한 블록에서 2~3문장
- **재리뷰**: 이전 지적에 대한 수정만 볼 때는 "이전 리뷰에서 지적된 <항목>이 실제로 해결됐는지, 수정 과정에서 새로 생긴 회귀가 있는지 확인하라"를 앞에 붙이고 원래 focus를 유지한다. focus를 바꿔 버리면 이전 리뷰와 비교가 불가능해진다.
