import { useEffect, useId, useState } from 'react'
import type { GeneratedIllustration } from '../api/types'
import {
  acquireGeneratedIllustrationImage,
  invalidateFailedGeneratedIllustrationImage,
} from './generatedIllustrationImageCache'
import { Button } from './ui/Button'

type ImageState = {
  key: string
  attempt: number
  url: string | null
  failed: boolean
}

export function GeneratedIllustrationImage({
  conversionId,
  asset,
  decorative = false,
}: {
  conversionId: string
  asset: GeneratedIllustration
  decorative?: boolean
}) {
  const errorId = useId()
  const key = `${conversionId}\0${asset.asset_id}`
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<ImageState | null>(null)

  useEffect(() => {
    let active = true
    const lease = acquireGeneratedIllustrationImage(conversionId, asset.asset_id)
    void lease.promise.then(
      (url) => {
        if (active) setState({ key, attempt, url, failed: false })
      },
      () => {
        if (active) setState({ key, attempt, url: null, failed: true })
      },
    )
    return () => {
      active = false
      lease.release()
    }
  }, [conversionId, asset.asset_id, key, attempt])

  const current = state?.key === key && state.attempt === attempt ? state : null
  if (current?.failed) {
    return (
      <div>
        <p id={errorId} role="alert">
          그림 미리보기를 불러오지 못했습니다.
        </p>
        <Button
          type="button"
          variant="outline"
          aria-describedby={errorId}
          onClick={() => {
            invalidateFailedGeneratedIllustrationImage(conversionId, asset.asset_id)
            setAttempt((value) => value + 1)
          }}
        >
          미리보기 다시 불러오기
        </Button>
      </div>
    )
  }
  if (current?.url == null) {
    return (
      <p role="status" aria-live="polite">
        그림을 불러오는 중…
      </p>
    )
  }
  return (
    <img
      src={current.url}
      alt={decorative ? '' : asset.alt_text}
      className="w-full max-w-lg rounded-lg border border-border"
    />
  )
}
