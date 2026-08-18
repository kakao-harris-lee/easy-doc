"""`00_progress.md` 의 `실행 경로` 열 규약을 **실행으로** 강제한다.

**이 파일은 답의 형식을 강제하지 답을 강제하지 않는다.** `ci:quality` 라고 적힌 행이
정말 `quality` 잡에서 도는지는 검사하지 않는다 — 잡 이름이 `ci.yml` 에 실재하는지만
본다. 형식이 맞으면서 내용이 거짓인 표는 이 검사를 통과한다. 그 층은 리뷰가 맡는다.

하네스 규칙 「선언한 범위와 실제 도달을 대조한다」의 **규칙 3**("이 게이트가 지금 어디서
도는가를 먼저 답한다. 도달 0을 특히 의심한다")은 오랫동안 문장으로만 있었고, 그동안
정확히 그 규칙이 잡아야 할 사고가 났다 — 「품질 합격선 확정·승인」 행이 `충족 = 예` 로
닫혔는데 그 합격선의 차단축은 전부 `-m llm` 마커라 `addopts = "-m 'not llm'"` 와 CI 잡
부재로 **CI 에서 0번 돌았다.** 표만 봐서는 그 사실을 알 수 없었다. 이 파일이 그 규칙에
붙인 실행 경로다 — 규칙이 스스로 "도달 0" 이던 상태를 닫는다.

**어휘 정본은 이 파일이 아니다.** `.claude/skills/kotlin-migration/SKILL.md` 의
「선언한 범위와 실제 도달을 대조한다」 절 → 「어디에 적용하는가」 → Phase 종료 판정
항목에 6종 표가 있다. 아래 상수·정규식은 그 명세의 **두 번째 표현**(명세↔테스트 관계)
이며, 둘이 갈리면 SKILL.md 가 이긴다. `00_progress.md` 는 정의를 복제하지 않고
포인터만 둔다.

강제하는 것:

1. 대상 표 4개 전부에 `실행 경로` 열이 있다.
2. **모든 행**이 비어 있지 않은 실행 경로를 갖는다 — `충족` 열의 유무·값과 무관하다.
   빈 칸·`-` 는 어디서든 위반이다.
3. `충족 = 예` 인 행의 실행 경로는 **실행을 가리킨다** (`안 돎`·`미배선` 불가).
   `충족` 셀은 `예`/`아니오` **정확 일치, 또는 그 낱말 뒤에 구분자**(공백·`—`·`(`)가
   올 때만 그 값으로 읽는다 — `아니오 — 1/11 생성` 같은 복합 표기가 이 문서의 관용이라
   완전 일치만으로는 부족하고, 그렇다고 **접두**로 읽으면 `예정`(뜻은 "아직")·`예외`·
   `예상`·`예비` 가 전부 충족으로 뒤집혀 **정상 문서를 거짓 고발한다.**
   어느 쪽으로도 읽히지 않으면 **조용히 건너뛰지 않고 위반**이다.
4. `ci:<잡>` 의 잡이 `.github/workflows/ci.yml` 의 `jobs:` 에 실재한다. 조건부 형식
   `ci:<잡>(조건:<조건 정본 경로>)` 도 **같은** 잡 실재 검사를 받고, 괄호 안 경로는
   규칙 5와 **같은 기준**(git 추적 파일)으로 본다 — 아니면 조건부 형식이 새 자유 통과
   카드가 된다. 깨진 형식(`ci:x(` · `ci:x()` · `ci:x(조건:)`)은 단순형으로 읽히지 않고
   어휘 밖이다.
5. `1회성:<경로>` 가 **git 이 추적하는 파일**이다(디렉터리·미추적 파일 거부).
6. `local:<명령>` 의 첫 낱말이 실행 파일 이름 꼴이다(산문 약속 거부).
7. `결정:<날짜>` 가 실제 달력 날짜다.
8. 어휘 7종 밖의 표기가 없다.

## 이 검사가 못 잡는 것 (한계 — 닫지 않고 적는다)

전부 막으려 들면 이 파일이 브리틀해지고, 브리틀해지면 다음 사람이 규칙을 느슨하게
만든다. 그래서 아래는 **의도적으로 열어 둔다.**

다만 **적지 않은 공백은 열어 둔 것이 아니라 모르는 것**이다. 실제 공백보다 좁게
선언한 한계 절은 그 자체가 「범위는 근거를 넘지 않는다」 위반이고, 읽는 사람에게
"여기 적힌 것 말고는 막힌다"는 거짓 안심을 준다. 그래서 아래는 독립 검증 레인이
**실제로 뚫은 경로를 전부** 적는다 — 막는 대신 적는 쪽을 택한 것이다.

### A. 어휘 자체가 자유 통과 카드가 되는 경로

- **`결정:<오늘 날짜>` 는 아무 행이나 닫는다.** `| 예 | ci:kotlin |` 을
  `| 예 | 결정:2026-08-13 |` 로 바꾸는 **두 낱말 편집**이면 통과한다. 규칙의 사문은
  "실행 경로는 실행을 가리킨다"인데 구현이 죽은 표기로 보는 것은 `안 돎`·`미배선`
  **둘뿐**이라, 나머지 넷은 무엇이든 실행을 가리킨 것으로 친다.
- **`결정:` 날짜에 범위가 없다.** 달력에 있기만 하면 되므로 `결정:2099-12-31`(미래)도
  `결정:1970-01-01`(저장소 첫 커밋 이전)도 통과한다.
- **`local:-` 와 `local:...` 이 통과한다.** 규칙 2는 실행 경로 칸의 `-` 를 "안 적음"
  으로 거부하는데, 같은 `-` 에 `local:` 만 붙이면 첫 낱말이 ASCII 라 통과한다.
  빈칸 표식을 세탁하는 경로다.
- **`local:` 명령의 실재.** 첫 낱말의 **모양**만 본다. `local:TBD 예정` 처럼 첫 낱말이
  ASCII 면 통과한다 — 명령이 실제로 존재하는지 확인하려면 실행해야 하고, 그건 이
  검사의 범위 밖이다. 막는 것은 `local:언젠가 돌릴 예정` 같은 **한글 산문**뿐이다.
- **조건부 표기를 쓸 의무가 없다.** `ci:llm-lane` 단순형은 그 잡이 실제로 조건부여도
  그대로 통과한다 — 검사기는 `ci.yml` 에서 잡 **이름**만 보고 `if:`·`paths` 같은 실행
  조건은 읽지 않는다. 그러니까 조건부 형식이 막는 것은 **"조건부라고 적었는데 조건
  정본이 없는 것"**이지 **"조건부인데 단순형으로 적어 과장하는 것"**이 아니다. 후자가
  바로 이 형식을 만든 계기인데, 형식은 그 과장을 **표현 가능하게** 할 뿐 강제하지
  못한다. 반대 방향(상시로 도는 잡에 조건을 붙여 축소해 적기)도 마찬가지로 안 잡힌다.
- **괄호 안 경로의 관련성을 안 본다.** `1회성:` 과 같은 한계다 — 추적 파일이기만 하면
  그 잡의 실행 조건과 무관한 파일이어도(`ci:quality(조건:.gitignore)`) 통과한다.
  마찬가지로 `git add` 만으로 근거가 생기며 커밋도 리뷰도 거치지 않는다.
- **`1회성:` 이 자기참조를 막지 않는다.** `1회성:tests/test_harness_scope_reach.py`
  (검사기 자신)나 `1회성:.gitignore` 로 아무 행이나 닫을 수 있다. 검사기가 자기
  존재를 자기 규칙의 근거로 인정하는 셈이다.
- **`1회성:` 근거는 커밋 없이 생긴다.** 판정 기준이 `git ls-files` 라 **`git add`
  만으로** 방금 만든 파일이 즉시 산출물 근거가 된다. 커밋도 리뷰도 거치지 않는다.
- **`1회성:` 경로의 관련성.** 추적 파일이기만 하면 그 행과 무관한 파일이어도 통과한다.

### B. 규칙을 조용히 끄는 편집

- **`아니오` 로 시작하면 규칙 3이 통째로 건너뛴다.** `아니오 → 예 (2026-08-13 해소)`
  는 `아니오` 로 읽혀 실행 경로 판정을 지나친다. 하필 이것이 **역사 행을 갱신할 때
  가장 자연스러운 편집 형태**라, 악의 없이 도달한다.
- **(닫힘) 충족 강등과 행 삭제.** 예전엔 하한에 여유(행 5 · `충족 = 예` 3 · 표기 7)가
  있어 `충족 = 예` 3행을 `아니오` + `안 돎` 으로 내리거나 행 5개를 지워도 통과했다.
  하필 지워도 안 걸리는 것 중에 **품질 게이트 행**(근거 6번의 그 행)이 있었다.
  `EXPECTED_ROWS`·`EXPECTED_REACH_TOKENS` 를 **정확 일치**로 바꿔 닫았다.
- **(닫힘) `충족 = 예` 행의 치환·개명.** 정확 일치 개수는 **순소실만** 막았다 — 품질
  게이트 행을 지우고 아무 행이나 하나 더하면 총개수가 그대로라 통과했고, 제목만 갈아
  끼워도 통과했다. **개수는 정체성의 대리 지표**이고, 대리 지표로 실물을 판정하는 것이
  이 하네스 규칙 2가 금지하는 바로 그것이다(근거 4번). 그래서 개수를 **정체성 집합**
  (`EXPECTED_MET_YES_KEYS`)으로 **교체**했다 — 병존이 아니라 교체다. 집합의 크기가
  개수를 포함하므로 두 벌을 두면 같은 사실의 두 표현이 갈릴 뿐이다.
- **(닫힘) `충족 = 예` 행의 표 간 이동.** 그 정체성 집합의 키를 **제목만**으로 만들었더니
  표를 가로질러 평평하게 모였다. 그래서 어떤 `충족 = 예` 행을 Phase 0 표에서 Phase 2 표로
  **옮기면** 키 집합이 그대로였고, 행 총수·표기 총수도 보존되므로 **어느 축도 걸리지
  않았다.** 이 문서에서는 행이 **어느 Phase 표에 있는가가 판정의 일부**다 — Phase 0 종료
  조건이 Phase 2 표로 가면 Phase 0 의 종료 판정이 조용히 달라지는데, 제목만으로는 그
  이동이 보이지 않는다. 그래서 키를 **`(표, 제목)` 쌍**으로 바꿔 정체성을 **자리에
  결속**했다. 표 쪽 키는 행 제목과 **같은 정규화**(`table_key` ↔ `identity_key`)를 쓰고,
  `line_number` 는 쓰지 않는다 — 문서 위쪽에 문단 한 줄만 늘어도 전부 어긋나 브리틀해진다.
  자리를 다시 뭉치게 하는 두 경로(**빈 caption**, **두 대상 표의 같은 caption**)는 그
  자체를 위반으로 잡는다. 뭉치면 서로 다른 표의 행이 같은 자리로 모여 이동이 다시 숨는데,
  이는 직전 라운드에서 행 키 충돌을 위반으로 잡은 것과 같은 이유다.
- **표기의 진실.** 위 첫 문단대로다. 문서가 `상태 = 미실행` 이라 적은 행에 `ci:quality`
  를 달아도 통과한다. 이 검사는 "어디서 도는가에 답했는가" 만 본다.
- **`local:` 로 닫힌 행.** 근거가 된 사고(합격선의 CI 도달 0)를 다시 겪어도 이 검사는
  막지 못한다. `local:` 은 CI 도달 0을 **드러내는** 표기이지 금지 표기가 아니기
  때문이다. 원인은 한 행이 여러 축을 겹쳐 담고 있다는 것이고, 차단하려면 규칙이 아니라
  **행을 갈라야** 한다.

### C. 행과 파일이 검사기 눈에서 사라지는 경로

- **(닫힘) 선행 파이프 한 글자를 빠뜨리면 그 행이 사라진다.** GitHub 렌더러는 그 행을
  정상 표시하므로 **문서엔 11행이 보이는데 검사기는 10행만 본다.** 표 중간에 빈 줄을
  하나 넣어도 같다 — 그 아래 전부가 별개 블록이 되어 표에서 떨어져 나간다. 하한에
  여유가 있던 시절엔 이것이 조용히 통과했지만, `EXPECTED_ROWS` 를 **정확 일치**로 바꾼
  뒤로는 둘 다 잡힌다 — 실측: 파이프 제거는 행 34/45·표기 39/52, 표 중간 빈 줄은 행
  39/45·표기 44/52 로 떨어진다. **남는 것은 진단의 어려움**이지 검출이 아니다. 실패
  메시지는 "대상 행이 34개다" 라고만 말하고 어느 줄의 파이프가 빠졌는지는 짚어 주지
  않으며, diff 가 **2글자**라 사람 눈에도 잘 띄지 않는다.
- **`충족 = 아니오` 행과 게이트 표 행은 개수로만 지켜진다 — 표 간 이동까지 포함이다.**
  `충족 = 예` 행은 정체성 집합에 박혀 삭제·치환·개명·**표 간 이동**이 이름과 함께
  드러나지만, 나머지 행은 총개수만 맞으면 된다. **실측으로 확인한 세 경로**: ① `충족 =
  아니오` 행을 Phase 0 표에서 Phase 1 표로 옮기기(두 표가 같은 7열이라 칸 손질도 필요
  없다), ② 게이트 표 행을 Phase 표로 칸만 맞춰 옮기기, ③ Phase 2 의 `충족 = 아니오` 행을
  게이트 표로 칸만 맞춰 옮기기 — 셋 다 행 45·표기 52 가 보존돼 **통과한다.** 하나를 지우고
  다른 하나를 넣거나 제목만 갈아 끼우는 것도 여전히 통과한다. 주장을 담은 행만 정체성으로
  고정한 것은 **위협의 무게를 따른 선택**이지 나머지가 안전해서가 아니다.
  반대 방향 하나는 닫혀 있다 — `충족 = 예` 행을 **게이트 표로** 옮기면 그 표엔 `충족` 열이
  없어 행이 집합에서 통째로 사라지므로 "없어진 행"으로 지목된다(실측).
- **제목·표 이름 앞 40자 밖의 편집은 안 잡는다.** 키는 행 제목과 표 caption 을 각각
  정규화해 앞 40자로 자른 값이라, 그보다 뒤를 고치거나 `종료 조건` 열 **밖의**
  칸(근거·미해결 항목·blocked-by)을 통째로 바꿔 써도 키는 그대로다. 클립을 없애면 사소한
  문구 수정마다 상수가 갈려 다음 사람이 규칙을 느슨하게 만든다 — 브리틀함과 맞바꾼
  자리다. 앞 40자가 겹치면 삭제·이동이 다시 숨을 수 있으므로 **같은 표 안의** 행 키
  충돌과 **대상 표 사이의** caption 충돌을 둘 다 위반으로 잡는다. 현재 대상 표 4개의
  caption 은 전부 40자 미만이라 클립이 걸리지 않는다.
- **표 이름이 자리 키라서 그 이름에 손대면 그 표의 `충족 = 예` 행이 전부 어긋난다.**
  실측: Phase 0 표 **바로 위에 `###` 소제목을 하나 끼우기만 해도** caption 이 그 소제목으로
  바뀌어(caption 은 직전에 나온 **아무 레벨** 제목이다) Phase 0 의 9행이 전부 "없어졌다 /
  새로 생겼다"로 지목된다 — 행·표기 총수는 그대로인데도 그렇다. 표 이름 개명도 같다.
  이 검사는 "표 이름만 고쳤다"와 "행들을 통째로 옮겼다"를 구분하지 못한다. 구분하려면
  표에 이름과 별개인 안정 식별자를 심어야 하고, 그건 `00_progress.md` 를 이 검사 전용
  형식으로 바꾸는 일이다. **막지 않고 적어 두는 마찰**이며, 정당한 편집이면 상수를 함께
  고치고 그 diff 가 리뷰에 올라가는 것이 값어치다(`_KEY_CLIP` 과 같은 거래다). 다만 자리를
  **다시 뭉치는** 방향 — 목적지 표에 출발지와 같은 이름을 붙여 이동을 숨기려는 편집 — 은
  caption 중복으로 잡힌다(실측).
- **상수를 함께 갱신하면 무엇이든 통과한다.** 행을 지우고 `EXPECTED_ROWS` 를 내리거나,
  위조 행을 넣고 그 키를 집합에 더하면 이 검사는 조용해진다. 기록 위조는 이 검사의
  threat model 이 아니라 **리뷰와 diff** 의 몫이다 — 다만 이제 그 diff 에 상수 변경이
  반드시 딸려 나오고, `충족 = 예` 쪽은 **어느 행인지가 이름으로** 드러난다.
- **(닫힘) 이 파일을 스위트에서 통째로 빼는 것.** `pytestmark = pytest.mark.llm`
  한 줄이면 `addopts = "-m 'not llm'"` 때문에 전건 제외되는데 전체 수집은 **exit 0**
  이라 스위트가 초록이었다(근거 6번과 같은 기제다). 파일 삭제도 마찬가지였다.
  이 하나는 열어 두지 않고 **CI 에서 막았다** — `quality` 잡에 이 경로를 명시하는
  스텝이 따로 있어 삭제·경로 변경은 exit 4, 마커 전건 제외는 exit 5 로 빨개진다.
  자기 안의 "나는 여기서 돈다"는 단언은 파일과 함께 사라지므로, 단언을 파일 밖에
  두는 것 말고는 방법이 없다. **남은 구멍은 그 스텝 자체의 삭제**이고, 저장소 안의
  어떤 파일도 자기 자신에 대한 절대 기준이 될 수 없으므로 그 지점의 방어선은 리뷰다.

판정 로직은 저장소 상태에 의존하지 않는 **순수 함수**(`parse_tables` ·
`select_target_tables` · `judge_tables` · `identity_key` · `table_key` ·
`identity_pair` · `census_problems`)로 빼 두었고, 아래쪽에 **음성 대조**가 합성 입력으로
붙어 있다. 각 규칙이 어떤 입력에서 실패하는지 보이지 않으면 이 파일의 통과는 "입력이
애초에 무해해서" 와 구분되지 않는다(규칙 5).

지금 어디서 도는가: `tests/` 아래라 `uv run pytest` 가 수집한다 — CI `quality` 잡의
`uv run pytest` 단계에서 매 실행 돈다. `-m llm` 마커를 붙이지 않았고 네트워크·LLM·DB 를
쓰지 않으므로 `addopts = "-m 'not llm'"` 기본 스위트에서 제외되지 않는다. **그리고 같은
잡에 이 경로를 명시하는 스텝이 하나 더 있다** — 이 문장이 파일과 함께 사라져도 CI 가
빨개지게 하려는 것이다(위 C 마지막 항목). 자기 도달을 자기 문서에 적는 것이 규칙 6이고,
그 단언을 파일 밖에도 한 벌 두는 것이 규칙 5다.
"""

