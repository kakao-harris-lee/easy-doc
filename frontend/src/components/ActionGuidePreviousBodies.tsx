import { useEffect, useState } from 'react'
import { getGuidePreviousBody, listGuidePreviousBodies } from '../api/client'
import type { GuidePreviousBody } from '../api/types'
import { Button } from './ui/Button'

export function ActionGuidePreviousBodies({
  conversionId,
  contentRevision,
}: {
  conversionId: string
  contentRevision: number
}) {
  const [opened, setOpened] = useState(false)
  const [entries, setEntries] = useState<GuidePreviousBody[]>([])
  const [selected, setSelected] = useState<{ id: string; body: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [listResolved, setListResolved] = useState(false)
  useEffect(() => {
    if (!opened) return
    let disposed = false
    listGuidePreviousBodies(conversionId)
      .then((next) => {
        if (!disposed) {
          setEntries(next)
          setError(null)
          setListResolved(true)
        }
      })
      .catch(() => {
        if (!disposed) setError('이전 본문 목록을 불러오지 못했습니다. 다시 열어 주세요.')
      })
    return () => {
      disposed = true
    }
  }, [conversionId, contentRevision, opened])

  async function read(snapshot: GuidePreviousBody) {
    setBusy(true)
    setError(null)
    setMessage(null)
    try {
      const result = await getGuidePreviousBody(conversionId, snapshot.snapshot_id)
      setSelected({ id: snapshot.snapshot_id, body: result.body })
    } catch {
      setError('이전 본문을 불러오지 못했습니다. 다시 시도해 주세요.')
    } finally {
      setBusy(false)
    }
  }
  async function copyBody() {
    if (!selected) return
    try {
      if (!navigator.clipboard) throw new Error('Clipboard unavailable')
      await navigator.clipboard.writeText(selected.body)
      setMessage('이전 본문을 복사했습니다. 본문 검수 편집기에 붙여 넣고 확인해 주세요.')
    } catch {
      setError('복사하지 못했습니다. 이전 본문 영역에서 직접 선택하여 복사해 주세요.')
    }
  }

  return (
    <section
      aria-label="반영 전 본문 보관"
      className="space-y-3 rounded-md border border-border p-4"
    >
      <h3 className="font-semibold">반영 전 본문 보관</h3>
      <p>
        전체 보완을 반영하기 전의 본문을 확인할 수 있습니다. 되돌리려면 이전 본문을 복사하여 본문
        검수 편집기에 붙여 넣고 확인한 뒤 저장해 주세요.
      </p>
      <Button
        type="button"
        className="min-h-11"
        variant="outline"
        onClick={() => setOpened((current) => !current)}
      >
        {opened ? '이전 본문 목록 닫기' : '이전 본문 목록 열기'}
      </Button>
      {error && <p role="alert">{error}</p>}
      {message && <p role="status">{message}</p>}
      {opened && (
        <>
          {!listResolved && !error && <p role="status">이전 본문 목록을 불러오고 있습니다.</p>}
          {listResolved && entries.length === 0 && !error && <p>보관된 이전 본문이 없습니다.</p>}
          <ul className="space-y-2">
            {entries.map((entry) => (
              <li key={entry.snapshot_id}>
                <Button
                  type="button"
                  className="min-h-11"
                  variant="outline"
                  disabled={busy}
                  onClick={() => {
                    void read(entry)
                  }}
                >
                  본문 버전 {entry.previous_content_revision} 열기
                </Button>
              </li>
            ))}
          </ul>
          {selected && (
            <div className="space-y-3">
              <label className="block">
                선택한 이전 본문
                <textarea
                  readOnly
                  rows={10}
                  className="mt-1 w-full rounded-md border border-input p-2"
                  value={selected.body}
                />
              </label>
              <Button
                type="button"
                className="min-h-11"
                variant="outline"
                onClick={() => {
                  void copyBody()
                }}
              >
                이전 본문 복사
              </Button>
              <Button
                type="button"
                className="min-h-11"
                variant="outline"
                onClick={() => {
                  const url = URL.createObjectURL(
                    new Blob([selected.body], { type: 'text/plain;charset=utf-8' }),
                  )
                  const link = document.createElement('a')
                  link.href = url
                  link.download = 'previous-document.txt'
                  document.body.append(link)
                  link.click()
                  link.remove()
                  URL.revokeObjectURL(url)
                }}
              >
                이전 본문 TXT 보관
              </Button>
            </div>
          )}
        </>
      )}
    </section>
  )
}
