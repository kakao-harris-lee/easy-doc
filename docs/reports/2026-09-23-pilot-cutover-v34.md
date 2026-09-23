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

절차: 테스트 계정(`pilot-cutover-check-20260923b@example.test`, 개인정보 없음) 가입 201 → 이메일 인증을 DB에서 해당 행만 완료 처리(운영 SMTP가 `example.test`로는 보내지 못함) → 로그인 → 실행서 「크레딧 수동 조정」의 `credit-grant` CLI로 1크레딧 부여(`reason=manual`, actor 사용자) → 공백 포함 정확히 100자·18어절 본문을 붙여넣기 모드로 등록.

| 단계 | 관측 |
|---|---|
| `POST /documents` | 202, `X-Credit-Balance: 0.9`, `Location: /conversions/{id}`, `char_count: 100` |
| 예약 직후 `GET /workspaces/{id}/credits` | balance 1.0 · reserved 0.1 |
| 변환 | 11초 만에 `done`, `openai / gpt-6-astra`, 입력 1,555·출력 65토큰(예상 US$0.0188), 쉬운 글 106자 |
| 소비 후 | balance 0.9 · reserved 0.0. 원장 `grant manual +1.0` → `reserve conversion reserved +0.1` → `consume conversion −0.1/−0.1` (numeric) |
| 정리 | `DELETE /documents/{id}` 204. 테스트 계정 2개(`…20260923@example.test`는 첫 시도에서 가입 크레딧 0으로 402를 받아 미사용, `…20260923b@…`는 잔액 0.9)는 남아 있으며 운영자가 관리 화면에서 정리한다 |

첫 시도가 402(`X-Credits-Required: 0.1`, 잔액 0)로 거절된 것은 집행 스위치가 켜진 상태에서 가입 크레딧이 부여되지 않은 새 계정의 정상 동작이다. **실패 반환 경로는 운영에서 유도하지 않았다** — fake provider의 동일 확인은 [소수 크레딧 로컬 완료 기록](../plans/2026-09-21-fractional-credits.md)과 E2E E21에 있다.

## 24시간 관측 (K1: 담당 사용자, 2026-09-23 15:23 ~ 2026-09-24 15:23 KST)

첫 판독 16:10 KST: api·worker 로그 ERROR 0건, WARN 4건은 모두 위 테스트 계정 주소로의 SMTP 발송 실패(인증 메일 2, 변환 완료 메일 1과 그 거절 로그)라 예상된 것이다. 컨테이너 5개 정상, health 200, 작업 11건 전부 `done`, 계정 8개 잔액 합계 31.9·예약 0, 문서 11건. 다음 판독은 관측 종료 시점에 같은 항목으로 남긴다.

## 하지 않은 것 · 남은 것

- 실패 반환(provider 오류 시 예약 반환) 경로는 운영에서 확인하지 않았다(위 절).
- 24시간 관측의 종료 판독(2026-09-24 15:23 KST 이후)이 남아 있다. 관측 항목은 검증·출시 계획 §8(오류율·충돌률·예약 잔액·작업 상태·추가 비용·파기 건수)이다.
- 복원 리허설은 수행하지 않았다. 전환 전 백업은 V27 스키마이므로, 소수 거래가 생긴 뒤에는 이 백업으로 단순 복원하지 않는다(소수 크레딧 전환 §배포 절차).
- 신규 기능은 전부 OFF다. 제한 파일럿에서 어느 토글을 켤지는 착수 계획 V2·V5와 K3·K5의 결정을 따른다.
- 이미지에 소스 커밋 라벨이 없다. 실행 커밋은 작업 폴더 HEAD와 빌드 시각으로만 대응시켰다.
