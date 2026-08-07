import { Link, useLocation, useParams } from 'react-router-dom'

import { ReviewEditor } from '../components/ReviewEditor'
import { failureMessage } from '../conversion/failureMessages'
import { useConversionPolling } from '../conversion/useConversionPolling'
import { HOME_PATH, type SourceTextState } from '../routes/paths'

/** 진행 중 상태별 안내. 사용자가 무엇을 기다리는지 알 수 있게 문구를 가른다. */
const PROGRESS_TEXT: Record<'pending' | 'processing', string> = {
  pending: '변환을 기다리고 있습니다…',
  processing: '쉬운 글로 바꾸고 있습니다…',
}

/**
 * 변환 화면.
 *
 * 접수 직후에는 결과가 없으므로 상태를 물어보며 기다리다가(폴링), 끝나면 같은
 * 주소에서 검수 에디터로 바뀐다. 주소가 변환 하나를 가리키므로 기록에서 다시 들어와도
 * 같은 화면이 열린다.
 */
export function ConversionPage() {
  const { conversionId } = useParams<'conversionId'>()
  const location = useLocation()
  // 붙여넣기로 올렸다면 업로드 화면이 원문을 함께 넘겨준다(routes/paths.ts 참고).
  const sourceText = (location.state as SourceTextState | null)?.sourceText ?? null

  // 라우트 패턴이 항상 값을 채우지만 타입은 undefined를 허용한다.
  const polling = useConversionPolling(conversionId ?? '')
  const { conversion, error, timedOut } = polling

  if (conversion !== null && conversion.status === 'done') {
    return <ReviewEditor conversion={conversion} sourceText={sourceText} />
  }

  if (conversion !== null && conversion.status === 'failed') {
    const failure = failureMessage(conversion.failure_code)
    return (
      <section aria-labelledby="conversion-heading">
        <h2 id="conversion-heading">변환하지 못했습니다</h2>
        <p className="form-error" role="alert">
          {failure.reason} {failure.advice}
        </p>
        <p>
          <Link to={HOME_PATH}>{failure.retryable ? '문서 다시 올리기' : '다른 문서 올리기'}</Link>
        </p>
      </section>
    )
  }

  return (
    <section aria-labelledby="conversion-heading">
      <h2 id="conversion-heading">변환 중</h2>
      {timedOut ? (
        <p className="form-error" role="alert">
          변환이 예상보다 오래 걸리고 있습니다. 잠시 후 변환 기록에서 결과를 다시 확인해 주세요.
        </p>
      ) : (
        <p className="conversion-progress" role="status">
          {/* 회전 표시는 장식이라 낭독기에서 숨긴다 — 상태는 옆 문구가 알린다. */}
          <span className="spinner" aria-hidden="true" />
          {conversion === null
            ? '변환 상태를 확인하고 있습니다…'
            : PROGRESS_TEXT[conversion.status === 'processing' ? 'processing' : 'pending']}
        </p>
      )}
      {error !== null && (
        <p className="field-error" role="status">
          {error}
        </p>
      )}
      <p className="field-hint">이 화면을 열어 두면 변환이 끝나는 대로 결과가 나타납니다.</p>
    </section>
  )
}