from __future__ import annotations

import re
import subprocess
from collections import Counter
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Final

import pytest
import yaml

_REPO_ROOT: Final = Path(__file__).resolve().parents[1]
_PROGRESS_PATH: Final = _REPO_ROOT / "docs" / "migration" / "_workspace" / "00_progress.md"
_CI_WORKFLOW_PATH: Final = _REPO_ROOT / ".github" / "workflows" / "ci.yml"

#: 규약이 걸린 표를 고르는 열 이름. `실행 경로` 가 아니라 이 셋으로 고르는 이유는,
#: 열이 **삭제됐을 때도 표를 찾아내야** 규칙 1이 실패로 드러나기 때문이다.
_GOAL_HEADER: Final = "종료 조건"
_MET_HEADER: Final = "충족"
_GATE_HEADER: Final = "게이트"
_REACH_HEADER: Final = "실행 경로"

_MET_YES: Final = "예"
_MET_NO: Final = "아니오"
_NEVER: Final = "안 돎"
_UNWIRED: Final = "미배선"

#: `충족` 셀을 읽는 낱말 경계. 낱말 **다음**에 구분자나 끝이 와야 그 값으로 읽는다.
#: 접두 판정이면 `예정`("아직"이라는 뜻)이 `예` 로 읽혀 정상 행을 거짓 고발하고,
#: 완전 일치면 이 문서의 관용인 `아니오 — 1/11 생성` 이 읽히지 않아 역시 뒤집힌다.
#: 구분자를 셋(공백·`—`·`(`)으로 좁게 잡은 이유는 근거가 그 셋뿐이기 때문이다 —
#: 넓히면 다음 `예정` 부류가 다시 새어 들어온다.
_MET_BOUNDARY: Final = r"(?=[\s—(]|$)"
_MET_YES_PATTERN: Final = re.compile(rf"^{_MET_YES}{_MET_BOUNDARY}")
_MET_NO_PATTERN: Final = re.compile(rf"^{_MET_NO}{_MET_BOUNDARY}")

#: 미해결 항목 열의 이름과, 그 칸이 "없음"을 뜻하는 표기들.
#: 대시 세 종을 다 받는 이유는 이 문서가 세 가지를 섞어 쓰기 때문이고, 늘리는 것은
#: 곧 **비어 있음의 정의를 넓히는 것**이라 근거 없이 늘리지 않는다.
_UNRESOLVED_HEADER: Final = "미해결 항목"
_UNRESOLVED_EMPTY: Final = frozenset({"", "-", "—", "–"})

#: 실행 경로 칸이 "아직 안 적음" 인 상태. 표기가 아니므로 어휘 검사 대상이 아니다.
_BLANK_MARKS: Final = frozenset({"", "-"})

#: 여러 경로가 함께 도는 행을 잇는 구분자.
_TOKEN_SEPARATOR: Final = "·"

#: 대상 표 개수 — Phase 0·1·2 종료 조건 표 + 「아직 돌리지 않은 검증 게이트」 표.
EXPECTED_TARGET_TABLES: Final = 4

#: 판정이 **0건 검사로 통과**하는 것을 막는 기대 개수. **하한이 아니라 정확 일치다.**
#:
#: 처음엔 여유를 둔 하한(40/15/45)이었는데, 그 여유만큼 **조용히 줄어들 수 있었다** —
#: 실측으로 `충족 = 예` 3행을 강등하거나 행 5개를 지워도 통과했다. 하필 지워도 안 걸리는
#: 것 중에 **품질 게이트 행**(근거 6번의 그 행)이 있었다. 이 파일은 범위를 스스로
#: 열거하는 장치이고, 그런 장치의 최대 위험은 **선언이 줄어든 채 초록이 되는 것**이다
#: (SKILL.md 규칙 4 ⑶). 여유를 두는 순간 그 위험을 여유 크기만큼 허용하게 된다.
#:
#: 그래서 정확 일치로 바꿨다. 표를 정당하게 고치면 이 상수도 함께 고쳐야 하고,
#: **그 diff 가 "판정 범위를 건드렸다"는 신호로 리뷰에 올라가는 것**이 이 상수의
#: 값어치다. 브리틀한 것이 아니라 마찰이 의도된 자리다.
#:
#: 이 둘이 덮는 것은 **주장 없는 행과 표기까지 포함한 전체 규모**다. 주장을 담은 행
#: (`충족 = 예`)의 정체성은 아래 `EXPECTED_MET_YES_KEYS` 가 따로 덮는다.
#: 2026-08-15 게이트 15 X14·C: 표기 수 52 → **54**. 행 수·정체성 집합은 무변동이다 —
#: Phase 2 표의 「프롬프트 렌더링…」·「스타일 규칙 포팅…」 두 행이 `ci:kotlin` 하나에서
#: `ci:kotlin · ci:quality` 로 늘었을 뿐이다. 늘어난 근거는 `04ced00` 이 X-9 마감의 뒤 조각
#: (스냅샷 재생성 diff)을 `ci.yml` **quality 잡**에 배선한 것이고, 그 배선이 두 행의 승격을
#: 유지시키는 근거다(리더 판정 — 마감 축소가 아니라 CI 배선 완성). 표기가 **는** 방향이라
#: 이 diff 는 "판정 범위를 넓혔다"는 신고다.
EXPECTED_ROWS: Final = 45
EXPECTED_REACH_TOKENS: Final = 54

#: 제목·caption 에서 키를 뽑을 때 자르는 길이. 길수록 사소한 문구 수정마다 상수가 갈리고,
#: 짧을수록 서로 다른 행이 같은 키로 뭉쳐 삭제가 숨는다. 40자는 현재 18개 행이 전부
#: 구분되면서(키 충돌 검사가 이를 강제한다) 꼬리말 편집에는 둔감한 지점이다. 표 caption
#: 에도 같은 값을 쓴다 — 현재 대상 표 4개의 caption 은 전부 40자 미만이라 클립이 걸리지
#: 않고, 걸려서 두 caption 이 뭉치면 caption 중복 검사가 그것을 위반으로 잡는다.
_KEY_CLIP: Final = 40

#: `(표, 제목)` 쌍을 사람이 읽는 한 줄로 붙일 때 쓰는 구분자. 표 안의 `·`(표기 구분자)와
#: 겹치지 않는 글자를 골랐다.
_PAIR_ARROW: Final = "▸"

_BR_TAG: Final = re.compile(r"<br\s*/?>", re.IGNORECASE)
#: `미해결 항목` 을 담은 행의 **정체성 집합** (게이트 14 R-10).
#:
#: ## 왜 이것이 따로 필요한가
#:
#: `EXPECTED_MET_YES_KEYS` 는 **주장을 담은 행**(`충족 = 예`)을 지킨다. 그런데 이 문서에서
#: 조용히 사라져서 가장 곤란한 것은 주장이 아니라 **아직 안 끝난 것의 목록**이다 — 행은
#: 그대로 두고 `미해결 항목` 칸만 비우면, 그 행은 여전히 세어지고 정체성 집합에도 남아
#: 있으며 실행 경로 표기 수도 그대로다. **어느 축도 걸리지 않는다.**
#:
#: 이 항목(R-10)은 게이트 13 산출물에 사양이 적혔다가 **회차 사이에서 소멸**했다.
#: 조용히 사라지는 것을 막는 장치가 조용히 사라진 것이고, 그것이 두 번 되지 않게 하는 것이
#: 이 상수의 존재 이유다.
#:
#: ## 무엇을 잡고 무엇을 못 잡는가
#:
#: 키는 `(표, 제목)` 쌍이고 값은 **그 칸이 비었는지 아닌지**다. 그래서 잡는 것은 행이
#: "미해결 있음 → 없음"으로 뒤집히는 사건이고, **칸 안의 항목 하나가 줄어드는 것은 못
#: 잡는다**(ⓐ~ⓓ 중 ⓑ만 지우는 편집). 후자까지 보려면 항목 문면을 키로 삼아야 하는데,
#: 그러면 문구를 다듬을 때마다 상수가 갈려 다음 사람이 규칙을 느슨하게 만든다 —
#: `_clip` 이 같은 이유로 앞 40자만 보는 것과 같은 맞바꿈이다. 여기 적어 두는 이유는 이
#: 장치가 막는 범위를 실제보다 넓게 읽지 않게 하기 위해서다.
#:
#: 값은 **실측**이다. 항목이 실제로 해소되면 이 집합에서 그 줄을 지우고, 그 diff 가
#: "무엇을 닫았다"는 신고가 된다.
EXPECTED_UNRESOLVED_KEYS: Final[frozenset[tuple[str, str]]] = frozenset(
    {
        # Phase 0 — 범위·계약 동결
        ("Phase 0 — 범위·계약 동결", "Argon2 PHC 검증 spike"),
        ("Phase 0 — 범위·계약 동결", "DOCX/PDF/HWPX 라이브러리 spike"),
        ("Phase 0 — 범위·계약 동결", "FastAPI OpenAPI·계약 파일·React 타입 3자 대조"),
        ("Phase 0 — 범위·계약 동결", "Fernet JVM 호환 spike"),
        ("Phase 0 — 범위·계약 동결", "JWT 양방향 호환 spike"),
        ("Phase 0 — 범위·계약 동결", "`contracts/easy-doc-v1.yaml` 작성"),
        ("Phase 0 — 범위·계약 동결", "리뷰 게이트 Critical 0건"),
        ("Phase 0 — 범위·계약 동결", "응답·헤더·오류·인증·권한·입력 상한을 contract test로 고정"),
        ("Phase 0 — 범위·계약 동결", "전역 요구사항 인벤토리 1차본 작성·승인 (계획 §5 Phase 0 · "),
        ("Phase 0 — 범위·계약 동결", "품질 합격선 기제 확정·승인 (계획 §5 Phase 0 · §4.6 게이"),
        # Phase 1 — Kotlin 골격과 CI
        ("Phase 1 — Kotlin 골격과 CI", "CI에 Kotlin build/test 추가 + 기존 Python/Rea"),
        ("Phase 1 — Kotlin 골격과 CI", "Dockerfile·compose Kotlin profile 추가 (기존"),
        ("Phase 1 — Kotlin 골격과 CI", "Testcontainers PostgreSQL + Flyway basel"),
        ("Phase 1 — Kotlin 골격과 CI", "`backend-kotlin` Gradle 멀티모듈 생성 (§3.2의 5"),
        ("Phase 1 — Kotlin 골격과 CI", "toolchain·dependency locking·version cat"),
        ("Phase 1 — Kotlin 골격과 CI", "리뷰 차단 C-1·C-2·C-3 — 오류 계약의 HTTP 경계 검증과 C"),
        ("Phase 1 — Kotlin 골격과 CI", "설정 바인딩·구조화 로그·비밀값 마스킹"),
        ("Phase 1 — Kotlin 골격과 CI", "필수 조치 D — `encryption_scheme` additive 추"),
        ("Phase 1 — Kotlin 골격과 CI", "필수 조치 E — Kotlin 테스트가 `parity/actual/` 을"),
        # Phase 2 — 순수 도메인 로직 포팅
        ("Phase 2 — 순수 도메인 로직 포팅", "LLM 응답 후처리 포팅"),
        ("Phase 2 — 순수 도메인 로직 포팅", "Python/Kotlin 공용 JSON fixture 생성 (`parit"),
        ("Phase 2 — 순수 도메인 로직 포팅", "placeholder 보존 검사 포팅"),
        ("Phase 2 — 순수 도메인 로직 포팅", "개인정보 마스킹 포팅 (`app/privacy/masking.py`)"),
        ("Phase 2 — 순수 도메인 로직 포팅", "내보내기 파일명·`Content-Disposition` 생성 포팅"),
        ("Phase 2 — 순수 도메인 로직 포팅", "도메인마다 `backend-kotlin/parity-domains.txt"),
        ("Phase 2 — 순수 도메인 로직 포팅", "보정 채택 판정 포팅"),
        ("Phase 2 — 순수 도메인 로직 포팅", "스타일 규칙 포팅 (`app/easyread/style_rules.py`"),
        (
            "Phase 2 — 순수 도메인 로직 포팅",
            "종료 조건: 외부 API·DB 없이 도는 parity suite 가 양쪽",
        ),
        ("Phase 2 — 순수 도메인 로직 포팅", "텍스트 정규화·제어문자 제거 포팅"),
        ("Phase 2 — 순수 도메인 로직 포팅", "프롬프트 렌더링과 동적 어려운 말 목록 포팅"),
    }
)

