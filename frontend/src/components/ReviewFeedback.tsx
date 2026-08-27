import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import { ListChecks, Send } from 'lucide-react'
import { Link } from 'react-router-dom'

import { ApiError, saveFeedback } from '../api/client'
import type { PublishIntent } from '../api/types'
import { HISTORY_PATH } from '../routes/paths'
import { Button } from './ui/Button'

interface ReviewFeedbackProps {
  /**
   * 피드백을 남길 변환.
   *
   * 완료(`done`)된 변환에서만 이 폼을 띄운다 — 그 밖의 상태는 서버가 409로 막으므로,
   * 화면이 먼저 같은 조건을 지켜야 사용자가 보낼 수 없는 폼을 채우지 않는다.
   */
  conversionId: string
  /**
   * 의견이 저장된 뒤 그 시각(ISO 8601)을 위로 올린다.
   *
   * 폼이 스스로 위쪽 상태 패널을 고치지 않는 이유: 그 패널은 이 변환의 상태를 말하는
   * 자리이고, 이 컴포넌트가 아는 것은 방금 보낸 의견 하나뿐이다. 서버가 응답에 실어
   * 준 `submitted_at`을 그대로 넘겨 주고, 무엇을 어떻게 보여줄지는 검수 화면이 정한다.
   */
  onSubmitted?: (submittedAt: string) => void
}

/** 배포 의향 선택지. 값은 계약의 `publish_intent`, 문구는 화면 라벨이다. */
const PUBLISH_INTENTS: readonly { readonly value: PublishIntent; readonly label: string }[] = [
  { value: 'as_is', label: '그대로 쓸 수 있다' },
  { value: 'with_edits', label: '조금 고쳐서 쓰겠다' },
  { value: 'not_usable', label: '쓸 수 없다' },
]

/** 품질 만족도 눈금. 계약이 1~5 정수로 못박았다. */
const QUALITY_SCORES = [1, 2, 3, 4, 5] as const

/** 이번 건 소요 시간(분) 상한. 계약의 minutes_spent 범위와 같은 값이다. */
const MAX_MINUTES = 600

/** 자유 의견 길이 상한. 계약의 comment 상한과 같은 값이다. */
const MAX_COMMENT_LENGTH = 500

/** 제출 결과 안내. 성공과 실패의 낭독 방식이 달라 종류를 함께 둔다. */
interface Result {
  kind: 'success' | 'error'
  message: string
}

/**
 * 파일럿 게이트 ① 피드백 폼.
 *
 * 검수 화면에서 실무자가 남기는 **수기 입력의 전부**다(docs/pilot-runbook.md 게이트 ①).
 * 배포 의향과 품질 만족도는 통과 기준 1·2를 스크립트가 판정하는 값이고, 소요 시간은
 * 기준 3을 사람이 판단할 때 쓰는 중앙값의 재료다. 그래서 세 값은 선택이 아니라 필수다.
 *
 * 에디터와 한 파일에 두지 않는다 — 검수 저장·내려받기 안내와 이 폼의 제출 결과는 서로
 * 다른 일이고, 한 컴포넌트에서 두 종류의 상태를 굴리면 어느 안내가 무엇의 결과인지
 * 화면에서도 코드에서도 흐려진다.
 */
