# G-γ 1단계 — codex 독립 리뷰 (`04_documents-c6`)

> **이 파일은 리더가 썼다.** `codex-reviewer` 래퍼가 codex 호출은 성공시켰으나 **산출물을 쓰기
> 전에 「폴링하고 종료」를 열네 회차 반복**했고(누적 ~1.8M 서브에이전트 토큰, 산출물 0건) 끝내
> 수거 책임을 반납했다. 그래서 리더가 원문을 **바이트 그대로** 옮겼다. **판정·순위·병합·재서술을
> 하지 않았다** — 그것은 3단계 교차 종합의 몫이다.
>
> ## 호출 파라미터 (실측)
>
> | 항목 | 값 |
> |---|---|
> | 모드 | `adversarial` |
> | `--base` | **`1fb5200`** (명시적으로 제공 — 스킬 §2.1-a 의 묶음 경계 요구) |
> | `--scope` | `auto` → **branch 모드, 276 파일 diff** |
> | focus | 3축(보안·개인정보 불변식 / 외부 HTTP 계약 / 게이트·탐지기 자신) |
> | 종료 코드 | **0** |
> | verdict | **needs-attention** |
>
> ## 리더가 함께 적어 두는 범위 경고
>
> 리더는 심판 범위를 **이 레인의 미리뷰 3건**(`65882bc`·`e2038dd`·`318bd36`)으로 지정했으나,
> `--base 1fb5200` 은 그 사이 **다른 레인의 15커밋도 포함**한다(276 파일). 즉 프롬프트 산문의
> 「다시 심판하지 마라」를 **기계적 경계가 강제하지 않았다** — 선언과 도달이 어긋난 자리이고
> 이번엔 리더가 만들었다. 3단계는 지적을 **레인별로 갈라** 읽고, 다른 레인 커밋에 대한 지적은
> 그쪽 게이트(`reviews/xx_harness*`)가 이미 다룬 것인지 대조해야 한다.

---

## codex 원문 (가공 없음)

codex-review: 리뷰 대상 = branch diff vs 1fb5200
codex-review: 대상 판정 = non-empty (merge-base=1fb5200ebe55, 변경 파일 276개 (branch 모드는 커밋된 변경만 센다))
codex-review: 헬퍼 = /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs
codex-review: 출처 = plugins cache (최신 버전 자동 선택)
codex-review: 모드 = adversarial-review / scope = auto(미지정) / base = 1fb5200
codex-review: focus = 축1. 보안·개인정보 불변식
------
M-3 ⒜ 소유권 은닉: C6이 GET /conversions/{id}를 추가하면서 `findOwnedResult(ownerId, conversionId)` 로 소유권을 검사하는 Kotlin 코드가 새로 생겼다. 그런데 같은 범위의 커밋에서 `lockSourceText(documentId)` 와 `lockEnvelope(conversionId)` 는 여전히 소유자 인자가 없이 남아 있다. 제품 호출자가 `EnvelopeRotation.kt` 하나라면 위험이 통제 가능한지 실측하고, 두 함수가 진짜로 소유 술어 없이 쓰이는지 데이터 흐름을 따라가며 확인해달라.

마스킹·평문 노출: 이 범위에서 `GET /conversions/{id}` 조회 결과에 문서 원문·마스킹 결과가 평문으로 돌아온다. 마스킹 범주는 주민등록번호(외국인등록번호 포함)·카드번호 2종이어야 하고, 응답 필드에 이 2종보다 넓은 범주(전화·이메일·계좌)가 마스킹되지 않은 채로 가는지 확인해달라.

복호화 경로 평문: 암호화된 문서를 복호화해 조회 결과에 담을 때, 복호화 중간 결과·예외 메시지·로그에 평문이 새는 경로가 있는지 data flow를 따라가며 찾아달라. 특히 계획 §4.3 표에 명시된 AEAD round-trip 실패나 변조 검증 실패 시의 예외 처리를 봐라.

축2. 외부 HTTP 계약
------
이 범위에서 `GET /conversions/{conversion_id}` 엔드포인트가 새로 생겼다. 계약 파일 `contracts/easy-doc-v1.yaml` 에 이 엔드포인트가 정의되어 있으므로 Kotlin 코드가 그 계약을 지키는지 확인해달라.

