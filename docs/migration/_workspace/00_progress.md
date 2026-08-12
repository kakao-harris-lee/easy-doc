# Kotlin 마이그레이션 진행 상태

**기준 문서:** `docs/plans/2026-08-11-kotlin-react-migration.md`
**실행 모드:** 초기 실행 (2026-08-11 착수)
**갱신 규칙:** 에이전트는 자기가 담당한 행만 고치고 `마지막 갱신 주체`에 자기 이름을 적는다. 다른 에이전트의 행은 건드리지 않는다.

> `충족`은 `예`/`아니오` 둘 중 하나만 쓴다. `진행 중`·`대체로`는 쓰지 않는다 — 게이트는 이분법이어야 판정된다.
> 근거가 비어 있는 `예`는 `아니오`로 취급한다.

---

## 재개발 전환 (2026-08-12 2차) — 아래 역사 행 중 일부가 무효화됨

**사용자 결정 + 리더 판정.** 방향이 **전환(포팅)에서 재개발(rebuild)**로 바뀌었다. Python은 폐기 대상이다. 근거·상세는 계획 `docs/plans/2026-08-11-kotlin-react-migration.md`의 "2026-08-12 (2차)" 변경 이력. 이 파일의 아래 역사 행은 **고치지 않는다**(파일 규약) — 대신 여기서 무엇이 무효화됐는지 못 박는다.

**호환성 요구 소멸로 무효화된 행(역사 참고로만 남긴다 — 게이트로 쓰지 말 것):**

- **Phase 0 「Fernet JVM 호환 spike」 · 「결정 3 상세 — Fernet 직접 구현」 전체 · 필수조치 C(Fernet TTL·60초 토큰)** — 롤백이 없어 Fernet 자체가 불필요. **표준 AEAD 신규 구현**(계획 §4.3). crypto spike의 역방향·tamper **호환** 검증도 근거 소멸.
- **Phase 0 결정표 결정 3(Fernet 직접 구현)** → **무효.** 결정 2(보존 DB 없음)는 유효하되 그 함의가 "재암호화 창 불필요"에서 "**롤백·호환성 자체가 소멸**"로 확장됐다. 결정 1의 "Python 도구는 독립 oracle로 존치"는 "**한시 존치 후 이식/폐기**"로 바뀐다(계획 Phase 9 개정).
- **JWT·Argon2의 「양방향」·「역방향」·「기존 PHC 그대로 검증」 프레이밍** — Python이 Kotlin 산출물을 읽을 요구가 없다. crypto·jwt·argon2 parity의 `compat` 판정 모드 근거 소멸.
- **검증 게이트 표 「Crypto (Python ↔ Kotlin)」 → 「Kotlin 단독」**, **「Ops (cutover/rollback)」 → 「첫 배포·파일럿 관찰(롤백 없음)」**.
- **Phase 0 「종료 조건 문구 정정」의 '역방향/롤백 창' 읽기(37~45행)** — 절체 후 Python이 이어 쓸 요구가 없다(롤백 소멸). Phase 0 실제 종료 조건은 계획 §5 Phase 0(2차)·SKILL.md와 같다: **표준 AEAD·Argon2·JWT 구현 경로(round-trip·변조 거부·필수조치 A·B — migration-safety-gate I-7·I-8·I-9) + 문서 포팅 가능성**. "기존 암호문 읽기"·"역방향 이어쓰기"는 종료 조건 아님.
- **「기준 전환 재판정」 §의 `[호환]` 근거 행(아래)** — spike 행이 '예'로 남는 근거는 이제 **가능성 확인 + 필수조치 A·B(migration-safety-gate I-7·I-8·I-9)**이지 "롤백 창 값 동일성"이 아니다. 판정(예)은 유지, 근거만 재기술.

**무효화되지 '않는' 것(혼동 주의 — 순수 정확성 문제라 그대로 유효):**

- **필수조치 A(Argon2 재해시 전체 파라미터 동등성)·B(JWT clock skew 0 경계)** — 이 둘은 *호환*이 아니라 *정확성* 문제다. 재개발 Argon2·JWT에도 그대로 적용된다. 탐침 7건·경계 fixture 2건 회귀 유지.
- **필수조치 D(`encryption_scheme` additive)** — 컬럼은 남기되 의미가 "롤백 호환"에서 **"향후 키 회전용"**으로 바뀐다. 처음부터 `'fernet-v1'`이 아니라 새 AEAD scheme 값(예: `'aes-gcm-v1'`)으로 쓴다.
- **계약·보안 불변식(마스킹 선행·no-store·404 소유권·평문 미저장)**, **DB baseline/Flyway 구조**, **contract 3자 대조·CORS·오류 계약(C-1/C-2/C-3)** — 재개발 기준(요구사항·계약·React)에서 그대로 유효.

**신설 게이트 — 추출 완료(Python 폐기 선행 조건):** 재개발에는 Python 차분 그물이 없어, 코드에만 있던 판단(프롬프트 전문·스타일 상수·**치환 비문 실측 튜닝** `eae75c7`/`0894854`/`a4c9fd9`·골든셋 채점 기준)을 지우기 전에 명세·fixture로 옮겨야 한다. 목록·폐기 게이트는 **`03_rebuild-extraction-list.md`**. 이 게이트가 0으로 닫히기 전 `app/**`·`tests/**` 삭제 금지.

**품질 문제는 이 전환이 해결하지 않는다:** 골든셋 통과율(실수집 52.8%·전체 64.3%)·judge·장문 절벽은 언어 무관이며 프롬프트·긴 문서 전략의 문제다(`02_quality-baseline.md`). Phase 0 합격선 확정 + Phase 2 이후 프롬프트 작업으로 이월. **[3차 갱신]** 합격선 확정은 **완료**됐다(기제로 확정 — 위 Phase 0 표). 남은 이월분은 프롬프트·긴문서 작업이고, 그 성과를 판정하는 자리는 **Phase 5 종료 게이트**로 확정됐다(계획 §4.6). **52.8%는 "괜찮다"가 아니라 코퍼스 난이도 판정이며 KPI 0.90은 목표선으로 살아 있다.**

---

## Phase 0 — 범위·계약 동결

계획 문서 §5 Phase 0. 원문 종료 조건: "Kotlin이 **기존 암호문**을 안전하게 읽을 경로와 문서 포팅 가능성이 확인됨."

**종료 조건 문구 정정 (2026-08-12, 리더).** §9-2가 "보존할 DB 없음"으로 확정되면서 **기존 암호문이 존재하지 않는다.** 계획 §4.3은 "기존 DB 본문이 Fernet 토큰이라 호환을 증명 못 하면 기존 문서를 못 읽는다"를 전제로 쓰였고 그 전제가 사라졌다. 실제로 남은 요구는 **역방향**이다 — 계획 §5 Phase 7이 관찰 기간(1~2주) 동안 롤백을 준비하라고 요구하므로, 절체 **후** Kotlin이 쓴 데이터를 롤백한 Python이 읽고 **이어서 쓸 수 있어야** 한다.

따라서 이 Phase의 실질 종료 조건은 다음으로 읽는다:

> **롤백 창에서 Python이 Kotlin 산출물을 읽고 이어서 쓸 수 있는 경로와, 문서 포팅 가능성이 확인됨.**

> **[2026-08-12 2차 개정 무효화]** 위 '역방향/롤백 창' 종료 조건은 **폐기됐다** — 롤백을 포기해(상단 재개발 masthead) 절체 후 Python이 이어 쓸 요구가 없다. Phase 0 실제 종료 조건은 계획 §5 Phase 0(2차)·SKILL.md와 같다(표준 AEAD·Argon2·JWT 구현 경로 + 문서 포팅 가능성 — migration-safety-gate I-7·I-8·I-9). 위 두 문단(39~43행)은 **1차 개정 기록으로만** 남긴다.

~~계획 문서 §5 Phase 0 본문도 같은 취지로 갱신할 것을 제안한다(문서 갱신은 별건).~~ → 계획 §5 Phase 0은 2차 개정으로 갱신됨(이 정정과 정합).

