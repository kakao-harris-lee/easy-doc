import { useEffect, useState } from 'react'
import { getGeneratedIllustrationImage } from '../api/client'
import type { GeneratedIllustration } from '../api/types'
import { Button } from './ui/Button'

export function GeneratedIllustrationImage({
  conversionId,
  asset,
}: {
  conversionId: string
  asset: GeneratedIllustration
}) {
  const [url, setUrl] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    let objectUrl: string | null = null
    void getGeneratedIllustrationImage(conversionId, asset.asset_id, controller.signal)
      .then((blob) => {
        if (controller.signal.aborted) return
        objectUrl = URL.createObjectURL(blob)
        setUrl(objectUrl)
      })
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true)
      })
    return () => {
      controller.abort()
      if (objectUrl !== null) URL.revokeObjectURL(objectUrl)
    }
  }, [conversionId, asset.asset_id, attempt])
  if (failed)
    return (
      <div>
        <p>그림 미리보기를 불러오지 못했습니다.</p>
        <Button
          type="button"
          variant="outline"
          onClick={() => {
            setFailed(false)
            setAttempt((value) => value + 1)
          }}
        >
          미리보기 다시 불러오기
        </Button>
      </div>
    )
  if (url === null) return <p>그림을 불러오는 중…</p>
  return (
    <img
      src={url}
      alt={asset.alt_text}
      className="w-full max-w-lg rounded-lg border border-border"
    />
  )
}