상태 코드: 응답·오류·경계 조건(타 사용자 자원·없는 자원)에서 나가는 상태 코드가 계약 명시값과 다른 경로가 있는가.

응답 필드: JSON 필드명이 snake_case 이고 오류 본문이 {"detail": ...} 형태인지 확인해달라. Spring이 기본으로 내뱉는 ProblemDetail 형태로 노출되는 경로는 없는가.

헤더: Cache-Control: no-store, X-Content-Type-Options: nosniff 이 붙는가. 사적 응답 헤더(있으면)가 Kotlin 구현에서 빠지는 경로가 있는가.

β-12 multipart: 문서 업로드 과정에서 multipart/form-data 팔의 계약 구현 상태가 전면 재작성돼야 하는 단계다. 계약이 multipart 신체 형식을 「흡수를 요구」하고 있는데(계약에 명시된 대로), 현 Kotlin 구현에서 그 흡수가 일어나는가 아니면 서버 로직이 계약보다 앞선 단계에서 이미 판정해 버리는가를 확인해달라.

축3. 게이트·탐지기 자신
------
이 범위가 강제자 여러 개를 추가·수정했다:

X1-1: 소유권 은닉 P1 판정을 `OwnershipConcealment` 한 벌로 합치면서 적용 자리가 ④ 곳이어야 한다고 적었다(검사 대상 4개). 실제로 네 자리 모두에서 이 합친 판정이 도는지 확인해달라. 검사를 우회하는 조회·삭제·다운로드 경로가 없는가.

시간 축 거짓 양성: 새로 추가된 `SCANNER_TIME_BUDGET_SECONDS = 30` 과 상한 래칫이 소유권 404 응답 시간을 재는데, 지난 부하 중 한 회차에서 소유권 404 응답 시간 축이 **문턱 1.5 배를 넘는 1.536으로 한 번 빨개졌다**. 그 빨강이 거짓 양성(진짜 버그가 아니라 부하 변동)인지 확인해달라. 초록으로 변하는 조건(유휴 때 26% 여유)과 빨강으로 변했던 조건(부하 시)의 차이를 계측하고, 그 축이 신뢰성 있게 설계되었는지 판단해달라.

⑤-b 파국적 백트래킹: 전 커밋(`e2038dd`)에서 인구조사 정규식의 백트래킹 83배 문제를 고쳤다. 그 고침 뒤에 정규식이 정말로 빨라졌는가 실측이 있는가. 한편, **고치기 전 백트래킹이 나던 같은 정규식을 따로 복원해서 다시 돌려도** 1.87s로 느리지 않았다는 보고가 있다. 원인 미확정이라는 뜻인데, 같은 종류의 파국적 백트래킹이 다른 정규식에서 숨어 있지 않은가 하는 의심이 남는다. Kotlin 코드에서 정규식을 쓰는 자리를 전부 찾아, 게으른 문자 클래스(. * + ?  그리고 이들의 조합)가 여러 줄 입력에 걸쳐 파일 끝까지 되짚는 구조를 갖지 않았는가 확인해달라.

게이트 도달 자체: X1(소유권)·X4(orphan job)·X5(인구조사·삼분할)가 실제로 도는가. 테스트만 있는 것은 아닌가. 그 테스트가 긍정·부정 케이스를 모두 다루는가.
codex-review: 실행 명령 = node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs adversarial-review --base 1fb5200 '축1. 보안·개인정보 불변식
------
M-3 ⒜ 소유권 은닉: C6이 GET /conversions/{id}를 추가하면서 `findOwnedResult(ownerId, conversionId)` 로 소유권을 검사하는 Kotlin 코드가 새로 생겼다. 그런데 같은 범위의 커밋에서 `lockSourceText(documentId)` 와 `lockEnvelope(conversionId)` 는 여전히 소유자 인자가 없이 남아 있다. 제품 호출자가 `EnvelopeRotation.kt` 하나라면 위험이 통제 가능한지 실측하고, 두 함수가 진짜로 소유 술어 없이 쓰이는지 데이터 흐름을 따라가며 확인해달라.

