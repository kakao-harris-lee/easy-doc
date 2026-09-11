-- 개인정보처리시스템 접속기록 — 개인정보의 안전성 확보조치 기준(접속기록 1년 이상 보관,
-- 월 1회 이상 점검). 계획 docs/plans/2026-09-11-access-log-retention.md.
--
-- **`docs/plans/2026-09-10-signup-consent.md`가 예약한 V22를 이 조각이 쓴다** — 가입
-- 동의는 약관·방침 확정을 기다리는 중이고 이 조각이 먼저 나간다. 그 계획의 마이그레이션
-- 번호는 V23으로 옮긴다(위 계획 §4).
--
-- 잡는 자리는 관리자 API 경계 하나(`AdminAccessInterceptor`, 계약의 `x-admin-only`
-- 10개 오퍼레이션)와 CLI 실행 프로필 셋(usage-report·credit-grant·admin-grant)뿐이다
-- (계획 §1·§3.2). 이용자가 사이트를 둘러본 기록이 아니다 — nginx 접근 기록은 이 표의
-- 대상이 아니다(계획 §1).
--
-- **애플리케이션에 갱신·삭제 경로를 두지 않는다** — 삽입 포트
-- (`PersonalDataAccessLogWriter`)에 그 메서드가 없다. 위·변조 방지에 대한 최소 대응이다
-- (계획 §3.1).
--
-- **`actor_user_id`에 FK를 걸지 않는다.** 처음에는 `ON DELETE`가 없는(즉 삭제를 막는)
-- FK를 걸었는데, 그것이 결함이었다 — 정정한다. 「관리자는 탈퇴가 409로 거절되므로 FK가
-- 실제로 막지 않는다」는 관리자 계정에만 맞는 말이고, 이 표는 관리자 계정만 쓰지 않는다.
-- 수용 기준 2가 요구하는 「거절된 접속(403)도 기록한다」때문에 **일반 이용자**가
-- `/admin/...`에 한 번만 접근을 시도해도 그 사람의 id가 `actor_user_id`에 남는다 —
-- 그런데 일반 이용자의 탈퇴(`DeleteAccountService`)는 `is_admin`만 보고 거절 여부를
-- 정할 뿐 이 표는 보지 않으므로, FK가 있으면 그 탈퇴가 FK 위반으로 500이 되어 **법정
-- 삭제권을 깨는 회귀**가 된다. 심지어 이 표가 잡으려는 바로 그 행위(권한 없는 접근
-- 시도)가 그 행위자의 탈퇴를 영구히 막는 자기모순이다.
--
-- **`signup_grant_records`(V20)가 이미 같은 선례를 남겼다** — 「이 행은 users·workspaces
-- 어디에도 FK를 걸지 않는다 — 계정이 탈퇴로 사라져도 이 표는 일부러 남아야 목적을
-- 이룬다」. 감사 기록은 그 대상보다 오래 살아야 하는 것이 본질이다 — `llm_calls.user_id`
-- 의 `SET NULL`(청구 집계는 살리고 사람 연결만 끊는다, 목적이 다르다)도 여기서는 쓰지
-- 않는다. 행위자를 지우면 감사 기록 자체가 무의미해진다.
--
-- **계정 삭제는 이 표의 행을 그대로 남긴다.** 다음에 이 열을 보는 사람이 "FK가 빠졌네"
-- 하고 다시 걸면 같은 결함이 돌아온다 — 걸지 않는다.
--
-- **`client_ip`는 우리가 원칙적으로 저장하지 않는 IP를 담는 유일한 예외 표다**(가입
-- 동의 계획 §2 결정 4 「수집 최소화가 분쟁 대비 가치보다 앞선다」의 예외, 접속기록 계획
-- §3.4). 대상은 취급자(운영자)의 접속지뿐이고, 일반 이용자의 IP는 이 표에도 다른
-- 어디에도 담기지 않는다. CLI 실행은 HTTP 요청이 아니라 접속지가 없으므로 실행 주체를
-- 대신 담는다(`CliActor`, `api` 모듈).
--
-- **일일 파기 배치(`RetentionPurgeScheduler`)가 이 표를 건드리지 않는다** — 보관은
-- 1년 이상이고, 보관기간이 지난 뒤의 파기는 이 조각의 범위 밖이다(계획 §3.3). 나중에
-- 파기를 붙일 자리는 `AccessLogProperties.retention`(구성값)으로 남겨 둔다.
CREATE TABLE personal_data_access_logs (
    id uuid NOT NULL,
    actor_user_id uuid NOT NULL,
    accessed_at timestamp with time zone DEFAULT now() NOT NULL,
    client_ip text NOT NULL,
    -- 계약의 `operationId`를 그대로 쓴다(예: `listAdminInvoiceRequests`) — 계획 §3.1.
    operation character varying(100) NOT NULL,
    -- 단건 조회·변경이면 그 식별자, 목록 조회면 조회 조건(쿼리 문자열) — 계획 §3.1.
    subject_scope text NULL,
    outcome character varying(20) NOT NULL,
    CONSTRAINT pk_personal_data_access_logs PRIMARY KEY (id),
    CONSTRAINT ck_personal_data_access_logs_outcome_valid CHECK (outcome IN ('success', 'rejected'))
);

-- 월 1회 점검 보고서(§3.5)가 기간으로 좁힌다.
CREATE INDEX ix_personal_data_access_logs_accessed_at
    ON personal_data_access_logs USING btree (accessed_at);