export function ReviewFeedback({ conversionId, onSubmitted }: ReviewFeedbackProps) {
  const intentId = useId()
  const scoreId = useId()
  const minutesId = useId()
  const commentId = useId()
  const noticeId = useId()

  const [intent, setIntent] = useState<PublishIntent | null>(null)
  const [score, setScore] = useState<number | null>(null)
  const [minutes, setMinutes] = useState('')
  const [comment, setComment] = useState('')
  /** 한 번이라도 보내려 했는지. 채우기도 전에 빨간 글씨를 띄우지 않으려고 나눈다. */
  const [attempted, setAttempted] = useState(false)
  const [result, setResult] = useState<Result | null>(null)
  const [busy, setBusy] = useState(false)

  // 숫자 입력은 문자열로 들고 있다가 여기서 한 번만 해석한다 — 빈 칸("")과 0은 다른
  // 뜻이라 number 상태로 두면 "아직 안 적음"을 표현할 수 없다.
  const minutesTrimmed = minutes.trim()
  const minutesSpent = /^\d+$/.test(minutesTrimmed) ? Number(minutesTrimmed) : null
  const minutesValid = minutesSpent !== null && minutesSpent <= MAX_MINUTES

  const intentInvalid = attempted && intent === null
  const scoreInvalid = attempted && score === null
  const minutesInvalid = attempted && !minutesValid

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setAttempted(true)
    // 필수 세 값이 비어 있으면 서버에 보내지 않는다. 422를 받아 오는 왕복은 사용자에게
    // 아무것도 알려주지 않고, 게이트 판정에 쓰이는 값이라 빈 채로 저장돼서도 안 된다.
    if (intent === null || score === null || !minutesValid || minutesSpent === null) {
      setResult({
        kind: 'error',
        message:
          '배포 의향, 품질 만족도, 이번 건 소요 시간(0~600분의 정수)을 모두 채운 뒤 보내 주세요.',
      })
      return
    }
    setBusy(true)
    setResult(null)
    try {
      const stored = await saveFeedback(conversionId, {
        publish_intent: intent,
        quality_score: score,
        minutes_spent: minutesSpent,
        // 빈 의견은 빈 문자열이 아니라 null로 보낸다 — 계약이 "적지 않음"을 null로 둔다.
        comment: comment.trim() === '' ? null : comment.trim(),
      })
      setResult({ kind: 'success', message: '의견을 보냈습니다. 감사합니다.' })
      // 저장 시각은 서버가 정한다 — 화면에서 `new Date()`로 지어내면 위 상태 패널이
      // 서버에 남은 것과 다른 시각을 말하게 된다.
      onSubmitted?.(stored.submitted_at)
    } catch (caught) {
      setResult({
        kind: 'error',
        message:
          caught instanceof ApiError
            ? caught.message
            : '의견을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.',
      })
    } finally {
      setBusy(false)
    }
  }

  return (
    <section
      className="rounded-[12px] border border-border bg-card p-5"
      aria-labelledby="feedback-heading"
    >
      <h2 className="font-bold" id="feedback-heading">
        이번 결과에 대한 의견
      </h2>
      <p className="field-hint">
        파일럿 판정에 쓰는 기록입니다. 문서 한 건마다 한 번만 보내면 되고, 다시 보내면 앞서 보낸
        내용을 덮어씁니다.
      </p>

      {/* 개인정보·본문 유입을 줄이는 고지. 조건 없이 늘 보인다 — 마스킹 범주가
          주민등록번호·카드번호 2종뿐이라(master-plan 3.2) 자유 의견에 본문이 섞여 들어오면
          가려지지 않은 채 저장된다. 자유 의견은 서버에서 봉인되지만, 애초에 들어오지 않게
          하는 것이 이 안내의 몫이다. */}
      <p className="field-hint" id={noticeId}>
        <strong>문서 내용은 적지 마세요.</strong> 이름·연락처 같은 개인정보와 문서 본문은 옮겨 적지
        말고, 결과가 어땠는지만 적어 주세요.
      </p>

      {/* noValidate — 상한을 넘긴 숫자를 브라우저 기본 검증에 맡기면 submit 자체가 막혀
          우리 안내(field-error·alert)가 뜨지 않고, 대신 번역도 스타일도 우리 것이 아닌
          말풍선이 뜬다. 검증은 아래 handleSubmit 한 곳에서만 한다. */}
      <form
        className="mt-4 flex flex-col gap-5"
        noValidate
        onSubmit={(event) => void handleSubmit(event)}
      >
        {/*
          오류는 `aria-describedby`로 묶음에 잇는다. `aria-invalid`만으로는 아무도 듣지
          못한다 — `fieldset`의 역할은 `group`이고 ARIA 1.2의 `group`은 `aria-invalid`를
          지원 속성으로 두지 않아 낭독기가 그냥 흘린다. 그렇다고 라디오 하나하나에
          붙이면 화살표로 지날 때마다 같은 문장이 세 번 읽힌다(§11 중복 낭독 금지).
          묶음에 한 번만 붙는 자리가 이 둘 사이의 유일한 답이다.
          (`aria-invalid`는 그대로 둔다 — 지원하는 AT에서는 여전히 상태를 알린다.)
        */}
        <fieldset
          className="field"
          aria-invalid={intentInvalid}
          aria-describedby={intentInvalid ? `${intentId}-error` : undefined}
        >
          <legend className="mb-1.5 text-[15px] font-semibold">
            이 결과를 실제로 배포할 수 있나요?
          </legend>
          <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
            {/*
              누르는 대상은 상자 전체다 — 라디오 상자(13px)와 그 옆 글자만 누를 수 있으면
              실제 터치 대상은 20px 남짓이라 §10 의 44px 에 한참 못 미친다. 테두리로
              그려 둔 48px 칸이 이미 있으므로, 그 칸 자체를 `<label>` 로 만들면 보이는
              모양은 그대로 두고 누를 수 있는 넓이만 칸 크기가 된다(새 변환 화면의 입력
              방식 선택이 이미 같은 모양이다).
            */}
            {PUBLISH_INTENTS.map((option) => (
              <label
                key={option.value}
                htmlFor={`${intentId}-${option.value}`}
                className={`flex h-12 cursor-pointer items-center gap-2 rounded-[10px] border px-3.5 font-semibold ${intent === option.value ? 'border-primary bg-accent text-accent-foreground' : 'border-border bg-background'}`}
              >
                <input
                  id={`${intentId}-${option.value}`}
                  className="accent-primary"
                  type="radio"
                  name={intentId}
                  value={option.value}
                  checked={intent === option.value}
                  onChange={() => setIntent(option.value)}
                />
                {option.label}
              </label>
            ))}
          </div>
          {intentInvalid && (
            <p className="field-error" id={`${intentId}-error`}>
              배포 의향을 골라 주세요.
            </p>
          )}
        </fieldset>

        {/* 1~5는 select가 아니라 라디오로 둔다. 눈금이 다섯 칸뿐이라 한눈에 다 보이는 편이
            비교해서 고르기 쉽고, 위 배포 의향과 조작 방법이 같아진다. select는 값을 열어야
            선택지가 보이고 낭독기에서도 현재 값 하나만 읽혀, 같은 폼 안에서 두 가지 조작
            규약이 섞인다. */}
        <fieldset
          className="field"
          aria-invalid={scoreInvalid}
          aria-describedby={scoreInvalid ? `${scoreId}-hint ${scoreId}-error` : `${scoreId}-hint`}
        >
          <legend className="mb-1.5 text-[15px] font-semibold">품질 만족도</legend>
          <div className="flex flex-wrap gap-2">
            {QUALITY_SCORES.map((value) => (
              <label
                key={value}
                htmlFor={`${scoreId}-${value}`}
                className={`flex h-12 min-w-11 cursor-pointer items-center justify-center gap-2 rounded-[10px] border px-3.5 font-semibold ${score === value ? 'border-primary bg-accent text-accent-foreground' : 'border-border bg-background'}`}
              >
                <input
                  id={`${scoreId}-${value}`}
                  className="accent-primary"
                  type="radio"
                  name={scoreId}
                  value={value}
                  checked={score === value}
                  onChange={() => setScore(value)}
                />
                {value}점
              </label>
            ))}
          </div>
          <p className="field-hint" id={`${scoreId}-hint`}>
            1점은 전혀 만족스럽지 않음, 5점은 매우 만족스러움입니다.
          </p>
          {scoreInvalid && (
            <p className="field-error" id={`${scoreId}-error`}>
              품질 만족도를 골라 주세요.
            </p>
          )}
        </fieldset>

        <div className="field">
          <label htmlFor={minutesId}>이번 건 소요 시간(분)</label>
          <input
            id={minutesId}
            type="number"
            inputMode="numeric"
            min={0}
            max={MAX_MINUTES}
            step={1}
            value={minutes}
            aria-invalid={minutesInvalid}
            aria-describedby={`${minutesId}-hint`}
            onChange={(event) => setMinutes(event.target.value)}
          />
          <p className={minutesInvalid ? 'field-error' : 'field-hint'} id={`${minutesId}-hint`}>
            올리기부터 검수를 마칠 때까지 걸린 시간을 분 단위로 적어 주세요. 0~{MAX_MINUTES}분.
          </p>
        </div>

        <div className="field">
          <label htmlFor={commentId}>의견 (선택)</label>
          <textarea
            id={commentId}
            className="w-full rounded-[10px] border border-input bg-card px-3.5 py-3 text-base leading-relaxed text-foreground"
            value={comment}
            rows={4}
            maxLength={MAX_COMMENT_LENGTH}
            aria-describedby={noticeId}
            onChange={(event) => setComment(event.target.value)}
          />
          <p className="field-hint">{MAX_COMMENT_LENGTH}자 이내.</p>
        </div>

        <Button type="submit" loading={busy} className="h-11 w-fit">
          <Send className="size-4" aria-hidden="true" />
          의견 보내기
        </Button>

        {/* 실패는 즉시 알리고(alert), 성공은 하던 일을 끊지 않게 알린다(status) —
            검수 에디터의 안내와 같은 규약이다. */}
        {result !== null && (
          <div className="flex flex-col items-start gap-3">
            <p
              className={result.kind === 'error' ? 'form-error' : 'form-success'}
              role={result.kind === 'error' ? 'alert' : 'status'}
            >
              {result.message}
            </p>
            {/* 보내고 나면 이 화면에서 할 일이 끝난다. 그렇다고 화면을 대신 넘기지는
                않는다 — 방금 무엇이 저장됐는지 확인할 틈을 뺏고, 저장하지 않은 수정이
                남아 있을 수도 있다(§9 «사용자가 다음 걸음을 고른다»). 대신 다음 걸음
                하나를 여기 눌 수 있게 둔다. 링크 밖 문구가 목적지를 말하므로 낭독기에서도
                "돌아가기"만 덩그러니 들리지 않는다. */}
            {result.kind === 'success' && (
              <Link
                className="inline-flex min-h-11 items-center gap-2 rounded-md border border-border px-4 text-[15px] font-semibold text-primary no-underline transition-colors hover:bg-muted"
                to={HISTORY_PATH}
              >
                <ListChecks className="size-[18px]" aria-hidden="true" />
                변환 기록으로 돌아가기
              </Link>
            )}
          </div>
        )}
      </form>
    </section>
  )
}
