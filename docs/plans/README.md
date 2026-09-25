# 활성 계획 인덱스

이 디렉터리에는 **현재 Kotlin/Spring Boot 백엔드와 React 프런트엔드에 적용되는 계획만** 둔다.

## 읽는 순서

1. 제품 범위와 우선순위: [`../master-plan.md`](../master-plan.md)
2. 현재 구현/미구현 목록: [`../kotlin-redevelopment-backlog.md`](../kotlin-redevelopment-backlog.md)
3. 현재 스프린트: [`2026-08-24-sprint-k1-kotlin-mvp-completion.md`](2026-08-24-sprint-k1-kotlin-mvp-completion.md)
4. 내용 손실 조사·개선 계획: [`2026-09-09-content-loss.md`](2026-09-09-content-loss.md)
5. 완료된 전환 기록: [`archive/transition/2026-08-24-python-removal-for-kotlin-redevelopment.md`](archive/transition/2026-08-24-python-removal-for-kotlin-redevelopment.md)

## 쉬운글 품질 개선 (2026-09-18 계획 · 2026-09-24 추가 계획 반영)

1. [로드맵·우선순위·단계별 완료 기준](2026-09-18-easy-read-improvement-roadmap.md)
2. [실행 계획·작업 분할·PR 절차](2026-09-18-easy-read-delivery-plan.md)
3. [구현 명세·API·데이터·비용·동시성](2026-09-18-easy-read-implementation-spec.md)
4. [UX·화면 상태·문구·접근성](2026-09-18-easy-read-ux-spec.md)
5. [검증·품질 평가·출시·롤백](2026-09-18-easy-read-validation-release.md)
6. [사람 검증 착수 계획 — 배포 전환·파일럿·R3 평가·사전 검수 데이터](2026-09-23-human-verification-kickoff.md)
7. [파일럿 참여 안내·동의·기록지 초안](2026-09-23-pilot-participant-drafts.md)
8. [R7 문맥 기반 그림 제안·생성 수정 계획](2026-09-24-contextual-illustration-correction.md)
9. [읽기 수준 선택·집중 검토 UX 추가 계획](2026-09-24-reading-level-focused-review.md)

## 상태 규칙

- 활성 계획의 경로와 명령은 Kotlin/Gradle 또는 React/npm 기준이어야 한다.
- Python/FastAPI/pytest/uv/arq를 실행 전제로 한 문서는 활성 계획으로 취급하지 않는다.
- 과거 구현 기록은 [`archive/python-era/`](archive/python-era/)에 보관하며, 현재 작업의 완료 근거로 재사용하지 않는다.
- 완료 표시는 현재 코드와 자동 검증으로 확인된 것만 사용한다. 과거 Python 구현의 완료 상태는 Kotlin 완료로 승계하지 않는다.
