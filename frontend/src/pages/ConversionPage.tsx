import { Link, useLocation, useParams } from 'react-router-dom'
import { CircleAlert, Clock, FilePlus2, History, LoaderCircle } from 'lucide-react'

import type { ConversionResponse } from '../api/types'
import { ConversionStages, type StageStatus } from '../components/ConversionStages'
import { PageHeader } from '../components/PageHeader'
import { ResultSkeleton } from '../components/ResultSkeleton'
import { ReviewEditor } from '../components/ReviewEditor'
import { failureMessage } from '../conversion/failureMessages'
import { useConversionPolling } from '../conversion/useConversionPolling'
import { HISTORY_PATH, HOME_PATH, type SourceTextState } from '../routes/paths'
import { useWorkspace } from '../workspace/context'

/** 진행 중 상태별 안내. 사용자가 무엇을 기다리는지 알 수 있게 문구를 가른다. */
const PROGRESS_TEXT: Record<'pending' | 'processing', string> = {
  pending: '변환을 기다리고 있습니다…',
  processing: '쉬운 글로 바꾸고 있습니다…',
}

/**
 * 단계 표시에 넘길 상태.
 *
 * 이 지점까지 온 변환은 done·failed가 아니지만(위에서 먼저 돌려보낸다) 타입은 그것을
 * 모르므로, 진행 중 두 값으로 좁혀 넘긴다.
 */
function stageStatus(conversion: ConversionResponse | null): StageStatus {
  if (conversion === null) {
    return null
  }
  return conversion.status === 'processing' ? 'processing' : 'pending'
}

/** 카드 공통 모양. 같은 작업군이라 그림자는 §8.3 상한 하나만 쓴다. */
const CARD_CLASS = 'rounded-[16px] border border-border bg-card p-6 shadow-card sm:p-8'

/** 보조 행동 링크(기록으로 나가기). 대표 행동과 구별되게 테두리형으로 둔다. */
const QUIET_LINK_CLASS =
  'inline-flex h-11 items-center justify-center gap-2 rounded-md border border-input bg-card px-4 text-[15px] font-semibold text-foreground no-underline transition-colors hover:bg-secondary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring'

const ACTION_LINK_CLASS =
  'inline-flex h-11 items-center justify-center gap-2 rounded-md bg-primary px-4 text-[15px] font-semibold text-primary-foreground no-underline transition-colors hover:bg-primary-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring'

/**
 * 변환 화면.
 *
 * 접수 직후에는 결과가 없으므로 상태를 물어보며 기다리다가(폴링), 끝나면 같은
 * 주소에서 검수 에디터로 바뀐다. 주소가 변환 하나를 가리키므로 기록에서 다시 들어와도
 * 같은 화면이 열린다.
 *
 * 기다리는 동안 보여줄 것은 §6.3이 정한다: 단계 표시, 결과형 스켈레톤, 맥락(작업 공간),
 * 이탈해도 된다는 안내. 단계 표시가 "아는 만큼만" 말하는 이유는
 * `ConversionStages`의 주석에 적혀 있다.
 *
 * 문서 제목은 표시하지 못한다 — `ConversionResponse`에 제목이 없고(계약
 * `contracts/easy-doc-v1.yaml`), 제목을 가진 것은 문서 목록(`DocumentListItem`)뿐이다.
 * 화면 하나를 위해 목록을 따로 불러오거나 계약에 필드를 만들지 않고, 지금 확인할 수 있는
 * 맥락(작업 공간)만 보여준다.
 */