| 종료 조건 | 충족 | 근거 | 미해결 항목 | blocked-by | 마지막 갱신 주체 |
|---|---|---|---|---|---|
| `contracts/easy-doc-v1.yaml` 작성 | 예 | `contracts/easy-doc-v1.yaml` 작성 완료. 14 엔드포인트(제품 13 + `/health`)가 FastAPI 실제 노출 경로와 **차집합 양쪽 공집합**으로 일치. `openapi-spec-validator` → OK. 413·502/503·`detail` union·응답 헤더 5종·multipart 요청 본문·`status` enum·CORS·입력 상한 전부 수기 기입 | **U-1 해소 (2026-08-12)** — 리더 결정 + 사용자 승인으로 **개선 수용**(Python 동작을 재현하지 않는다). 계약 파일의 `x-cors.x-known-limitation` → `x-cors.x-unhandled-500-cors`("결정됨 — Python과 의도적으로 다름")로 대체, `components.responses.InternalError.description` 정정, v2 후보 `V2-2` **종결**, `x-changelog` 항목 추가. `info.version`은 1.0.0 유지. 근거 `00_contract-keeper_changelog.md` | - | contract-keeper (2026-08-12) |
| 응답·헤더·오류·인증·권한·입력 상한을 contract test로 고정 | 아니오 | 목록·기준만 작성 (`00_contract-keeper_test-plan.md` — 엔드포인트별 세트 14 + 횡단 48종). **테스트 코드는 미구현**이며 실행도 하지 않았다 | 리더 지시로 Phase 0에서는 목록만 세웠다(Kotlin API가 Phase 3부터 생긴다). 추가로 **G-1: Python 기준선에도 없는 공백** — `POST /workspaces`·`PATCH /workspaces/{id}`의 캐시 헤더를 어떤 테스트도 단언하지 않는다(계약상 10곳 중 2곳). Python에 먼저 채울지 Phase 3에서 양쪽 동시에 넣을지 판단 필요.<br>**2026-08-12 OQ-1 판정 반영** — 사적 헤더가 **모든 응답**에 붙게 되면서 테스트 계획의 헤더 축이 바뀌었다: ① 오류 응답의 **부정** 단언이 **긍정**으로 뒤집힘(Kotlin 한정 — Python 부정 단언은 그대로 옳다), ② 열거 10곳 개별 단언은 **하한선으로 유지**(삭제 금지), ③ 신규 — DELETE 204·`/health`·프리플라이트·**헤더 중복 부착 부재**(H-2), ④ 신규 — 컨테이너 레벨 응답 도달 여부(**H-1**). 체크 항목 정본 `.claude/skills/api-contract-freeze` §5.1·§5.2.<br>**2026-08-12 H-1 실측 반영** — ④가 **실측 완료**로 닫혔다(X-D2c). 필터 1층으로는 파싱 단계 거절 7종에 못 닿아 **Tomcat Engine 밸브를 더한 2층**이 됐고, 테스트 축에 X-D2d(밸브 음성 대조)·X-D2e(Tomcat 결합 인지)가 추가됐다. 부수로 **오류 본문 사각지대**가 드러나 X-C7·X-C8(`sendError`→`/error` 본문이 `{"detail":…}`가 아니다)·X-C9(컨테이너 응답 본문 **미측정**)가 신설됐다. 계약 정본 `x-error-body-universality`, 절차 정본 스킬 §5.3 | 리더(G-1 시점 판단) → Phase 3 kotlin-implementer | contract-keeper (2026-08-12) |
| FastAPI OpenAPI·계약 파일·React 타입 3자 대조 | 예 | `00_contract-keeper_three-way-diff.md`. 불일치 **21건 + 계획-코드 3건 + 미결 1건**. ①이 런타임과 **다른 값**을 말하는 곳 3건(422의 `input`/`ctx`, `loc` 타입, export의 `application/json`), 누락 6건, 느슨함 5건, 의도된 차이 6건. `DELETE /workspaces/{id}`가 React에 없는 것은 **의도된 차이**로 기록 | **없음** — 유일하게 남았던 U-1(§7)이 2026-08-12 리더 결정으로 종결(위 행) | - | contract-keeper (2026-08-12) |
| 대상 DB와 보존할 파일럿 데이터 유무 확인 | 예 | 사용자 확인 (2026-08-12): **보존할 운영/파일럿 DB 없음. 빈 DB로 시작한다.** 이로써 계획 §7이 "변동 폭이 가장 크다"고 지목한 두 변수 중 하나(기존 암호문 호환)가 소멸했다 | - | - | leader |
| 범위 승인: 런타임만 Kotlin화 vs 오프라인 도구까지 Python 제거 | 예 | 사용자 승인 (2026-08-12): **제품 런타임만 Kotlin화**(§9-1). Phase 9(오프라인 도구)는 착수하지 않는다. 골든셋 평가·벤치마크·수집·파일럿 리포트 도구는 Python으로 남아 **독립 검증 oracle** 역할을 유지한다 | - | - | leader |
| Fernet JVM 호환 spike | 예 | `00_privacy-gate_crypto-spike.md` §4. `com.macasaet.fernet:fernet-java8:1.5.0` / Temurin 21.0.4 / Gradle 9.1.0. **정방향 8/8**(한글·빈 값·긴 값·제어문자·변조·다른 키·garbage), **역방향 5/5**(`verify-crypto` 통과, `crypto-verify.verified.json` status: pass), **tamper 5/5**(version·timestamp·IV·ciphertext·HMAC 각 1비트 변조 전건 `StorageError` 거부, 무변조 대조군 정상). 즉흥 암호 구현은 하지 않았다 | **(1) 조달 유보** — 이 라이브러리는 최신 1.5.0이 **2020-09-26** 릴리스로 약 5년 11개월 무릴리스(`maven-metadata.xml`·jar `Last-Modified` 실측). §4.3-2의 "유지보수 상태"를 만족한다고 보기 어렵다. 채택(코드 전량 검토 조건) vs JDK primitive 자체 조립 중 선택 필요 = **§9 결정 3**. **(2) 필수 조치 C** — 기본 Validator는 TTL 60초라 **유효 토큰 5건 전부 `TokenExpiredException`으로 실패**한다. 그대로 쓰면 업로드 60초 뒤 모든 문서가 안 읽힌다. Phase 4에서 TTL·maxClockSkew 명시적 무력화 + 60초 경과 토큰으로 회귀 테스트 필요. **(3)** AES-GCM(선택지 2)은 미검증 — 권고하지 않아 수행하지 않았다 | 리더(§9 결정 3) | privacy-gate |
| Argon2 PHC 검증 spike | 예 | `00_privacy-gate_crypto-spike.md` §2. `spring-security-crypto:6.4.2`(`Argon2PasswordEncoder(16,32,4,65536,3)`) + `bcprov-jdk18on:1.78.1`. 파라미터는 `app/services/auth.py:59`에서 직접 읽었고 salt 16B·hash 32B는 fixture PHC base64 길이에서 역산. **정방향 13/13**(한글·NFD 불일치 거부·legacy `m=8192,t=2,p=2` 검증·변조·비PHC 문자열이 예외 아닌 `false`), **역방향 4/4**(`app/services/auth.py::_HASHER`가 Kotlin 산출 PHC를 전건 검증, `needs_rehash=false`, 틀린 비밀번호 거부, prefix `$argon2id$v=19$m=65536,t=3,p=4$` 동일) | **필수 조치 A** — 재해시 판정이 갈린다. Python `check_needs_rehash`는 **전체 파라미터 동등성**, Spring `upgradeEncoding`은 **memory·iterations의 "미만"만** 본다. 자체 탐침 7건 중 5건 불일치(parallelism만 다름·더 강한 memory·더 강한 iterations·hash_len·salt_len에서 Python `true` / Kotlin `false`). **공식 fixture 14건으로는 드러나지 않는다.** 지금은 무해하나(살아 있는 해시가 전부 현재 파라미터) 파라미터를 바꾸는 날 **이관이 조용히 멈춘다**. Phase 3에서 전체 동등성 판정 함수로 교체 + 탐침 7건 회귀 고정 필요 | Phase 3 kotlin-implementer | privacy-gate |
| JWT 양방향 호환 spike | 예 | `00_privacy-gate_crypto-spike.md` §3. **정방향 17/17을 두 라이브러리에서 각각**(`nimbus-jose-jwt:9.41.2`, `auth0 java-jwt:4.4.0`) — alg=none·RS256 헤더 혼동·서명 위조·페이로드 변조·`sub`/`exp`/`typ` 누락·`typ` 불일치·비UUID sub·32B 시크릿 통과·31B `configuration_error` 전건 일치. **`exp` 경계 질문 해소**: skew 0에서 두 라이브러리 모두 `exp <= now`를 만료로 봐 PyJWT와 같다(`exp-2…exp+2` 훑어 예외 타입까지 확인 — 결과만 보고 오독하지 않도록 메커니즘 대조). **역방향 4/4**(`verify-jwt` 통과, subject 2종 × 유효/만료, `jwt-verify.verified.json` status: pass) | **필수 조치 B** — 경계가 맞은 것은 skew를 0으로 **명시했기 때문**이다. `DefaultJWTClaimsVerifier` 기본 `maxClockSkew`는 **60초**라 기본값으로 두면 만료 토큰이 `+59s`까지 ACCEPT돼 `jwt-exp-boundary-exact` fixture에서 실패한다. Spring Security `NimbusJwtDecoder`의 `JwtTimestampValidator`도 기본 60초라 같은 함정. auth0는 기본 leeway 0이라 무해. Phase 3에서 skew 0 명시 + 경계 fixture 2건 회귀 고정 필요 | Phase 3 kotlin-implementer | privacy-gate |
| DOCX/PDF/HWPX 라이브러리 spike | 예 | `00_kotlin-implementer_doc-spike.md`. **§4.5가 경고한 DOCX 위험은 해소됨** — POI를 usermodel이 아니라 OOXML DOM 순회로 쓰면 Python `_docx_blocks`와 **블록 리스트가 완전 일치**한다. 기존 fixture 6개 + 합성 fixture 4개 전부 Python 산출값 일치(거부 메시지 문자열까지). 동등성 6항목 전부 확인(표 제자리·텍스트박스·SDT·`w:ins`/`w:delText`·`mc:Fallback`·`a:t`/`m:t`·linked 머리글). HWPX: DTD/UTF-16 DTD/XXE 차단, 1GiB zip bomb을 힙 256MB에서 거부(힙 증가 0MB), 자체 round-trip·mimetype STORED 첫 항목·생성 결정성 PASS, Python↔Kotlin 패키지 교차 읽기 PASS. 검증 조합: Java 21.0.4 / Gradle 9.1.0 / Kotlin 2.2.0 / POI 5.4.1 / PDFBox 3.0.5 / commons-compress 1.27.1. `uv run pytest tests/ingest -q` 57 passed로 Python 기준선 무손상 | **가능성은 확인됐고 남은 것은 Phase 4 결정·구현이다.** (1) POI 산출 DOCX에 `styles.xml`/`theme` 부재 — Heading 1 서식이 사라짐, 템플릿 정책 결정 필요 (2) zip 컨테이너 바이트는 Python과 동일해질 수 없음(실측 `java=434B` vs `python=348B`) → parity fixture를 바이트 해시로 잡으면 안 됨, `parity-verifier` 합의 필요 (3) StAX DTD 판정을 예외 **메시지**로 하면 로케일 의존 — `DTD` 이벤트 직접 처리로 바꿔야 함 (4) 위조 크기 zip의 사용자 메시지가 Python과 갈림(`손상되었습니다` vs `너무 큽니다`) (5) **미검증**: 실제 한컴/Word 저장 파일, 실제 공공기관 PDF의 pypdf↔PDFBox 동등성, `MAX_EXTRACTED_CHARS`·10MB 경계, 암호 PDF/DOCX 실파일, Spring Boot BOM 적용 후 버전 재정렬 | - | kotlin-implementer |
| 리뷰 게이트 Critical 0건<br>→ **범위를 좁혀 판정**: "Phase 2 착수를 막는 Critical 0건" | 아니오 | **[criteria-pivot 2026-08-12 재판정] 예→아니오.** 3회차 리뷰(`02_criteria-pivot_cross.md` §6.2)가 이 행이 2회차 근거로 닫힌 **뒤** Phase 2 착수 차단 ② 5건을 새로 냈다 — X-05·X-06(privacy 레인)·X-09~X-12(parity 레인)·X-08(본 인벤토리 레인). 하나라도 열려 있으면 "Phase 2 착수를 막는 Critical 0건"은 거짓이다. blocked-by는 그 세 레인. 상세는 아래 「기준 전환 재판정」. 이하 근거는 2회차 시점의 역사 기록이다 — 2회차(Phase 1 골격) 실행 완료 — `reviews/01_skeleton_{codex-reviewer,migration-reviewer,cross}.md` 3건 (정본은 `_cross.md`). 1회차 수정(pre-phase0 X-1·X-2)이 2회차 리뷰를 실제로 받았고, 2회차가 새로 지적한 차단 중 **Phase 2 작업에 닿는 것은 parity CI(2회차 X-2) 하나**였으며 `01_kotlin-implementer_parity-ci-fix.md`로 닫혔다. 함께 닫힌 것: C-1·C-2·C-3(`01_kotlin-implementer_error-cors-fix.md`), T-5=P1-1 판정(`01_kotlin-implementer_boot41-upgrade.md`), F-8 확인(Phase 0 §9-2 "보존할 DB 없음"이 이미 답). **판정 근거는 교차 종합 §7.1·§8** — Phase 2는 순수 도메인 로직 포팅이라 HTTP·DB·CORS 경계를 쓰지 않으므로 심각도가 높다는 이유만으로 착수를 막는 것은 과잉이라는 권고를 채택했다 | **나머지 차단 지적은 사라지지 않는다** — 마감이 명시된 미결 원장(§Phase 1 종료 판정)으로 이월했고 각 Phase 착수 게이트에서 다시 센다. 원 판정("Critical 0건")을 그대로 쓴 것이 아니라 **범위를 좁혀** 닫은 것임을 명시한다. 상충-2(심각도 척도)는 1회차에 리더가 판정해 반영 완료 — 사건뿐 아니라 **탐지 장치의 무력화도 Critical**로 세되, 심각도와 착수 차단은 별개 축이고 마감은 그 게이트의 첫 실사용 시점이다 | - | leader (2026-08-12) |
| 전역 요구사항 인벤토리 1차본 작성·승인 (계획 §5 Phase 0 · §1.1) | 아니오 | **[criteria-pivot 신설 2026-08-12]** 커밋 49ea2eb가 Phase 0 종료 조건에 "요구사항 인벤토리 1차본 승인"을 넣었으나 산출물이 없어 행조차 세우지 못했다(리뷰 A-1/X-08 — "미충족 0"이 항목 0개에서 참이 되던 구멍). **1차본 작성 완료** → `00_requirements-inventory.md`(항목 39 + 확인방법 미확정 4건 명시). 게이트가 이제 실제 항목을 가리킨다 | 1차본은 존재하나 **승인 미완**(충족=예는 승인까지 요구) | 리더·사용자 승인 | criteria-pivot 재판정 (2026-08-12) |
| 품질 합격선 **기제** 확정·승인 (계획 §5 Phase 0 · §4.6 게이트2·5)<br>*직전 행 제목: "합격선 **수치** 확정·승인" — 확정된 것이 수치가 아니므로 제목을 고쳤다* | 예 | **[2026-08-12 3차 갱신, 아니오→예]** 요지는 **합격선을 절대 수치가 아니라 기제로 확정**했다는 것이다 — 수치를 못 정해 우회한 것이 아니라, 고정할 수 없는 것(채점 모델)과 코퍼스 난이도에 좌우되는 것(통과율)을 차단축에서 분리한 결과다. **사용자 결정 4건**: ① 하한선의 출처 = **이 저장소의 직전 기록 측정치**(상대 — Python이 아니다) ② 실수집 52.8%는 **코퍼스 난이도**로 판정 — 목표를 낮춘 것이 아니며 **KPI 0.90은 목표선으로 존속**한다 ③ **judge에 차단 권한을 주지 않는다** ④ **통과하는 실행도 수치를 남긴다**. **두 차단축** — 필수 정보 보존 **절대**(누락 0건, 결정적, LLM-as-judge 미사용) / 규칙 통과율 **상대**(직전 기록 대비 하락 0, 코퍼스·판정 기준 지문이 다르면 비교 불가로 차단). **구현·검증·커밋됨** (`c43cae5`) — `tests/golden/baseline.py`(지문·상대 판정)·`tests/golden/report.py`(통과 실행도 기록)·`app/easyread/goldenset.py`의 `find_fact_losses`(절대 팩트축). **리더 직접 실행 실측**: `pytest` **916 passed / 68 skipped**, `pytest tests/golden` **63 passed**, mypy **122 files**, 지문·기록·하락 재현 **14건** 통과. 인벤토리 §9-A의 네 공백(코퍼스 고정 → 지문 2축 / 통과 시 기록 → `report.py` / 채점자 고정 → 결정 ③으로 **소멸** / 축별 허용치 → 팩트축 절대 0·스타일축 상대화) **전부 처분**. 계획 반영: §4.6 게이트2·5 재작성, 게이트5 단서 미결 **종결**, codex Q6 **확정**(담당 = **Phase 5 종료 게이트**, Phase 7 진입 전 필수), §6 검증 매트릭스 Quality 행 교체 | **ⓐ `tests/golden/baseline.json` 미기록** — LLM 키가 없어 실측할 수 없었고 **수치를 지어내지 않았다.** 지금은 "기준선 없음 → 차단, 기록 필요"로 떨어지며 **이것이 설계된 정상 상태**다. 첫 기록: `GOLDEN_RECORD_BASELINE=1 GOLDEN_PROVIDER=anthropic uv run pytest tests/golden -m llm`. **ⓑ 절대 팩트 게이트가 첫 실행에서 통과하지 못할 공산** — 마지막 저장 실행(2026-08-08)의 팩트 잔존 90.1%·14문서 누락이고 실손실과 `accept` 목록 공백의 비율은 **정적 판정 불가**. 게이트를 낮출 사유가 아니라 프롬프트 문제이며 첫 `-m llm` 실행이 삼분류 근거를 만든다. **ⓒ 날조(fidelity=1)에 결정적 차단 없음 — 리더 판정 대기.** 역방향 축(출력의 숫자·금액·날짜가 원문에 존재하는가)은 합의 범위 밖의 새 차단 게이트라 짓지 않았다. **ⓓ 독립 리뷰 미실시.** **적용 범위의 한계(오독 금지)**: 게이트 로직·배선은 매 CI 기본 스위트에서 돌지만(재현 40건, LLM 호출 없음) **실제 변환문에 대한 적용은 `-m llm` 레인 전용**이다 — 변환 없이는 검사할 대상이 없다. "매 커밋 차단"이 아니다 | ⓐ LLM 키 / ⓒ 리더 판정 / ⓓ 리뷰 레인 | leader (2026-08-12 3차) |

