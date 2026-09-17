# 접속기록 보관기간 경과 후 파기

- 작성 2026-09-17. 사용자 지시: 「접속기록 파기 미구현은 계획 세우고 개발 진행.」
- 근거: 개인정보처리방침 §8의 게시 전 빈칸 — 「보관기간이 지난 접속기록의 파기. 현재는 기록을 쌓기만 하고 파기 기능을 구현하지 않았습니다」. `docs/plans/2026-09-11-access-log-retention.md` §3.3·§6이 범위 밖으로 남긴 항목이다.
- **게시 선행조건이다.** 「보관기간이 지나면 파기한다」고 방침에 적으려면 그 기능이 먼저 있어야 한다.

## 1. 지금 참인 것 (2026-09-17 확인)

- `personal_data_access_logs`(V22)는 삽입만 된다. 삽입 포트 `PersonalDataAccessLogWriter`에 갱신·삭제 메서드가 없고(접속기록 계획 §3.1, 수용 기준 5), 일일 파기 배치 `RetentionPurgeScheduler`의 다섯 단계 어느 것도 이 표를 건드리지 않는다(`RetentionPurgeIsolationTest`).
- 보관기간 자리는 있다 — `AccessLogProperties.retention: Period = P1Y`(`easydoc.access-log.retention`). 어느 코드도 읽지 않는다. `application.yml`(api·worker)에 `access-log` 키 자체가 없어 기본값으로만 바인딩된다.
- 파기의 선례는 다섯이고 모양이 같다: 포트(`purge(cutoff, batchSize)`) → 결과 객체(건수만) → 관측자(로깅) → 정책(구성값) → 유스케이스(트랜잭션마다 한 배치, 배치가 한도만큼 차면 반복, `MAX_ROUNDS=10_000`) → `@Profile("worker")` 조립. 가장 가까운 선례는 `PurgeSignupGrantRecords`/`JdbcSignupGrantRecordPurge`(FK 없는 원장, 시각 기준 한 조건, 서브쿼리 한 문장 `FOR UPDATE SKIP LOCKED`).
- `accessed_at`에 인덱스가 있다(`ix_personal_data_access_logs_accessed_at`). 새 마이그레이션이 필요 없다.

## 2. 결정

### 2.1 파기 포트는 삽입 포트와 **다른 인터페이스**다

`PersonalDataAccessLogWriter`에 `delete`를 더하지 않는다. 그 포트에 삭제가 없다는 것이 위·변조 방지의 최소 대응이고 수용 기준 5로 고정돼 있다. 파기는 별도 포트 `PersonalDataAccessLogPurge`(application 모듈, 같은 패키지의 별도 파일)로 두고, **worker 프로필에서만 조립**한다. api 프로세스에는 이 표를 지우는 코드 경로가 아예 존재하지 않는다 — 관리자 API가 실수로든 취약점으로든 접속기록을 지울 수 없다.

### 2.2 보관기간 하한을 정책 타입이 거부한다

고시가 요구하는 최소 보관기간은 1년이다. 구성값 `easydoc.access-log.retention`이 `P1Y`보다 짧으면 **worker가 뜨지 않는다** — `PersonalDataAccessLogPurgePolicy.init`이 `require`로 거부하고, 조립 지점이 그것을 `ConfigurationException`으로 옮긴다. 운영자가 `P30D`를 넣어 의무를 위반하는 사고를 설정 단계에서 막는다. 하한은 코드 상수(`Period.ofYears(1)`)다 — 법정 불변식이므로 구성값이 아니다.

`Period` 비교는 `P1Y`·`P12M`·`P365D`가 섞이면 산술이 애매하다. 판정은 **고정 기준일에서 뺀 결과를 비교**한다: `epochDate.minus(retention) <= epochDate.minus(P1Y)` 이면 통과. 이 판정을 단위 테스트로 고정한다(`P1Y` 통과, `P12M` 통과, `P2Y` 통과, `P11M` 거부, `P364D` 거부, `P0D`·음수 거부).

### 2.3 컷오프는 실행마다 한 번 계산하고, 경계는 남긴다

`accessedBefore = now(UTC).minus(retention)`, 삭제 조건은 `accessed_at < :accessedBefore`. 같은 실행 안의 배치 반복은 같은 컷오프를 쓴다(`PurgeSignupGrantRecords.drainPurges`와 같은 이유). 경계 시각과 정확히 같은 행은 남는다.

### 2.4 결과·로그에는 건수만

결과 객체는 `enabled`와 `deleted` 둘뿐이다. `client_ip`(접속지 정보)·`actor_user_id`·`subject_scope`는 JVM으로 꺼내지 않는다 — 서브쿼리 한 문장으로 서버 안에서 끝낸다. 강제 TRACE 로그에 `client_ip` 원문이 찍히지 않는 것을 실 DB 테스트로 고정한다(`JdbcSignupGrantRecordPurgeTest`의 누출 테스트와 같은 관문).

### 2.5 일일 배치의 여섯 번째 단계

`RetentionPurgeScheduler`에 `access-log` 단계를 더한다. 다른 단계와 같은 독립 예외 경계. 스케줄(`easydoc.retention.cron`)은 그대로다.

`RetentionPurgeIsolationTest`(「다섯 단계가 이 표를 건드리지 않는다」)는 **그대로 둔다** — 그 다섯이 여전히 이 표를 건드리지 않아야 한다는 사실은 변하지 않았다. 여섯 번째 단계가 이 표를 **보관기간이 지난 행만** 건드린다는 것은 새 테스트가 잰다. 그 파일의 KDoc에 여섯 번째 단계의 존재와 이 계획을 한 문장으로 적는다.

### 2.6 구성값