_EMPHASIS: Final = re.compile(r"\*+")
_WHITESPACE: Final = re.compile(r"\s+")

#: `충족 = 예` 행의 **정체성 집합**. 개수(`EXPECTED_MET_YES` 였다)를 **대체한** 것이지
#: 병존하지 않는다 — 집합의 크기가 개수를 포함하므로, 두 벌을 두면 같은 사실의 두 표현이
#: 갈릴 뿐이고 갈리면 둘 다 못 믿게 된다.
#:
#: 개수로는 **순소실만** 막혔다. 「품질 합격선 기제 확정·승인」 행을 지우고 아무 행이나
#: 하나 더하면 총개수가 그대로라 통과했고, 제목만 갈아 끼워도 통과했다. **개수는 정체성의
#: 대리 지표**이고, 대리 지표로 실물을 판정하는 것이 하네스 규칙 2가 금지하는 바로
#: 그것이다(근거 4번 — "지적 건수"를 "변경 여부"의 대리로 써서 원장을 새로 만들고도
#: 성공 코드로 끝났다). 집합은 삭제·치환·개명을 **무엇이 없어졌고 무엇이 생겼는지**까지
#: 드러낸다.
#:
#: 키는 **`(표, 제목)` 쌍**이다. 제목만으로 만들었더니 표를 가로질러 평평하게 모여,
#: 「품질 합격선 기제 확정·승인」 행을 Phase 0 표에서 Phase 2 표로 **옮겨도** 집합이
#: 그대로였다(행 총수·표기 총수도 보존되므로 어느 축도 걸리지 않았다 — 실측 확인).
#: 이 문서에서는 행이 **어느 Phase 표에 있는가가 판정의 일부**이므로, 정체성을 자리에
#: 결속하지 않으면 Phase 0 종료 조건이 Phase 2 표로 가도 판정이 조용히 지나간다.
#:
#: 값은 **실측**이다(`select_target_tables` → 표 caption `table_key` × `충족 = 예` 행
#: `identity_key`). 불투명한 해시를 쓰지 않는 이유는, 리뷰에 올라가는 diff 가 이 상수의
#: 값어치인데 해시는 diff 에서 아무것도 말해 주지 않기 때문이다. 표별로 묶어 적는 것도
#: 같은 이유다 — 행이 표를 건너뛰면 diff 에서 블록 사이를 이동한 것으로 보인다.
EXPECTED_MET_YES_KEYS: Final[frozenset[tuple[str, str]]] = frozenset(
    {
        # Phase 0 — 범위·계약 동결
        ("Phase 0 — 범위·계약 동결", "Argon2 PHC 검증 spike"),
        ("Phase 0 — 범위·계약 동결", "DOCX/PDF/HWPX 라이브러리 spike"),
        ("Phase 0 — 범위·계약 동결", "FastAPI OpenAPI·계약 파일·React 타입 3자 대조"),
        ("Phase 0 — 범위·계약 동결", "Fernet JVM 호환 spike"),
        ("Phase 0 — 범위·계약 동결", "JWT 양방향 호환 spike"),
        ("Phase 0 — 범위·계약 동결", "`contracts/easy-doc-v1.yaml` 작성"),
        ("Phase 0 — 범위·계약 동결", "대상 DB와 보존할 파일럿 데이터 유무 확인"),
        ("Phase 0 — 범위·계약 동결", "범위 승인: 런타임만 Kotlin화 vs 오프라인 도구까지 Python "),
        ("Phase 0 — 범위·계약 동결", "품질 합격선 기제 확정·승인 (계획 §5 Phase 0 · §4.6 게이"),
        # Phase 1 — Kotlin 골격과 CI
        ("Phase 1 — Kotlin 골격과 CI", "Dockerfile·compose Kotlin profile 추가 (기존"),
        ("Phase 1 — Kotlin 골격과 CI", "Testcontainers PostgreSQL + Flyway basel"),
        ("Phase 1 — Kotlin 골격과 CI", '`/health` 가 계약대로 응답 (상수 `{"status":"ok"}'),
        ("Phase 1 — Kotlin 골격과 CI", "`backend-kotlin` Gradle 멀티모듈 생성 (§3.2의 5"),
        ("Phase 1 — Kotlin 골격과 CI", "toolchain·dependency locking·version cat"),
        ("Phase 1 — Kotlin 골격과 CI", "리뷰 차단 C-1·C-2·C-3 — 오류 계약의 HTTP 경계 검증과 C"),
        ("Phase 1 — Kotlin 골격과 CI", "설정 바인딩·구조화 로그·비밀값 마스킹"),
        ("Phase 1 — Kotlin 골격과 CI", "종료 조건: 빈 DB와 기존 schema snapshot 양쪽에서 기동 "),
        ("Phase 1 — Kotlin 골격과 CI", "필수 조치 D — `encryption_scheme` additive 추"),
        # Phase 2 — 순수 도메인 로직 포팅 (2026-08-13 · kotlin-implementer 조각에서 3행 충족)
        #   나머지 Phase 2 행(텍스트 정규화·프롬프트·스타일·내보내기·후처리)은 아직 `아니오` 다.
        #   아직 돌리지 않은 검증 게이트 표에는 `충족` 열 자체가 없어 구조적으로 이 집합 밖이다.
        #   2026-08-14 리더 재판정(게이트 08 C-12): 「개인정보 마스킹 포팅」을 예 → 아니오로
        #   되돌려 집합에서 제거했다 — 차단 ①사건 C-01(보충 평면 숫자·복합 카드 구분자가
        #   실측 items=0 통과)이 열린 행을 예로 둘 수 없다.
        #   근거: reviews/08_conversion-usecase_cross.md §10.
        ("Phase 2 — 순수 도메인 로직 포팅", "placeholder 보존 검사 포팅"),
        ("Phase 2 — 순수 도메인 로직 포팅", "보정 채택 판정 포팅"),
        # 2026-08-15 Phase 2 조건부 종료 판정(리더): 6행을 예로 올렸다.
        # 근거: 전체 parity 게이트 8/8 exit 0 러너 실측(run 31854263996·31868504346),
        # 게이트 14 §6.3 권고 + A 목록(F-1·F-2·F-12·N-13·N-14·R-10) 전건 해소.
        # L344·L345(X-9)·L348(F-3·F-4)은 아니오 유지 — 마감은 원장 종료 판정 절.
        ("Phase 2 — 순수 도메인 로직 포팅", "개인정보 마스킹 포팅 (`app/privacy/masking.py`)"),
        ("Phase 2 — 순수 도메인 로직 포팅", "텍스트 정규화·제어문자 제거 포팅"),
        ("Phase 2 — 순수 도메인 로직 포팅", "LLM 응답 후처리 포팅"),
        ("Phase 2 — 순수 도메인 로직 포팅", "Python/Kotlin 공용 JSON fixture 생성 (`parit"),
        ("Phase 2 — 순수 도메인 로직 포팅", "도메인마다 `backend-kotlin/parity-domains.txt"),
        (
            "Phase 2 — 순수 도메인 로직 포팅",
            "종료 조건: 외부 API·DB 없이 도는 parity suite 가 양쪽",
        ),
        # 2026-08-15 2차: X-9 해소(ef7b4a8 — 스냅샷 생성기, 재생성 diff 0)와
        # C-3·C-4 해소(f73b8bd)로 L344·L345 를 예로 올렸다.
        ("Phase 2 — 순수 도메인 로직 포팅", "프롬프트 렌더링과 동적 어려운 말 목록 포팅"),
        ("Phase 2 — 순수 도메인 로직 포팅", "스타일 규칙 포팅 (`app/easyread/style_rules.py`"),
    }
)

#: 위 세 상수가 실패했을 때 붙는 안내. 세 곳에서 같은 문장을 쓰므로 한 벌만 둔다.
_UPDATE_GUIDE: Final = (
    "표를 정당하게 고쳤다면 이 상수를 갱신하라 — 그 diff 가 "
    "'판정 범위를 건드렸다'는 신호로 리뷰에 올라가는 것이 이 상수의 값어치다."
)

#: 정체성 불일치 메시지를 음성 대조에서 골라내는 표식. 문자열을 두 곳에 따로 적으면
#: 한쪽만 고쳐졌을 때 음성 대조가 조용히 빈 필터가 된다.
_IDENTITY_MISMATCH_MARK: Final = "정체성 집합이 기대와 다르다"

#: 자리(표)가 뭉개지는 두 상태의 표식. 뭉개지면 `(표, 제목)` 쌍의 표 쪽이 무의미해져
#: 이동이 다시 숨으므로, **그 상태 자체를** 위반으로 잡는다.
_EMPTY_CAPTION_MARK: Final = "표 이름(caption)이 비었다"
_DUPLICATE_CAPTION_MARK: Final = "두 대상 표의 이름(caption)이 같다"

_CI_TOKEN: Final = re.compile(r"^ci:([A-Za-z0-9][A-Za-z0-9_.-]*)$")

#: 조건부 CI 표기 — `ci:<잡>(조건:<조건 정본 경로>)`. 잡에 배선돼 있으나 **조건이 맞을
#: 때만** 도는 레인을 적는 자리다. 단순형으로 적으면 "매 실행 돈다"로 읽혀 과장이 되고,
#: 어휘에 자리가 없으면 그 과장 말고는 적을 방법이 없다 — 그래서 형식을 더했다.
#:
#: **양끝을 앵커로 묶는 것이 이 정규식의 요점이다.** `ci:llm-lane(` 처럼 잘린 표기가
#: 단순형 `_CI_TOKEN` 으로 읽히면 조건 정본 검사가 통째로 우회된다. 단순형 쪽 문자
#: 클래스에 `(` 가 없어 지금도 그렇게 읽히지 않지만, 그 사실은 **테스트로 고정**해
#: 둔다(문자 클래스를 넓히는 다음 편집이 조용히 뚫지 못하게).
#:
#: 경로를 `\S+` 로 받는 이유는 공백이 낀 값을 형식 단계에서 떨어뜨리기 위해서다.
#: 산문(`조건:나중에`)은 여기서 통과하고 아래 추적 파일 검사가 잡는다 — 한글이라고
#: 모양으로 거르면 한글 파일명(이 저장소에 실재한다)이 함께 막힌다.
_CI_CONDITIONAL_TOKEN: Final = re.compile(r"^ci:([A-Za-z0-9][A-Za-z0-9_.-]*)\(조건:(\S+)\)$")

_LOCAL_TOKEN: Final = re.compile(r"^local:(\S.*)$")
_ONCE_TOKEN: Final = re.compile(r"^1회성:(\S+)$")
_DECISION_TOKEN: Final = re.compile(r"^결정:(\d{4}-\d{2}-\d{2})$")

#: `local:` 뒤 첫 낱말이 만족해야 하는 모양. 러너 이름을 화이트리스트로 못 박지 않는
#: 이유는 `make`·`npm`·`./gradlew` 같은 정당한 미래 값을 막기 때문이다. 막으려는 것은
#: `local:언젠가 돌릴 예정` 같은 **산문 약속** 하나다 — 그것이 통과하면 `local:` 은
#: 어떤 행이든 닫는 자유 통과 카드가 된다.
_LOCAL_COMMAND_HEAD: Final = re.compile(r"^[A-Za-z0-9._/-]+$")

#: `\|` 로 escape 된 파이프는 셀 구분자가 아니다 — 이 파일의 표 안에 실제로 있다.
_CELL_SPLIT: Final = re.compile(r"(?<!\\)\|")
_SEPARATOR_CELL: Final = re.compile(r"^:?-{2,}:?$")

_TITLE_CLIP: Final = 60


# --- 파싱 (순수 함수) ---------------------------------------------------------


@dataclass(frozen=True)
class Table:
    """마크다운 표 하나. 셀은 굵게 표시를 걷어 낸 정규화 문자열이다."""

    caption: str
    headers: tuple[str, ...]
    rows: tuple[tuple[str, ...], ...]
    line_number: int


@dataclass(frozen=True)
class JudgeContext:
    """판정에 필요한 바깥 사실. 합성 입력으로 갈아 끼울 수 있어야 음성 대조가 선다."""

    ci_jobs: frozenset[str]
    #: 존재가 아니라 **git 추적 여부**다. 존재만 보면 `1회성:.` (저장소 루트)로 어떤
    #: 행이든 닫을 수 있고, 미추적 스크래치 파일도 산출물 근거가 되어 버린다.
    is_tracked_file: Callable[[str], bool]


@dataclass(frozen=True)
class Violation:
    """규약 위반 하나. 표·행·사유를 담아 실패 메시지에서 바로 읽히게 한다."""

    table: str
    row: str
    reason: str

    def __str__(self) -> str:
        return f"[{self.table}] {self.row} — {self.reason}"