### Phase 0에서 사용자 승인이 필요한 다섯 결정 (계획 §9)

| # | 결정 | 상태 |
|---|---|---|
| 1 | 목표가 "제품 런타임 Kotlin화"인지 "오프라인 도구 포함 Python 완전 제거"인지 | **승인 (2026-08-12)** — 제품 런타임만 Kotlin화. Phase 9 미착수, Python 도구는 독립 oracle로 존치 |
| 2 | 파일럿/보존 대상 DB가 있는지, 유지보수 창을 쓸 수 있는지 | **승인 (2026-08-12)** — 보존 대상 DB 없음. 빈 DB로 시작 |
| 3 | Fernet JVM 호환 구현 승인 여부와 실패 시 재암호화 방식 | **승인 (2026-08-12)** — **JDK primitive로 직접 구현**(~150줄). 아래 상세 |
| 4 | PostgreSQL 작업 큐로 전환하며 Redis를 최종 제거할지 | 미승인 — Phase 5 착수 전까지 결정 |
| 5 | 시각 UI 개편을 이번 전환과 분리하는 원칙 승인 | 미승인 — 계획 §4.1이 이미 분리를 전제하므로 기본값대로 진행하되, Phase 6 착수 전 확인 |

#### 결정 3 상세 — Fernet 직접 구현

**채택하지 않은 것과 이유** (`00_privacy-gate_crypto-spike.md` + JVM Fernet 생태계 조사):

- `com.macasaet.fernet:fernet-java8:1.5.0` — 정방향 8/8·역방향 5/5·tamper 5/5로 **기능은 통과했으나**, 최신 릴리스가 2020-09-26으로 약 5년 11개월 무릴리스라 계획 §4.3-2의 "유지보수 상태"를 만족한다고 보기 어렵다. 기본 Validator TTL 60초를 무력화해야 하는데 이는 라이브러리 설계와 반대로 쓰는 것이고, 나중에 누군가 TTL을 켜면 1초 경계 불일치가 되살아난다
- `dev.ercan:fernet:1.0.0` — Python `cryptography`와 **시맨틱이 정확히 일치**(no-TTL 경로에 타임스탬프 검사 없음, 경계·60초 skew 규칙 동일, `MessageDigest.isEqual` 사용)하나 2026-07-26 릴리스로 2주 됐고 star 0·저자 1명·보안 정책 없음·fuzzing 없음. 시민 문서를 다루는 시스템에서 검증되지 않은 단독 저자 암호 라이브러리는 다른 종류의 위험이다
- `io.github.atkawa7:fernet` — **채택 금지.** HMAC 검증에 `Arrays.equals`를 써 첫 불일치에 단락되는 타이밍 사이드채널이 있고, 바로 위 주석은 "using a constant-time comparison function"이라고 적혀 있다. `<scm>` URL이 404라 신고 경로도 없다
- `core-dragonby7k/fernet-java8` — **무단 복제본**(fork 아님, 원본 CI 배지 유지). 검색으로 흘러들지 않도록 기록
- **Kotlin-native 구현은 존재하지 않는다** — 이름이 그런 저장소 하나는 전체 구현이 `TODO("Not implemented yet.")`이고 키 생성기가 urlsafe가 아닌 표준 Base64를 써 스펙에도 어긋난다

**§4.3-4 "즉흥 구현 금지" 해석**: 그 조항은 *호환 실패 시 대안으로 새 암호 방식을 급조하는 것*을 막는다. 여기서는 **공개 스펙(fernet/spec)이 있고, 교차 런타임 fixture로 검증하며, Python oracle이 §9-1로 존치**한다. 즉 "검증 없는 자작"이 아니라 "스펙 구현 + 기계 검증"이므로 조항 위반이 아니다. 다만 이 해석은 리뷰 게이트에서 다시 도전받아야 하며, 구현 시 **JVM Fernet 생태계 전체가 CVE 0건**이라는 사실이 면죄부가 아님을 유의한다.

**구현 요건** (Phase 4 착수 전 확정):
- 토큰 형식: version(0x80)·timestamp(8B BE)·IV(16B)·ciphertext·HMAC-SHA256(32B), Base64URL
- **HMAC 검증을 복호화보다 먼저**, `MessageDigest.isEqual`로 상수 시간 비교
- **TTL 검사 없음** — Python이 `ttl=None`으로 쓰는 경로와 같아야 한다. 이것이 `fernet-java8`에서 실패했던 바로 그 지점이다
- 교차 런타임 fixture(`parity/fixtures/crypto`)로 정방향·역방향·tamper 전건 검증. **60초 경과 토큰을 반드시 포함** — 갓 만든 토큰으로만 테스트하면 TTL 결함이 통과해 버린다

#### 결정 4·5는 아직 열려 있다

Phase 1 착수를 막지는 않는다. 4는 Phase 5(작업 큐)에서, 5는 Phase 6(React 통합)에서 처음 구속력을 갖는다.

---

## Phase 0 종료 판정 — **조건부 종료** (2026-08-12, 리더 + 사용자 승인)

종료 조건 10행 중 **8행 충족, 2행 미충족**. 오케스트레이터 규칙은 "이전 Phase의 종료 조건 행이 전부 `충족 = 예`가 아니면 다음 Phase 에이전트를 호출하지 않는다"이고, 예외는 "미충족인데 진행해야 할 사정이 있으면 사용자 승인을 받고, 승인 사실과 미충족 행을 남긴다"이다. **사용자 승인을 받아 Phase 1에 착수한다.**

### 미충족으로 남기는 2행과 닫는 시점

| 미충족 행 | 왜 지금 못 닫는가 | 언제 닫는가 |
|---|---|---|
| 응답·헤더·오류·인증·권한·입력 상한을 contract test로 고정 | 계약 테스트는 HTTP 경계에서 도는데 **Kotlin API가 아직 없다.** 지금 Python에만 채우면 절반이고, 그 절반이 "계약이 검증됐다"는 착각을 준다 | **Phase 3** — `/auth/*`·`/workspaces/*`가 Kotlin에 생기는 시점. 목록과 기준은 `00_contract-keeper_test-plan.md`에 이미 있다 |
| 리뷰 게이트 Critical 0건 | 지적된 Critical 2건은 코드로 닫았으나 **그 수정 자체가 리뷰를 받지 않았다.** 이번 세션에서 "고쳤다고 보고한 직후 codex가 그 수정의 결함을 잡은" 일이 세 번 있었다 | **Phase 1 종료 시** — Kotlin 골격이라는 실제 코드가 생긴 뒤 3단계 게이트를 돌린다. 문서·스크립트만 있는 지금보다 그때 리뷰가 값이 크다 → **2026-08-12 닫힘.** 예정대로 Phase 1 종료 시점에 2회차 게이트를 돌렸고, "Phase 2 착수를 막는 Critical 0건"으로 **범위를 좁혀** 판정했다(위 표) |

**2026-08-12 갱신.** 위 2행 중 아래 행이 닫혀 Phase 0 종료 조건은 **10행 중 9행 충족 / 1행 미충족**이 됐다.
남은 1행(contract test)의 마감은 그대로 Phase 3다. 위 "8행 충족" 문장은 2026-08-12 판정 시점의 기록이므로 고치지 않는다.

### 기준 전환 재판정 (criteria-pivot, 2026-08-12)

커밋 49ea2eb가 판정 기준을 "Python 출력 일치"에서 "요구사항 충족"으로 바꾸면서 **이미 닫힌 Phase의 종료 조건을 소급 개서**했다. 리뷰 02_criteria-pivot A-1/X-08의 지적에 따라 닫힌 판정 전부를 새 기준으로 다시 봤다.

**Phase 0 표 변동 3건**
- **신설 2행(둘 다 아니오)** — 커밋이 Phase 0 종료 조건에 넣은 "요구사항 인벤토리 1차본 승인"·"품질 합격선 승인"을 행으로 세웠다. 인벤토리는 1차본(`00_requirements-inventory.md`)을 만들어 게이트가 실제 항목을 가리키게 했으나 **승인 미완**이고, 품질 합격선은 **수치 자체가 없다**.
- **1행 뒤집음(예→아니오)** — "리뷰 게이트 Critical 0건". 2회차(Phase 1 골격) 리뷰로 닫힌 뒤 3회차(02_criteria-pivot)가 Phase 2 착수 차단 ② 5건을 새로 냈다(X-05·X-06·X-08·X-09~X-12). blocked-by: privacy 레인(X-05·X-06)·parity 레인(X-09~X-12)·본 인벤토리 레인(X-08). X-08의 계측기 공백은 이 1차본으로 닫혔고, 남은 것은 승인 판정과 네 건(병렬 레인 진행 중)이다.

**재판정 후 Phase 0 집계: 12행 중 8행 충족 / 4행 미충족.** (직전 기록 "10행 중 9행"은 기준 전환 전 판정이라 위 문장들과 함께 남긴다 — 고치지 않는다.)

> **[2026-08-12 3차 갱신]** 위 4행 중 **「품질 합격선 확정·승인」이 닫혔다**(아니오→예 — 위 Phase 0 표). 합격선을 **절대 수치가 아니라 기제로** 확정하고 계측기를 구현·검증·커밋했다(`c43cae5`). **현재 집계: 12행 중 9행 충족 / 3행 미충족**(남은 3행 = contract test 고정 / 리뷰 게이트 Critical 0건 / 인벤토리 1차본 승인). 위 "8행 충족" 문장은 판정 시점의 기록이므로 고치지 않는다. 이 행에 붙은 미해결 4건(ⓐ `baseline.json` 미기록 · ⓑ 첫 실행 미통과 공산 · ⓒ 날조 차단 부재 리더 판정 대기 · ⓓ 독립 리뷰 미실시)은 **행을 닫은 것과 별개로 살아 있다** — 행이 판정하는 것은 "합격선이 확정·승인됐는가"이지 "골든 품질이 합격했는가"가 아니다. 후자를 판정하는 자리는 **Phase 5 종료 게이트**로 확정됐다(계획 §4.6 codex Q6).

**뒤집지 않은 행 — 새 기준에서도 예가 유지되는 근거(‘Python과 같다’ 근거는 재기술)**
- **`[호환]` Fernet·JWT·Argon2 spike + DB baseline(필수조치 D)** — 계획 변경 이력·CLAUDE.md가 지정한 **기준 전환 예외**다. **[2026-08-12 2차 정정]** 아래 '롤백 창 값 동일성' 근거는 **폐기**됐다(상단 재개발 masthead) — spike '예'는 유지하되 근거는 **가능성 확인 + 필수조치 A·B·D(migration-safety-gate I-7·I-8·I-9)**다. 이하 문장은 1차 재판정 기록이다. 롤백 창에서 값이 같아야 하는 것 자체가 요구사항이므로 "Python과 같다"가 정당한 근거다. 예 유지. (단 spike는 **가능성** 판정이고 실제 구현·필수조치 A·B·C는 Phase 3·4 미결로 이미 원장에 있다.)
- **`[계약]` contract yaml·3자 대조·리뷰 차단 C-1/C-2/C-3·/health** — 계약은 요구사항이 요구하는 인터페이스다. Python(FastAPI)에 맞춘 부분은 계약 요구이고, 의도적으로 다르게 간 U-1은 "개선 수용"으로 기록됐다 — Python을 oracle로 삼지 않는다. 예 유지.
- **DOCX/PDF/HWPX spike** — 근거가 "Python `_docx_blocks` 완전 일치"라 **근거를 다시 댄다**: 요구사항(인벤토리 DOC-01, 필수 정보 누락 없는 추출)은 더 강한 결과(블록 동일성)로부터 **a fortiori** 충족된다. 동일성은 향후 합격 바가 아니며 갈림은 Difference 게이트가 분류한다(계획 §4.5 — 미결 원장의 바이트 해시 금지·2.3.21 재검증과 정합). 포팅 **가능성** 판정으로서 예 유지.
- **구조·기동 행(Gradle 멀티모듈·toolchain·설정 바인딩·Dockerfile·Testcontainers·빈DB/스냅샷 기동)** — Python 출력 품질과 무관한 구조·기동 요구다. 기준 전환의 영향을 받지 않는다. 예 유지.

