import { Fragment, useId } from 'react'
import type { GeneratedIllustration } from '../api/types'
import { GeneratedIllustrationImage } from './GeneratedIllustrationImage'

interface Props {
  conversionId: string
  savedBody: string
  assets: GeneratedIllustration[]
  blocked: boolean
}

/** The saved body uses the same zero-based, inclusive line range as the suggestion. */
export function GeneratedIllustrationPreview({ conversionId, savedBody, assets, blocked }: Props) {
  const headingId = useId()
  const lines = savedBody.split('\n')
  const validAssets = assets.filter(
    ({ body_range: range, source_anchors: anchors }) =>
      Number.isInteger(range.start) &&
      Number.isInteger(range.end) &&
      range.start >= 0 &&
      range.start <= range.end &&
      range.end < lines.length &&
      anchors.length > 0,
  )
  if (blocked) {
    return (
      <p className="mt-4 text-sm text-muted-foreground">
        본문을 저장하고 최신 상태를 확인하면 그림을 적용한 문서 미리보기가 표시됩니다.
      </p>
    )
  }
  if (validAssets.length === 0) return null
  return (
    <section
      aria-labelledby={headingId}
      className="mt-4 min-w-0 rounded-xl border border-border p-4"
    >
      <h2 id={headingId} className="text-lg font-semibold">
        그림을 적용한 문서 미리보기
      </h2>
      <div className="mt-3 space-y-3 whitespace-pre-wrap [overflow-wrap:anywhere]">
        {lines.map((line, index) => (
          <Fragment key={index}>
            <p>{line || '\u00a0'}</p>
            {validAssets
              .filter((asset) => asset.body_range.end === index)
              .map((asset) => (
                <figure key={asset.asset_id} className="space-y-2">
                  <GeneratedIllustrationImage
                    conversionId={conversionId}
                    asset={asset}
                    decorative
                  />
                  <figcaption className="text-sm">{asset.alt_text}</figcaption>
                  {asset.provenance === 'fake' && (
                    <p className="text-sm">테스트 그림입니다. 실제 AI가 생성한 그림이 아닙니다.</p>
                  )}
                </figure>
              ))}
          </Fragment>
        ))}
      </div>
    </section>
  )
}