def _normalize_cell(raw: str) -> str:
    """셀에서 마크다운 굵게 표시를 걷어 비교 가능한 문자열로 만든다."""
    return raw.replace("**", "").strip()


def _split_row(line: str) -> tuple[str, ...]:
    """`| a | b |` 를 `("a", "b")` 로 자른다. 양끝의 빈 조각만 버린다."""
    parts = _CELL_SPLIT.split(line.strip())
    if parts and parts[0].strip() == "":
        parts = parts[1:]
    if parts and parts[-1].strip() == "":
        parts = parts[:-1]
    return tuple(_normalize_cell(part) for part in parts)


def _is_separator_row(cells: Sequence[str]) -> bool:
    return len(cells) > 0 and all(_SEPARATOR_CELL.match(cell) is not None for cell in cells)


def parse_tables(markdown: str) -> list[Table]:
    """마크다운에서 표를 전부 뽑는다. `caption` 은 직전에 나온 제목 줄이다."""
    lines = markdown.splitlines()
    tables: list[Table] = []
    caption = ""
    index = 0
    while index < len(lines):
        line = lines[index]
        if line.startswith("#"):
            caption = line.lstrip("#").strip()
            index += 1
            continue
        if not line.lstrip().startswith("|") or index + 1 >= len(lines):
            index += 1
            continue
        headers = _split_row(line)
        separator = lines[index + 1]
        if not separator.lstrip().startswith("|"):
            index += 1
            continue
        separator_cells = _split_row(separator)
        if len(separator_cells) != len(headers) or not _is_separator_row(separator_cells):
            index += 1
            continue
        rows: list[tuple[str, ...]] = []
        cursor = index + 2
        while cursor < len(lines) and lines[cursor].lstrip().startswith("|"):
            rows.append(_split_row(lines[cursor]))
            cursor += 1
        tables.append(
            Table(
                caption=caption,
                headers=headers,
                rows=tuple(rows),
                line_number=index + 1,
            )
        )
        index = cursor
    return tables


def select_target_tables(tables: Sequence[Table]) -> list[Table]:
    """규약이 걸린 표만 고른다 — 종료 조건 표(`종료 조건`+`충족`)와 검증 게이트 표."""
    selected: list[Table] = []
    for table in tables:
        is_goal_table = _GOAL_HEADER in table.headers and _MET_HEADER in table.headers
        is_gate_table = len(table.headers) > 0 and table.headers[0] == _GATE_HEADER
        if is_goal_table or is_gate_table:
            selected.append(table)
    return selected


def split_reach_tokens(cell: str) -> list[str]:
    """실행 경로 칸을 표기 단위로 자른다. 빈 칸·`-` 는 표기가 없는 것으로 본다."""
    if cell.strip() in _BLANK_MARKS:
        return []
    return [token.strip().strip("`").strip() for token in cell.split(_TOKEN_SEPARATOR)]


def _normalize_key(text: str) -> str:
    """행 제목과 표 caption 이 **공유하는** 키 정규화.

    `<br>` 이후 절단 → 강조 표시 제거 → 연속 공백 축약 → 앞 `_KEY_CLIP` 자 절단.
    두 쪽을 한 함수로 묶어 둔 이유는, 정규화가 갈리면 `(표, 제목)` 쌍의 두 축이 서로 다른
    민감도를 갖게 되어 "표 이름은 못 알아보는데 행 제목은 알아보는" 비대칭이 생기기
    때문이다. 이 함수는 저장소 상태를 보지 않으므로 아래에서 직접 검사한다.
    """
    head = _BR_TAG.split(text, maxsplit=1)[0]
    plain = _EMPHASIS.sub("", head)
    return _WHITESPACE.sub(" ", plain).strip()[:_KEY_CLIP]


def identity_key(title: str) -> str:
    """행 제목(첫 셀)에서 **안정된 정체성 키**를 뽑는다.

    키가 만족해야 하는 두 성질이 서로 반대 방향이라 정규화가 필요하다.

    * **같은 행은 같은 키여야 한다.** 이 문서의 제목은 굵게·기울임 표시가 붙었다 떨어지고
      (`품질 합격선 **기제** 확정`), `<br>` 뒤에 개정 꼬리말이 자란다
      (`…<br>*직전 행 제목: "합격선 **수치** 확정·승인" — …*`). 그런 표기 변화마다 상수가
      갈리면 다음 사람이 규칙을 느슨하게 만든다.
    * **다른 행은 다른 키여야 한다.** 뭉치면 한 행이 지워져도 집합이 그대로라 삭제가
      숨는다. 그래서 `census_problems` 가 키 충돌 자체를 위반으로 잡는다.

    **이 값 하나로는 정체성이 되지 못한다** — 표를 가로질러 평평하게 모이기 때문이다.
    실제 정체성은 `table_key` 와 짝지은 `identity_pair` 다.
    """
    return _normalize_key(title)


def table_key(caption: str) -> str:
    """표 caption 에서 **자리 키**를 뽑는다. 행 제목과 같은 정규화(`_normalize_key`)다.

    `line_number` 를 쓰지 않는 이유가 여기 있다 — 줄 번호는 문서 위쪽에 문단 한 줄만
    늘어도 전부 어긋나 브리틀해지고, 브리틀해지면 다음 사람이 규칙을 느슨하게 만든다.
    caption 은 그 행이 **어느 Phase 표에 속하는가**를 문서가 이미 말하고 있는 자리다.

    빈 값과 중복은 이 함수가 아니라 `census_problems` 가 위반으로 잡는다 — 둘 다 자리가
    다시 뭉쳐 표 간 이동이 숨는 상태이고, 그것은 한 표만 봐서는 판정할 수 없다.
    """
    return _normalize_key(caption)


def identity_pair(caption: str, title: str) -> tuple[str, str]:
    """행의 정체성 = **`(표, 제목)` 쌍**. 제목만으로는 표 간 이동이 보이지 않는다."""
    return (table_key(caption), identity_key(title))


def _render_pair(key: tuple[str, str]) -> str:
    """쌍을 사람이 읽는 한 줄로. 실패 메시지는 **이름을 나열**해야 diff 가 말을 한다."""
    caption, title = key
    return f"{caption} {_PAIR_ARROW} {title}"


# --- 판정 (순수 함수) ---------------------------------------------------------


def _unknown_job_problem(job: str, context: JudgeContext) -> str | None:
    """`ci:` 두 형식이 **같은** 잡 실재 검사를 쓰게 하는 한 곳.

    각자 판정하면 한쪽만 느슨해지고, 느슨한 쪽이 곧 자유 통과 카드가 된다.
    """
    if job in context.ci_jobs:
        return None
    known = ", ".join(sorted(context.ci_jobs)) or "없음"
    return f"`ci:{job}` 인데 ci.yml 의 jobs 에 `{job}` 이 없다 (실재하는 잡: {known})"


def _untracked_problem(label: str, target: str, context: JudgeContext) -> str | None:
    """`1회성:` 과 `ci:…(조건:…)` 이 **같은 기준**을 쓰게 하는 한 곳.

    둘 다 "저장소가 추적하는 파일을 가리켜라" 이고, 존재가 아니라 **git 추적**을 본다.
    조건부 표기의 괄호 안이 이 검사를 안 받으면 `조건:나중에` 같은 산문으로 아무 행이나
    닫을 수 있어, 형식을 더한 것이 곧 구멍을 더한 것이 된다.
    """
    if context.is_tracked_file(target):
        return None
    return (
        f"{label} `{target}` 이 git 이 추적하는 **파일**이 아니다 — "
        "디렉터리(`.` 같은)·미추적 파일·산문(`조건:나중에`)은 근거가 되지 못한다"
    )


def _vocabulary_problem(token: str, context: JudgeContext) -> str | None:
    """표기 하나를 어휘 7종에 대조한다. 맞으면 None, 어긋나면 사유를 돌려준다."""
    if token in (_NEVER, _UNWIRED):
        return None

    # 조건부를 먼저 본다. 두 정규식이 양끝 앵커라 순서가 판정을 바꾸지는 않지만,
    # **더 구체적인 형식을 먼저 시도한다**는 것이 읽는 사람에게 보이는 편이 낫다.
    conditional_match = _CI_CONDITIONAL_TOKEN.match(token)
    if conditional_match is not None:
        job, condition = conditional_match.group(1), conditional_match.group(2)
        job_problem = _unknown_job_problem(job, context)
        if job_problem is not None:
            return job_problem
        return _untracked_problem(f"`ci:{job}(조건:…)` 의 조건 정본 경로", condition, context)

    ci_match = _CI_TOKEN.match(token)
    if ci_match is not None:
        return _unknown_job_problem(ci_match.group(1), context)

    once_match = _ONCE_TOKEN.match(token)
    if once_match is not None:
        return _untracked_problem("`1회성:` 의 산출물 경로", once_match.group(1), context)

    local_match = _LOCAL_TOKEN.match(token)
    if local_match is not None:
        head = local_match.group(1).split()[0]
        if _LOCAL_COMMAND_HEAD.match(head) is None:
            return (
                f"`local:` 의 첫 낱말 `{head}` 이 실행 파일 이름 꼴이 아니다 — "
                "산문 약속은 실행 경로가 아니다. 실제로 칠 수 있는 명령을 적어라"
            )
        return None

    decision_match = _DECISION_TOKEN.match(token)
    if decision_match is not None:
        stamp = decision_match.group(1)
        try:
            date.fromisoformat(stamp)
        except ValueError:
            return f"`결정:{stamp}` 이 실제 달력 날짜가 아니다 — 모양만 맞는 값은 근거가 아니다"
        return None

    return (
        f"어휘 밖 표기 `{token}` — 허용은 `ci:<잡>` · `ci:<잡>(조건:<조건 정본 경로>)` · "
        f"`local:<명령>` · `1회성:<경로>` · `결정:<YYYY-MM-DD>` · `{_NEVER}` · "
        f"`{_UNWIRED}` 일곱뿐이다"
    )


def _clip(title: str) -> str:
    return title if len(title) <= _TITLE_CLIP else title[:_TITLE_CLIP] + "…"


def met_verdict(cell: str) -> bool | None:
    """`충족` 셀을 예/아니오로 읽는다. 읽을 수 없으면 None.

    **낱말 경계 판정**이다 — `예`/`아니오` 와 정확히 같거나, 그 낱말 **뒤에 구분자**
    (공백·`—`·`(`)가 올 때만 그 값으로 읽는다.

    완전 일치만으로는 안 된다. 이 문서는 실제로 `아니오 — **1/11 생성**` 같은 복합
    표기를 쓰고(역사 행이라 고칠 수 없다), 완전 일치로 두면 그 행이 읽히지 않아
    **정상 행이 위반으로** 뒤집힌다.

    접두로도 안 된다. `예정` 은 접두가 `예` 라 **충족으로 읽히는데 뜻은 정반대**
    ("아직")다. 그러면 `예정` + `안 돎` 인 정상 행에 규칙 3이 발동해 "충족 = 예인데
    안 돎"이라고 **거짓 고발**한다. `예외 — 범위 밖`·`예상`·`예비` 도 같은 부류이고
    전부 이 문서의 관용 어휘권 안에 있다. 정상 문서를 고발하는 검사는 한계가 아니라
    버그이며, 몇 번 겪으면 다음 사람이 규칙째로 지운다.

    두 요구를 동시에 만족시키는 것이 낱말 경계다. `예정`·`예외`·`예상`·`예비` 는
    `예` 다음이 낱자라 탈락하고, `아니오 — 1/11 생성` 은 다음이 공백이라 통과한다.

    None 은 "건너뜀"이 아니라 **위반**으로 처리된다. `O`·`Y`·`완료`·`✅` 로 적으면
    규칙 3이 조용히 꺼지는데, 그것이 평범한 문서 편집으로 도달하는 경로이기 때문이다.
    """
    text = cell.strip()
    if _MET_NO_PATTERN.match(text) is not None:
        return False
    if _MET_YES_PATTERN.match(text) is not None:
        return True
    return None


def judge_tables(tables: Sequence[Table], context: JudgeContext) -> list[Violation]:
    """대상 표들을 모듈 docstring 의 규약으로 판정한다. 통과면 빈 목록이다."""
    violations: list[Violation] = []
    for table in tables:
        caption = table.caption or f"{table.line_number}행의 이름 없는 표"

        # 규칙 1 — 열 존재.
        if _REACH_HEADER not in table.headers:
            violations.append(
                Violation(
                    table=caption,
                    row="(표 전체)",
                    reason=(
                        f"`{_REACH_HEADER}` 열이 없다 — 이 표는 규약 대상인데 "
                        f"열이 사라졌다 (헤더: {' | '.join(table.headers)})"
                    ),
                )
            )
            continue

        reach_index = table.headers.index(_REACH_HEADER)
        met_index = table.headers.index(_MET_HEADER) if _MET_HEADER in table.headers else None

        for row in table.rows:
            if len(row) != len(table.headers):
                violations.append(
                    Violation(
                        table=caption,
                        row=_clip(row[0]) if row else "(빈 줄)",
                        reason=(
                            f"셀 수가 헤더와 다르다 (셀 {len(row)}개 / 헤더 "
                            f"{len(table.headers)}개) — 열이 밀려 판정할 수 없다"
                        ),
                    )
                )
                continue

            title = _clip(row[0])
            tokens = split_reach_tokens(row[reach_index])

            # 규칙 2 — 모든 행이 실행 경로를 갖는다. `충족` 열이 없는 게이트 표에도
            # 적용된다 — 그 표는 규칙 3이 구조적으로 닿지 않아 여기가 유일한 방어다.
            if not tokens:
                violations.append(
                    Violation(
                        table=caption,
                        row=title,
                        reason=(
                            f"`{_REACH_HEADER}` 가 비었다 — 어디서 도는지 적지 않으면 "
                            "도달 0을 구분할 수 없다 (`충족` 열의 유무·값과 무관하다)"
                        ),
                    )
                )

            # 규칙 4·5·6·7·8 — 표기 자체의 타당성. `충족` 값과 무관하게 본다.
            for token in tokens:
                problem = _vocabulary_problem(token, context)
                if problem is not None:
                    violations.append(Violation(table=caption, row=title, reason=problem))

            if met_index is None:
                continue

            # 규칙 3 — `충족` 을 읽고, `예` 인 행은 실행을 가리켜야 한다.
            verdict = met_verdict(row[met_index])
            if verdict is None:
                violations.append(
                    Violation(
                        table=caption,
                        row=title,
                        reason=(
                            f"`{_MET_HEADER}` 값 `{row[met_index]}` 을 "
                            f"`{_MET_YES}`/`{_MET_NO}` 로 읽을 수 없다 — "
                            "읽지 못하면 실행 경로 판정이 조용히 꺼진다"
                        ),
                    )
                )
                continue
            if not verdict:
                continue
            dead = [token for token in tokens if token in (_NEVER, _UNWIRED)]
            if dead:
                violations.append(
                    Violation(
                        table=caption,
                        row=title,
                        reason=(
                            f"`{_MET_HEADER} = {_MET_YES}` 인데 실행 경로가 "
                            f"`{'`·`'.join(dead)}` 다 — 돌지 않는 근거로 종료 조건을 "
                            "닫을 수 없다"
                        ),
                    )
                )
    return violations


