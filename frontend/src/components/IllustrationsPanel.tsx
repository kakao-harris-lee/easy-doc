import { useEffect, useId, useRef, useState } from 'react'

import { ApiError, getIllustrations, illustrationImageUrl } from '../api/client'
import type { Illustration, IllustrationPurpose } from '../api/types'

function errorText(caught: unknown, fallback: string): string {
  return caught instanceof ApiError ? caught.message : fallback
}

/** 계약 `IllustrationPurpose`의 10개 값 한국어 라벨. 모르는 값은 wire 값 그대로 보여준다. */
const PURPOSE_LABELS: Record<string, string> = {
  visit_office: '기관 방문',
  phone_call: '전화 문의',
  submit_document: '서류 제출',
  apply_online: '온라인 신청',
  deadline: '기한',
  payment: '비용',
  identification: '신분증',
  wait: '기다림',
  caution: '주의',
  done: '완료',
}

function purposeLabel(purpose: IllustrationPurpose): string {
  return PURPOSE_LABELS[purpose] ?? purpose
}

/** 목록 항목 하나. 검수자가 대체텍스트·출처·라이선스를 화면에서 바로 확인할 수 있어야 한다. */
function IllustrationCard({ item }: { item: Illustration }) {
  return (
    <li className="rounded-[10px] border border-border p-4">
      <div className="flex flex-wrap items-start gap-3">
        <img
          src={illustrationImageUrl(item.image_url)}
          alt={item.alt_text}
          width={64}
          height={64}
          loading="lazy"
          className="size-16 shrink-0 rounded-[6px] border border-border object-contain"
        />
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <span className="font-semibold">{item.caption}</span>
          <span className="text-sm text-muted-foreground">{purposeLabel(item.purpose)}</span>
          <p className="text-sm text-foreground [overflow-wrap:anywhere]">
            대체텍스트: {item.alt_text}
          </p>
        </div>
      </div>

      {item.mapping_examples.length > 0 && (
        <div className="mt-3">
          <p className="text-sm font-semibold">이런 문장에 어울려요</p>
          <ul className="mt-1 flex flex-col gap-1">
            {item.mapping_examples.map((example, index) => (
              <li key={index} className="text-sm text-foreground">
                {example}
              </li>
            ))}
          </ul>
        </div>
      )}

      <p className="mt-3 text-xs text-muted-foreground [overflow-wrap:anywhere]">
        라이선스 {item.license} · 출처 {item.source} · 검수 {item.reviewed_by} ({item.reviewed_at})
        · v{item.version}
      </p>
    </li>
  )
}

/**
 * R7 — 검수를 마친 그림(권리·의미·대체텍스트) 목록.
 *
 * 본문에 그림을 넣거나 그림 포함 출력을 만드는 기능(ER-16)은 아직 없다 — 이 패널은 목록을
 * 보여줄 뿐이고, 그 사실을 안내문으로 먼저 말한다.
 */
export function IllustrationsPanel() {
  const headingId = useId()
  const [illustrations, setIllustrations] = useState<Illustration[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const requestVersion = useRef(0)

  useEffect(() => {
    const version = requestVersion.current + 1
    requestVersion.current = version
    const controller = new AbortController()
    async function load(): Promise<void> {
      setLoading(true)
      setError(null)
      try {
        const response = await getIllustrations(controller.signal)
        if (controller.signal.aborted || requestVersion.current !== version) return
        setIllustrations(response.illustrations)
      } catch (caught) {
        if (controller.signal.aborted || requestVersion.current !== version) return
        setError(errorText(caught, '그림 목록을 불러오지 못했습니다. 다시 시도해 주세요.'))
      } finally {
        if (!controller.signal.aborted && requestVersion.current === version) setLoading(false)
      }
    }
    void load()

    return () => controller.abort()
  }, [])

  return (
    <section
      className="mt-6 rounded-xl border border-border bg-card p-4 sm:p-6"
      aria-labelledby={headingId}
    >
      <h2 id={headingId} className="text-lg font-semibold">
        그림 목록
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        검수된 그림만 보여 줍니다. 본문에 넣기와 파일 출력은 다음 단계에서 지원합니다.
      </p>

      {loading && (
        <p className="mt-5" role="status" aria-busy="true">
          그림 목록을 불러오는 중…
        </p>
      )}
      {error !== null && (
        <div className="form-error mt-4" role="alert">
          {error}
        </div>
      )}
      {!loading && error === null && illustrations.length === 0 && (
        <p className="mt-5 text-sm text-muted-foreground">검수된 그림이 없습니다.</p>
      )}
      {illustrations.length > 0 && (
        <ul className="mt-5 flex flex-col gap-3">
          {illustrations.map((item) => (
            <IllustrationCard key={item.asset_id} item={item} />
          ))}
        </ul>
      )}
    </section>
  )
}