**Phase 1 전면 재검토 — 뒤집음 0.** Phase 1 종료 조건 행은 전부 구조·기동·호환성·계약 근거이고 "Python 출력 품질 일치"로 닫힌 행이 없다. 이미 아니오인 2행(GitHub Actions 미검증·필수조치 E fixture 부재)은 새 기준에서도 아니오다. **Phase 2 표는 전부 미착수(아니오)라 뒤집을 닫힌 판정이 없다.**

**Phase 2 착수 함의:** X-08 계측기 공백은 닫혔으나 ① 인벤토리 1차본 **승인**, ② 품질 합격선 **수치**, ③ 나머지 차단 4건(X-05·X-06·X-09~X-12, 병렬 레인)이 남는다. Phase 2 착수 가부는 이 인벤토리 레인 단독으로 판정하지 않는다 — 리더 몫이다.
> **[2026-08-12 3차 갱신]** ②는 닫혔다 — 다만 확정된 것은 **수치가 아니라 기제**다(위 표 행). 남는 것은 ①과 ③이다.

### 착수 전 판단이 필요했으나 Phase 1을 막지 않는 항목

- ~~**U-1** — 미처리 500 응답의 CORS 헤더를 Kotlin에서 재현할지 개선할지~~ → **2026-08-12 결정됨: 개선 수용**(리더 판정 + 사용자 승인). 착수 전 전제였던 "React가 그 동작(`status = 0`)에 의존한다"는 **실측으로 무너졌다** — `grep -rn "NETWORK_ERROR_STATUS|status === 0|\.status" frontend/src` 결과 `status = 0` 경로에 의존하는 화면 분기가 없고, 모든 화면이 `caught instanceof ApiError ? caught.message : <폴백>` 한 모양이다. 상태로 갈리는 자리는 `client.ts`의 401 하나뿐이다. 바뀌는 것은 사용자에게 보이는 **문구뿐**("서버에 연결하지 못했습니다…" → "서버 오류가 발생했습니다"). 되돌리려면 `CorsFilter`를 감싸 **일부러 나쁜 동작을 만드는 코드**를 영구히 남겨야 한다. 근거 `00_contract-keeper_changelog.md`, 비용 비교 `01_kotlin-implementer_error-cors-fix.md` §5. **범위**: 달라지는 것은 CORS 응답 헤더의 유무뿐이며 상태 코드(500)·본문(`{"detail": "서버 오류가 발생했습니다"}`)이 갈리면 그것은 의도된 차이가 아니라 계약 위반이다
- **G-1** — `POST /workspaces`·`PATCH /workspaces/{id}`의 캐시 헤더를 **Python 기준선에서도 어떤 테스트가 단언하지 않는다.** 계약상 10곳 중 2곳이 회귀 방지 없이 구현에만 존재한다. Phase 3에서 Kotlin·Python 양쪽에 동시에 넣는다
- **DOCX 내보내기 템플릿** — POI 산출물에 `styles.xml`/`theme`가 없어 Heading 1 서식이 사라진다. 템플릿을 저장소에 동봉할지 **Phase 4 착수 전** 결정
- **내보내기 parity 비교 기준** — zip 컨테이너 바이트는 Python과 동일해질 수 없다(실측 java 434B vs python 348B). 바이트 해시가 아니라 정규화 텍스트로 비교하도록 `parity-verifier`와 합의 필요. **Phase 4 착수 전**

### Phase 1로 넘기는 필수 조치 (spike에서 확정된 구현 지침)

| # | 내용 | 마감 |
|---|---|---|
| A | Argon2 재해시 판정을 **전체 파라미터 동등성**으로 구현. Spring `upgradeEncoding`은 memory·iterations의 "미만"만 봐 Python `check_needs_rehash`와 갈린다. 탐침 7건을 회귀로 고정 | Phase 3 |
| B | JWT 검증에 **clock skew 0을 명시**. `DefaultJWTClaimsVerifier`·`NimbusJwtDecoder` 기본값이 60초라 만료 토큰이 +59초까지 통과한다. 경계 fixture 2건 회귀 고정 | Phase 3 |
| C | Fernet 구현에 **TTL 검사를 넣지 않는다**. Python이 `ttl=None`으로 쓰는 경로와 같아야 한다. **60초 경과 토큰**을 fixture에 반드시 포함 | Phase 4 |
| D | `encryption_scheme` 컬럼을 **Phase 1 첫 Flyway에 additive로** 추가(기본값 `'fernet-v1'`). 관찰 기간엔 `fernet-v1` 고정, AEAD 전환은 Phase 8 이후 단일 런타임 상태에서 별건 | Phase 1 |
| E | parity 게이트의 마지막 구멍 — **Kotlin 테스트 하네스가 `parity/actual/{도메인}/*.json`을 쓰도록 CI에 배선.** 지금은 "산출물을 Kotlin이 만들었다"는 보장이 없다(fixture가 키·시크릿을 공개하므로 Python으로도 같은 파일을 만들 수 있다) | Phase 1 |

---

## Phase 1 — Kotlin 골격과 CI

계획 문서 §5 Phase 1. 원문 종료 조건: "**빈 DB와 기존 schema snapshot 양쪽에서 Kotlin 앱이 기동되고 `/health`가 응답함.**"

작업 항목 6개를 종료 조건 행으로 쪼갰다. 상세 근거·명령·출력은 `01_kotlin-implementer_skeleton.md`.