export function ConversionPage() {
  const { conversionId } = useParams<'conversionId'>()
  const location = useLocation()
  // 붙여넣기로 올렸다면 업로드 화면이 원문을 함께 넘겨준다(routes/paths.ts 참고).
  const sourceText = (location.state as SourceTextState | null)?.sourceText ?? null

  // 지금 고른 작업 공간. 변환 응답은 작업 공간을 싣지 않으므로 "이 문서의 작업 공간"이
  // 아니라 "지금 보고 있는 작업 공간"으로만 말한다.
  const { workspaces, currentId } = useWorkspace()
  const workspaceName = workspaces.find((item) => item.id === currentId)?.name ?? null
  const context = workspaceName === null ? '쉬운 글 변환' : `‘${workspaceName}’ · 쉬운 글 변환`

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
        <PageHeader
          context={context}
          title="변환하지 못했습니다"
          description="무엇이 잘못됐는지와 지금 할 수 있는 일을 아래에 정리했습니다."
          titleId="conversion-heading"
        />
        {/* §6.3: 오류 코드가 아니라 원인 → 보존된 항목 → 다음 행동 순서로 보여준다.
            failure_code는 사용자에게 의미가 없어 화면에 싣지 않는다(failureMessages.ts). */}
        <div className={`${CARD_CLASS} flex flex-col gap-6`}>
          <div className="flex items-start gap-3">
            <CircleAlert className="mt-0.5 size-[18px] shrink-0 text-danger" aria-hidden="true" />
            {/* 실패는 이 화면의 유일한 alert다 — 같은 사실을 두 번 알리지 않는다(§11). */}
            <p className="m-0 font-semibold text-danger" role="alert">
              {failure.reason}
            </p>
          </div>

          <div className="rounded-[12px] bg-muted p-4">
            <h2 className="m-0 text-[15px] font-bold">남아 있는 것</h2>
            {/* 원문이 서버에 남아 있는지는 이 화면이 확인할 수 없다 — 조회 응답에 원문이
                없고(routes/paths.ts), 계약에도 원문을 돌려주는 경로가 없다. 그래서 §9의
                예시 문구 `원문은 남아 있습니다.`를 그대로 쓰지 않고, 실제로 확인할 수 있는
                사실(이 변환이 기록에 남아 있다는 것)만 말한다. */}
            <p className="m-0 mt-1 text-sm leading-[22px] text-muted-foreground">
              이 변환은 변환 기록에 남아 있습니다. 화면을 닫아도 기록에서 다시 열 수 있습니다.
            </p>
          </div>

          <div>
            <h2 className="m-0 text-[15px] font-bold">다음에 할 일</h2>
            <p className="m-0 mt-1 text-sm leading-[22px] text-muted-foreground">
              {failure.advice}
            </p>
            <div className="mt-4 flex flex-wrap gap-2">
              <Link className={ACTION_LINK_CLASS} to={HOME_PATH}>
                <FilePlus2 className="size-[18px]" aria-hidden="true" />
                {failure.retryable ? '문서 다시 올리기' : '다른 문서 올리기'}
              </Link>
              <Link className={QUIET_LINK_CLASS} to={HISTORY_PATH}>
                <History className="size-[18px]" aria-hidden="true" />
                변환 기록 보기
              </Link>
            </div>
          </div>
        </div>
      </section>
    )
  }

  return (
    <section aria-labelledby="conversion-heading">
      <PageHeader
        context={context}
        title="쉬운 글로 바꾸는 중"
        description="변환이 끝나면 이 화면이 검수 화면으로 바뀝니다."
        titleId="conversion-heading"
      />
      <div className={`${CARD_CLASS} flex flex-col gap-8`}>
        <ConversionStages status={stageStatus(conversion)} />

        {/* 이 화면의 유일한 live region. 진행 문장·조회 오류·지연 안내를 한자리에 모아
            같은 사실이 여러 번 낭독되지 않게 한다(§11). */}
        <div role="status" className="flex flex-col gap-2">
          {timedOut ? (
            // 오래 걸리는 것은 실패가 아니다 — 위험 색 대신 정보 색을 쓴다(§6.3).
            <div className="flex flex-col gap-3 rounded-[12px] border border-info/25 bg-info-surface px-4 py-3">
              <p className="m-0 flex items-start gap-2 text-sm font-medium leading-[22px] text-info">
                <Clock className="mt-0.5 size-[18px] shrink-0" aria-hidden="true" />
                변환이 예상보다 오래 걸리고 있습니다. 이 화면을 닫아도 됩니다 — 결과는 변환 기록에서
                확인할 수 있습니다.
              </p>
              <Link className={`${QUIET_LINK_CLASS} self-start`} to={HISTORY_PATH}>
                <History className="size-[18px]" aria-hidden="true" />
                변환 기록에서 확인하기
              </Link>
            </div>
          ) : (
            <p className="m-0 flex items-center gap-2 font-semibold text-primary">
              {/* 이 화면에서 유일하게 반복 움직이는 표시다(§12). 장식이므로 낭독기에서
                  숨기고, 상태는 옆 문구가 알린다. */}
              <LoaderCircle
                className="size-[18px] shrink-0 animate-spin motion-reduce:animate-none"
                aria-hidden="true"
              />
              {conversion === null
                ? '변환 상태를 확인하고 있습니다…'
                : PROGRESS_TEXT[conversion.status === 'processing' ? 'processing' : 'pending']}
            </p>
          )}
          {error !== null && <p className="m-0 text-sm text-muted-foreground">{error}</p>}
        </div>

        <ResultSkeleton />

        {!timedOut && (
          <p className="field-hint">
            화면을 닫아도 됩니다. 변환이 끝난 결과는 <Link to={HISTORY_PATH}>변환 기록</Link>에서
            다시 열 수 있습니다.
          </p>
        )}
      </div>
    </section>
  )
}