`AccessLogProperties`(접두사 `easydoc.access-log`)에 두 값을 더한다. `retention`은 있던 자리를 쓴다.

| 키 | 기본값 | 환경변수 |
|---|---|---|
| `easydoc.access-log.retention` | `P1Y` (있음) | `EASYDOC_ACCESS_LOG_RETENTION` |
| `easydoc.access-log.purge-enabled` | `true` | `EASYDOC_ACCESS_LOG_PURGE_ENABLED` |
| `easydoc.access-log.purge-batch-size` | `200` | `EASYDOC_ACCESS_LOG_PURGE_BATCH_SIZE` |

worker `application.yml`에만 `easydoc.access-log` 블록을 적는다(`zone`은 보고서용이라 함께 적는다). api는 `zone`만 쓰고 파기를 조립하지 않으므로 api yml은 건드리지 않는다.

## 3. 슬라이스 (S~M, 마이그레이션·계약 변경 없음)

| 계층 | 파일 | 내용 |
|---|---|---|
| application | `accesslog/PersonalDataAccessLogPurge.kt` (신규) | `PersonalDataAccessLogPurgeResult`, `PersonalDataAccessLogPurge`(포트), `PersonalDataAccessLogPurgeObserver`, `PersonalDataAccessLogPurgePolicy`(하한 검증), `PurgePersonalDataAccessLogs`(유스케이스), `LoggingPersonalDataAccessLogPurgeObserver` |
| application | `accesslog/PersonalDataAccessLog.kt` | 삽입 포트 KDoc에 「삭제는 worker 전용 별도 포트 `PersonalDataAccessLogPurge`뿐」 한 문장 |
| infrastructure | `accesslog/JdbcPersonalDataAccessLogPurge.kt` (신규) | 서브쿼리 한 문장 `DELETE … WHERE id IN (SELECT id … WHERE accessed_at < :accessedBefore ORDER BY accessed_at LIMIT :limit FOR UPDATE SKIP LOCKED)` |
| infrastructure | `accesslog/PersonalDataAccessLogPurgeConfiguration.kt` (신규) | `@Profile("worker")`, 정책 조립, 하한 위반을 `ConfigurationException`으로 |
| infrastructure | `accesslog/AccessLogProperties.kt` | `purgeEnabled`·`purgeBatchSize` 추가, `retention` KDoc을 「이제 쓴다」로 |
| worker | `RetentionPurgeScheduler.kt` | 여섯 번째 단계 `ACCESS_LOG_STEP = "access-log"` |
| worker | `application.yml` | `easydoc.access-log` 블록 |
| 테스트 | application `PersonalDataAccessLogPurgeServiceTest`, infrastructure `JdbcPersonalDataAccessLogPurgeTest`·`PersonalDataAccessLogPurgeConfigurationTest`, worker `RetentionPurgeSchedulerTest`(여섯 단계)·`WorkerStartupTest`(빈 존재), `RetentionPurgeIsolationTest` KDoc | 아래 수용 기준 |

**건드리지 않는 것:** V22 마이그레이션 파일(머리주석의 「파기는 범위 밖」 문장이 낡지만, 기존 마이그레이션은 다시 쓰지 않는다 — 체크섬이 바뀐다). 계약 파일. 프런트. 법무 문서(§8 빈칸을 채우는 것은 버전 문자열을 올리는 별도 개정이다 — 이 조각은 그 전제인 기능만 만든다).

## 4. 수용 기준

1. `accessed_at`이 컷오프보다 오래된 행만 지워지고, 최근 행과 **경계와 정확히 같은 행**은 남는다(실 DB).
2. 대상이 배치를 넘으면 한 스케줄 실행이 배치를 반복해 전부 지운다(실 DB, `batchSize=2`로 3건).
3. `purge-enabled=false`면 저장소를 부르지 않고 `enabled=false` 결과만 관측한다(대역).
4. `retention < P1Y`면 정책 생성이 실패하고, 조립 지점은 `ConfigurationException`을 던진다. `P1Y`·`P12M`·`P2Y`는 통과한다.
5. 강제 TRACE 로그에 지워진 행의 `client_ip` 원문이 찍히지 않는다(실 DB).
6. `RetentionPurgeScheduler`가 여섯 단계를 모두 돌리고, 접속기록 단계의 실패가 다른 단계를 막지 않으며 다른 단계의 실패가 접속기록 단계를 막지 않는다(대역).
7. worker 컨텍스트에 `PurgePersonalDataAccessLogs` 빈이 있다. api 컨텍스트에는 없다(`@Profile("worker")`).
8. 기존 다섯 단계는 여전히 이 표를 건드리지 않는다(`RetentionPurgeIsolationTest` 그대로 통과).
9. `./gradlew build` 통과.

## 5. 뒤따르는 것 (이 조각 밖)

- 개인정보처리방침 §8 빈칸(「파기 기능을 구현하지 않았습니다」)과 §3.2(「접속기록은 이 자동 삭제의 대상이 아닙니다」)를 「보관기간(1년)이 지난 기록은 매일 정해진 시각에 자동으로 파기합니다」로 고치는 법무 문서 개정. **→ 2026-09-17 사용자 지시로 같은 PR(#128)에서 처리** — §3.1 행·§3.2·§8 항목 둘을 고치고 §8 빈칸을 지웠다. 버전 문자열은 상류 개정으로 이미 `privacy-2026-09-17-draft`(같은 날)라 올리지 않았다. 남은 §8 빈칸은 월 1회 점검 절차 하나다.
- `docs/plans/2026-09-11-access-log-retention.md` §3.3·§6의 「범위 밖」 문장에 이 계획을 가리키는 한 줄(이 조각에서 함께 적는다).
