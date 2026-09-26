# R2 미리보기 수정과 행동 분석 기반 구현

작성일: 2026-09-26 · 상태: 접근성 후속 수정 포함 로컬 검증·PR 리뷰·최종 운영 재시작 완료

## 구현 범위

[R2 정정 계획](../plans/2026-09-26-action-guide-correction.md)의 첫 구현 조각이다. ER-27의 미리보기 표시를 수정하고 ER-28의 v1과 분리된 분석 계약·암호화 스냅샷·fake 기반을 추가했다. **ER-28 전체 완료나 ER-29~32의 실제 행동 추출·두 산출물 생성·전체 본문 반영·품질 평가 완료가 아니다.**

- 후보에 주의사항과 검토 상태를 모두 표시하고 항목별 모든 원문 인용을 열어 볼 수 있게 했다. 기존 `/` 병합을 제거하고 ‘행동 안내 보조자료 미리보기’로 범위를 명시했다. 적용·저장·과금·CAS 동작은 유지했다.
- 분석 생성/최신 조회/개별 조회 API와 Kotlin·React 소비자를 함께 추가했다. 분석에는 서버가 고정한 원문 단위, 저장 본문, 읽기 수준, 적합성·행동·조건·근거·검토 상태·허용 모드를 담는다.
- 요청 멱등과 본문 revision 재사용, 소유권·만료·stale, AES-GCM 저장, 삭제와 키 회전을 연결했다. V37 마이그레이션은 분석 스냅샷과 요청 별칭 테이블을 추가한다.
- 새 분석은 기본 OFF이며 명시적 local/test fake 프로필에서만 사용한다. fake 결과는 실제 분석을 수행하지 않았음을 표시하고 판단 불확실·검토 필요로 남긴다. 생성 capability는 OFF다. 이 상태를 실제 문맥 이해나 품질 검증 결과로 취급하지 않는다.

## 검증 상태

