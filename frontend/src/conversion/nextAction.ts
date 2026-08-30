/**
 * 규칙 기반 「다음 할 일」(DESIGN.md §7).
 *
 * 개인화는 추천 목록이 아니다 — **서버 상태로 확실히 판단할 수 있는 행동 한 개**만
 * 고른다. 그래서 이 모듈은 새 API도, 추천 모델도, LLM 호출도 쓰지 않고 이미 받은
 * `GET /documents` 응답만 읽는다(§3 표: "별도 추천 모델이나 유료 LLM 호출을 추가하지
 * 않는다").
 *
 * 컴포넌트 밖의 순수 함수인 이유는 규칙이 조건 조합이라서다. 다섯 조건과 그 우선순위를
 * 화면 렌더로 확인하려면 조합마다 화면을 그려야 하고, 그러면 정작 규칙이 무엇인지
 * 테스트에서 읽히지 않는다. 여기서는 입력(문서 목록)과 출력(제안 한 건)만 있다.
 *
 * 근거로 쓰는 값은 §3의 개인화 우선순위 안에 있는 것뿐이다 — 현재 작업 공간으로 좁힌
 * 서버 상태, 검수 여부, 최근 문서. 성별·나이·위치·브라우징 기록·추론한 관심사는 쓰지
 * 않으며, 추천 이유를 추론하거나 감정적 표현을 붙이지도 않는다(§7 마지막 문단).
 */

import type { DocumentListItem } from '../api/types'

/** 제안의 종류. 화면이 아이콘·행동 경로를 고를 때 쓰는 식별자다. */
export type NextActionKind = 'review' | 'inProgress' | 'failed' | 'reviewed' | 'newConversion'

/** 화면에 보여줄 제안 한 건. */
export interface NextAction {
  kind: NextActionKind
  /** 제안 문구(§7 표). */
  message: string
  /** 행동 라벨. 낭독기가 읽는 이름이므로 무엇이 열리는지 말한다. */
  actionLabel: string
  /** 열 변환. `newConversion`은 열 변환이 없으므로 null이다. */
  conversionId: string | null
  /** 어느 문서에 대한 제안인지. `newConversion`은 대상 문서가 없어 null이다. */
  documentTitle: string | null
}

/** 열 변환이 있는 문서. `conversion_id`가 null인 줄은 행동을 만들 수 없다. */
type OpenableDocument = DocumentListItem & { conversion_id: string }

function isOpenable(item: DocumentListItem): item is OpenableDocument {
  return item.conversion_id !== null
}

interface Rule {
  kind: NextActionKind
  message: string
  actionLabel: string
  matches: (item: OpenableDocument) => boolean
}

/**
 * §7 표를 우선순위 순서로 옮긴 것. 배열 순서가 곧 우선순위다.
 *
 * §7 마지막 문단이 정한 순서는 `미검수 완료 문서` → `진행 중` → `실패` → `새 변환`이다.
 * `검수 저장됨`은 그 문장에 없는데, 이유는 그 상태가 "지금 할 일이 남아 있지 않다"에
 * 해당하기 때문이다 — 그래서 앞의 셋 중 하나라도 맞으면 밀리고, 아무것도 맞지 않을 때만
 * 나온다. `새 변환`은 열 문서가 하나도 없을 때의 마지막 자리이므로 이 표에 넣지 않고
 * 아래에서 fallback으로 둔다.
 */
const RULES: readonly Rule[] = [
  {
    kind: 'review',
    message: '쉬운 글 초안을 검수해 주세요',
    actionLabel: '검수 열기',
    // 「done이고 미검수」다. 변환이 끝났다는 사실만으로는 부족하고, 검수본이 저장되지
    // 않았다는 것까지 맞아야 한다 — reviewed_at이 null이면 아직 AI 초안 그대로다.
    matches: (item) => item.status === 'done' && item.reviewed_at === null,
  },
  {
    kind: 'inProgress',
    message: '변환 중인 문서를 확인하세요',
    actionLabel: '변환 열기',
    matches: (item) => item.status === 'pending' || item.status === 'processing',
  },
  {
    kind: 'failed',
    message: '원문은 그대로 두고 다시 시도해 보세요',
    actionLabel: '실패 상세 열기',
    matches: (item) => item.status === 'failed',
  },
  {
    kind: 'reviewed',
    /*
     * §7 표의 문구는 「원본 형식으로 내려받을 수 있습니다」이지만 그대로 쓰지 않는다.
     *
     * 원본 형식 내보내기는 §13의 4단계이고 아직 구현되지 않았다 — 현재 계약의
     * `ExportFormat`은 `docx|txt|hwpx`이고 **클라이언트가 형식을 고르며**,
     * `ConversionResponse`에는 원본 형식조차 실리지 않는다. 그 문구를 지금 화면에 두면
     * §2가 금지한 "미구현 기능을 현재 기능처럼 노출"이 되고, 사용자는 DOCX로 올린 문서를
     * 열었다가 형식을 직접 고르라는 화면을 만난다.
     *
     * 그래서 오늘 실제로 되는 일만 말한다: 검수본이 담긴 파일을 내려받을 수 있다.
     * 4단계가 끝나 입력 형식이 출력 형식을 결정하게 되면 §7의 원래 문구로 되돌린다.
     */
    message: '검수한 내용을 파일로 내려받을 수 있습니다',
    actionLabel: '문서 열기',
    matches: (item) => item.status === 'done' && item.reviewed_at !== null,
  },
]

/**
 * 열 문서가 하나도 없을 때의 제안(§7 표의 「문서 없음」).
 *
 * 문서 목록이 비었을 때뿐 아니라, 모든 줄의 `conversion_id`가 null이라 열 변환이 하나도
 * 없을 때도 여기로 온다 — 어느 쪽이든 사용자가 이어서 할 수 있는 작업이 없다.
 */
const NEW_CONVERSION: NextAction = {
  kind: 'newConversion',
  message: '첫 문서를 쉬운 글로 바꿔 보세요',
  actionLabel: '새 변환',
  conversionId: null,
  documentTitle: null,
}

/**
 * 지금 가장 중요한 행동 한 개를 고른다.
 *
 * `documents`가 null이면 **아무것도 제안하지 않는다**(null 반환). 목록을 아직 못 받았거나
 * 조회가 실패한 상태이며, §6.2가 이 제안을 "기존 문서 목록 응답에 완료 상태와 검수 여부가
 * 확인될 때만" 보이라고 했기 때문이다. 서버가 모르는 상태를 추측하지 않는다(§3).
 *
 * 같은 조건이 여러 줄에서 맞으면 목록 순서상 첫 줄을 고른다. `GET /documents`는 최신순으로
 * 내려주므로 그 줄이 가장 최근 문서다(§3 개인화 우선순위 5).
 */
export function chooseNextAction(documents: readonly DocumentListItem[] | null): NextAction | null {
  if (documents === null) {
    return null
  }
  const openable = documents.filter(isOpenable)
  for (const rule of RULES) {
    const found = openable.find(rule.matches)
    if (found !== undefined) {
      return {
        kind: rule.kind,
        message: rule.message,
        actionLabel: rule.actionLabel,
        conversionId: found.conversion_id,
        documentTitle: found.title,
      }
    }
  }
  return NEW_CONVERSION
}