def census_problems(
    tables: Sequence[Table],
    *,
    expected_rows: int,
    expected_met_yes_keys: frozenset[tuple[str, str]],
    expected_unresolved_keys: frozenset[tuple[str, str]],
    expected_reach_tokens: int,
) -> list[str]:
    """대상 표의 **규모와 정체성**을 기대값에 대조한다. 통과면 빈 목록이다.

    규모(행 수·표기 수)는 순소실만 막는다 — 하나를 지우고 하나를 넣으면 그대로다.
    그래서 주장을 담은 행(`충족 = 예`)은 개수가 아니라 **정체성 집합**으로 보고, 그
    정체성은 제목만이 아니라 **`(표, 제목)` 쌍**이다 — 제목만으로는 표를 가로질러 평평하게
    모여, 행을 다른 Phase 표로 옮겨도 집합이 그대로였다.

    쌍이 성립하려면 표가 서로 구분돼야 하므로 **빈 caption 과 중복 caption 자체를**
    위반으로 잡는다. 뭉개진 자리는 이동을 다시 숨긴다.

    실물 판정과 음성 대조가 **같은 함수**를 부르게 해 둔 이유는, 둘이 다른 코드를 보면
    음성 대조가 "이 검사가 잡는다"가 아니라 "닮은 검사가 잡는다"만 증명하기 때문이다.
    """
    rows = sum(len(table.rows) for table in tables)
    met_yes_keys: list[tuple[str, str]] = []
    unresolved_keys: list[tuple[str, str]] = []
    table_keys: list[str] = []
    nameless: list[str] = []
    tokens = 0
    for table in tables:
        caption_key = table_key(table.caption)
        table_keys.append(caption_key)
        if not caption_key:
            nameless.append(f"{table.line_number}행의 표")
        if _REACH_HEADER not in table.headers:
            continue
        reach_index = table.headers.index(_REACH_HEADER)
        met_index = table.headers.index(_MET_HEADER) if _MET_HEADER in table.headers else None
        open_index = (
            table.headers.index(_UNRESOLVED_HEADER) if _UNRESOLVED_HEADER in table.headers else None
        )
        for row in table.rows:
            if len(row) != len(table.headers):
                continue
            tokens += len(split_reach_tokens(row[reach_index]))
            if met_index is not None and met_verdict(row[met_index]) is True:
                # `(caption_key, identity_key(...))` 를 손으로 짜지 않고 `identity_pair` 를
                # 부른다 — 그래야 아래 단위 검사가 **실물이 쓰는 그 함수**를 검사한다.
                met_yes_keys.append(identity_pair(table.caption, row[0]))
            if open_index is not None and row[open_index].strip() not in _UNRESOLVED_EMPTY:
                unresolved_keys.append(identity_pair(table.caption, row[0]))

    problems: list[str] = []
    if rows != expected_rows:
        problems.append(
            f"대상 행이 {rows}개다 (기대 {expected_rows}) — 표가 사라졌거나 파싱이 깨졌다."
        )

    if nameless:
        problems.append(
            f"{_EMPTY_CAPTION_MARK}: {nameless}. 행의 정체성은 `(표, 제목)` 쌍이라 "
            "표 이름이 없으면 서로 다른 표의 행이 같은 자리로 뭉치고, 그러면 표 간 "
            "이동이 다시 숨는다 — 표 앞에 제목 줄을 두어라."
        )

    #: 빈 caption 은 위에서 이미 지목했으므로 중복 집계에서 뺀다 — 같은 사실을 두 번
    #: 세면 음성 대조가 어느 검사를 보고 있는지 흐려진다.
    duplicates = sorted(
        key for key, count in Counter(table_keys).items() if count > 1 and key != ""
    )
    if duplicates:
        problems.append(
            f"{_DUPLICATE_CAPTION_MARK}: {duplicates}. 같은 이름이면 두 표의 행이 한 자리로 "
            "뭉쳐 표 간 이동이 숨는다 — 행 제목 키 충돌을 위반으로 잡는 것과 같은 이유다. "
            f"caption 은 앞 {_KEY_CLIP}자로 자르므로 앞부분이 갈리도록 표 이름을 구분하라."
        )

    collisions = sorted(key for key, count in Counter(met_yes_keys).items() if count > 1)
    if collisions:
        problems.append(
            f"정체성 키가 겹치는 `{_MET_HEADER} = {_MET_YES}` 행이 있다: "
            f"{[_render_pair(key) for key in collisions]}. "
            f"**같은 표 안에서** 제목 앞 {_KEY_CLIP}자가 같으면 그중 한 행이 지워져도 "
            "집합은 그대로라 삭제가 숨는다 — 앞부분이 갈리도록 행 제목을 구분하라."
        )

    actual_keys = frozenset(met_yes_keys)
    missing = sorted(expected_met_yes_keys - actual_keys)
    added = sorted(actual_keys - expected_met_yes_keys)
    if missing or added:
        problems.append(
            f"`{_MET_HEADER} = {_MET_YES}` 행의 {_IDENTITY_MISMATCH_MARK} "
            f"(기대 {len(expected_met_yes_keys)}개 / 실제 {len(actual_keys)}개).\n"
            f"     없어진 행: {[_render_pair(key) for key in missing] if missing else '없음'}\n"
            f"     새로 생긴 행: {[_render_pair(key) for key in added] if added else '없음'}\n"
            f"     (`표 {_PAIR_ARROW} 제목` 이다 — 제목이 같은데 표만 다르면 **표 간 이동**이다)"
        )

    actual_unresolved = frozenset(unresolved_keys)
    lost = sorted(expected_unresolved_keys - actual_unresolved)
    gained = sorted(actual_unresolved - expected_unresolved_keys)
    if lost or gained:
        problems.append(
            f"`{_UNRESOLVED_HEADER}` 을 담은 행의 {_IDENTITY_MISMATCH_MARK} "
            f"(기대 {len(expected_unresolved_keys)}개 / 실제 {len(actual_unresolved)}개).\n"
            f"     칸이 비워진 행: {[_render_pair(key) for key in lost] if lost else '없음'}\n"
            f"     새로 생긴 행: {[_render_pair(key) for key in gained] if gained else '없음'}\n"
            f"     (항목을 실제로 닫았다면 `EXPECTED_UNRESOLVED_KEYS` 에서 그 줄을 지운다 — "
            "그 diff 가 '무엇을 닫았다'는 신고다)"
        )

    if tokens != expected_reach_tokens:
        problems.append(
            f"실행 경로 표기가 {tokens}개다 (기대 {expected_reach_tokens}) — "
            "규칙 3·4·5가 검사할 대상이 사라졌다."
        )
    return problems


def read_ci_job_names(workflow_yaml: str) -> frozenset[str]:
    """워크플로의 `jobs:` 이름을 읽는다.

    문자열 grep 이 아니라 YAML 파싱이다 — 주석이나 `run:` 본문에 잡 이름처럼 생긴
    문자열이 있으면 grep 은 없는 잡을 있다고 답하고, 그 순간 규칙 3이 무의미해진다.
    """
    document = yaml.safe_load(workflow_yaml)
    if not isinstance(document, dict):
        raise AssertionError(
            "ci.yml 최상위가 매핑이 아니다 — 워크플로 파일이 아니거나 파싱이 깨졌다."
        )
    jobs = document.get("jobs")
    if not isinstance(jobs, dict):
        raise AssertionError("ci.yml 에 `jobs:` 매핑이 없다 — 잡 이름을 대조할 근거가 사라졌다.")
    if len(jobs) == 0:
        raise AssertionError("ci.yml 의 `jobs:` 가 비었다 — 어떤 `ci:` 표기도 통과할 수 없다.")
    return frozenset(str(name) for name in jobs)


def read_tracked_files(repo_root: Path) -> frozenset[str]:
    """git 이 추적하는 파일 목록. `-z` 로 받는 이유는 한글 경로가 이 저장소에 실재하고,
    기본 출력은 그것을 따옴표로 감싸 escape 하기 때문이다(그러면 대조가 조용히 빗나간다).
    """
    completed = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=repo_root,
        capture_output=True,
        check=True,
    )
    names = completed.stdout.decode("utf-8").split("\0")
    return frozenset(name for name in names if name)


def _render(violations: Sequence[Violation]) -> str:
    return "\n".join(f"  {number}. {violation}" for number, violation in enumerate(violations, 1))


# --- 저장소 실물 판정 ---------------------------------------------------------


@pytest.fixture(scope="module")
def target_tables() -> list[Table]:
    """`00_progress.md` 에서 규약 대상 표만 골라 온다."""
    return select_target_tables(parse_tables(_PROGRESS_PATH.read_text(encoding="utf-8")))


@pytest.fixture(scope="module")
def repo_context() -> JudgeContext:
    """실제 ci.yml 의 잡 이름과 실제 git 추적 목록을 판정에 넣는다."""
    tracked = read_tracked_files(_REPO_ROOT)

    def is_tracked_file(target: str) -> bool:
        return target in tracked

    return JudgeContext(
        ci_jobs=read_ci_job_names(_CI_WORKFLOW_PATH.read_text(encoding="utf-8")),
        is_tracked_file=is_tracked_file,
    )


def test_규약_대상_표_네_개를_찾는다(target_tables: list[Table]) -> None:
    """표를 못 찾으면 아래 판정이 조용히 0건 검사로 바뀐다 — 그 경로를 먼저 막는다."""
    captions = [table.caption for table in target_tables]
    assert len(target_tables) == EXPECTED_TARGET_TABLES, (
        f"규약 대상 표가 {len(target_tables)}개다 (기대 {EXPECTED_TARGET_TABLES}개). "
        f"찾은 표: {captions}.\n"
        "  Phase 표를 **정당하게 늘렸다면** `EXPECTED_TARGET_TABLES` 를 올려라 — "
        "그 diff 가 '판정 범위를 건드렸다'는 신호로 리뷰에 올라가는 것이 이 상수의 값어치다.\n"
        "  줄었다면 헤더(`종료 조건`+`충족`, `게이트`)가 바뀐 것이고, 그때 이 파일은 "
        "아무것도 검사하지 않게 된다."
    )


def test_대상_표에_실행_경로_열이_있다(target_tables: list[Table]) -> None:
    """규칙 1. 열이 사라지면 나머지 네 규칙이 통째로 무력해지므로 따로 세운다."""
    missing = [table.caption for table in target_tables if _REACH_HEADER not in table.headers]
    assert not missing, (
        f"`{_REACH_HEADER}` 열이 없는 표: {missing}. "
        "어휘 정본은 .claude/skills/kotlin-migration/SKILL.md 의 "
        "「선언한 범위와 실제 도달을 대조한다」 절이다."
    )


def test_판정이_실제로_행을_보고_있다(target_tables: list[Table]) -> None:
    """아래 판정이 **0건 검사로 통과**하는 경로를 막는다.

    이 하네스가 이미 겪은 실패다 — "미충족 0" 이 항목 0개에서 참이 되던 구멍(리뷰
    A-1/X-08). 표를 못 읽거나 `충족 = 예` 행을 하나도 못 골라도 규칙 2는 조용히
    통과하므로, 규모와 정체성을 못 박아 그 상태를 실패로 만든다.

    **규모는 정확 일치이지 하한이 아니다.** 여유를 두면 그만큼 조용히 줄어들 수 있고,
    실측에서 지워도 안 걸리는 행 중에 **품질 게이트 행**(근거 6번)이 있었다.

    **그리고 규모만으로는 부족하다.** 총개수는 순소실만 막는다 — 그 품질 게이트 행을
    지우고 아무 행이나 하나 더하면 45가 그대로라 통과했고, 제목만 갈아 끼워도 통과했다.
    개수는 정체성의 **대리 지표**이고, 대리 지표로 실물을 판정하는 것이 하네스 규칙 2가
    금지하는 그것이다. 그래서 주장을 담은 행은 개수 대신 **정체성 집합**으로 본다.

    **그 집합의 키도 제목만으로는 부족하다.** 제목만이면 표를 가로질러 평평하게 모여,
    같은 품질 게이트 행을 Phase 0 표에서 Phase 2 표로 **옮겨도** 세 축(행 45 · 표기 52 ·
    제목 집합)이 전부 보존돼 통과했다 — 실측으로 확인했다. 이 문서에서는 행이 어느 Phase
    표에 있는가가 판정의 일부이므로 키를 **`(표, 제목)` 쌍**으로 자리에 결속했다.
    """
    problems = census_problems(
        target_tables,
        expected_rows=EXPECTED_ROWS,
        expected_met_yes_keys=EXPECTED_MET_YES_KEYS,
        expected_unresolved_keys=EXPECTED_UNRESOLVED_KEYS,
        expected_reach_tokens=EXPECTED_REACH_TOKENS,
    )
    rendered = "\n".join(f"  {number}. {problem}" for number, problem in enumerate(problems, 1))
    assert not problems, (
        f"대상 표의 규모·정체성이 기대와 다르다 ({len(problems)}건):\n{rendered}\n  {_UPDATE_GUIDE}"
    )


def test_진행상태표의_실행_경로가_규약을_지킨다(
    target_tables: list[Table], repo_context: JudgeContext
) -> None:
    """규칙 2~5를 실물에 적용한다.

    이 테스트가 실패한다면 표가 틀린 것이지 검사가 틀린 것이 아니다. **실패를 없애는
    올바른 방법은 값을 부풀리거나 규칙을 느슨하게 하는 것이 아니라, 그 행의 게이트를
    실제로 돌게 배선하거나 `충족` 판정을 되돌리는 것이다.**
    """
    violations = judge_tables(target_tables, repo_context)
    assert not violations, (
        f"실행 경로 규약 위반 {len(violations)}건:\n{_render(violations)}\n"
        "  (규칙 3 — 이 게이트가 지금 어디서 도는가. 도달 0을 특히 의심한다)"
    )


