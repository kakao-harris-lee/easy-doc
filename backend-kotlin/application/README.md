# application — 유스케이스 계층

계획 §3.2가 정한 다섯 모듈 중 하나. **Phase 1에서는 모듈 경계와 의존 방향만 세우므로
본 소스가 비어 있다.** 유스케이스 포팅은 Phase 3~5다.

## 담을 것 (kotlin-spring-conventions §2 매핑)

| Python 원본 | 옮길 시점 |
|---|---|
| `app/services/auth.py` — 인증·가입·기본 작업 공간 원자 생성 | Phase 3 |
| `app/services/workspaces.py` — 작업 공간 유스케이스 | Phase 3 |
| `app/services/documents.py` — 업로드·조회·검수·내보내기 | Phase 4 |
| `app/services/conversion.py` — 변환과 문서당 LLM 호출 상한 | Phase 5 |

## 규칙

- **`infrastructure` 의 구현 클래스를 import 하지 않는다.** 필요한 저장소·암호화·LLM 계약은
  이 모듈이 인터페이스로 선언하고 `infrastructure` 가 구현한다. 현재 Python이
  `app/services` 에서 `Protocol` 로 저장소 계약을 선언하고 `app/repositories` 가
  만족시키는 구조와 같다.
- **트랜잭션 경계를 이 계층이 연다** — 유스케이스 하나 = 트랜잭션 하나. 라우터나
  리포지터리 메서드마다 `@Transactional` 을 흩뿌리면 경계가 어디인지 알 수 없어진다.
- **LLM 호출·문서 파싱처럼 수 초가 걸리는 작업은 트랜잭션 안에서 하지 않는다.**
  커넥션을 붙잡은 채 외부 호출을 기다리면 풀이 마른다.
- **입력 규칙의 단일 기준**이다. 라우터는 판단하지 않는다.