마스킹·평문 노출: 이 범위에서 `GET /conversions/{id}` 조회 결과에 문서 원문·마스킹 결과가 평문으로 돌아온다. 마스킹 범주는 주민등록번호(외국인등록번호 포함)·카드번호 2종이어야 하고, 응답 필드에 이 2종보다 넓은 범주(전화·이메일·계좌)가 마스킹되지 않은 채로 가는지 확인해달라.

복호화 경로 평문: 암호화된 문서를 복호화해 조회 결과에 담을 때, 복호화 중간 결과·예외 메시지·로그에 평문이 새는 경로가 있는지 data flow를 따라가며 찾아달라. 특히 계획 §4.3 표에 명시된 AEAD round-trip 실패나 변조 검증 실패 시의 예외 처리를 봐라.

축2. 외부 HTTP 계약
------
이 범위에서 `GET /conversions/{conversion_id}` 엔드포인트가 새로 생겼다. 계약 파일 `contracts/easy-doc-v1.yaml` 에 이 엔드포인트가 정의되어 있으므로 Kotlin 코드가 그 계약을 지키는지 확인해달라.

상태 코드: 응답·오류·경계 조건(타 사용자 자원·없는 자원)에서 나가는 상태 코드가 계약 명시값과 다른 경로가 있는가.

응답 필드: JSON 필드명이 snake_case 이고 오류 본문이 {"detail": ...} 형태인지 확인해달라. Spring이 기본으로 내뱉는 ProblemDetail 형태로 노출되는 경로는 없는가.

헤더: Cache-Control: no-store, X-Content-Type-Options: nosniff 이 붙는가. 사적 응답 헤더(있으면)가 Kotlin 구현에서 빠지는 경로가 있는가.

β-12 multipart: 문서 업로드 과정에서 multipart/form-data 팔의 계약 구현 상태가 전면 재작성돼야 하는 단계다. 계약이 multipart 신체 형식을 「흡수를 요구」하고 있는데(계약에 명시된 대로), 현 Kotlin 구현에서 그 흡수가 일어나는가 아니면 서버 로직이 계약보다 앞선 단계에서 이미 판정해 버리는가를 확인해달라.

축3. 게이트·탐지기 자신
------
이 범위가 강제자 여러 개를 추가·수정했다:

X1-1: 소유권 은닉 P1 판정을 `OwnershipConcealment` 한 벌로 합치면서 적용 자리가 ④ 곳이어야 한다고 적었다(검사 대상 4개). 실제로 네 자리 모두에서 이 합친 판정이 도는지 확인해달라. 검사를 우회하는 조회·삭제·다운로드 경로가 없는가.

시간 축 거짓 양성: 새로 추가된 `SCANNER_TIME_BUDGET_SECONDS = 30` 과 상한 래칫이 소유권 404 응답 시간을 재는데, 지난 부하 중 한 회차에서 소유권 404 응답 시간 축이 **문턱 1.5 배를 넘는 1.536으로 한 번 빨개졌다**. 그 빨강이 거짓 양성(진짜 버그가 아니라 부하 변동)인지 확인해달라. 초록으로 변하는 조건(유휴 때 26% 여유)과 빨강으로 변했던 조건(부하 시)의 차이를 계측하고, 그 축이 신뢰성 있게 설계되었는지 판단해달라.

⑤-b 파국적 백트래킹: 전 커밋(`e2038dd`)에서 인구조사 정규식의 백트래킹 83배 문제를 고쳤다. 그 고침 뒤에 정규식이 정말로 빨라졌는가 실측이 있는가. 한편, **고치기 전 백트래킹이 나던 같은 정규식을 따로 복원해서 다시 돌려도** 1.87s로 느리지 않았다는 보고가 있다. 원인 미확정이라는 뜻인데, 같은 종류의 파국적 백트래킹이 다른 정규식에서 숨어 있지 않은가 하는 의심이 남는다. Kotlin 코드에서 정규식을 쓰는 자리를 전부 찾아, 게으른 문자 클래스(. * + ?  그리고 이들의 조합)가 여러 줄 입력에 걸쳐 파일 끝까지 되짚는 구조를 갖지 않았는가 확인해달라.