| 종료 조건 | 충족 | 근거 | 미해결 항목 | blocked-by | 마지막 갱신 주체 |
|---|---|---|---|---|---|
| `backend-kotlin` Gradle 멀티모듈 생성 (§3.2의 5개 모듈, 의존 방향) | 예 | `core`/`application`/`infrastructure`/`api`/`worker` 생성. `api`·`worker` 는 `infrastructure` 를 **`runtimeOnly`** 로만 의존해 JDBC·(Phase 5) LLM SDK 타입이 컴파일 시점에 보이지 않는다. `application` 은 `infrastructure` 를 의존하지 않는다. `api`↔`worker` 상호 의존 없음. **`core` 의 Spring·DB 비의존을 `CoreModuleBoundaryTest` 가 실행으로 확인**(7개 클래스 부재: `ApplicationContext`·`SpringApplication`·`JdbcClient`·`Flyway`·`org.postgresql.Driver`·Jackson 2/3 `ObjectMapper`) | `application` 본 소스는 비어 있다(경계만 세움, 유스케이스는 Phase 3~5). 계약은 `application/README.md` | - | kotlin-implementer |
| toolchain·dependency locking·version catalog·ktlint/detekt·테스트 설정 | 예 | Java 21 toolchain(`jvmToolchain(21)`), `allWarningsAsErrors=true`. 락파일 6개 커밋(모듈 5 + settings, 792줄) — `clean build` 가 락 갱신 없이 성공. catalog 가 유일한 버전 선언 지점이고 **BOM 밖에서 버전을 고르는 것은 Kotlin 플러그인·ktlint·detekt 셋뿐**. ktlintCheck·detekt 모두 통과(위반 0). **locking 이 실제 드리프트를 잡았다** — kotlinx-serialization 1.11.0이 테스트 클래스패스 stdlib 만 2.2.21→2.3.20으로 올린 것을 발견해 BOM(1.9.0)에 넘겼다 | 기본값을 벗어난 규칙 2건(ktlint `class-signature` 임계 1→2, detekt `SpreadOperator` off) — 사유는 산출물 §2.4.<br>**2026-08-12 사실 정정** — detekt 1.23.8의 내장 파서는 Kotlin **1.9가 아니라 2.0.21**이다(`detekt-parser` POM 실측). 같은 종류의 간격이 ktlint에도 있고 Phase 1 문서에 언급이 없었다 — ktlint-cli 1.8.0은 **2.2.21**을 내장한다. Boot 4.0.7 시절엔 컴파일러도 2.2.21이라 우연히 같았고 **2.3.21로 올리면서 갈렸다**. 둘 다 올릴 곳이 없다(ktlint 1.8.0/플러그인 14.2.0이 최신, detekt 2.x는 `dev.detekt` 좌표에 alpha만). 남는 위험은 "Kotlin 2.3 신문법을 쓰면 그때 파싱 실패"인데 **태스크 실패로 드러나므로 조용히 틀리는 종류가 아니다**. 근거 `01_kotlin-implementer_boot41-upgrade.md` §6 | - | kotlin-implementer (2026-08-12) |
| `/health` 가 계약대로 응답 (상수 `{"status":"ok"}`) | 예 | `HealthContractTest` 4건 — 200·`{"status":"ok"}`(strict)·인증 불필요·**캐시 금지 헤더 없음**·DataSource 없이도 200(=의존 서비스 진단 안 함). compose 실측: `HTTP/1.1 200 / Content-Type: application/json / {"status":"ok"}`. Actuator 미도입(계약 14 엔드포인트 밖 경로를 노출하지 않으려고) | - | - | kotlin-implementer |
| 설정 바인딩·구조화 로그·비밀값 마스킹 | 예 | `EasyDocProperties`(`app/config.py` 포팅) + `Secret` 타입(`SecretStr` 대응) + `SecretConverter`. `SecretTest` 7건 — `toString`·문자열 템플릿·**데이터 클래스 필드로 들어가도** 평문 미노출, 값 비의존 `hashCode`, 상수 시간 비교. 구조화 로그는 Dockerfile `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` 로 ECS JSON — compose 로그로 확인. `server.error.include-*` 를 전부 `never`/false 로 꺼 스택·입력값이 응답에 실리지 않게 했다.<br>**2026-08-12** — `easydoc.cors-origins` 가 `CorsConfig` 에서 **실제로 소비**되면서 바인딩 경로가 `CorsContractTest` 로 실행된다(리뷰 T-1 부분 해소) | `Secret` 필드(`jwtSecret`·`fernetKey`)와 `SecretConverter` 의 바인딩은 여전히 미실행 — 값을 쓰는 기능이 Phase 3·4에 온다 | - | kotlin-implementer (2026-08-12) |
| **리뷰 차단 C-1·C-2·C-3** — 오류 계약의 HTTP 경계 검증과 CORS | 예 | 상세는 `01_kotlin-implementer_error-cors-fix.md`. **C-1**: `GlobalExceptionHandler` 가 `ResponseEntityExceptionHandler` 를 상속해 프레임워크 예외 20종의 **상태 코드는 위임**하고 **본문만** `createResponseEntity` 한 곳에서 `{"detail": ...}`·`application/json` 으로 덮는다. `detail` 은 상태 코드의 표준 사유 문구만 쓴다(Spring `ProblemDetail.detail` 은 예외 메시지에서 유도돼 요청 본문 조각이 실릴 수 있다). `WebMvcConfig` 로 내용 협상을 끄고(FastAPI 는 협상하지 않는다) 검증 실패를 **422 + `[{loc,msg,type}]`** 로 되돌렸다(`input`·`ctx` 미노출). **C-2**: `ErrorContractTest` 를 핸들러 직접 호출 → `@WebMvcTest`+MockMvc 로 이전하고 테스트 소스셋 전용 `ErrorProbeController`(`/__probe`, 운영 JAR 에 없음)를 세웠다. **고치기 전 8건 실패 → 고친 뒤 31건 전건 통과**(도메인 매핑 12건은 전후 모두 통과 = 새 테스트가 정확히 C-1 범위만 새로 잡았다). **C-3**: `CorsConfig` 의 `CorsFilter`(order HIGHEST_PRECEDENCE) — origin 설정값·credentials false·메서드 5종·요청 헤더 2종·**노출 헤더 `Content-Disposition, Location`**·max-age 600. `addCorsMappings` 가 아니라 필터인 이유는 Starlette 미들웨어처럼 **라우팅 밖**이어야 404·405 에도 헤더가 붙기 때문(실측 일치). 살아 있는 두 컨테이너 16 케이스 대조: C-1 세 케이스 **전건 일치**, CORS 5 케이스 일치 | 남은 차이 5건 전부 기록·분류함 — **U-1(미처리 500 의 CORS 헤더)은 2026-08-12 리더 결정으로 종결**(개선 수용), `GET /health/` 307 vs 404는 판단 대기, **범위 밖 3건**(`OPTIONS` Origin 없음 = 리뷰 C-4, `HEAD /health`, preflight 본문).<br>**C-2 의 8건 중 1건은 고치지 않고 테스트를 철회했다** — `컨트롤러가 적어 둔 캐시 금지 헤더가 오류 응답으로 새지 않는다`. 서블릿 API 에 헤더 삭제가 없고(`setHeader(name, null)` 무시) `response.reset()` 은 CORS 헤더까지 지운다. 강제 가능한 규칙은 "쓰지 않는 것"뿐이며, 이 사실이 계약 §2.7-3 재작성(규칙 1 + 단언 A·B)의 근거가 됐다. 남아 있는 보호: "핸들러 자신이 캐시 헤더를 붙이지 않는다" 단언 7건.<br>`handleHandlerMethodValidationException` 은 `spring-boot-starter-validation` 이 없어 **HTTP 경계 미검증**(의존성 추가는 동시 작업 중인 빌드 스크립트를 건드려야 해 보류) | - | kotlin-implementer (2026-08-12) |
| Testcontainers PostgreSQL + Flyway baseline 구축 | 예 | `V1__python_schema_baseline.sql` 을 **Alembic 을 실제로 돌려**(`uv run alembic upgrade head` → `alembic_version=0006`) 뽑은 스키마로 작성. 지문 대조 **전건 일치**(extension 1 · table 4 · column 32(서수 포함) · constraint 11 · index 11). 회귀는 `PythonSchemaBaselineTest` 4건 + `FlywayBaselineGuardTest` 4건. `baseline-on-migrate=true` 를 쓰지 않고 **지문이 일치할 때만** baseline 하는 `FlywayBaselineGuard` 를 만들었다(§4.2-4). `alembic_version` 은 만들지도 읽지도 쓰지도 않는다(§4.2-7) | Testcontainers 컨테이너가 모듈마다 따로 뜬다(`withReuse` 미적용). 로컬 전체 16초라 지금은 무해 | - | kotlin-implementer |
| **필수 조치 D** — `encryption_scheme` additive 추가 | 예 | **V2에 배치**(V1 아님). 근거: ① V1은 "Python 스키마 재현"이라 신규 컬럼이 들어가면 지문 대조가 성립하지 않는다 ② **결정적** — baseline 은 V1을 건너뛰므로 V1에 넣으면 기존 Alembic DB에서 컬럼이 영원히 안 생긴다 ③ §4.2-5가 "Kotlin 전용 변경은 V2부터"라고 명시. 대상 `documents`·`conversions`, 기본값 `'fernet-v1'`, CHECK 제약 동반. `V2 는 encryption_scheme 을 additive 로 추가한다`·`Python 컬럼만 지정한 INSERT 가 성공한다` 테스트 통과 | 관찰 기간 내내 `fernet-v1` 고정. AEAD 전환은 Phase 8 이후 별건 | - | kotlin-implementer |
| Dockerfile·compose Kotlin profile 추가 (기존 Python 서비스 유지) | 예 | `backend-kotlin/Dockerfile`(멀티스테이지, api·worker bootJar 한 이미지). compose에 `kotlin-migrate`·`kotlin-api`(8100)·`kotlin-worker` 를 `profiles:["kotlin"]` 뒤에 추가 — **기존 Python 서비스 정의를 하나도 바꾸지 않았고** 기본 `docker compose up` 동작이 그대로다. 실측: 두 스택 동시 기동, Kotlin 8100·Python 8000 양쪽 `/health` 200. `kotlin-migrate` exit 0. §4.2-6대로 **DB를 갈랐다**(`easydoc` / `easydoc_kotlin`) — Python DB에 `flyway_schema_history` 0개 확인 | `easydoc_kotlin` 은 기존 볼륨에서 자동 생성되지 않는다(initdb 는 빈 데이터 디렉터리에서만 실행) — 수동 절차 문서화. compose 실행 중 **worker 즉시 종료**를 발견해 `spring.main.keep-alive: true` 로 고쳤다(산출물 §9.5) | - | kotlin-implementer |
| CI에 Kotlin build/test 추가 + 기존 Python/React gate 유지 | 아니오 | `.github/workflows/ci.yml` 에 `kotlin` 잡 추가(9 steps: setup-java 21 · setup-gradle · setup-uv · 이미지 pull · `./gradlew build` · `parityHarness` · 배선 확인 · parity 비교). **기존 `quality`(8 steps)·`frontend`(6 steps) 잡을 건드리지 않았다** — 로컬에서 `ruff`·`ruff format`·`mypy`·`pytest`(820 passed, 68 skipped) 전부 통과 확인 | **CI가 실제 GitHub Actions 에서 도는 것을 확인하지 못했다.** YAML 파싱과 로컬 동등 명령만 검증했다. `gradle/actions/setup-gradle@v4`·러너 Docker 데몬 위 Testcontainers 는 **첫 push 에서 처음 검증된다**. 이 행은 그때 닫는다 | 첫 PR 실행 | kotlin-implementer |
| **필수 조치 E** — Kotlin 테스트가 `parity/actual/` 을 쓰도록 CI 배선 | 아니오 | 배선 구조 완성: `ParityActual`(경로를 시스템 프로퍼티로만 받고 **없으면 던진다**) + `parityHarness` Gradle 태스크(`@Tag("parity")` 만, 저장소 루트로 출력) + 일반 `test` 는 모듈 `build/` 로 격리 + CI 3단계(생성·선언 대조 → 존재·`runtime:kotlin` 확인 → 비교). `ParityActualTest` 5건이 산출물 형식·경로·한글 비이스케이프·거부 조건을 고정. 실측 산출물 `parity/_harness-selfcheck/kotlin.json`(`runtime:kotlin`, JVM 21.0.4 Temurin, Kotlin 2.2.21).<br>**2026-08-12 X-1·X-2 수정 (`01_kotlin-implementer_parity-ci-fix.md`)** — 판정 범위를 디렉터리 유무가 아니라 버전 관리 선언 `backend-kotlin/parity-domains.txt` 에서 가져오고, 그 선언을 Gradle `parityManifestCheck` 가 실제 산출물과 **양방향 대조**한다(선언 O/산출 X·선언 X/산출 O·json 0건 전부 빌드 실패). `parityActualClean` 이 매 실행 전 `parity/actual/` 을 비워 stale 산출물 통과를 막는다. **종료 코드 2 사면은 제거**했고(실측: exit 2 는 "선언한 도메인의 역방향 산출물 미생성"일 때만 난다), 사면은 `--only-domain` 부분 검증(exit 3)으로 옮겨 **탐지(Gradle 단계)와 사면(비교 단계)을 다른 CI 단계에 분리**했다. 선언이 정본 11개를 덮으면 좁히기가 자동으로 사라진다. 실증 14종(CI 셸 8 + Gradle 6): 현재 상태 exit 0 / Phase 2 흉내 exit 0(값 21건 대조) / 선언했는데 산출물 없음 exit 1 / 값 불일치 exit 1 / fixture 트리 삭제 exit 1 / 전체 게이트 exit 0(값 101건 + 외부 2건). Python 게이트 무손상(820 passed).<br>**2026-08-12 가드 2종 추가 (`01_parity-canonical-floor.md`)** — ① **정본 0개 가드**: `canonical_count == 0`이면 exit 1(`ci.yml:197`). `--list`가 exit 0인데 출력만 비는 경로는 `pipefail`이 못 잡고, 그대로 두면 "11개를 안 봤다"는 경고가 "0개를 안 봤다"로 바뀌어 무검증이 통과한다. ② **정본 하한**: `.github/parity-canonical-floor.txt`(초기값 정본 11개) + **비대칭 검사**(현재 정본 ⊇ 스냅샷). 추가는 통과시키고 **삭제만 막는다** — 축소가 "전체 게이트 통과"로 위장되던 경로가 닫혔다. 실증 12종: 11→3 축소가 선언 0개·선언 3개 양쪽에서 exit 1, 가드 제거 변형 4종은 전부 exit 0(막고 있는 것이 정확히 이 비교임을 확인), 하한 파일 삭제·비움도 exit 1 | **채우지 못한 것**: `parity/fixtures/` 는 여전히 저장소에 없다(Phase 2 산출물). 실증에 쓴 Kotlin 산출물은 Python 스탠드인이며 실제 Kotlin 구현으로 도는 것은 Phase 2 첫 도메인에서 처음 확인된다. **GitHub Actions 러너 실행 미검증**(로컬 bash 3.2 재현). `parityManifestCheck` 는 도메인 입도까지만 봐서 **X-5 의 모듈↔도메인 대응 단언은 열려 있다**. 하한도 도메인 **이름**만 보므로 도메인 안의 케이스 축소는 잡지 못하고, 같은 커밋에서 정본과 하한을 함께 줄이는 것은 원리적으로 막을 수 없다(최종 방어선은 `.github/` diff 를 사람이 읽는 리뷰 게이트다). 미결 원장 `P1-2`(종료 코드 2 완화)는 **해소 확인**(아래 사실 정정) | Phase 2 (fixture 생성 · 첫 push 에서 러너 검증) | kotlin-implementer (2026-08-12) |
| 종료 조건: 빈 DB와 기존 schema snapshot 양쪽에서 기동 + `/health` 응답 | 예 | `ApiStartupOnEmptyDatabaseTest` 2건 + `ApiStartupOnPythonSnapshotTest` 2건. `@SpringBootTest(RANDOM_PORT)` + JDK `HttpClient` 로 **실제 소켓**을 친다. 빈 DB → `flyway_schema_history=[1,2]`, 200 `{"status":"ok"}`. 기존 스냅샷(Alembic 0006 상태) → `[1(BASELINE), 2(SQL)]`, `alembic_version=0006` 불변, 200 `{"status":"ok"}`. compose 실측으로도 재확인(산출물 §9).<br>**2026-08-12 Boot 4.1.0 업그레이드 후 재확인** — 같은 4건이 그대로 통과하고, Flyway 11→12(유일한 메이저 상승)에 대해 **11.14.1이 쓴 `flyway_schema_history` 를 12.4.0이 `Successfully validated 2 migrations` 로 수용**하는 것까지 compose 로그로 확인했다(체크섬 재계산 요구 없음). 근거 `01_kotlin-implementer_boot41-upgrade.md` §5·§8-4·§8-5 | - | - | kotlin-implementer (2026-08-12) |

**전체 테스트**: `./gradlew build` → **BUILD SUCCESSFUL, tests=75 failures=0** (core 19 · infrastructure 8 · api 45 · worker 3).
2026-08-12 리뷰 C-1·C-2·C-3 수정으로 48 → 75 (api 18 → 45: `ErrorContractTest` 10→18 경계 이전 ·
`FrameworkErrorContractTest` 9 신규 · `CorsContractTest` 10 신규). 기존 48건은 전건 유지.
**Boot 4.1.0 업그레이드 후에도 같은 75건이 그대로 통과한다** — 업그레이드 전(4.0.7) 기준선을 먼저 찍고 대조했다.

### 확정한 버전 조합 (Boot 4.1.0 BOM 적용 후 — 2026-08-12 갱신)

| 항목 | 값 | 이전(4.0.7) 대비 |
|---|---|---|
| JDK / Gradle | Temurin 21.0.4 / 9.1.0 | 동일 |
| Kotlin | **2.3.21** | 2.2.21 → BOM `kotlin.version` 정렬 (변경) |
| Spring Boot | **4.1.0** | 4.0.7 → **P1-1 판정 이행**. 4.1 계열 안정판은 4.1.0 하나뿐(Maven Central 메타데이터 실측) |
| Flyway | **12.4.0** | 11.14.1 → **유일한 메이저 상승**. §5 에서 따로 검증 |
| kotlinx-serialization | **1.11.0** | 1.9.0 → BOM |
| Spring Framework / Jackson / JUnit / Testcontainers / PG 드라이버 | 7.0.8 / 3.1.4 / 6.0.3 / 2.0.5 / 42.7.11 | **전부 동일** (BOM 관리) |
| ktlint(플러그인/CLI) / detekt | 14.2.0 / 1.8.0 / 1.23.8 | 동일 (§위 표의 사실 정정 참고) |

Boot 4가 spike 이후 바꾼 좌표(실측)는 4.1.0에서도 그대로다: `FlywayMigrationStrategy` → `spring-boot-starter-flyway` /
`org.springframework.boot.flyway.autoconfigure`, `@WebMvcTest` → `spring-boot-starter-webmvc-test` /
`org.springframework.boot.webmvc.test.autoconfigure`, Testcontainers → `org.testcontainers:testcontainers-postgresql`.
**Jackson 3.1.4**(패키지 `tools.jackson`)가 관리 버전이라 Phase 2 이후 JSON 처리 시 주의가 필요하다.

