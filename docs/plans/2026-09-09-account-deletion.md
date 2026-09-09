# 회원 탈퇴와 개인정보 파기 경로

- 작성 2026-09-09. 사용자 지시: 「회원 탈퇴와 개인정보 파기 경로를 신설해.」
- 근거: `docs/plans/2026-09-09-legal-footer-and-support.md` §4.1이 **법 이행 구멍**으로 지목한 항목이다. 개인정보 보호법은 목적이 끝난 개인정보를 지체 없이(정당한 사유가 없으면 5일 이내) 파기하도록 하는데, 지금은 **계정을 지울 경로가 아예 없다.** 같은 문서 §8.1이 이것을 1차 배포의 조건으로 세웠다.
- 문서 본문 30일 파기(`RetentionPurgeScheduler`)는 이미 있다. 없는 것은 **계정 단위 파기**다.

## 1. 지금 참인 것

1. `users` 행을 지우면 CASCADE로 함께 사라지는 것: `workspaces`, `documents`(→`conversions`→`segment_map`·`document_originals`), `user_identities`, 이메일 인증·비밀번호 재설정 코드, `oauth_states`, `credit_transactions`(owner), `invoice_requests`(owner), `llm_calls`, `announcements`(created_by).
2. **`documents.workspace_id`는 `ON DELETE` 절이 없다(NO ACTION).** 문서가 든 작업 공간을 DB가 지우지 못하게 일부러 막아 둔 것이다(V1 주석). 사용자 삭제 한 문장에서는 문서·작업 공간이 함께 지워지므로 참조가 남지 않아 통과하지만, **실 DB 테스트로 확인해야 한다.**
3. **`conversion_feedback`은 FK가 없다**(V2). 문서 30일 파기 뒤에도 게이트 ① 판정 근거가 남도록 일부러 끊어 둔 것이다. 따라서 **사용자를 지워도 이 표는 남는다** — 자유 의견이 AEAD로 봉인돼 있으므로 탈퇴 시 명시적으로 지워야 한다.
4. `llm_calls`는 본문·개인정보를 담지 않는다(문서 id·글자 수·토큰·비용). `workspace_id`는 이미 `SET NULL`이지만 `user_id`가 `CASCADE`다.
5. 관리자 표시는 `users.is_admin`이고, `announcements.created_by`는 `NOT NULL` + `CASCADE`, `credit_transactions.actor_user_id`·`invoice_requests.handled_by`는 `SET NULL`이다.
6. 계약 2.26.0, 마이그레이션 V18. 이 묶음은 **2.27.0 · V19**.

## 2. 결정

1. **경로는 `POST /auth/me/deletion` → 204.** `DELETE`에 본문을 싣지 않는다(중간 프록시가 떨구는 경우가 있다). 요청 본문 `{ password?, confirmation }`.
2. **재확인 두 겹.** ⑴ 비밀번호가 있는 계정(`has_password`)은 **비밀번호**를 받는다. 소셜 전용 계정은 받지 않는다. ⑵ 두 경우 모두 **확인 문구**를 그대로 입력받는다(`confirmation` == `"탈퇴합니다"`). 틀리면 422. 비밀번호가 틀리면 401이 아니라 **422**다 — 이미 인증된 세션이고, 여기서 401을 내면 화면이 로그아웃 처리로 오해한다.
3. **즉시 파기다. 유예 기간·복구 기간을 두지 않는다.** 「30일 뒤 삭제」는 파기 의무를 미루는 것이고, 복구를 위해 개인정보를 남기려면 그 보관에 별도 근거가 필요하다. 되돌릴 수 없음을 확인 화면에 명시한다.
4. **파기 범위.**
   - **삭제**: `users` 행과 위 §1-1의 CASCADE 전부.
   - **명시 삭제**: `conversion_feedback` — 그 사용자의 변환 id 목록으로 먼저 지운다(FK가 없어 CASCADE가 닿지 않는다). **사용자 삭제보다 먼저** 실행해야 변환 id를 찾을 수 있다.
   - **비식별 보존**: `llm_calls.user_id`를 `CASCADE` → **`SET NULL`**(V19). 개인정보가 없고 원가·사용량 집계의 근거이며, `workspace_id`가 이미 같은 규칙이다. 탈퇴 뒤 이 행은 주인 없는 사용량 통계로 남는다.
   - 나머지 청구 기록(`credit_transactions`·`invoice_requests`)은 **CASCADE 그대로 지운다.** 아직 실제 결제가 없어 법정 보존 대상이 성립하지 않는다. **결제 도입 시 분리 보존 설계가 필요하다** — 그때 보존기간표(법률 자문 §7-2)와 함께 다시 연다.