| 검증 | 현재 결과 |
|---|---|
| 프런트 `npm run check` | 통과 |
| 프런트 `npm run test -- --run --maxWorkers=2` | 62파일·845테스트 통과 |
| 프런트 `npm run build` | 통과 |
| Kotlin 전체 build | `./gradlew ktlintFormat build --continue --max-workers=1` 통과, 7분 57초. 3,567개 테스트 실패/오류/skip 0 |
| 격리 fake E2E | R2 2개 통과 후 접근성 후속 수정까지 포함한 `--grep 'E17\|ER-07'` 최종 3개 통과(42.3초). API 18127·PG 55427·합성 계정 사용, 종료 후 스택 정리 확인 |
| PR 독립 리뷰 | [PR #162](https://github.com/kakao-harris-lee/easy-doc/pull/162), `43a02dc0` 기준 차단 P0/P1/P2 없음. [리뷰 기록](https://github.com/kakao-harris-lee/easy-doc/pull/162#issuecomment-5841598368) |
| `docker_startup.sh restart` | pilot 모드로 실행 완료, 종료 코드 0. 공개 프런트 200·API database/queue true, API/worker 정상 기동 |

프런트 기본 병렬 실행에서는 기존 unsavedChanges 테스트가 대기 시간 초과로 실패했다. 해당 테스트를 수정하지 않고 worker 수를 2개로 제한한 최종 전체 실행은 통과했다. 백엔드 준비 검증에서는 Jackson map 호출, 암호화 필드 exhaustive 분기, 신규 HTTP 인증 등록과 계약/보호 검사 목록 누락을 찾아 수정했다. 이 중간 결과를 최종 전체 통과로 대체하지 않는다.

최종 Kotlin XML 집계는 core 827, application 684, infrastructure 1,191, API 797, worker 68개다. 이 R2 검증은 리뷰 기준 `43a02dc0`과 같은 코드에서 수행했다. 아래 접근성 후속 수정은 프런트만 바꾸며, Kotlin·계약·React API는 이 기준과 같다. 프런트 최종 검증·리뷰·재시작은 후속 수정을 포함해 다시 수행한다.

### 원격 CI에서 발견한 접근성 후속 수정

[첫 CI 실행](https://github.com/kakao-harris-lee/easy-doc/actions/runs/36205521357)은 backend·frontend·Compose·dictionary를 통과했지만 기본 E2E 28개 통과·4개 skip·1개 실패였다. 실패한 E17은 기존 ‘그림 제안 확인’ 버튼 높이 40px가 모바일 기준 44px보다 작은 문제였으며 행동 안내 E2E 2개는 원격에서도 통과했다.

`IllustrationSuggestionsPanel.tsx`의 생성/재전송 버튼 두 곳에만 `min-h-11`을 추가했다. 공통 Button이나 접근성 기준을 바꾸지 않았다. 기존 E17에는 해당 버튼이 실제로 보인 뒤 치수를 검사하도록 visible 확인을 추가해 토글 OFF나 로딩 전 검사로 통과하지 못하게 했다. 이 추가 diff도 독립 리뷰에서 제품 차단 결함은 발견되지 않았다.

후속 수정의 프런트 check·845개 테스트·build와 E17/행동 안내 총 3개 E2E가 모두 통과했다. 그림 제안 토글을 true로 명시했고 E17에서 문제 버튼 노출을 확인했으므로 기능이 꺼진 경로의 통과를 근거로 삼지 않았다. 최종 후속 커밋 `3551a467`의 [원격 CI](https://github.com/kakao-harris-lee/easy-doc/actions/runs/36206923088)는 Compose·backend·frontend·dictionary·E2E 모두 통과했다. 이 결과는 아래 새 후속 구현의 검증 결과가 아니다.

## 검토용 운영 재시작

이미지를 먼저 빌드한 뒤 `EASYDOC_DEPLOYMENT_MODE=pilot ./docker_startup.sh restart`를 실행했다. 2026-09-26 09:50 KST 확인 기준 API와 worker의 정상 시작 로그를 각각 확인했고 기동 실패·ERROR 기록은 없었다. API·PostgreSQL·백업은 healthy, worker와 frontend는 running이다. 공개 `/`는 HTTP 200, `/api/health`는 database/queue 모두 true다.

V37 마이그레이션 적용에 성공했다. 기존 `easy-doc_postgres_data` 볼륨과 PostgreSQL 호스트 포트 미노출을 유지했고, 요청된 변환은 done/revision 1·후보 1건으로 남아 있다. 배포된 `ConversionPage-Ci8DH3K0.js`에 보조자료 미리보기와 원문 근거 표시가 포함됐음을 확인했다. 새 fake 분석 기능은 운영에서 활성화하지 않았다.

접근성 후속 수정 검증 후 이미지를 다시 빌드하고 같은 `docker_startup.sh restart`를 재실행했다(종료 코드 0). 2026-09-26 09:59 KST 최종 확인에서 공개 프런트 HTTP 200, API database/queue true, API/worker 정상 시작·재시작 0회·ERROR 0건, DB/백업 healthy를 확인했다. 최종 배포 번들은 `ConversionPage-B8S_hyCx.js`이며 보조자료 미리보기와 44px 버튼 수정이 함께 포함됐다.

PR은 검토용으로 유지하며 main 병합은 수행하지 않았다. 최초 R2 코드 커밋 `43a02dc0` 이후 접근성 보완과 최종 검증·운영 결과를 후속 커밋으로 기록한다.

## 후속 범위와 비용

실제 분석 provider, 단계별 추가 안내/전체 보완 생성, 누락 신호 해결 UX, 전체 본문 반영과 이전 본문 보존은 ER-28 잔여 및 ER-29~31로 남는다. 모드별 길이·고객 요금·시도 상한도 확정이 필요하다. 실제 모델 품질·독자/실무자 검증은 ER-32다.

현재 fake 분석 계약은 토글 OFF 시 저장된 분석 조회도 404다. 현 계약과 일치하고 v1에 영향을 주지 않지만, 실제 출시 전에는 계획 §5.4에 따라 신규 분석/생성 차단과 저장 자료 읽기를 분리해야 한다. 분석 판단 정정·담당자 신호 해결 API도 이 기반 조각에는 없다.

사용자는 필요한 유료 호출을 허용했다. 자동 평가의 명시적인 달러 상한은 별도로 요청했으며 이번 fake 기반 구현 검증에는 유료 호출이 필요하지 않다. 운영 재시작은 사용자가 요청했으며 새 fake 분석 기능을 운영에서 활성화하는 작업과 구분한다.