# --- 음성 대조 (합성 입력) -----------------------------------------------------
#
# 아래는 저장소 상태와 무관하다. 위 판정이 "입력이 무해해서" 통과한 것이 아님을
# 보이는 자리이며, 다섯 규칙이 각각 정확히 어떤 입력에서 실패하는지 고정한다.

#: **실제 CI 잡(`quality`·`frontend`·`kotlin`)과 일부러 다르게** 둔다. 같은 이름을 쓰면
#: 컨텍스트 치환이 실제로 먹는지, 아니면 어딘가에서 진짜 ci.yml 을 읽고 있는지 구분되지
#: 않는다 — 합성 입력의 값어치가 사라진다.
_FAKE_JOBS: Final = frozenset({"unit", "lint", "e2e"})
_FAKE_TRACKED_PATH: Final = "docs/추적되는-산출물.md"


def _fake_context() -> JudgeContext:
    def is_tracked_file(target: str) -> bool:
        return target == _FAKE_TRACKED_PATH

    return JudgeContext(ci_jobs=_FAKE_JOBS, is_tracked_file=is_tracked_file)


def _goal_table(met: str, reach: str) -> str:
    """종료 조건 표 한 줄짜리 합성 마크다운."""
    return (
        "## 합성 Phase 표\n"
        "\n"
        f"| 종료 조건 | 충족 | {_REACH_HEADER} | 근거 |\n"
        "|---|---|---|---|\n"
        f"| 어떤 종료 조건 | {met} | {reach} | 어떤 근거 |\n"
    )


def _judge_markdown(markdown: str) -> list[Violation]:
    return judge_tables(select_target_tables(parse_tables(markdown)), _fake_context())


def _sole_reason(markdown: str) -> str:
    violations = _judge_markdown(markdown)
    assert len(violations) == 1, f"위반이 정확히 1건이어야 한다: {[str(v) for v in violations]}"
    return violations[0].reason


def _gate_table(reach: str) -> str:
    """게이트 표 한 줄짜리 합성 마크다운. `충족` 열이 **없는** 모양이다."""
    return (
        "## 합성 게이트 표\n"
        "\n"
        f"| 게이트 | {_REACH_HEADER} | 상태 |\n"
        "|---|---|---|\n"
        f"| 어떤 게이트 | {reach} | 미실행 |\n"
    )


def test_대조군_정상_표는_통과한다() -> None:
    """어휘 7종을 모두 쓴 정상 표. 이게 실패하면 아래 음성 대조가 무의미하다."""
    markdown = (
        "## 합성 정상 표\n"
        "\n"
        f"| 종료 조건 | 충족 | {_REACH_HEADER} | 근거 |\n"
        "|---|---|---|---|\n"
        "| CI 로 도는 행 | 예 | `ci:unit` | 근거 |\n"
        f"| 조건부로 도는 행 | 예 | `ci:e2e(조건:{_FAKE_TRACKED_PATH})` | 근거 |\n"
        "| 여러 경로가 도는 행 | 예 | `ci:lint` · `ci:e2e` | 근거 |\n"
        "| 로컬로만 도는 행 | 예 | `local:uv run pytest tests/golden -m llm` | 근거 |\n"
        f"| 한 번 재고 만 행 | 예 | `1회성:{_FAKE_TRACKED_PATH}` | 근거 |\n"
        "| 결정으로 닫은 행 | 예 | `결정:2026-08-12` | 근거 |\n"
        "| 복합 표기로 닫힌 행 | 아니오 — **1/11 생성** | `안 돎` | 근거 |\n"
        "| 배선이 없는 행 | 아니오 | `미배선` | 근거 |\n"
        "| 상대 경로 러너 | 예 | `local:./gradlew build` | 근거 |\n"
        "\n" + _gate_table("`ci:e2e`")
    )
    assert _judge_markdown(markdown) == []


def test_음성1_충족_예인데_안_돎이면_실패한다() -> None:
    reason = _sole_reason(_goal_table("예", "`안 돎`"))
    assert _NEVER in reason
    assert "충족 = 예" in reason
    # `미배선` 도 같다.
    assert _UNWIRED in _sole_reason(_goal_table("예", "`미배선`"))


def test_음성2_충족_예인데_실행_경로가_비면_실패한다() -> None:
    reason = _sole_reason(_goal_table("예", " "))
    assert "비었다" in reason
    # `-` 도 같은 취급이다 — 안 적은 것과 줄표를 그은 것은 같은 상태다.
    assert "비었다" in _sole_reason(_goal_table("예", "-"))


def test_음성3_존재하지_않는_CI_잡이면_실패한다() -> None:
    reason = _sole_reason(_goal_table("예", "`ci:golden`"))
    assert "ci.yml" in reason
    assert "golden" in reason
    # `충족 = 아니오` 인 행도 똑같이 걸린다 — 이 규칙은 충족 값과 무관하다.
    assert "ci.yml" in _sole_reason(_goal_table("아니오", "`ci:nightly`"))
    # 잡 이름 모양 자체가 GitHub 규칙(영숫자·`-`·`_`·`.`)을 벗어나면 어휘 밖으로 잡힌다.
    assert "어휘 밖 표기" in _sole_reason(_goal_table("예", "`ci:없는 잡`"))
    # 실제 CI 잡 이름은 합성 컨텍스트에서 **통과하면 안 된다** — 통과한다면 컨텍스트
    # 치환이 먹지 않고 어딘가에서 진짜 ci.yml 을 읽고 있다는 뜻이다.
    assert "ci.yml" in _sole_reason(_goal_table("예", "`ci:quality`"))


def test_음성3b_조건부_표기의_잡_이름도_ci_yml_에_실재해야_한다() -> None:
    """조건부 형식이 잡 실재 검사를 **우회하지 않는다.** 우회하면 괄호 한 쌍이
    "없는 잡을 적어도 되는" 권한이 된다.
    """
    reason = _sole_reason(_goal_table("예", f"`ci:llm-lane(조건:{_FAKE_TRACKED_PATH})`"))
    assert "ci.yml" in reason
    assert "llm-lane" in reason
    # `충족 = 아니오` 인 행도 똑같이 걸린다 — 이 규칙은 충족 값과 무관하다.
    nightly = _goal_table("아니오", f"`ci:nightly(조건:{_FAKE_TRACKED_PATH})`")
    assert "ci.yml" in _sole_reason(nightly)
    # 실제 CI 잡 이름은 합성 컨텍스트에서 **통과하면 안 된다** — 통과한다면 컨텍스트
    # 치환이 먹지 않고 어딘가에서 진짜 ci.yml 을 읽고 있다는 뜻이다. `llm-lane` 은 실물
    # ci.yml 에 실재하는 잡이라 이 대조가 특히 잘 든다.
    assert "ci.yml" in _sole_reason(_goal_table("예", f"`ci:quality(조건:{_FAKE_TRACKED_PATH})`"))


def test_음성3c_조건_정본_경로가_추적_파일이_아니면_실패한다() -> None:
    """괄호 안이 `1회성:` 과 **같은 기준**을 받는다.

    이 검사가 없으면 조건부 형식은 새 자유 통과 카드다 — `ci:<실재하는 잡>(조건:나중에)`
    한 줄로 아무 행이나 닫을 수 있고, 잡 이름이 실재하므로 규칙 4도 조용하다.
    """
    for bad in (
        "`ci:unit(조건:나중에)`",  # 산문
        "`ci:unit(조건:.)`",  # 저장소 루트
        "`ci:unit(조건:docs)`",  # 디렉터리
        "`ci:unit(조건:scratch.md)`",  # 미추적 파일
        "`ci:unit(조건:.github/llm-lane-paths.txt)`",  # 실물엔 있으나 합성 컨텍스트엔 없다
    ):
        reason = _sole_reason(_goal_table("예", bad))
        assert "git 이 추적하는" in reason, f"{bad} 이 통과했다: {reason}"
    # 잡 이름은 전부 실재하는 것을 썼다 — 그래야 위 실패가 조건 경로 때문임이 확정된다.
    assert "unit" in _FAKE_JOBS


def test_음성3d_깨진_조건부_표기는_단순_ci_로_읽히지_않는다() -> None:
    """**이번 형식 추가의 경계.** 잘린 조건부 표기가 `ci:<잡>` 단순형으로 읽히면 안 된다.

    경계가 보이려면 잡 이름이 합성 컨텍스트에 **실재해야** 한다 — 없는 잡을 쓰면 어느
    쪽으로 읽히든 "잡이 없다"로 걸려서 두 경로가 구분되지 않는다. 실재하는 잡을 쓰면
    단순형으로 오독되는 순간 위반 0건이 되어 **조용히 통과**하므로, 그 상태가 실패로
    드러난다.
    """
    assert "unit" in _FAKE_JOBS, "이 대조의 전제 — 잡 이름이 실재해야 오독이 침묵으로 드러난다"
    for broken in (
        "`ci:unit(`",  # 괄호가 열리기만 함
        f"`ci:unit(조건:{_FAKE_TRACKED_PATH}`",  # 괄호가 안 닫힘
        f"`ci:unit조건:{_FAKE_TRACKED_PATH})`",  # 여는 괄호가 없음
        "`ci:unit()`",  # 조건 자리가 통째로 빔
        "`ci:unit(조건:)`",  # 경로가 빔
        f"`ci:unit(조건: {_FAKE_TRACKED_PATH})`",  # 경로 앞에 공백
        f"`ci:unit (조건:{_FAKE_TRACKED_PATH})`",  # 잡 이름과 괄호 사이 공백
        f"`ci:unit(조건={_FAKE_TRACKED_PATH})`",  # 낱말이 `조건:` 이 아님
        f"`ci:(조건:{_FAKE_TRACKED_PATH})`",  # 잡 이름이 빔
    ):
        reason = _sole_reason(_goal_table("예", broken))
        assert "어휘 밖 표기" in reason, f"깨진 조건부 표기가 통과했다: {broken} → {reason}"

    # 그러면서 단순형 판정은 **그대로다** — 형식을 더하며 기존 규칙을 느슨하게 하지 않았다.
    assert _judge_markdown(_goal_table("예", "`ci:unit`")) == []
    assert "ci.yml" in _sole_reason(_goal_table("예", "`ci:golden`"))


def test_음성4_존재하지_않는_1회성_경로면_실패한다() -> None:
    reason = _sole_reason(_goal_table("예", "`1회성:docs/없는-산출물.md`"))
    assert "git 이 추적하는" in reason


def test_음성5_어휘_밖_표기는_실패한다() -> None:
    for outside in ("`가끔 돎`", "`ci`", "`결정:2026년 8월`", "`1회성:`", "`local:`"):
        reason = _sole_reason(_goal_table("예", outside))
        assert "어휘 밖 표기" in reason, f"{outside} 이 어휘 밖으로 잡히지 않았다: {reason}"


def test_음성6_실행_경로_열이_없으면_실패한다() -> None:
    markdown = (
        "## 열이 사라진 합성 표\n"
        "\n"
        "| 종료 조건 | 충족 | 근거 |\n"
        "|---|---|---|\n"
        "| 어떤 종료 조건 | 예 | 어떤 근거 |\n"
    )
    reason = _sole_reason(markdown)
    assert f"`{_REACH_HEADER}` 열이 없다" in reason
    # 게이트 표에서도 같아야 한다 — 표 모양이 달라도 규칙은 하나다.
    gate_markdown = "## 게이트 표\n\n| 게이트 | 상태 |\n|---|---|\n| 어떤 게이트 | 미실행 |\n"
    assert f"`{_REACH_HEADER}` 열이 없다" in _sole_reason(gate_markdown)


# --- 음성 대조 F1~F5 (독립 검증 레인이 뚫은 통과 경로) --------------------------
#
# 아래 다섯은 적대적 조작이 아니라 **평범한 문서 편집으로 도달**하던 통과 경로다.
# 각각 정확히 한 건씩 실패하는 것을 고정한다.


def test_음성F1_충족을_예_아니오로_읽을_수_없으면_실패한다() -> None:
    """`O`·`Y`·`완료`·`✅` 로 적으면 예전에는 규칙 3이 **침묵 건너뛰었다.**"""
    for unreadable in ("O", "Y", "완료", "✅", "-", "충족"):
        reason = _sole_reason(_goal_table(unreadable, "`안 돎`"))
        assert "읽을 수 없다" in reason, f"`{unreadable}` 이 조용히 통과했다: {reason}"


def test_음성F1_낱말_경계라_복합_표기는_읽히고_다른_낱말은_안_읽힌다() -> None:
    """양쪽 오독을 동시에 고정한다 — 복합 표기는 읽히고, `예`로 시작하는 딴 낱말은 안 읽힌다.

    접두 판정이던 시절의 버그가 근거다. `예정` 은 접두가 `예` 라 **충족으로 읽혔고**,
    뜻이 "아직"인 행에 규칙 3이 발동해 "충족 = 예인데 안 돎"이라고 **거짓 고발**했다.
    정상 문서를 고발하는 검사는 한계가 아니라 버그다.
    """
    # (가) 읽혀야 하는 것 — 완전 일치와, 이 문서의 관용인 복합 표기.
    assert met_verdict("예") is True
    assert met_verdict("아니오") is False
    assert met_verdict("아니오 — 1/11 생성") is False, (
        "역사 행 `아니오 — **1/11 생성**` 이 읽히지 않으면 정상 행이 위반으로 뒤집힌다."
    )
    assert met_verdict("예 — 부분") is True
    assert met_verdict("예(부분)") is True

    # (나) 읽히면 안 되는 것 — `예` 로 시작하지만 뜻이 다른 낱말들. 전부 이 문서의
    #      관용 어휘권 안에 있고, 특히 `예정` 은 뜻이 정반대다.
    for other_word in ("예정", "예외 — 범위 밖", "예상", "예비", "예외"):
        assert met_verdict(other_word) is None, (
            f"`{other_word}` 이 충족으로 읽혔다 — 접두 판정의 거짓 고발 버그가 되돌아왔다."
        )
    assert met_verdict("완료") is None

    # (다) 거짓 고발이 실제로 사라졌는가. `예정` + `안 돎` 은 규칙 3이 발동할 행이
    #      아니다 — 나와야 하는 위반은 "읽을 수 없다" 하나뿐이다.
    reason = _sole_reason(_goal_table("예정", "`안 돎`"))
    assert "읽을 수 없다" in reason, f"`예정` 이 거짓 고발됐다: {reason}"
    assert "충족 = 예" not in reason, f"`예정` 을 충족으로 읽고 고발했다: {reason}"

    # (라) 그러면서 `예 — 부분` 은 여전히 `예` 이므로 규칙 3이 **적용된다**(건너뛰지 않는다).
    assert "충족 = 예" in _sole_reason(_goal_table("예 — 부분", "`안 돎`"))
    # (마) 그리고 복합 `아니오` 행은 규칙 3을 건너뛴 채 통과한다 — 역사 행 그대로.
    assert _judge_markdown(_goal_table("아니오 — 1/11 생성", "`안 돎`")) == []


