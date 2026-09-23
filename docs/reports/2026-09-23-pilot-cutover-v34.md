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

## 하지 않은 것 · 남은 것

- **100자 문서의 예약 0.1·소비·실패 반환 3경로 확인은 실행하지 않았다.** 운영 스택은 실제 OpenAI provider라 변환 1건이 유료 호출이며, 착수 계획 V1 완료 기준 중 이 항목은 사용자가 비용을 승인한 뒤 별도로 수행한다. 테스트 PostgreSQL·fake provider 경로의 동일 확인은 [소수 크레딧 로컬 완료 기록](../plans/2026-09-21-fractional-credits.md)에 있다.
- 첫 24시간 관측 담당·시각은 미지정이다(착수 계획 K1). 관측 항목은 검증·출시 계획 §8(오류율·충돌률·예약 잔액·작업 상태·추가 비용·파기 건수)이다.
- 복원 리허설은 수행하지 않았다. 전환 전 백업은 V27 스키마이므로, 소수 거래가 생긴 뒤에는 이 백업으로 단순 복원하지 않는다(소수 크레딧 전환 §배포 절차).
- 신규 기능은 전부 OFF다. 제한 파일럿에서 어느 토글을 켤지는 착수 계획 V2·V5와 K3·K5의 결정을 따른다.
- 이미지에 소스 커밋 라벨이 없다. 실행 커밋은 작업 폴더 HEAD와 빌드 시각으로만 대응시켰다.
