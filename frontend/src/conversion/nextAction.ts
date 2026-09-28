/**
 * 규칙 기반 「다음 할 일」.
 *
 * 개인화는 추천 목록이 아니다 — **서버 상태로 확실히 판단할 수 있는 행동 한 개**만
 * 고른다. 그래서 이 모듈은 새 API도, 추천 모델도, LLM 호출도 쓰지 않고 이미 받은
 * `GET /documents` 응답만 읽는다.
 *
 * 컴포넌트 밖의 순수 함수인 이유는 규칙이 조건 조합이라서다. 다섯 조건과 그 우선순위를
 * 화면 렌더로 확인하려면 조합마다 화면을 그려야 하고, 그러면 정작 규칙이 무엇인지
 * 테스트에서 읽히지 않는다. 여기서는 입력(문서 목록)과 출력(제안 한 건)만 있다.
 *
 * 근거로 쓰는 값은 현재 작업 공간으로 좁힌 서버 상태와 검수 여부, 최근 문서뿐이다.
 * 성별·나이·위치·브라우징 기록·추론한 관심사는 쓰지
 * 않으며, 추천 이유를 추론하거나 감정적 표현을 붙이지도 않는다.
 */

import type { DocumentListItem } from '../api/types'

/** 제안의 종류. 화면이 아이콘·행동 경로를 고를 때 쓰는 식별자다. */
export type NextActionKind = 'review' | 'inProgress' | 'failed' | 'reviewed' | 'newConversion'

/** 화면에 보여줄 제안 한 건. */
export interface NextAction {
  kind: NextActionKind
  /** 제안 문구. */
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

/** 변환 기록과 같은 완료 기준: 검수 저장 또는 의견 제출. 누락된 시각은 미완료다. */
function isReviewed(item: DocumentListItem): boolean {
  return typeof item.reviewed_at === 'string' || typeof item.feedback_submitted_at === 'string'
}

interface Rule {
  kind: NextActionKind
  message: string
  actionLabel: string
  matches: (item: OpenableDocument) => boolean
}

/**
 * 제안 규칙을 우선순위 순서로 옮긴 것. 배열 순서가 곧 우선순위다.
 *
 * 순서는 `미검수 완료 문서` → `진행 중` → `실패` → `새 변환`이다.
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
    matches: (item) => item.status === 'done' && !isReviewed(item),
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
    message: '검수한 내용을 파일로 내려받을 수 있습니다',
    actionLabel: '문서 열기',
    matches: (item) => item.status === 'done' && isReviewed(item),
  },
]

/**
 * 열 문서가 하나도 없을 때의 제안.
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
 * 조회가 실패한 상태이므로 서버가 모르는 상태를 추측하지 않는다.
 *
 * 같은 조건이 여러 줄에서 맞으면 목록 순서상 첫 줄을 고른다. `GET /documents`는 최신순으로
 * 내려주므로 그 줄이 가장 최근 문서다.
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