def test_음성F2_게이트_표의_실행_경로를_지워도_실패한다() -> None:
    """게이트 표엔 `충족` 열이 없어 규칙 3이 구조적으로 안 닿는다 — 규칙 2가 유일한 방어다."""
    for erased in (" ", "-"):
        reason = _sole_reason(_gate_table(erased))
        assert "비었다" in reason, f"게이트 표의 빈 실행 경로가 통과했다: {reason}"


def test_음성F3_local_이_산문_약속이면_실패한다() -> None:
    """`local:` 이 임의 문자열 자유 통과 카드가 되던 경로."""
    for prose in ("`local:언젠가 돌릴 예정`", "`local:아직 안 정함`", "`local:나중에 배선`"):
        reason = _sole_reason(_goal_table("예", prose))
        assert "실행 파일 이름 꼴이 아니" in reason, f"산문 약속이 통과했다: {prose} → {reason}"

    # 한계 — 첫 낱말만 본다. `local:TBD 예정` 처럼 첫 낱말이 ASCII 면 통과한다.
    # 모든 산문을 막으려면 명령 실재를 확인해야 하고, 그건 이 검사의 범위 밖이다.
    assert _judge_markdown(_goal_table("예", "`local:TBD 예정`")) == []

    # 러너 이름을 화이트리스트로 못 박지 않는다 — 정당한 미래 값이 막히면 안 된다.
    for runner in (
        "`local:uv run pytest tests/golden -m llm`",
        "`local:make verify`",
        "`local:npm run test -- --run`",
        "`local:./gradlew build --no-daemon`",
        "`local:.claude/skills/x/scripts/run.sh`",
    ):
        assert _judge_markdown(_goal_table("예", runner)) == [], f"정당한 러너가 막혔다: {runner}"


def test_음성F4_1회성이_디렉터리나_미추적_파일이면_실패한다() -> None:
    """`1회성:.` 로 어떤 행이든 닫을 수 있던 경로. 존재가 아니라 **git 추적**을 본다."""
    for not_a_tracked_file in ("`1회성:.`", "`1회성:docs`", "`1회성:scratch.md`"):
        reason = _sole_reason(_goal_table("예", not_a_tracked_file))
        assert "git 이 추적하는" in reason, f"{not_a_tracked_file} 이 통과했다: {reason}"


def test_음성F5_결정_날짜가_달력에_없으면_실패한다() -> None:
    """모양만 맞는 `결정:9999-99-99` 가 통과하던 경로."""
    for impossible in ("`결정:9999-99-99`", "`결정:2026-02-30`", "`결정:2026-13-01`"):
        reason = _sole_reason(_goal_table("예", impossible))
        assert "실제 달력 날짜가 아니" in reason, f"{impossible} 이 통과했다: {reason}"

    # 윤년은 통과해야 한다 — 날짜 검사를 정규식으로 흉내 내지 않았다는 증거다.
    assert _judge_markdown(_goal_table("예", "`결정:2024-02-29`")) == []
    assert _sole_reason(_goal_table("예", "`결정:2026-02-29`"))


# --- 음성 대조 I1~I6 / M1~M4 (정체성이 개수를 대체하고, 자리에 결속됐음을 보이는 자리) ---
#
# I 계열은 `EXPECTED_ROWS` 만 있던 시절 통과하던 조작이다. 총개수는 **순소실만** 막으므로,
# 지운 자리에 아무 행이나 채워 넣거나 제목을 갈아 끼우면 초록이었다.
#
# M 계열은 그 정체성 집합의 키가 **제목만**이던 시절 통과하던 조작이다. 제목만으로는
# 표를 가로질러 평평하게 모여, 행을 다른 Phase 표로 **옮겨도** 아무 축도 걸리지 않았다.

#: 합성 원장의 표 이름 둘. 실제 원장처럼 **서로 다른 Phase 표**를 흉내 낸다 — 표가 하나뿐인
#: 합성 입력으로는 "표 간 이동" 을 애초에 표현할 수 없어 M 계열이 성립하지 않는다.
_TABLE_A: Final = "합성 Phase 0"
_TABLE_B: Final = "합성 Phase 2"

type _SyntheticRow = tuple[str, str, str]
type _SyntheticTable = tuple[str, Sequence[_SyntheticRow]]

#: 합성 행. 실제 원장의 두 행 제목을 본떠 두는 이유는, 대리 지표 문제가 실제로 드러난
#: 자리가 「품질 합격선 …」 행이기 때문이다. B 표의 행은 `충족 = 아니오` 라 정체성 집합
#: 밖에 있다 — 그 행이 개수로만 지켜진다는 사실도 아래에서 함께 고정한다.
_ROWS_A: Final[tuple[_SyntheticRow, ...]] = (
    ("품질 합격선 **기제** 확정·승인<br>*직전 제목: 합격선 수치 확정·승인*", "예", "`ci:unit`"),
    ("`contracts/easy-doc-v1.yaml` 작성", "예", "`ci:lint`"),
)
_ROWS_B: Final[tuple[_SyntheticRow, ...]] = (("아직 안 닫힌 행", "아니오", "`미배선`"),)

#: 손대지 않은 합성 원장.
_NORMAL: Final[tuple[_SyntheticTable, ...]] = ((_TABLE_A, _ROWS_A), (_TABLE_B, _ROWS_B))

_IDENTITY_KEYS: Final[frozenset[tuple[str, str]]] = frozenset(
    {
        (_TABLE_A, "품질 합격선 기제 확정·승인"),
        (_TABLE_A, "`contracts/easy-doc-v1.yaml` 작성"),
    }
)
_IDENTITY_ROW_COUNT: Final = 3
_IDENTITY_TOKEN_COUNT: Final = 3


def _identity_markdown(tables: Sequence[_SyntheticTable]) -> str:
    """(표 이름, 행들) 여러 개짜리 합성 마크다운. 표 이름이 빈 문자열이면 제목 줄을 뺀다."""
    chunks: list[str] = []
    for caption, rows in tables:
        heading = f"## {caption}\n\n" if caption else ""
        header = f"| 종료 조건 | 충족 | {_REACH_HEADER} | 근거 |\n|---|---|---|---|\n"
        body = "".join(f"| {title} | {met} | {reach} | 근거 |\n" for title, met, reach in rows)
        chunks.append(heading + header + body)
    return "\n".join(chunks)


def _census(
    tables: Sequence[_SyntheticTable],
    *,
    keys: frozenset[tuple[str, str]] = _IDENTITY_KEYS,
    unresolved: frozenset[tuple[str, str]] | None = None,
) -> list[str]:
    """합성 표를 **실물과 같은 함수**로 판정한다 — 닮은 검사가 아니라 그 검사여야 한다.

    합성 표에는 `미해결 항목` 열이 없다. 그래서 기본 기대값은 빈 집합이고, R-10 축을
    재는 탐침만 값을 준다 — 다른 탐침이 이 축의 잡음에 걸리지 않게 한다.
    """
    return census_problems(
        select_target_tables(parse_tables(_identity_markdown(tables))),
        expected_rows=_IDENTITY_ROW_COUNT,
        expected_met_yes_keys=keys,
        expected_unresolved_keys=unresolved if unresolved is not None else frozenset(),
        expected_reach_tokens=_IDENTITY_TOKEN_COUNT,
    )


def _counts(tables: Sequence[_SyntheticTable]) -> tuple[int, int]:
    """(행 총수, 표기 총수). 이동 시나리오가 개수 축을 **안 건드렸다**는 주장을 눈에 보이게 둔다."""
    parsed = select_target_tables(parse_tables(_identity_markdown(tables)))
    rows = sum(len(table.rows) for table in parsed)
    tokens = 0
    for table in parsed:
        reach_index = table.headers.index(_REACH_HEADER)
        tokens += sum(
            len(split_reach_tokens(row[reach_index]))
            for row in table.rows
            if len(row) == len(table.headers)
        )
    return rows, tokens


def _title_only_keys(tables: Sequence[_SyntheticTable]) -> frozenset[str]:
    """**직전 버전의 키 산출**(제목만)을 그대로 재현한다.

    M1 에서 "이 편집은 직전 버전이라면 통과했다"를 주장으로 적지 않고 **보이기** 위한
    장치다. 산출 방식이 옛 코드와 같아야 하므로 `identity_key` 를 표와 무관하게 모은다.
    """
    parsed = select_target_tables(parse_tables(_identity_markdown(tables)))
    keys: list[str] = []
    for table in parsed:
        met_index = table.headers.index(_MET_HEADER)
        keys.extend(
            identity_key(row[0])
            for row in table.rows
            if len(row) == len(table.headers) and met_verdict(row[met_index]) is True
        )
    return frozenset(keys)


def test_정체성_대조군_손대지_않은_표는_통과한다() -> None:
    """대조군. 이게 실패하면 아래 전부가 "입력이 애초에 틀려서" 와 구분되지 않는다."""
    assert _census(_NORMAL) == []
    assert _counts(_NORMAL) == (_IDENTITY_ROW_COUNT, _IDENTITY_TOKEN_COUNT)


def test_정체성_I1_충족_예_행을_지우면_없어진_키가_지목된다() -> None:
    deleted: tuple[_SyntheticTable, ...] = ((_TABLE_A, _ROWS_A[1:]), (_TABLE_B, _ROWS_B))
    problems = _census(deleted)
    identity = [problem for problem in problems if _IDENTITY_MISMATCH_MARK in problem]
    assert len(identity) == 1, f"정체성 위반이 정확히 1건이어야 한다: {problems}"
    assert "없어진 행: ['합성 Phase 0 ▸ 품질 합격선 기제 확정·승인']" in identity[0]
    assert "새로 생긴 행: 없음" in identity[0]
    # 순소실은 규모 축도 함께 잡는다(행 2≠3 · 표기 2≠3). 정체성 축이 더하는 것은
    # **무엇이** 없어졌는지를 이름으로 말한다는 한 줄이고, 그 한 줄이 아래 I2 를 가능하게 한다.
    assert len(problems) == 3, problems


def test_정체성_I2_총개수를_유지한_치환은_정체성만_잡는다() -> None:
    """세 개수 축이 전부 그대로인데 원장의 주장이 갈린다.

    「품질 합격선 …」 행을 지우고 근거 없는 행을 하나 채워 넣은 편집이다. 행 3개,
    표기 3개, `충족 = 예` **2개** — 개수만 보는 검사는 전부 통과한다. 대리 지표로
    실물을 판정하면 이 편집이 초록으로 지나간다는 것이 이 자리의 요점이다.
    """
    swapped: tuple[_SyntheticTable, ...] = (
        (_TABLE_A, (("근거 없이 새로 넣은 행", "예", "`ci:e2e`"), _ROWS_A[1])),
        (_TABLE_B, _ROWS_B),
    )
    problems = _census(swapped)
    assert len(problems) == 1, f"개수 축이 걸렸다 — 총개수 유지 시나리오가 아니다: {problems}"
    assert _IDENTITY_MISMATCH_MARK in problems[0]
    # `충족 = 예` **개수까지 같다** — 옛 `EXPECTED_MET_YES` 였다면 통과했을 편집이다.
    assert "(기대 2개 / 실제 2개)" in problems[0], problems[0]
    assert "없어진 행: ['합성 Phase 0 ▸ 품질 합격선 기제 확정·승인']" in problems[0]
    assert "새로 생긴 행: ['합성 Phase 0 ▸ 근거 없이 새로 넣은 행']" in problems[0]


def test_정체성_I3_제목만_개명해도_없어진_키와_새_키가_둘_다_지목된다() -> None:
    renamed: tuple[_SyntheticTable, ...] = (
        (_TABLE_A, (("품질 합격선 **수치** 확정·승인<br>*꼬리말*", "예", "`ci:unit`"), _ROWS_A[1])),
        (_TABLE_B, _ROWS_B),
    )
    problems = _census(renamed)
    assert len(problems) == 1, f"개수 축이 걸렸다 — 개명 시나리오가 아니다: {problems}"
    assert "없어진 행: ['합성 Phase 0 ▸ 품질 합격선 기제 확정·승인']" in problems[0]
    assert "새로 생긴 행: ['합성 Phase 0 ▸ 품질 합격선 수치 확정·승인']" in problems[0]


def test_정체성_I4_표기만_달라진_제목과_표이름은_같은_자리로_읽힌다() -> None:
    """거짓 고발 쪽 경계. 강조가 떨어지고 꼬리말이 자라고 공백이 늘어도 같은 행·같은 표다.

    이쪽을 고정하지 않으면 문구를 다듬을 때마다 상수가 갈리고, 몇 번 겪은 다음 사람이
    규칙을 통째로 느슨하게 만든다. 정상 문서를 고발하는 검사는 한계가 아니라 버그다.
    **표 이름에도 같은 정규화가 걸린다**는 것을 함께 고정한다 — 두 축의 민감도가 갈리면
    "표는 못 알아보는데 행은 알아보는" 비대칭이 생긴다.
    """
    retouched: tuple[_SyntheticTable, ...] = (
        (
            "합성  **Phase 0**",
            (
                ("품질 합격선  기제   확정·승인<br />*완전히 다른 꼬리말*", "예", "`ci:unit`"),
                _ROWS_A[1],
            ),
        ),
        (_TABLE_B, _ROWS_B),
    )
    assert _census(retouched) == []


def test_정체성_I5_같은_표에서_앞_40자가_겹치는_두_행은_위반이다() -> None:
    """키가 뭉치면 그중 하나가 지워져도 집합이 그대로라 **삭제가 다시 숨는다.**"""
    shared_head = "앞부분이 똑같아서 정체성 키가 뭉쳐 버리는 아주 긴 종료 조건 제목이다 그래서"
    assert len(shared_head) >= _KEY_CLIP, "이 대조의 전제 — 앞머리가 클립 길이보다 길어야 한다"
    collided: tuple[_SyntheticTable, ...] = (
        (
            _TABLE_A,
            (
                (shared_head + " 갑", "예", "`ci:unit`"),
                (shared_head + " 을", "예", "`ci:lint`"),
            ),
        ),
        (_TABLE_B, _ROWS_B),
    )
    problems = _census(collided, keys=frozenset({(_TABLE_A, identity_key(shared_head))}))
    assert len(problems) == 1, problems
    assert "정체성 키가 겹치는" in problems[0]
    assert f"합성 Phase 0 {_PAIR_ARROW}" in problems[0]


