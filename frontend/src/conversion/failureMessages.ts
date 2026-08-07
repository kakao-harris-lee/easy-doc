/**
 * 실패 코드 → 사용자 문구.
 *
 * 백엔드는 실패한 변환에 `failure_code`(예외 클래스명 또는 그와 같은 네임스페이스의
 * 짧은 코드)만 남긴다 — 예외 메시지에는 문서 본문이 섞일 수 있어 응답에 싣지 않기
 * 때문이다(app/workers/tasks.py). 그래서 사람이 읽을 문구는 여기서 만든다.
 *
 * 코드 출처: app/exceptions.py(LLM* 계열), app/workers/tasks.py의
 * PROVIDER_UNAVAILABLE_CODE, app/services/documents.py의 ENQUEUE_FAILURE_CODE.
 */

/** 사용자에게 보여줄 실패 안내. 원인과 다음 행동을 함께 준다. */
export interface FailureMessage {
  /** 무엇이 잘못됐는지. */
  reason: string
  /** 사용자가 지금 할 수 있는 일. */
  advice: string
  /** 같은 문서를 그대로 다시 시도하면 될 만한 실패인지 (버튼 노출 판단). */
  retryable: boolean
}

const MESSAGES: Record<string, FailureMessage> = {
  LLMTruncatedError: {
    reason: '문서가 길어 변환이 도중에 잘렸습니다.',
    advice: '문서를 더 짧게 나눠 다시 올려 주세요.',
    retryable: false,
  },
  LLMEmptyResultError: {
    reason: '변환 결과가 비어 있습니다.',
    advice: '잠시 후 다시 시도해 주세요. 반복되면 원문에 본문이 충분한지 확인해 주세요.',
    retryable: true,
  },
  LLMProviderError: {
    reason: '변환 서비스와 통신하지 못했습니다.',
    advice: '잠시 후 다시 시도해 주세요.',
    retryable: true,
  },
  ProviderUnavailable: {
    reason: '변환 서비스 설정이 아직 완료되지 않았습니다.',
    advice: '관리자에게 문의해 주세요. 다시 시도해도 같은 결과가 나옵니다.',
    retryable: false,
  },
  EnqueueFailed: {
    reason: '변환 작업을 등록하지 못했습니다.',
    advice: '잠시 후 문서를 다시 올려 주세요.',
    retryable: true,
  },
}

/** 알 수 없는 코드에 쓰는 문구. 코드 자체는 보여주지 않는다(사용자에게 의미가 없다). */
const UNKNOWN: FailureMessage = {
  reason: '변환에 실패했습니다.',
  advice: '잠시 후 다시 시도해 주세요. 반복되면 관리자에게 문의해 주세요.',
  retryable: true,
}

/** 실패 코드에 맞는 안내를 돌려준다. 모르는 코드는 일반 안내로 받는다. */
export function failureMessage(code: string | null): FailureMessage {
  if (code === null) {
    return UNKNOWN
  }
  return MESSAGES[code] ?? UNKNOWN
}