게이트 도달 자체: X1(소유권)·X4(orphan job)·X5(인구조사·삼분할)가 실제로 도는가. 테스트만 있는 것은 아닌가. 그 테스트가 긍정·부정 케이스를 모두 다루는가.'
[codex] Starting Codex task thread.
[codex] Thread ready (01a026ef-4b98-7432-9140-62e8c5cf4a7b).
[codex] Turn started (01a026ef-4d95-7902-9492-8b0d1319248e).
[codex] Assistant message captured: {"verdict":"needs-attention","summary":"기준 커밋 대비 실제 diff와 런타임 데이터 흐름·계약·게이트 도달성을 읽기 전용으로 대조하겠...
[codex] Running command: /bin/zsh -lc "pwd && rg --files -g 'AGENTS.md' -g '"'!build'"' -g '"'!**/.gradle/**'"' && rg ...
[codex] Command failed: /bin/zsh -lc "pwd && rg --files -g 'AGENTS.md' -g '"'!build'"' -g '"'!**/.gradle/**'"' && rg ... (exit 1)
[codex] Running command: /bin/zsh -lc 'find .. -name AGENTS.md -print'
[codex] Running command: /bin/zsh -lc 'git status --short && git rev-parse HEAD && git merge-base HEAD 1fb5200 && git ...
[codex] Running command: /bin/zsh -lc 'rg -n "C2 document storage|EnvelopeRotation|ConversionRead|C6|ownership conceal...
[codex] Command completed: /bin/zsh -lc 'rg -n "C2 document storage|EnvelopeRotation|ConversionRead|C6|ownership conceal... (exit 0)
[codex] Command completed: /bin/zsh -lc 'git status --short && git rev-parse HEAD && git merge-base HEAD 1fb5200 && git ... (exit 0)
[codex] Command completed: /bin/zsh -lc 'find .. -name AGENTS.md -print' (exit 0)
[codex] Running command: /bin/zsh -lc "sed -n '370,410p' /Users/harris/.codex/memories/MEMORY.md"
[codex] Command completed: /bin/zsh -lc "sed -n '370,410p' /Users/harris/.codex/memories/MEMORY.md" (exit 0)
[codex] Running command: /bin/zsh -lc "sed -n '1,220p' /Users/harris/.codex/memories/rollout_summaries/2026-08-19T23-5...
[codex] Command completed: /bin/zsh -lc "sed -n '1,220p' /Users/harris/.codex/memories/rollout_summaries/2026-08-19T23-5... (exit 0)
[codex] Running command: /bin/zsh -lc 'git diff --unified=80 1fb5200...HEAD -- backend-kotlin/api/src/main/kotlin/kr/e...
[codex] Running command: /bin/zsh -lc "rg -n \"(/conversions/\\{|conversion_id|ConversionResponse|multipart/form-data|...
[codex] Running command: /bin/zsh -lc "rg -n \"OwnershipConcealment|SCANNER_TIME_BUDGET_SECONDS|1\\.536|1\\.5|26%|orph...
[codex] Running command: /bin/zsh -lc "rg -n \"findOwnedResult|lockSourceText|lockEnvelope|rewriteEnvelope|ConversionQ...
[codex] Command completed: /bin/zsh -lc 'git diff --unified=80 1fb5200...HEAD -- backend-kotlin/api/src/main/kotlin/kr/e... (exit 0)
[codex] Command completed: /bin/zsh -lc "rg -n \"(/conversions/\\{|conversion_id|ConversionResponse|multipart/form-data|... (exit 0)
[codex] Command completed: /bin/zsh -lc "rg -n \"findOwnedResult|lockSourceText|lockEnvelope|rewriteEnvelope|ConversionQ... (exit 0)
[codex] Command completed: /bin/zsh -lc "rg -n \"OwnershipConcealment|SCANNER_TIME_BUDGET_SECONDS|1\\.536|1\\.5|26%|orph... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/... (exit 0)
[codex] Running command: /bin/zsh -lc 'nl -ba backend-kotlin/api/src/main/kotlin/kr/easydoc/api/document/ConversionCon...
[codex] Command completed: /bin/zsh -lc 'nl -ba backend-kotlin/api/src/main/kotlin/kr/easydoc/api/document/ConversionCon... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/application/src/main/kotlin/kr/easydoc/application/docume...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/application/src/main/kotlin/kr/easydoc/application/docume... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba contracts/easy-doc-v1.yaml | sed -n '220,340p'; nl -ba contracts/easy-do...
[codex] Command completed: /bin/zsh -lc "nl -ba contracts/easy-doc-v1.yaml | sed -n '220,340p'; nl -ba contracts/easy-do... (exit 0)
[codex] Running command: /bin/zsh -lc "rg -n '"'^ /conversions/|''^ /documents:|''^ ConversionResponse:|''^ MaskedItem...
[codex] Command completed: /bin/zsh -lc "rg -n '"'^ /conversions/|''^ /documents:|''^ ConversionResponse:|''^ MaskedItem... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba contracts/easy-doc-v1.yaml | sed -n '1280,1510p'; nl -ba contracts/easy-...
[codex] Command completed: /bin/zsh -lc "nl -ba contracts/easy-doc-v1.yaml | sed -n '1280,1510p'; nl -ba contracts/easy-... (exit 0)
[codex] Assistant message captured: {"verdict":"needs-attention","summary":"현재 구현은 성공 응답의 필드명·보안 헤더와 소유자 결합 조회를 명시적으로 갖추고 있습니다. 이...
[codex] Running command: /bin/zsh -lc "rg -n \"logger|log\\.|println|printStackTrace|message|cause|decrypt\\(\" backen...
[codex] Command completed: /bin/zsh -lc "rg -n \"logger|log\\.|println|printStackTrace|message|cause|decrypt\\(\" backen... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/api/src/test/kotlin/kr/easydoc/api/ConversionReadReachTes...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/api/src/test/kotlin/kr/easydoc/api/ConversionReadReachTes... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/api/src/main/kotlin/kr/easydoc/api/config/PrivateResponse...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/api/src/main/kotlin/kr/easydoc/api/config/PrivateResponse... (exit 0)
[codex] Running command: /bin/zsh -lc "rg -n \"mappingFor|StorageException|jsonError|UNEXPECTED_MESSAGE|UNMAPPED_DOMAI...
[codex] Command completed: /bin/zsh -lc "rg -n \"mappingFor|StorageException|jsonError|UNEXPECTED_MESSAGE|UNMAPPED_DOMAI... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/api/src/main/kotlin/kr/easydoc/api/error/GlobalExceptionH...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/api/src/main/kotlin/kr/easydoc/api/error/GlobalExceptionH... (exit 0)
[codex] Running command: /bin/zsh -lc 'rg -n "저장된 (문서|변환 결과)를 읽을 수 없습니다|DecryptionFailedException|복호화 실패|변조" contracts...
[codex] Command completed: /bin/zsh -lc 'rg -n "저장된 (문서|변환 결과)를 읽을 수 없습니다|DecryptionFailedException|복호화 실패|변조" contracts... (exit 0)
[codex] Running command: /bin/zsh -lc "rg -n \"\\.lockSourceText\\(|\\.lockEnvelope\\(|lockSourceText\\(|lockEnvelope\...
[codex] Command completed: /bin/zsh -lc "rg -n \"\\.lockSourceText\\(|\\.lockEnvelope\\(|lockSourceText\\(|lockEnvelope\... (exit 0)
[codex] Running command: /bin/zsh -lc "rg -n \"rotateDocument\\(|rotateConversion\\(\" backend-kotlin --glob '*.kt'; n...
[codex] Command completed: /bin/zsh -lc "rg -n \"rotateDocument\\(|rotateConversion\\(\" backend-kotlin --glob '*.kt'; n... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/api/src/main/kotlin/kr/easydoc/api/document/DocumentContr...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/api/src/main/kotlin/kr/easydoc/api/document/DocumentContr... (exit 0)
[codex] Running command: /bin/zsh -lc "rg -n \"multipart|max-file-size|max-request-size|spring.servlet\" backend-kotli...
[codex] Command completed: /bin/zsh -lc "rg -n \"multipart|max-file-size|max-request-size|spring.servlet\" backend-kotli... (exit 0)
[codex] Running command: /bin/zsh -lc "rg -n \"상한 초과.*(UUID|작업 공간|workspace)|잘못된.*(상한|크기)|복합.*(413|422)|DC-26|DC-27\" ...
[codex] Command completed: /bin/zsh -lc "rg -n \"상한 초과.*(UUID|작업 공간|workspace)|잘못된.*(상한|크기)|복합.*(413|422)|DC-26|DC-27\" ... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba docs/migration/_workspace/04_kotlin-implementer_c6-plan.md | sed -n '45,...
[codex] Command completed: /bin/zsh -lc "nl -ba docs/migration/_workspace/04_kotlin-implementer_c6-plan.md | sed -n '45,... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/api/src/main/kotlin/kr/easydoc/api/config/TypedValueSlotI...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/api/src/main/kotlin/kr/easydoc/api/config/TypedValueSlotI... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/api/src/test/kotlin/kr/easydoc/api/DocumentEndpointReachT...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/api/src/test/kotlin/kr/easydoc/api/DocumentEndpointReachT... (exit 0)
[codex] Running command: /bin/zsh -lc 'rg -n "OwnershipConcealment|적용 자리|네 자리|4개|P1 판정" docs/migration/_workspace/04_k...
[codex] Command completed: /bin/zsh -lc 'rg -n "OwnershipConcealment|적용 자리|네 자리|4개|P1 판정" docs/migration/_workspace/04_k... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/OwnershipConce...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/OwnershipConce... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/api/src/test/kotlin/kr/easydoc/api/WorkspaceEndpointReach...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/api/src/test/kotlin/kr/easydoc/api/WorkspaceEndpointReach... (exit 0)
[codex] Running command: /bin/zsh -lc "rg -n \"1\\.536|1\\.53|26%|26 %|0\\.74|응답 시간 중앙값|문턱.*1\\.5\" . --hidden -g '"'!...
[codex] Command completed: /bin/zsh -lc "rg -n \"1\\.536|1\\.53|26%|26 %|0\\.74|응답 시간 중앙값|문턱.*1\\.5\" . --hidden -g '"'!... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba docs/migration/_workspace/04_kotlin-implementer_conversion-read.md | sed...
[codex] Command completed: /bin/zsh -lc "nl -ba docs/migration/_workspace/04_kotlin-implementer_conversion-read.md | sed... (exit 0)
[codex] Running command: /bin/zsh -lc "rg -n \"Regex\\(|toRegex\\(|Pattern\\.compile|replace\\(Regex|split\\(Regex|mat...
[codex] Command completed: /bin/zsh -lc "rg -n \"Regex\\(|toRegex\\(|Pattern\\.compile|replace\\(Regex|split\\(Regex|mat... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/core/src/main/kotlin/kr/easydoc/core/easyread/GlossCollis...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/core/src/main/kotlin/kr/easydoc/core/easyread/GlossCollis... (exit 0)
[codex] Running command: /bin/zsh -lc "rg -n \"test_kotlin_gate_reach|gradlew|quality_gate_local|pytest tests\" .githu...
[codex] Command completed: /bin/zsh -lc "rg -n \"test_kotlin_gate_reach|gradlew|quality_gate_local|pytest tests\" .githu... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/api/src/test/kotlin/kr/easydoc/api/NamedReferenceGuardTes...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/api/src/test/kotlin/kr/easydoc/api/NamedReferenceGuardTes... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/api/src/main/kotlin/kr/easydoc/api/auth/AuthenticatedEndp...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/api/src/main/kotlin/kr/easydoc/api/auth/AuthenticatedEndp... (exit 0)
[codex] Running command: /bin/zsh -lc "find backend-kotlin -path '*/build/test-results/test/TEST-*.xml' -type f -maxde...
[codex] Command completed: /bin/zsh -lc "find backend-kotlin -path '*/build/test-results/test/TEST-*.xml' -type f -maxde... (exit 0)
[codex] Running command: /bin/zsh -lc ".venv/bin/python -m pytest -p no:cacheprovider tests/test_kotlin_gate_reach.py ...
[codex] Command completed: /bin/zsh -lc ".venv/bin/python -m pytest -p no:cacheprovider tests/test_kotlin_gate_reach.py ... (exit 0)
[codex] Running command: /bin/zsh -lc "find backend-kotlin -path '*/build/test-results/test/TEST-kr.easydoc.api.*.xml'...
[codex] Command completed: /bin/zsh -lc "find backend-kotlin -path '*/build/test-results/test/TEST-kr.easydoc.api.*.xml'... (exit 0)
[codex] Running command: /bin/zsh -lc 'rg -n "소유권 404 응답 시간|failure|system-out" backend-kotlin/api/build/test-results/...
[codex] Command completed: /bin/zsh -lc 'rg -n "소유권 404 응답 시간|failure|system-out" backend-kotlin/api/build/test-results/... (exit 0)
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/core/src/main/kotlin/kr/easydoc/core/crypto/StoredContent...
[codex] Command completed: /bin/zsh -lc "nl -ba backend-kotlin/core/src/main/kotlin/kr/easydoc/core/crypto/StoredContent... (exit 0)
[codex] Running command: /bin/zsh -lc "rg -n \"status = 'done'|status = 'failed'|easy_text_encrypted|masked_items_encr...
[codex] Command completed: /bin/zsh -lc "rg -n \"status = 'done'|status = 'failed'|easy_text_encrypted|masked_items_encr... (exit 0)
[codex] Running command: /bin/zsh -lc "rg -n '/\\*.*?/\\*|/\\*' backend-kotlin --glob '*.kt' | head -80; rg -l '/\\*' ...
[codex] Command completed: /bin/zsh -lc "rg -n '/\\*.*?/\\*|/\\*' backend-kotlin --glob '*.kt' | head -80; rg -l '/\\*' ... (exit 0)
[codex] Running command: /bin/zsh -lc "rg -n \"MAX_TIMING_RATIO|WorkspaceEndpointReachTest.*1\\.5|DocumentDeleteReachT...
[codex] Command completed: /bin/zsh -lc "rg -n \"MAX_TIMING_RATIO|WorkspaceEndpointReachTest.*1\\.5|DocumentDeleteReachT... (exit 0)
[codex] Running command: /bin/zsh -lc 'git diff 1fb5200...HEAD -- backend-kotlin/api/src/test/kotlin/kr/easydoc/api/Wo...
[codex] Command completed: /bin/zsh -lc 'git diff 1fb5200...HEAD -- backend-kotlin/api/src/test/kotlin/kr/easydoc/api/Wo... (exit 0)
[codex] Running command: /bin/zsh -lc 'rg -n "''^@cache|''^def _kotlin_test_sources|''^def _kotlin_main_sources|''^def...
[codex] Command completed: /bin/zsh -lc 'rg -n "''^@cache|''^def _kotlin_test_sources|''^def _kotlin_main_sources|''^def... (exit 0)
[codex] Running command: /bin/zsh -lc "sed -n '890,905p;998,1012p;1280,1300p;1338,1358p' tests/test_kotlin_gate_reach.py"
[codex] Command completed: /bin/zsh -lc "sed -n '890,905p;998,1012p;1280,1300p;1338,1358p' tests/test_kotlin_gate_reach.py" (exit 0)
[codex] Assistant message captured: {"verdict":"needs-attention","summary":"No-ship: multipart 오류 우선순위가 계약과 다르고, 변조된 변환 결과의 500 본...
[codex] Turn completion inferred after the main thread finished and subagent work drained.
# Codex Adversarial Review

Target: branch diff against 1fb5200
Verdict: needs-attention

No-ship: multipart 오류 우선순위가 계약과 다르고, 변조된 변환 결과의 500 본문도 계약 문구에서 벗어난다. 소유권 타이밍 축은 부하에 따라 실제로 흔들려 게이트로 신뢰하기 어렵다.

Findings:
- [high] multipart 복합 오류가 계약상 413 대신 422를 반환한다 (backend-kotlin/api/src/main/kotlin/kr/easydoc/api/document/DocumentController.kt:61-67)
  Kotlin은 호출 인자를 순서대로 평가한다. `readBounded(file)`은 상한+1 바이트를 반환할 뿐 크기를 판정하지 않고, 이어지는 `parseWorkspaceId(...)`가 잘못된 UUID에서 즉시 422를 던진다. 실제 크기 판정은 그 뒤 호출될 `DocumentService.createFromFile` 안에 있으므로, 상한 초과 파일과 잘못된 `workspace_id`를 함께 보내면 계약이 명시한 첫 오류 413이 아니라 422가 나간다. 추출 실패나 본문 길이 오류보다 workspace 형식 판정이 앞서는 문제도 같은 구조다.
  Recommendation: multipart workspace 파싱을 파일 크기·추출·본문 길이 판정 뒤로 옮기고, 상한 초과+잘못된 UUID 및 추출 실패+잘못된 UUID 복합 입력을 실제 HTTP 테스트로 고정하라.
- [medium] AEAD 변조 실패가 변환 조회 계약과 다른 detail을 반환한다 (backend-kotlin/application/src/main/kotlin/kr/easydoc/application/document/ConversionQueryService.kt:31-38)
  세 복호화 호출은 `DecryptionFailedException`을 그대로 전파한다. 이 예외의 고정 메시지는 "저장된 문서를 읽을 수 없습니다"이고 전역 핸들러가 `StorageException.message`를 그대로 500 detail에 사용하지만, 계약의 storage 응답은 "저장된 변환 결과를 읽을 수 없습니다"이다. 따라서 easy_text·edited_text·masked_items 중 하나가 변조되거나 봉투 결속이 깨지면 상태와 JSON 모양은 맞아도 외부 오류 본문이 계약에서 벗어난다. 현재 C6 HTTP 테스트에는 이 변조 팔이 없다.
  Recommendation: 조회 유스케이스에서 복호화 실패를 변환 전용 고정 StorageException으로 매핑하거나 오류 타입을 문맥화하고, 세 암호문 열 각각을 변조한 실 HTTP 테스트에서 500·정확한 detail·보안 헤더·로그 평문 부재를 단언하라.
- [medium] 소유권 응답시간 검사는 부하를 보안 신호로 오인한다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/WorkspaceEndpointReachTest.kt:181-192)
  이 검사는 각 팔의 첫 요청 하나만 버린 뒤 21개 벽시계 표본의 중앙값 비를 단 한 번 1.5와 비교한다. 부하 계측·독립 반복·절대 차이 또는 분포 판정이 없다. 같은 코드가 저장된 최근 실행에서는 비 1.019로 통과했지만, 제시된 부하 실행에서는 1.536으로 실패했고 유휴 기준선은 최대 1.110이었다. 이는 제품 회귀와 환경 지터를 구분하지 못하는 관측이다. 또한 새 `RATCHET_CEILING_PINS`는 Python의 `SCANNER_TIME_BUDGET_SECONDS`만 고정하므로 이 Kotlin 1.5 문턱을 보호하거나 안정화하지 않는다.
  Recommendation: 일반 빌드의 단발 벽시계 판정을 제거하고 격리된 성능 잡에서 여러 독립 라운드, 충분한 워밍업, 분포·절대 차이와 호스트 부하를 함께 판정하라. 결정적 CI 방어는 SQL 소유 술어 음성 대조로 유지하고 Kotlin 타이밍 문턱도 별도 상한 라쳇에 포함하라.

Next steps:
- multipart 복합 오류 순서를 수정하고 원시 HTTP 회귀 테스트를 추가한다.
- 세 암호문 열의 변조 실패를 HTTP 경계까지 검증해 계약 문구와 로그 비노출을 고정한다.
- 타이밍 테스트를 격리·반복 가능한 성능 게이트로 재설계한 뒤 부하/유휴 조건에서 다시 측정한다.

[exited with code 0]