def test_정체성_I6_다른_표의_같은_제목은_충돌이_아니다() -> None:
    """쌍으로 바꾸면서 **좁아진** 자리. 같은 제목이어도 표가 다르면 별개 행이다.

    이걸 고정해 두는 이유는 I5 의 충돌 검사가 표 간에도 발동하면 정상 문서를 고발하기
    때문이다 — 서로 다른 Phase 표에 같은 이름의 종료 조건이 있는 것은 이 문서에서
    자연스럽고, 그 둘은 쌍이 다르므로 한쪽이 지워지면 그대로 드러난다.
    """
    twinned: tuple[_SyntheticTable, ...] = (
        (_TABLE_A, (_ROWS_A[0],)),
        (_TABLE_B, (_ROWS_A[0], _ROWS_B[0])),
    )
    assert _counts(twinned) == (_IDENTITY_ROW_COUNT, _IDENTITY_TOKEN_COUNT)
    keys = frozenset(
        {
            (_TABLE_A, "품질 합격선 기제 확정·승인"),
            (_TABLE_B, "품질 합격선 기제 확정·승인"),
        }
    )
    assert _census(twinned, keys=keys) == []


def test_정체성_M1_충족_예_행을_다른_표로_옮기면_잡힌다() -> None:
    """**이번 수정의 핵심.** 세 개수 축과 **제목 집합까지** 전부 그대로인데 자리가 갈린다.

    「품질 합격선 …」 행을 A 표에서 B 표로 옮긴 편집이다. 행 3개, 표기 3개, `충족 = 예`
    2개, 그리고 **제목만으로 만든 키 집합도 동일** — 직전 버전(제목만 키)이라면 이 편집이
    초록으로 지나갔다. 아래에서 그 사실을 주장이 아니라 **실행으로** 보인다.

    이 문서에서는 행이 어느 Phase 표에 있는가가 판정의 일부다. Phase 0 종료 조건이
    Phase 2 표로 가면 Phase 0 의 종료 판정이 조용히 달라진다.
    """
    moved: tuple[_SyntheticTable, ...] = (
        (_TABLE_A, _ROWS_A[1:]),
        (_TABLE_B, (*_ROWS_B, _ROWS_A[0])),
    )

    # (가) 개수 축은 전부 보존된다.
    assert _counts(moved) == _counts(_NORMAL) == (_IDENTITY_ROW_COUNT, _IDENTITY_TOKEN_COUNT)
    # (나) 직전 버전의 키 집합(제목만)도 **그대로다** — 그 버전이라면 통과했을 편집이다.
    assert _title_only_keys(moved) == _title_only_keys(_NORMAL)

    # (다) 그런데 쌍으로 보면 자리가 갈린 것이 드러난다.
    problems = _census(moved)
    assert len(problems) == 1, f"개수 축이 걸렸다 — 이동 시나리오가 아니다: {problems}"
    assert _IDENTITY_MISMATCH_MARK in problems[0]
    assert "(기대 2개 / 실제 2개)" in problems[0], problems[0]
    assert "없어진 행: ['합성 Phase 0 ▸ 품질 합격선 기제 확정·승인']" in problems[0]
    assert "새로 생긴 행: ['합성 Phase 2 ▸ 품질 합격선 기제 확정·승인']" in problems[0]


def test_정체성_M2_표_이름_개명은_그_표의_행이_없어진_것으로_지목된다() -> None:
    """**옳은 동작이다.** 정체성을 자리에 결속했으므로 자리 이름이 바뀌면 자리가 바뀐 것이다.

    이 검사는 "표 이름만 고쳤다"와 "행들을 새 표로 통째 옮겼다"를 구분하지 못한다 —
    문서만 봐서는 둘 다 `(표, 제목)` 쌍이 갈린 것으로 보이기 때문이다. 구분하려면 표에
    이름과 별개인 안정 식별자를 심어야 하고, 그건 `00_progress.md` 를 이 검사 전용
    형식으로 바꾸는 일이다. 표 이름을 정당하게 고쳤다면 `EXPECTED_MET_YES_KEYS` 를 함께
    고치고, **그 diff 가 리뷰에 올라가는 것**이 이 결속의 값어치다(`_KEY_CLIP` 과 같은
    거래 — 브리틀함을 얼마간 사서 은폐를 막는다).
    """
    renamed: tuple[_SyntheticTable, ...] = (("합성 Phase 0 (개명)", _ROWS_A), (_TABLE_B, _ROWS_B))
    problems = _census(renamed)
    assert len(problems) == 1, f"개수 축이 걸렸다 — 개명 시나리오가 아니다: {problems}"
    assert _IDENTITY_MISMATCH_MARK in problems[0]
    # 그 표의 `충족 = 예` **두 행 모두**가 없어진 것으로, 새 이름 아래 둘이 생긴 것으로 나온다.
    assert "합성 Phase 0 ▸ 품질 합격선 기제 확정·승인" in problems[0]
    assert "합성 Phase 0 ▸ `contracts/easy-doc-v1.yaml` 작성" in problems[0]
    assert "합성 Phase 0 (개명) ▸ 품질 합격선 기제 확정·승인" in problems[0]
    assert "합성 Phase 0 (개명) ▸ `contracts/easy-doc-v1.yaml` 작성" in problems[0]


def test_정체성_M3_두_대상_표의_이름이_같으면_위반이다() -> None:
    """자리가 다시 뭉치는 경로. **키 집합이 기대와 맞아도** 이 상태 자체가 위반이다.

    같은 이름이면 두 표의 행이 한 자리로 모여 M1 의 이동이 도로 숨는다 — I5 에서 행 키
    충돌을 위반으로 잡은 것과 같은 이유다.
    """
    duplicated: tuple[_SyntheticTable, ...] = ((_TABLE_A, _ROWS_A), (_TABLE_A, _ROWS_B))
    problems = _census(duplicated)
    assert len(problems) == 1, f"다른 축이 걸렸다 — caption 중복 단독 시나리오가 아니다: {problems}"
    assert _DUPLICATE_CAPTION_MARK in problems[0]
    assert _TABLE_A in problems[0]


def test_정체성_M4_표_이름이_비면_위반이다() -> None:
    """M3 와 같은 이유의 다른 입구. 이름이 없으면 자리가 성립하지 않는다.

    여기서도 **키 집합은 기대와 맞다**(빈 이름으로 기대를 맞춰 넣었다). 그래도 위반이다 —
    빈 이름은 표가 하나 더 생기는 순간 곧바로 M3 상태가 되기 때문이다.
    """
    nameless: tuple[_SyntheticTable, ...] = (("", (*_ROWS_A, *_ROWS_B)),)
    keys = frozenset(
        {
            ("", "품질 합격선 기제 확정·승인"),
            ("", "`contracts/easy-doc-v1.yaml` 작성"),
        }
    )
    problems = _census(nameless, keys=keys)
    assert len(problems) == 1, f"다른 축이 걸렸다 — 빈 caption 단독 시나리오가 아니다: {problems}"
    assert _EMPTY_CAPTION_MARK in problems[0]


def test_정체성_키는_표기를_걷어_내고_앞부분만_남긴다() -> None:
    """키 산출 함수를 직접 본다 — 표를 거쳐서만 검사하면 어느 정규화가 살아 있는지 흐려진다."""
    # 강조 표시(굵게·기울임)는 걷어 낸다. `_normalize_cell` 이 `**` 만 걷으므로 `*` 도 여기서 본다.
    assert identity_key("**품질 합격선** 확정") == "품질 합격선 확정"
    assert identity_key("*품질 합격선* 확정") == "품질 합격선 확정"
    # `<br>` 이후 꼬리말은 버린다 — 이 문서의 개정 주석이 거기 자란다. 변종 표기도 같다.
    assert identity_key("품질 합격선 확정<br>*직전 제목: 다른 이름*") == "품질 합격선 확정"
    assert identity_key("품질 합격선 확정<br/>꼬리말") == "품질 합격선 확정"
    assert identity_key("품질 합격선 확정<br />꼬리말") == "품질 합격선 확정"
    assert identity_key("품질 합격선 확정<BR>꼬리말") == "품질 합격선 확정"
    # 연속 공백은 하나로 줄이고 양끝은 턴다.
    assert identity_key("  품질   합격선\t확정  ") == "품질 합격선 확정"
    # 앞 `_KEY_CLIP` 자로 자른다. 그래서 그 뒤만 다른 두 제목은 같은 키가 되고,
    # 그 상태를 `census_problems` 가 키 충돌로 잡는다(I5).
    assert identity_key("가" * 60) == "가" * _KEY_CLIP
    assert identity_key("나" * _KEY_CLIP + "갑") == identity_key("나" * _KEY_CLIP + "을")


def test_표_키는_행_제목과_같은_정규화를_쓴다() -> None:
    """두 축의 민감도가 갈리지 않았음을 직접 고정한다.

    갈리면 표 이름의 사소한 표기 변화가 `충족 = 예` 행 전부를 없어진 것으로 만들거나,
    반대로 표 이름이 실질적으로 바뀌었는데 같은 자리로 읽힌다.
    """
    assert table_key("합성 **Phase 0**") == identity_key("합성 **Phase 0**")
    assert table_key("Phase 0 — 범위·계약 동결") == "Phase 0 — 범위·계약 동결"
    assert table_key("  Phase 0  —  범위·계약 동결 ") == "Phase 0 — 범위·계약 동결"
    assert table_key("가" * 60) == "가" * _KEY_CLIP
    assert table_key("") == ""
    # 쌍은 두 정규화의 곱이다.
    assert identity_pair("합성 **Phase 0**", "**품질** 합격선<br>꼬리말") == (
        "합성 Phase 0",
        "품질 합격선",
    )
    assert _render_pair(("합성 Phase 0", "품질 합격선")) == "합성 Phase 0 ▸ 품질 합격선"


def test_git_추적_목록은_NUL_구분으로_읽는다() -> None:
    """한글 경로가 이 저장소에 실재한다 — 기본 출력은 그것을 escape 해 대조가 빗나간다."""
    tracked = read_tracked_files(_REPO_ROOT)
    assert "tests/test_harness_scope_reach.py" in tracked, (
        "이 테스트 파일이 git 추적 목록에 없다 — `git add` 를 하지 않았다면 "
        "이 검사의 CI 도달은 0이다."
    )
    assert not any(name.startswith('"') for name in tracked), (
        "따옴표로 감싸인 경로가 있다 — `-z` 가 빠져 한글 경로가 escape 됐다."
    )


def test_ci_잡_이름은_YAML_파싱으로_읽는다() -> None:
    """규칙 3의 근거가 grep 이 아님을 고정한다. 주석 속 문자열은 잡이 아니다."""
    workflow = (
        "name: CI\non:\n  push:\njobs:\n  quality:\n    steps: []\n  kotlin:\n    steps: []\n"
    )
    assert read_ci_job_names(workflow) == frozenset({"quality", "kotlin"})

    commented = (
        "name: CI\njobs:\n  quality:\n    # kotlin: 이건 주석이지 잡이 아니다\n    steps: []\n"
    )
    assert read_ci_job_names(commented) == frozenset({"quality"})

    for broken in ("[]\n", "name: CI\n", "name: CI\njobs: {}\n"):
        with pytest.raises(AssertionError):
            read_ci_job_names(broken)


# ── R-10 음성 대조 — 미해결 항목이 조용히 비워지는가 ─────────────────────────────────
#
# 다른 축은 전부 보존된다: 행 총수도, `충족 = 예` 정체성도, 실행 경로 표기 수도. 칸 하나를
# 비우는 편집이라 diff 도 작다. **이 축이 없으면 어디에도 안 걸린다.**


def _blank_one_unresolved_cell(markdown: str) -> tuple[str, str]:
    """실물에서 `미해결 항목` 칸 하나를 비운 사본과, 비운 행의 제목."""
    lines = markdown.splitlines()
    for index, line in enumerate(lines):
        if not line.startswith("|") or _UNRESOLVED_HEADER in line:
            continue
        cells = _split_row(line)
        if len(cells) < 6 or _is_separator_row(cells):
            continue
        # 대상 표의 열 구성(종료 조건 … 미해결 항목 … blocked-by … 갱신 주체)에서
        # 미해결 항목은 뒤에서 세 번째다. 열 이름으로 찾지 않는 이유는 이 함수가
        # **한 줄만** 보기 때문이고, 잘못 고르면 아래 단언이 그것을 알려 준다.
        target = len(cells) - 3
        if cells[target].strip() in _UNRESOLVED_EMPTY:
            continue
        blanked = list(cells)
        blanked[target] = " - "
        lines[index] = "|" + "|".join(blanked) + "|"
        return "\n".join(lines), cells[0].strip()
    raise AssertionError("비울 미해결 항목 칸을 찾지 못했다 — 문서 구조가 바뀌었다")


def test_R10_미해결_항목을_비우면_잡힌다() -> None:
    """**칸만 비우는 편집은 다른 어떤 축에도 안 걸린다.** 그것이 이 축의 존재 이유다."""
    original = _PROGRESS_PATH.read_text(encoding="utf-8")
    tampered, title = _blank_one_unresolved_cell(original)
    assert tampered != original, "변조가 일어나지 않았다"

    tables = select_target_tables(parse_tables(tampered))
    problems = census_problems(
        tables,
        expected_rows=EXPECTED_ROWS,
        expected_met_yes_keys=EXPECTED_MET_YES_KEYS,
        expected_unresolved_keys=EXPECTED_UNRESOLVED_KEYS,
        expected_reach_tokens=EXPECTED_REACH_TOKENS,
    )

    assert any(_UNRESOLVED_HEADER in problem for problem in problems), (
        f"`{title}` 의 미해결 항목을 비웠는데 아무 축도 걸리지 않았다"
    )
    # 다른 축은 그대로여야 한다 — 그래야 "이 축이 잡았다"가 성립한다.
    assert not any(problem for problem in problems if _UNRESOLVED_HEADER not in problem), (
        f"다른 축이 함께 걸렸다 — 이 탐침이 무엇을 재는지 흐려진다: {problems}"
    )


def test_R10_실물에서는_통과한다() -> None:
    """오경보가 없어야 한다. 기대 집합이 실물과 어긋나면 여기서 먼저 빨개진다."""
    tables = select_target_tables(parse_tables(_PROGRESS_PATH.read_text(encoding="utf-8")))
    problems = census_problems(
        tables,
        expected_rows=EXPECTED_ROWS,
        expected_met_yes_keys=EXPECTED_MET_YES_KEYS,
        expected_unresolved_keys=EXPECTED_UNRESOLVED_KEYS,
        expected_reach_tokens=EXPECTED_REACH_TOKENS,
    )

    assert not problems, f"실물이 기대값과 어긋난다: {problems}"