5. **관리자 계정은 탈퇴할 수 없다(409).** `announcements.created_by`가 `NOT NULL CASCADE`라 관리자를 지우면 공지가 함께 사라지고, 감사 흔적(`actor_user_id`)도 끊긴다. `admin-grant --revoke`로 권한을 회수한 뒤 탈퇴한다. 안내 문구에 그 절차를 적는다.
6. **처리 대기 중인 세금계산서 요청이 있어도 막지 않는다.** 삭제 요구는 정보주체의 권리이므로 업무 절차로 가둘 수 없다. 대신 ⑴ 확인 화면에 「처리 중인 세금계산서 요청 N건이 함께 취소된다」를 명시하고 ⑵ 운영자에게 알림 메일을 보낸다(`easydoc.billing.operator-email`, 요청 id만 — 이름·이메일을 싣지 않는다). 발송 실패는 탈퇴를 막지 않는다.
7. **남은 크레딧은 소멸하고 환불하지 않는다.** 확인 화면에 남은 이용량을 숫자로 보여 주고 그 사실을 명시한다. 환불이 필요하면 탈퇴 전에 고객지원으로 요청하도록 안내한다.
8. **재가입은 막지 않는다.** 탈퇴한 이메일을 다시 쓸 수 있다. **알려진 구멍**: 가입 부여(`easydoc.credits.signup-grant`)를 반복 수령할 수 있다. 이를 막으려면 탈퇴 이메일의 단방향 해시를 보관해야 하는데 그 보관 자체에 근거가 필요하다 — **법률 자문 항목으로 올리고 이번 범위에서는 열어 둔다.** 파일럿에서는 부여값이 작고 운영자가 어드민에서 관측할 수 있다.
9. **세션 무효화.** 탈퇴와 같은 트랜잭션에서 그 사용자의 인증 수단이 사라지므로 이후 요청은 자연히 401이다. 프런트는 응답 204를 받으면 세션 저장소를 비우고 로그인 화면으로 보낸다(기존 로그아웃 경로 재사용).
10. **감사 로그.** `admin_action`이 아니라 애플리케이션 로그에 `user_id`만 한 줄 남긴다(이메일·이름을 남기지 않는다). 삭제한 행 수는 남기지 않는다 — 재현에 쓰이지 않고 그 자체가 사용 이력이다.

## 3. 슬라이스

**한 조각(M)으로 닫는다.** 계층이 여럿이지만 되돌릴 수 있는 한 단위이고, 나누면 「지울 수는 있는데 화면이 없는」 중간 상태가 생긴다.

- V19: `llm_calls.user_id` FK를 `SET NULL`로 교체(열 nullable 화 포함).
- core/application: `DeleteAccountService`(재확인 검증 → 피드백 삭제 → 사용자 삭제, 한 트랜잭션), 포트 `AccountDeletionRepository`.
- infrastructure: Jdbc 어댑터. 변환 id 조회 후 `conversion_feedback` 삭제, `users` 삭제.
- api: `POST /auth/me/deletion`, 422·409 오류, `AuthenticatedEndpoints` 등재, 운영자 알림 메일.
- 계약 2.27.0: 작업 추가, 요청 스키마(`x-request-field-constraints`), 204·401·409·422, 변경 기록, 엔드포인트 수 갱신.
- 프런트: 계정 설정에 「회원 탈퇴」, 확인 화면(남은 이용량·처리 중 요청 수·되돌릴 수 없음·확인 문구·비밀번호), 성공 시 세션 정리.
- 인구조사·가드 테스트 갱신(`AuthenticationCoverageContractTest`, `SensitiveToStringReachTest`, `OwnershipPredicateGuardTest`, `ContractHeaderDeclarationTest` 등 해당하는 것).

## 4. 수용 기준

1. 비밀번호 계정이 올바른 비밀번호와 확인 문구로 탈퇴하면 204이고, 같은 토큰의 다음 요청은 401이다.
2. 소셜 전용 계정은 확인 문구만으로 탈퇴된다.
3. 비밀번호가 틀리거나 확인 문구가 다르면 422이고 **아무것도 지워지지 않는다**.
4. 관리자 계정은 409이고 아무것도 지워지지 않는다.
5. 탈퇴 뒤 그 사용자의 `documents`·`conversions`·`workspaces`·`credit_transactions`·`invoice_requests`·`user_identities`·`conversion_feedback` 행이 **0건**이다(실 DB).
6. 탈퇴 뒤 그 사용자의 `llm_calls` 행은 **남아 있고 `user_id`가 null**이다(실 DB).
7. 문서가 있는 계정도 탈퇴된다 — `documents.workspace_id`의 NO ACTION이 막지 않는다(실 DB로 확인).
8. 처리 대기 세금계산서 요청이 있으면 운영자 메일이 1통 나가고, 메일 발송이 실패해도 탈퇴는 성공한다.
9. 탈퇴한 이메일로 다시 가입할 수 있다.
10. e2e: 가입 → 문서 1건 변환 → 탈퇴 → 로그인 화면 도착 → 같은 이메일 재가입 성공.

## 5. 범위 밖

유예 기간·계정 복구, 재가입 제한(§2-8), 결제 기록 분리 보존(§2-4 — 결제 도입 시), 어드민의 강제 탈퇴, 개인정보 다운로드(이동권), 개인정보처리방침 문안(법률 자문 뒤).