**업그레이드 결과 (`01_kotlin-implementer_boot41-upgrade.md`)** — **코드 변경 0줄.** 바뀐 파일은 version catalog 와
락파일 5개뿐이고, `clean build` tests=75 failures=0 · ktlint/detekt 위반 0 · 컴파일 경고 0(`allWarningsAsErrors=true`) ·
Python 게이트 820 passed 가 업그레이드 전과 동일하다. Kotlin 2.3.21이 실제로 런타임까지 갔다는 증거는
`parity/_harness-selfcheck/kotlin.json` 의 `kotlinVersion` 필드(JVM 이 `KotlinVersion.CURRENT` 를 읽어 쓴다)다.

두 가지가 부수 성과다.

- **Jackson 2가 클래스패스에서 제거됐다.** Flyway 12가 Jackson 2 → 3으로 갈아타면서, 4.0.7에서 api `runtimeClasspath` 에
  Jackson 2 databind 와 3 databind 가 **동시에** 올라와 있던 상태가 해소됐다. 직렬화 라이브러리 두 벌은 나중에
  오류 본문·`Content-Disposition` 같은 계약 지점에서 "어느 `ObjectMapper` 가 잡혔는가"로 번지기 쉬운 배치였다.
- **락파일 재생성 절차를 확립했다.** 증분 `--write-locks` 는 계열이 바뀌는 업그레이드에서 **stale 제약을 남긴다** —
  KGP 2.3이 더 이상 해석하지 않는 `kotlinCompilerClasspath` 의 락 항목이 2.2.21로 남아 2.3.21 요청을 **강등**시켰다
  (`dependencyInsight` 로 확인). 조치: 락파일 5개를 지우고 `clean build --write-locks --no-build-cache` 로 재생성.
  `clean` 과 `--no-build-cache` 가 함께 없으면 태스크가 up-to-date 로 건너뛰어 락파일이 실제보다 **비어 있게** 생성된다.

### 리더 판단이 필요했던 항목 — 둘 다 해소 (2026-08-12)

| # | 내용 | 결과 |
|---|---|---|
| P1-1 | **Spring Boot 4.0.7 vs 4.1.0.** 계획 §3.1은 "4.1 계열 후보"라 적었으나 4.0.7을 골랐다 (2차 리뷰 K-3 = 교차 T-5, 마감 Phase 2 착수 전) | **해소 — 4.1.0으로 올렸다.** 되돌릴 이유를 찾지 못했다(코드 0줄, 이동·폐기 좌표 0건, 테스트 75건 유지, 메이저 상승인 Flyway 12는 별도 검증). 되돌리는 비용은 시간에 비례해 커지므로 판단을 미루지 말라는 리뷰 권고를 따랐다 |
| P1-2 | CI parity 비교 단계가 **종료 코드 2를 통과 처리**한다 (마감 Phase 4 종료 시) | **해소 — 사면 자체가 제거됐다.** 아래 사실 정정 참고 |

### Phase 1에서 손대지 않은 것 (지시대로 보류)

- ~~**U-1**(미처리 500 응답의 CORS 헤더) — 리더 판단 전이라 **CORS 자체를 설정하지 않았다**~~
  → **2026-08-12 갱신**: CORS 는 구현했다(리뷰 C-3). **U-1 경로에는 헤더를 붙이는 코드도 떼는 코드도 넣지 않았고 테스트로도 고정하지 않았다.** 다만 `CorsFilter` 가 체인 앞에서 헤더를 쓰므로 **기계적으로 "개선" 쪽 값이 나온다**(측정: 미처리 500 에 `Access-Control-Allow-Origin`·`Expose-Headers` 있음)
  → **2026-08-12 종결**: 리더가 그 값을 **확정**했다(개선 수용, 사용자 승인). 계약 파일과 changelog 에 "의도된 차이"로 기록됐고, parity 대조에서 이 헤더 불일치는 차단 사유가 아니다. `kotlin-implementer` 는 이 결정을 **테스트로 고정**할 것 — 현재 이 경로에는 긍정 단언도 부정 단언도 없다
- ~~검증 실패(422) 응답의 `detail` **배열** 형태~~ → **2026-08-12 구현**. 상태 코드 422 + `[{loc,msg,type}]`, `rejectedValue`·`input`·`ctx` 미노출을 HTTP 경계 테스트로 고정. **`msg`/`type` 문자열은 Pydantic 과 바이트 동일할 수 없다**(검증 엔진이 다르다) — 계약이 동결한 것은 상태 코드·키 구성·입력값 부재이고 문구는 그 아래라는 판단이며 `contract-keeper` 확인이 필요하다
- ~~`GlobalExceptionHandler` 의 HTTP 경계 검증~~ → **2026-08-12 이전 완료**(리뷰 C-2). `@WebMvcTest`+MockMvc + 테스트 전용 `ErrorProbeController`. Phase 3 는 이 위에 **실제 엔드포인트의** 401·404·409·422·500 을 얹으면 된다
- **성공 응답의 캐시 금지 헤더는 Phase 3 에서 `ResponseEntity` 에 붙여야 한다** — 컨트롤러가 `HttpServletResponse` 에 직접 쓰면 예외가 나도 그 헤더가 오류 응답에 남는다(Python 과 반대 거동). 서블릿 API 에 헤더 삭제가 없고 `response.reset()` 은 CORS 헤더까지 지워 대안이 되지 못한다. 이 제약을 `GlobalExceptionHandler` KDoc 에 적어 두었다
- `spring-boot-starter-validation` 미추가 — 의존성·락파일·빌드 스크립트가 동시 작업 중이라 열지 않았다. 그래서 `handleHandlerMethodValidationException` 은 **미검증**이다. Phase 3 에서 입력 상한과 함께 회귀 고정
- `app/`·`tests/`·`frontend/`·`scripts/`·`.claude/`·`contracts/` — 읽기만 했다
  (이후 같은 라운드에서 `contract-keeper` 가 자기 소유 파일인 `contracts/easy-doc-v1.yaml` 과
  `.claude/skills/api-contract-freeze/SKILL.md` 를 U-1·§2.7-3 반영으로 고쳤다. `app/`·`tests/`·`frontend/` 는 무손상)

---

## Phase 1 종료 판정 — **조건부 종료** (2026-08-12, 리더)

계획 §5 Phase 1의 **명시 종료 조건**("빈 DB·기존 schema snapshot 양쪽에서 Kotlin 앱이 기동되고 `/health` 가 응답함")은
**충족**이며, Boot 4.1.0 업그레이드 후에도 재확인됐다.

**"리뷰 게이트 Critical 0건" 행은 "Phase 2 착수를 막는 Critical 0건"으로 범위를 좁혀 판정하고 충족으로 닫는다.**
근거는 2회차 교차 종합(`reviews/01_skeleton_cross.md` §7.1·§8)이다 — Phase 2는 순수 도메인 로직 포팅이고
종료 조건이 "외부 API·DB 없이 실행하는 parity suite"라서 **HTTP·DB·CORS 경계를 쓰지 않는다.**
실제로 Phase 2를 막던 것은 parity CI(2회차 X-2) 하나였고 그것이 닫혔다. 심각도가 높다는 이유만으로
착수를 막는 것은 과잉이라는 교차 종합의 권고를 채택했다.

**나머지 차단 지적은 마감이 명시된 미결 원장으로 이월한다.** 이렇게 하면 (a) 충족된 종료 조건을 인위적으로
열어 두지 않고, (b) 차단 항목이 마감 없이 사라지지 않으며, (c) Phase 2가 실제로 막히는 한 건만 선행 조건이 된다.

> **조건부인 이유** — Phase 1 표에 `아니오` 2행이 남아 있다: CI가 실제 GitHub Actions에서 도는 것 미검증(첫 push),
> 필수 조치 E의 fixture 산출물 부재(Phase 2). 둘 다 이 저장소 안에서는 닫을 수 없고 마감이 명시돼 있다.

### 이번 라운드에 닫힌 것

| 항목 | 내용과 근거 |
|---|---|
| **C-1** 프레임워크 예외 500 | 상태 코드는 프레임워크에 위임(`ResponseEntityExceptionHandler` 상속)하고 **본문만** `createResponseEntity` 한 곳에서 계약대로 덮는다. `detail` 은 상태 코드의 **표준 사유 문구**만 쓴다 — Spring `ProblemDetail.detail` 은 예외 메시지에서 유도돼 요청 본문 조각이 실릴 수 있기 때문이다. 살아 있는 컨테이너에서 404 · 405+`Allow: GET` · `Accept: application/xml` 에도 200 확인 |
| **C-2** 오류 계약 테스트가 HTTP 경계를 안 봄 | 핸들러 직접 호출 → `@WebMvcTest`+MockMvc + 테스트 소스셋 전용 `ErrorProbeController`(`/__probe`, 운영 JAR 에 없음)로 이전. **새 테스트를 먼저 넣어 8건 실패를 확인한 뒤 고쳤고**(도메인 매핑 12건은 전후 모두 통과 = 새 테스트가 정확히 C-1 범위만 새로 잡았다) → **31건 전건 통과**. 8건 중 1건은 고칠 수 없어 테스트를 철회했고 그 근거가 계약 §2.7-3 재작성으로 이어졌다(위 표 참고) |
| **CORS** (C-3) | `addCorsMappings` 가 아니라 **`CorsFilter`**(order HIGHEST_PRECEDENCE) — Starlette 미들웨어와 같은 **라우팅 밖** 위치여야 404·405에도 헤더가 붙는다(Python 실측 일치). 노출 헤더 `Content-Disposition`·`Location` 을 **preflight·실요청 양쪽에서** 확인. 회귀 테스트 10건 |
| **parity CI** (2회차 X-1·X-2) | 판정 범위를 **"디렉터리 유무"에서 "버전 관리되는 선언"으로** 옮겼다. `backend-kotlin/parity-domains.txt` + Gradle `parityManifestCheck` 양방향 대조(선언 O/산출 X · 선언 X/산출 O · json 0건 전부 빌드 실패) + `parityActualClean`. **exit 2 사면은 제거** — 실측 결과 그 조건은 발생하지 않았고, exit 2는 "선언해 놓고 역방향 산출물을 안 만든" 상태에서만 난다. 사면은 `--only-domain` 부분 검증(exit 3)으로 옮겨 **탐지(Gradle 단계)와 사면(비교 단계)을 다른 CI 단계에 배치**했다 |
| **정본 0개 가드** | `canonical_count == 0` 이면 exit 1. `--list` 가 exit 0 인데 출력만 비는 경로는 `pipefail` 이 못 잡는다 |
| **정본 하한** | `.github/parity-canonical-floor.txt` + **비대칭 검사**(현재 정본 ⊇ 스냅샷). 추가는 자유, **삭제는 차단** |
| **Boot 4.1.0** (P1-1) | Kotlin 2.3.21. **코드 변경 0.** 락파일 재생성 절차 확립(증분 `--write-locks` 는 stale 제약을 남긴다). Jackson 2가 클래스패스에서 제거됨 |
| **U-1** 미처리 500의 CORS 헤더 | **개선 수용**(리더 판정 + 사용자 승인). React 분기 실측 결과 `status = 0` 에 의존하는 화면이 없다 |
| **계약 §2.7-3** | 실행 불가능한 종료 조건을 **"규칙 1(사적 헤더는 `ResponseEntity` 에만) + 단언 A·B"** 로 재작성 |

### 사실 정정 (기존 기재가 틀렸다)

- **`P1-2`**(CI parity 비교 단계가 exit 2를 통과 처리) — **해소됨.** exit 2 사면 **자체가 제거**됐다.
  마감을 "Phase 4 종료 시"로 잡았던 것은 그 완화를 그때까지 유지한다는 전제였는데, 실측 결과 그 사면이
  겨냥한 조건("아직 포팅하지 않았다")은 애초에 exit 2를 내지 않았다 — exit 1을 낸다. 사면할 이유가 없었다.
- **detekt 1.23.8의 내장 Kotlin 파서는 1.9가 아니라 2.0.21이다**(`detekt-parser` POM 실측).
  그리고 **ktlint-cli 1.8.0은 2.2.21을 내장해 이제 컴파일러(2.3.21)와 다르다** — 4.0.7 시절엔 우연히 같아서
  Phase 1 문서에 기재되지 않았다.

### 미결 원장 (마감 명시) — 각 Phase 착수 게이트에서 다시 센다

