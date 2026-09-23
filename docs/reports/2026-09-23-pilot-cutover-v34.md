# 운영 파일럿 배포 전환 V27→V34 기록

실행일: 2026-09-23 · 트랙: [사람 검증 착수 계획 V1](../plans/2026-09-23-human-verification-kickoff.md) · 승인: 2026-09-23 사용자 결정(K1, `docker_startup.sh restart` 지시 후 재빌드·마이그레이션 포함 전환임을 확인받음). 이 문서는 실행 기록이며 기능 ON·파일럿 완료·출시 판정이 아니다.

## 실행 환경

| 항목 | 값 |
|---|---|
| 환경 | easydoc.kr 운영 파일럿 스택(이 호스트, `compose.yml` + `compose.pilot.yml`, `EASYDOC_DEPLOYMENT_MODE=pilot`) |
| 실행 커밋 | `afdd04e3`(main `e9fad55f` + PR #144 문서 변경만. 제품 코드는 main과 동일) |
| 이전 이미지 | `easy-doc-backend:local` 2026-09-18 20:23 KST 빌드, Flyway V27 |
| 새 이미지 | `easy-doc-backend:local` 2026-09-23 15:22 KST 빌드 |
| 실행 명령·시각 | `./docker_startup.sh restart`(= `up -d --build --wait`) 15:19:xx~15:23:13 KST |
| 담당 | 사용자(승인·결정) / Claude 세션(실행·확인) |

## 전환 전 확인 (15:18~15:20 KST, 읽기 전용)

- 진행 중 변환 작업 0건(`conversion_jobs` 11건 모두 `done`), 크레딧 예약 합계 0.
- 잔액 스냅샷: 워크스페이스 6개, 잔액 합계 31, 예약 0, 허용량 20, 거래 29건(balance_delta 합 31).
- 기존 자동 백업 `easydoc-20260923T045336Z.dump` SHA-256 검증 OK.
- 전환 직전 수동 백업 `backups/postgres/easydoc-20260923T062006Z-precutover-v27.dump`(175,783바이트, 600 권한) + `.sha256` 생성·검증 OK, `pg_restore --list`로 25개 TABLE DATA 확인. `latest.dump` 심볼릭 링크는 백업 서비스가 관리하므로 건드리지 않았다.
- 9월 21일 보고서가 우려한 운영 폴더 미커밋 변경(4단어 제한)은 `055196d8`로 main에 포함돼 있어 사라질 운영 전용 변경이 없었다.

## 전환 결과 (15:23 KST)

| 확인 | 결과 |
|---|---|
| Flyway | `Successfully validated 34 migrations` → V28~V34 7건 적용, `now at version v34`(0.394초). 실패 0 |
| 컨테이너 | api·worker·frontend·postgres·postgres-backup 5개 `--wait` 통과, api/postgres/backup healthy |
| health | `https://easydoc.kr/api/health` 200, `Origin: https://easydoc.kr` 포함 200, 인증 없는 `/api/auth/me` 401 |
| 토글 | 새 컨테이너 env에 R1~R7 토글(`EASYDOC_REVIEW_SUPPORT/ACTION_GUIDE/TABLE_RELATIONS/REVIEW_HISTORY/EXPLANATIONS/ILLUSTRATIONS_ENABLED`, `EASYDOC_PROMPT_CONTEXT_EXPLANATION_VERSION`) 미설정 → 소스 기본값 OFF/BASELINE |
| 잔액 보존 | 워크스페이스별 잔액·예약·허용량, 합계(31/0/20), 거래 29건·합 31이 전환 전과 **동일**. 컬럼 타입은 integer → numeric(V31) |
| 데이터 | 사용자 6, 변환 11, 작업 11(`done`) 변동 없음 |
| 로그 | api·worker 기동 로그에 ERROR/Exception 없음 |

## 100자 예약·소비 확인 (16:05~16:09 KST, 사용자 승인 유료 호출 1건)

절차: 테스트 계정(`pilot-cutover-check-20260923b@example.test`, 개인정보 없음) 가입 201 → 이메일 인증을 DB에서 해당 행만 완료 처리(운영 SMTP가 `example.test`로는 보내지 못함) → 로그인 → 실행서 「크레딧 수동 조정」의 `credit-grant` CLI로 1크레딧 부여(`reason=manual`, `--actor-email`로 지정한 사용자 계정을 접속기록 actor로 사용) → 공백 포함 정확히 100자·18어절 본문을 붙여넣기 모드로 등록.

| 단계 | 관측 |
|---|---|
| `POST /documents` | 202, `X-Credit-Balance: 0.9`, `Location: /conversions/{id}`, `char_count: 100` |
| 예약 직후 `GET /workspaces/{id}/credits` | balance 1.0 · reserved 0.1 |
| 변환 | 11초 만에 `done`, `openai / gpt-6-astra`, 입력 1,555·출력 65토큰(예상 US$0.0188), 쉬운 글 106자 |
| 소비 후 | balance 0.9 · reserved 0.0. 원장 `grant manual +1.0` → `reserve conversion reserved +0.1` → `consume conversion −0.1/−0.1` (numeric) |
| 정리 | `DELETE /documents/{id}` 204(문서·작업 cascade 삭제 — 아래 24시간 관측의 작업·문서 수가 늘지 않은 이유) |

`POST /documents` 응답의 `X-Credit-Balance: 0.9`와 그 직후 원장 조회의 `balance 1.0 · reserved 0.1`은 다른 값이 아니다. 헤더는 **사용 가능 잔액**(= 원장 `balance` − `reserved` = 1.0 − 0.1)을 보여주고, 원장은 아직 차감되지 않은 총 잔액(`balance`)과 보류액(`reserved`)을 나눠 보여준다.

첫 시도가 402(`X-Credits-Required: 0.1`, 잔액 0)로 거절된 것은 집행 스위치가 켜진 상태에서 가입 크레딧이 부여되지 않은 새 계정의 정상 동작이다. **실패 반환 경로는 운영에서 유도하지 않았다** — fake provider의 동일 확인은 [소수 크레딧 로컬 완료 기록](../plans/2026-09-21-fractional-credits.md)과 E2E E21에 있다.

테스트 계정 2개(`…20260923@example.test`는 첫 시도에서 가입 크레딧 0으로 402를 받아 미사용, `…20260923b@…`는 잔액 0.9)는 남아 있다. **운영자가 관리 화면에서 정리할 수 있는 경로는 없다** — `AdminWorkspaceController`(`/admin/workspaces`)는 조회(`GET`)와 크레딧 조정(`POST .../credits`)만 제공하며 계정 삭제 엔드포인트가 없다. 제거할 수 있는 유일한 경로는 자기 계정 탈퇴 `POST /auth/me/deletion`(비밀번호·확인 재확인 필요)이며, 두 테스트 계정 각각의 자격 증명으로만 본인이 호출할 수 있다. 자격 증명은 저장소가 아니라 운영자 스크래치 파일에 있다. 담당·기한은 아래 「하지 않은 것 · 남은 것」에 남긴다.

이메일 인증을 DB에서 직접 완료 처리한 UPDATE는 애플리케이션의 접근 로그 경로를 거치지 않아 `personal_data_access_logs`에 흔적을 남기지 않는다.

## 24시간 관측 (K1: 담당 사용자, 2026-09-23 15:23 ~ 2026-09-24 15:23 KST)

첫 판독 16:10 KST: api·worker 로그 ERROR 0건, WARN 4건은 모두 위 테스트 계정 주소로의 SMTP 발송 실패(인증 메일 2, 변환 완료 메일 1과 그 거절 로그)라 예상된 것이다. 컨테이너 5개 정상, health 200, 작업 11건 전부 `done`, 계정 8개 잔액 합계 31.9·예약 0, 문서 11건. 다음 판독은 관측 종료 시점에 같은 항목으로 남긴다.

## 기능 토글 전부 On (19:28 KST, 사용자 결정)

2026-09-23 사용자 지시 「전부 테스트 가능하도록 기능 On」과 선택 「운영 파일럿에서 전부 On」·「프롬프트 BASELINE 유지」에 따라 R1~R7 토글을 켰다. 검증·출시 계획 §7의 순서(R2 품질 기준 통과 뒤 제한 파일럿)를 앞당긴 사용자 결정이며, 출시 판정이 아니다.

| 항목 | 값 |
|---|---|
| 실행 커밋 | main `40e56837`(PR #145~#148 머지). 전환 커밋 대비 `src/main`·마이그레이션 변경 없음 → 스키마 V34 그대로 |
| 사전 확인 | 변환 작업 11건 모두 `done` |
| 변경 | `.env`에 `EASYDOC_REVIEW_SUPPORT/TABLE_RELATIONS/REVIEW_HISTORY/EXPLANATIONS/ILLUSTRATIONS/ACTION_GUIDE_ENABLED=true`, `EASYDOC_ACTION_GUIDE_WORKER_ENABLED=true`, `EASYDOC_PROMPT_CONTEXT_EXPLANATION_VERSION=BASELINE`. 이전 `.env`는 호스트에 `.env.bak-20260923-pre-toggles`(600, git 무시)로 보관 |
| 실행 | `./docker_startup.sh restart` → 컨테이너 5개 `--wait` 통과, api·worker 컨테이너 env에 위 8개 값 확인 |
| 확인 | `https://easydoc.kr/api/health` 200, 기동 후 3분 api·worker 로그 ERROR/Exception 0건 |
| 되돌리기 | `.env`에서 위 8줄을 지우거나 `false`로 바꾸고 `./docker_startup.sh restart`. 스키마 변경이 없어 데이터 복원은 필요 없다 |

영향: 실제 OpenAI를 호출하므로 행동 안내 등 새 기능을 쓰면 사용자 크레딧과 API 비용이 든다. 기존 파일럿 사용자에게도 기능이 보인다. 재시작으로 24시간 관측 창은 이 시각부터 다시 센다(21:47 재배포로 다시 갱신 — 아래 절). 기능별 실제 경로 확인(행동 안내 생성·실패 반환 포함)은 사용자 테스트로 남는다.

## 결함 수정 재배포 (21:47 KST, 사용자 승인)

토글 On 뒤 Sonnet 후속 점검에서 나온 결함 수정을 반영했다. 사용자 선택 「머지 후 파일럿 재배포」.

| 항목 | 값 |
|---|---|
| 실행 커밋 | main `9ee62f17`(PR #150 행동 안내 미시작 실패 차감·준비 실패 무한 재획득 수정, PR #152 활성 행동 안내 작업이 있는 계정의 탈퇴 FK 실패 수정). 마이그레이션 변경 없음 → V34 그대로 |
| 사전 확인 | 변환 작업 11건 모두 `done`, 행동 안내 작업 0건 |
| 실행 | `./docker_startup.sh restart`, 백엔드 이미지 21:47 KST 재빌드, 컨테이너 5개 `--wait` 통과 |
| 확인 | health 200, worker env 토글 유지, 기동 후 3분 ERROR/Exception 0건 |

19:28~21:47 KST 사이에는 행동 안내 작업이 대기·실행 중인 순간의 탈퇴가 FK 오류로 실패할 수 있었다. 해당 구간 행동 안내 작업은 0건이었다. 24시간 관측 창은 21:47 KST부터 다시 센다(종료 판독 2026-09-24 21:47 KST 이후). 테스트만 바꾼 PR #151은 이 뒤에 머지했다(`ef5fb70b`, 운영 동작 무관).

## 하지 않은 것 · 남은 것

- 실패 반환(provider 오류 시 예약 반환) 경로는 운영에서 확인하지 않았다(위 절).
- 24시간 관측의 종료 판독(2026-09-24 15:23 KST 이후)이 남아 있다. 관측 항목은 검증·출시 계획 §8(오류율·충돌률·예약 잔액·작업 상태·추가 비용·파기 건수)이다.
- 테스트 계정 2개(`pilot-cutover-check-20260923@example.test`, `…20260923b@…`) 삭제가 남아 있다. 관리자 삭제 경로가 없어 각 계정 소유자(=이번 검증을 수행한 운영자 본인)가 자신의 자격 증명으로 `POST /auth/me/deletion`을 호출해야 한다. 담당: 운영자(사용자). 기한: 24시간 관측 종료 판독(2026-09-24 15:23 KST) 때.
- 복원 리허설은 수행하지 않았다. 전환 전 백업은 V27 스키마이므로, 소수 거래가 생긴 뒤에는 이 백업으로 단순 복원하지 않는다(소수 크레딧 전환 §배포 절차).
- 신규 기능은 전부 OFF다. 제한 파일럿에서 어느 토글을 켤지는 착수 계획 V2·V5와 K3·K5의 결정을 따른다.
- 이미지에 소스 커밋 라벨이 없다. 실행 커밋은 작업 폴더 HEAD와 빌드 시각으로만 대응시켰다.