| 항목 | 마감 |
|---|---|
| Flyway 지문 TOCTOU + Alembic head 미확인 (2회차 codex #3·#4 / 교차 F-3·F-5) — 지문 판정·baseline·migrate 가 각각 별도 연결이고 어떤 잠금에도 덮이지 않는다. `alembic_version` **읽기**는 계획이 금지한 적이 없다(구현자의 자기부과 제약이었다) | Phase 3 착수 전 |
| `CoreModuleBoundaryTest` 우회 (2회차 codex #5) — `compileOnly` + 목록 밖 타입이면 통과한다. `api`·`worker` 가 `infrastructure` 를 `runtimeOnly` 로 유지하는지 **단언하는 코드가 0건**이라, `runtimeOnly` → `implementation` 한 글자 변경에 아무 테스트도 깨지지 않는다 | Phase 3 착수 전 |
| ~~provenance·external이 **같은 변경 가능 소스를 신뢰** (2회차 codex #2 / 교차 X-3)~~ — **판정 완료 (2026-08-12, 리더).** 아래 상세 | ~~Phase 2 착수 전~~ **판정됨** |
| crypto 음성 케이스가 정본 대조에서 빠짐 (H-3 = 교차 X-4) — `VOLATILE_INPUT_FIELDS` 가 도메인 단위라 crypto 의 `input`(`{key, token}`)이 통째로 빠지고 음성 3건의 `expected` 가 동일하다. **`crypto-tampered.token` 을 쓰레기로 바꿔도 게이트가 닫힌다.** 해법은 같은 파일 안 `argon2` 방식(파생 성질을 `expected` 에 담기)에 이미 실증돼 있다 | Phase 4 종료 전 |
| 계약의 요청 길이 제약 5개가 계약 자신의 422 규칙과 충돌 (F3 = 교차 C-5) — 코드에서 이 다섯은 스키마 제약이 아니라 서비스 계층 규칙이라 422 **문자열** `detail` 인데, 계약은 스키마 실패를 422 **배열**로 못박았다. 셋은 코드보다 엄격하기까지 하다(코드는 정규화 **후** 길이를 잰다) | Phase 3 착수 전 |
| 계약 multipart `contentType` 제약이 구현에 없음 (F2 = 교차 C-6) — 성실히 구현하면 `.hwpx` 업로드가 깨지고 **그 구현이 contract test 를 통과한다**(`.hwpx` 는 브라우저가 `application/octet-stream` 을 보내고, `application/hwp+zip` 은 내보내기 mimetype 이다) | Phase 4 착수 전 |
| **G-1** `POST /workspaces`·`PATCH /workspaces/{id}` 캐시 헤더 테스트 공백 — Python 기준선에도 없다(계약상 10곳 중 2곳) | Phase 3 |
| ~~계약 §2.7-3 **규칙 1**을 Phase 3에서 **실측**해 성립 확인~~ — **폐기 (2026-08-12 리더 판정, OQ-1).** 규칙 1(사적 헤더는 `ResponseEntity`로만 싣고 필터 금지)이 전역 부착과 정면 충돌해 내려갔다. 이 항목의 자리는 아래 **H-1**이 대신한다 | ~~Phase 3 종료 전~~ **폐기** |
| ~~**H-1** 전역 헤더 필터가 **컨테이너 레벨 응답까지 닿는가 — 미실측**~~ — **실측 완료 (2026-08-12, kotlin-implementer / Tomcat 11.0.22 · Boot 4.1.0).** 원시 소켓 + `@SpringBootTest(RANDOM_PORT)`로 측정. **필터에 닿음**: 핸들러 없는 404 · 415 · 413 · 프리플라이트 OPTIONS 200 · `sendError`→`/error` 503. **필터에 못 닿음 7종**: 요청 대상 금지 문자 400 · 콜론 없는 헤더 줄 400 · 요청 줄 파손 400 · 헤더 상한 초과 400 · `Host` 없음 400 · 알 수 없는 HTTP 버전 505 · 알 수 없는 메서드 405 — 파싱 단계에서 거절돼 **필터 체인이 시작조차 하지 않는다**(배치로 고칠 수 없다). **해결: 계약을 좁히지 않고 강제 수단을 넓혔다** — Tomcat Engine 밸브가 7종 전부를 덮음을 계측 확인, 음성 대조(밸브 제거 시 malformed 3건만 깨짐)까지 돌렸다. 리더 재심 불필요. **후보 원인 ⓐ `shouldNotFilterErrorDispatch()`는 기각** — 그것과 `DispatcherType.ERROR`를 둘 다 기본값으로 되돌려도 헤더가 남는다(필터가 `chain.doFilter` 앞에서 쓰고 Tomcat이 포워딩에서 버퍼만 비운다). ⚠️ **회귀는 원시 소켓으로 유지** — MockMvc로 옮기면 "측정한 것처럼 보이는 통과"가 나온다. ⚠️ **밸브는 Tomcat 결합** — 컨테이너 교체 시 기동 시점에 깨진다(계약 `x-container-coupling`) | ~~Phase 3 종료 전~~ **완료** |
| **H-4** 오류 본문이 **경로를 가리지 않는가** — H-1 실측의 **범위 밖 부수 발견.** `sendError` → `/error` 응답이 `{"detail":…}`가 아니라 Spring `BasicErrorController` 기본 본문 `{"timestamp","status","error","path"}`를 낸다. 계약의 "오류 본문 최상위 `detail` 하나"는 계획 §2.2 **불변식**인데 이 경로가 사각지대였다. **지금은 운영 코드가 `sendError`를 안 불러 안 드러난다 — Phase 3에서 인증 필터가 401을 `sendError`로 내는 것이 가장 흔한 구현이고 그 순간 깨진다**(401은 React `client.ts`의 세션 만료 분기라 화면 동작까지 바뀐다). 계약에 `x-error-body-universality` 신설, 검증 E-1~E-3 = 테스트 X-C7·X-C8. **구현 수단은 규정하지 않는다** — 계약이 정하는 것은 나간 바이트뿐이다. 남은 미측정: 위 7종의 **본문 모양**(E-4 = X-C9) — 이번 실측은 헤더만 봤다. 만족시킬 수 없으면 계약을 좁히지 말고 리더 재심 | Phase 3 종료 전 |
| **H-2** 전역 필터 도입에 따른 **헤더 중복 부착** — 필터와 컨트롤러 `ResponseEntity`가 같은 헤더를 둘 다 실으면 `Cache-Control: no-store, no-store`가 나가 계약의 `const` 제약에 위반된다. 값만 보는 단언은 통과하므로 **개수까지** 단언해야 한다(`header().stringValues(...)`). 필터는 `add`가 아니라 `set` | Phase 3 |
| **문서 spike 재검증** — Phase 0 spike 는 Kotlin **2.2.0** 조합에서 통과한 것이다. 현재 2.3.21이므로 POI 5.4.1·PDFBox 3.0.5·commons-compress 1.27.1 조합으로 **DOCX 동등성 7항목을 다시 확인**해야 한다. 이번에 확인한 것은 좌표 해석과 컴파일뿐이다 | Phase 4 착수 전 |
| DOCX 내보내기 템플릿 동봉 여부 — POI 산출물에 `styles.xml`/`theme` 가 없어 Heading 1 서식이 소실된다 | Phase 4 착수 전 |
| 내보내기 parity 를 **바이트 해시가 아닌 정규화 텍스트로** — zip 컨테이너 바이트는 Python 과 같아질 수 없다(실측 java 434B vs python 348B). `parity-verifier` 합의 필요 | Phase 4 착수 전 |
| `handleHandlerMethodValidationException` **HTTP 경계 미검증** — `spring-boot-starter-validation` 부재. 입력 상한과 함께 회귀 고정 | Phase 3 |
| parity 하네스 배선 주의 — 디렉터리 비교는 `actual_root / fixture_path.relative_to(fixture_root)` 로 짝짓는다(`compare_parity.py:713-715`). 즉 **actual 파일명이 fixture 파일명과 같아야** 한다 — `masking.json` 이나 `kotlin.json` 이 아니다 | Phase 2 배선 시 |
| `resolveAndLockAll` 태스크 도입 · Gradle 플러그인 클래스패스 locking · Gradle 10 deprecation 경고 — 셋 다 기존 상태이거나 별건이며 구현자가 코드에 넣지 않았다 | 승인 대기 |
| **다음 회차 리뷰 focus 를 계약·보안 불변식·테스트 적정성 축으로** — 이번 codex focus 를 5축(모듈 경계·Flyway·`encryption_scheme`·CI 게이트·parity 우회)으로 좁힌 탓에 그 세 축의 Claude 지적 **17건(C-1~C-9, S-1~S-5, T-1~T-6)이 단일 관점 판정으로 남아 교차 검증을 받지 못했다** | Phase 2 리뷰 |
| CI가 실제 GitHub Actions 에서 도는 것 미검증 — YAML 파싱과 로컬 동등 명령만 검증했다. `::error::`/`::notice::` 렌더링, `setup-gradle@v4`, 러너 Docker 위 Testcontainers 는 첫 push 에서 처음 검증된다 | 첫 push |

### 판정 — codex #2 / 교차 X-3 (2026-08-12, 리더)

교차 종합 §7.1은 이 항목을 **"Phase 2 착수 전 필수"**로 놓았고 §8은 "4건을 닫은 시점에 리뷰 게이트 행을 판정"하라고 권고했다. 실제로 닫힌 것은 3건이고 이 판정만 열려 있었으므로 지금 내린다. (직전 기재의 마감 `Phase 2 종료 전`은 리더가 교차 문서보다 느슨하게 옮겨 적은 것이었다 — 정정한다.)

**판정: 이 항목은 "닫을 수 있는 결함"이 아니라 "관리해야 하는 한계"다. 추가 방어를 넣지 않고 현재 상태로 Phase 2에 착수한다.**

근거:

1. **원리적으로 닫히지 않는다.** 지적의 요지는 "생성기와 검증기를 같은 diff에서 함께 약화시키면 못 잡는다"이다. 그런데 무엇을 정본으로 삼든 그 정본도 저장소 안에 있고 같은 커밋에서 함께 바뀔 수 있다. codex 권고대로 manifest를 `BUILDERS`에서 분리해도 **분리된 manifest가 새 정본이 될 뿐**이며, 이번 세션이 그 연쇄를 이미 네 번 겪었다(proof 파일 → fixture 출처 → 도메인 디렉터리 → 정본 목록). 한 칸 더 옮기는 것은 방어가 아니라 이동이다.

2. **이미 세운 것이 "조용한 축소"를 막는다.** `.github/parity-canonical-floor.txt`의 비대칭 검사가 정본 축소를 차단하고(추가는 자유, 삭제는 exit 1 + 사라진 도메인 이름 출력), 하한 파일 자체를 줄이려면 `.github/` diff가 남는다. `01_parity-canonical-floor.md`가 그 성격을 정확히 적었다 — **"이 검사는 축소를 불가능하게 만들지 않고 조용할 수 없게 만든다."**

3. **최종 방어선은 코드가 아니라 리뷰 게이트다.** `codex-review` 스킬이 `.claude/**` 수정을 게이트 면제에서 **뺀** 것이 이 장치다(확장자가 `.md`여도 "문서 수정"이 아니다). 이번 세션의 지적이 전부 하네스에서 나온 것이 그 판단의 값어치를 보였다. 생성기·검증기·정본을 함께 건드리는 커밋은 정의상 하네스 수정이므로 반드시 리뷰를 지난다.

**채택하지 않은 codex 권고와 이유**

- *정본 manifest를 `BUILDERS`에서 분리* — 위 1의 이유로 기각. 다만 **분리 자체가 무해하므로**, Phase 2에서 도메인이 늘 때 `parity-domains.txt`·`parity-canonical-floor.txt`·`BUILDERS` 셋을 한 커밋에서 고치게 되면 그 커밋은 리뷰에서 특별히 다룬다.
- *알려진 손상 산출물을 먹여 exit 1을 단언하는 adversarial 테스트* — **부분 채택하되 별건으로 돌린다.** 이번 세션의 실증(손으로 쓴 proof → 2, 축소 fixture → 1, `runtime: not-kotlin` → 1, 역방향 패딩 → 1, 정본 0개 → 1, 정본 부분 삭제 → 1)이 전부 **일회성 수동 확인**이라 회귀로 고정돼 있지 않다. 지금은 사람이 다시 뚫어야 알 수 있다. 이를 자동 회귀로 만드는 것은 실질 가치가 있으나 Phase 2 착수를 막을 이유는 없다 → 아래 원장에 별건으로 추가.

**Phase 2 착수를 막지 않는 이유**: Phase 2는 순수 도메인 로직 포팅이고, 이 항목이 실제 피해를 내려면 "누군가 하네스와 정본을 함께 약화시키는 커밋을 만들고 그것이 리뷰를 통과"해야 한다. 그 시나리오의 방어는 리뷰 게이트이지 코드가 아니다.

| 별건으로 추가 | 마감 |
|---|---|
| **게이트 우회 시나리오를 자동 회귀로 고정** — 이번 세션에서 수동 실증한 6종(손으로 쓴 proof / 축소 fixture / `runtime` 위조 / 역방향 패딩 / 정본 0개 / 정본 부분 삭제)이 회귀 테스트로 남아 있지 않다. 게이트가 다시 뚫려도 CI가 모른다 | Phase 3 착수 전 |

---

## Phase 2 — 순수 도메인 로직 포팅

계획 문서 §5 Phase 2. 원문 종료 조건: "**외부 API·DB 없이 실행하는 parity suite가 동일 결과를 냄.**"

**전부 미착수다.** 아래 표는 착수 시점의 판정 기준이며, 근거 없는 `예`는 `아니오`로 취급한다는 규칙이 그대로 적용된다.
`관련 정본 도메인`은 `dump_parity_fixtures.py --list` 의 11개 중 이 Phase 가 덮는 8개를 배정한 것이다
(나머지 `crypto`·`jwt`·`argon2` 는 Phase 3·4).

| 종료 조건 | 충족 | 관련 정본 도메인 | 근거 | 미해결 항목 | blocked-by | 마지막 갱신 주체 |
|---|---|---|---|---|---|---|
| 개인정보 마스킹 포팅 (`app/privacy/masking.py`) | 아니오 | `masking` | - | 패턴 우선순위·자리표시자 번호·구간 겹침이 판정 대상 | - | - |
| 텍스트 정규화·제어문자 제거 포팅 | 아니오 | `text` | - | XML 1.0 비허용 문자만 제거하고 탭·개행·복귀는 유지 | - | - |
| 프롬프트 렌더링과 동적 어려운 말 목록 포팅 | 아니오 | `prompts` | - | 시스템·사용자·보정 프롬프트 **전문**이 대조 대상 | - | - |
| 스타일 규칙 포팅 (`app/easyread/style_rules.py`) | 아니오 | `style` · `style-tables` | - | 규칙 상수 표 전체 덤프까지 일치해야 한다. CLAUDE.md 규약대로 프롬프트 생성과 골든셋 평가가 같은 정의를 써야 한다 | - | - |
| 보정 채택 판정 포팅 | 아니오 | `repair-adoption` | - | 자리표시자 유실·위반 건수 악화 가드 | - | - |
| placeholder 보존 검사 포팅 | 아니오 | `repair-adoption` · `export` | - | **확인 필요**: 변환 결과 전체의 `missing_placeholders` 산출(`app/services/conversion.py:115`)은 어느 정본 도메인에도 배정돼 있지 않다. 도메인을 늘릴지 기존 도메인에 케이스를 넣을지 착수 시 판정 | - | - |
| 내보내기 파일명·`Content-Disposition` 생성 포팅 | 아니오 | `export` | - | RFC 5987 헤더·파일명 정제·자리표시자 복원·TXT 바이트. **바이트 해시로 비교하지 않는다**(미결 원장) | - | - |
| LLM 응답 후처리 포팅 | 아니오 | `postprocess` | - | 코드 펜스·머리말 제거에서 **과잉 제거 금지** | - | - |
| Python/Kotlin 공용 JSON fixture 생성 (`parity/fixtures/`) | 아니오 — **1/11 생성** | 전 도메인 | `parity/fixtures/masking/masking.json` **생성됨** (**22 케이스** — 2026-08-12 유니코드 8건 확장, `dump_parity_fixtures.py --domain masking`). 재현성 실측: 두 번 뽑아 `generated_at`(벽시계) 외 **바이트 동일**. 확장 시 기존 14 케이스의 `id`·`input`·`expected`·순서·`description` **전부 불변** 확인. 정본 대조(`provenance_problems`) 통과 — 새 케이스 기대값 손편집도 exit 1(`정본과 다르다`). 검출 실증 7종 — 정상 산출물 exit 3 / 값 1건 변조 exit 1 / 케이스 1건 누락 exit 1(`미실행`) / 자리표시자 번호 어긋남 exit 1 / `runtime` 비-kotlin exit 1 / **fixture 기대값 손편집 exit 1** / **fixture 케이스 삭제 exit 1**. **유니코드 확장 검출 실증 5종**(JVM 스탠드인, 대조군이 실제 `mask_text`와 22건 전건 일치함을 먼저 확인) — `java-default`(`\d`=`[0-9]`) exit **1**/6건 지목 / `java-ucc`(`UNICODE_CHARACTER_CLASS`만) exit **1**/1건 / `rrn-widened`(`[1-8]` 확대) exit **1**/1건 / `pre-strip-control`(마스킹 전 제어문자 제거) exit **1**/2건 / `faithful` exit **3**. **네 결함 모두 확장 전 14 케이스로는 0건 검출** — 공백이 실재했음의 증거다. CI 셸 실측 4종 — 현재 상태(선언 0개) exit 0(미가동 경고) / 선언 masking + 산출물 없음 exit **1** / 선언 masking + 산출물 정상 exit 0(부분 게이트 notice) / 산출물 틀림 exit 1. 명세는 `02_parity-verifier_masking-spec.md`(§3.3·§6.4·§6.5) | 나머지 10개 도메인 미생성 — **의도적**(도메인은 검증에 들어가는 시점에 뽑는다. 미리 뽑으면 `style_rules.py` 변경 때 아무도 안 보는 도메인이 정본 불일치로 빨개진다). 전체 게이트는 지금 돌리면 exit 1(도메인 누락 10개)이며 이는 사실 그대로다. ~~**fixture 커버리지 공백**~~ → **닫힘(2026-08-12)**. 초판 §6.2의 "유니코드 공백 12종"은 **과소 집계**였다 — 전 코드포인트 재측정 결과 Python `re`의 `\s`는 **29종**이고 Java 기본이 놓치는 것은 6종이 아니라 **23종**, `UNICODE_CHARACTER_CLASS`로도 못 잡는 잔여가 **4종**(U+001C~1F)이다. 남은 한계: 이 게이트도 "그 산출물을 Kotlin이 만들었는가"는 증명하지 못한다(CI 배선이 유일한 방어) | - | parity-verifier (2026-08-12) |
| 도메인마다 `backend-kotlin/parity-domains.txt` 선언 + Kotlin parity 테스트가 `parity/actual/` 산출 | 아니오 | 전 도메인 | - | **구현과 선언이 같은 커밋에 들어가야 한다** — 선언만 하면 `parityManifestCheck` 가 "선언 O/산출 X", 구현만 하면 "선언 X/산출 O" 로 빌드를 깬다 | - | - |
| **종료 조건**: 외부 API·DB 없이 도는 parity suite 가 양쪽에서 같은 결과 | 아니오 | 전 도메인 | - | 8개 도메인 전부 선언되어 값 비교가 실제로 돌아야 한다. 부분 선언은 exit 3(부분 게이트)이며 **전체 통과가 아니다** | - | - |

---

## 아직 돌리지 않은 검증 게이트 (계획 §6)

| 게이트 | 상태 |
|---|---|
| Build (Gradle, TypeScript) | **Gradle 실행됨** (Phase 1) — `./gradlew clean build` BUILD SUCCESSFUL (컴파일 + ktlintCheck + detekt + test). TypeScript 는 기존 `frontend` 잡이 그대로 담당 |
| Unit (core, application, React) | **부분 실행** (Phase 1) — core 19건(모듈 경계 7 · Secret 7 · parity 하네스 5) 통과. **도메인 로직은 아직 없다**(마스킹·스타일 규칙 등은 Phase 2). `application` 은 본 소스가 없어 테스트도 없다. React 는 기존 `frontend` 잡 |
| Contract (14 endpoints) | 미실행 — 계약 파일은 **작성됨**(`contracts/easy-doc-v1.yaml`)이나 contract test 미구현. 실행은 Kotlin API가 생기는 Phase 3부터 (`00_contract-keeper_test-plan.md` §5) |
| DB (Testcontainers) | **실행됨** (Phase 1) — pgvector/pgvector:pg16 컨테이너로 8건. V1↔Alembic 지문 대조, V2 additive, baseline 가드 4갈래, 빈 DB·기존 스냅샷 기동. **repository·트랜잭션·SKIP LOCKED 는 아직 없다**(Phase 3·5) |
| Crypto (Python ↔ Kotlin) | 미실행 — fixture **생성기**가 11개 도메인을 지원할 뿐, **`parity/fixtures/` 산출물은 저장소에 존재하지 않는다**(`parity/` 디렉터리 자체가 없음). Kotlin 측도 부재 |
| Document (docx/pdf/hwpx/txt) | 미실행 |
| Worker (lease/retry/crash) | 미실행 |
| Quality (골든셋) | 미실행 |
| Security (소유권·로그·캐시) | 미실행 |
| E2E (compose + browser) | **compose 부분 실행** (Phase 1) — Kotlin api·worker·migrate 3서비스가 Python 스택과 동시 기동, `/health` 200 확인. **browser·업무 흐름은 미실행**(Phase 6) |
| Ops (cutover/rollback) | 미실행 |

**돌리지 않은 게이트를 통과한 것처럼 보고하지 않는다.**

---

## 착수 전 정리된 선행 작업 (하네스 구축 중 확인·수정)

Phase 0 착수 전에 하네스 구축 과정에서 발견해 처리한 항목이다. 마이그레이션 Phase 자체는 아니지만 기준선에 영향을 주므로 남긴다.

| 항목 | 내용 | 상태 |
|---|---|---|
| 계약 사실 정정 | 계획 §2.2에 없는 **413**(10MB 초과) 실재. 오류 본문 `detail`이 문자열/객체배열 **union**. 401에 `WWW-Authenticate: Bearer`. 엔드포인트는 제품 13 + `/health` = 14 | 반영됨 (`api-contract-freeze`) |
| 502/503 구분 | 큐 **등록 실패** = 502(`QueueUnavailableError`), 큐/설정 **미배선** = 503(`ConfigurationError`, `app/api/deps.py`) | 반영됨 |
| `PUT /conversions/{id}` 캐시 헤더 누락 | GET과 같은 스키마라 `masked_items[].original`에 개인정보가 실리는데 `no-store`/`nosniff` 부재 | **완료** — 커밋 `0fafac7`. 회귀 테스트 `tests/api/test_documents.py::test_검수_저장_응답은_캐시하지_않는다` |
| `/auth` 3종 캐시 헤더 누락 | `POST /auth/signup`·`POST /auth/login`·`GET /auth/me`가 `PRIVATE_RESPONSE_HEADERS`를 import조차 하지 않았음. 로그인 응답 본문은 Bearer 토큰 자체, 나머지 둘은 이메일 | **완료** — 커밋 `0fafac7`(같은 커밋). 회귀 테스트 3건 `tests/api/test_auth.py::test_{가입,로그인,내_정보}_응답은_캐시하지_않는다` |
| 캐시 금지 헤더 대상 범위 | 위 두 건으로 대상이 **6개 → 10개**로 늘었다 (documents 4 · workspaces 3 · auth 3). 계약 스킬 §2.5가 정본이고 §1 표는 포인터 표시만 둔다 | **완료** — 코드 확인: `grep -rn "headers.update(PRIVATE_RESPONSE_HEADERS)\|\*\*PRIVATE_RESPONSE_HEADERS" app/api/ \| wc -l` = 10 |
| parity 게이트 공백 | crypto 도메인이 Fernet만 검증. JWT·Argon2 fixture 부재 상태로 Crypto 게이트가 닫혔음 | 수정됨 — 커밋 `e88db3e`. 생성기에 jwt 18건·argon2 14건 추가 (11 도메인). **fixture 산출물은 아직 없음** |
| parity 게이트 우회 | 도메인 디렉터리를 통째로 빼면 "전건 일치"로 통과 | **완료** — 커밋 `e88db3e`. `compare_parity.py`의 `EXPECTED_DOMAINS` 검사 + 도메인 누락 시 exit 1 |
| 리뷰 게이트 1회차 | Phase 0 착수 **전** 점검으로 codex·Claude 독립 리뷰 + 교차 종합을 실행 | **완료** — `reviews/00_pre-phase0_{codex-reviewer,migration-reviewer,cross}.md` 3건. 교차 결과 **합의 9건 · codex 단독 4건 · Claude 단독 23건 · 상충 2건**. 정본은 `_cross.md` |
| 계약 스킬 §1↔§2.5 불일치 | §2.5는 헤더 대상 10개인데 §1 표에는 8개만 표기(`GET /conversions/{id}/export`·`PATCH /workspaces/{id}` 누락). §1만 보고 계약을 쓰면 두 곳이 헤더 요구 없이 동결됨 (X-14) | **완료** — 코드 기준으로 §1을 §2.5에 맞추고, §2.5를 정본으로 선언 |
| 오류 응답 캐시 헤더 | 오류 경로(`app/api/errors.py`)에 헤더를 붙일지 미결 (X-15) | **완료 — 현행 유지 판정**(붙이지 않음). 근거: 오류 본문에 개인정보 없음을 실측 확인. `api-contract-freeze` §2.7 해결 3에 전제 파기 조건과 함께 기록 |
| `status` 필드 넓이 | 백엔드 Pydantic `str` vs React 4값 리터럴 union. OpenAPI 생성 타입으로 그냥 교체하면 타입 안전성 퇴보 | **완료** — `contracts/easy-doc-v1.yaml`의 `components/schemas/ConversionStatus`에 enum 4값으로 고정. **Phase 6 타입 생성은 FastAPI 산출물이 아니라 이 계약 파일에서 한다**(3자 대조 D-1) |
