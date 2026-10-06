import { useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { adjustAdminWorkspaceCredits } from '../../api/admin'
import { getAdminBillingRequest } from '../../api/subscriptions'
import { ApiError } from '../../api/client'
import type { AdminCreditAdjustmentRequest } from '../../api/types'
import { Button } from '../../components/ui/Button'
import { formatCredits, isCreditAmount } from '../../lib/credits'

import { useAdminOperationKey, readOperation, saveOperation } from './adminOperationStorage'

export function AdminCreditAdjustment({
  workspaceId,
  balance,
  reserved,
  revision,
  onChanged,
}: {
  workspaceId: string
  balance: number
  reserved: number
  revision: number
  onChanged: () => void
}) {
  const storageKey = useAdminOperationKey(workspaceId, 'credit')
  const [pending] = useState(() => readOperation<AdminCreditAdjustmentRequest>(storageKey))
  const [amount, setAmount] = useState(pending ? String(Math.abs(pending.credits)) : '')
  const [kind, setKind] = useState<'grant' | 'withdraw' | 'restore'>(
    pending
      ? pending.credits < 0
        ? 'withdraw'
        : pending.reason === 'refund'
          ? 'restore'
          : 'grant'
      : 'grant',
  )
  const [note, setNote] = useState(pending?.note ?? '')
  const [busy, setBusy] = useState(false)
  const [uncertain, setUncertain] = useState(Boolean(pending))
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [preview, setPreview] = useState<AdminCreditAdjustmentRequest | null>(pending)
  const attempt = useRef<AdminCreditAdjustmentRequest | null>(pending)
  const signed = (kind === 'withdraw' ? -1 : 1) * Number(amount)
  function resetPreview() {
    setPreview(null)
    setMessage(null)
  }
  function prepare(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (Number(amount) <= 0 || !isCreditAmount(signed)) {
      setError('크레딧은 0보다 큰 0.1 단위 숫자여야 합니다.')
      return
    }
    if (!note.trim()) {
      setError('조정 사유를 메모에 입력해 주세요.')
      return
    }
    if (
      signed < 0 &&
      Math.round(-signed * 10) > Math.round(balance * 10) - Math.round(reserved * 10)
    ) {
      setError('회수량은 현재 사용 가능 크레딧을 넘을 수 없습니다.')
      return
    }
    const input = {
      credits: signed,
      reason: kind === 'restore' ? ('refund' as const) : ('manual' as const),
      note: note.trim(),
      expected_balance: balance,
      expected_reserved: reserved,
      expected_revision: revision,
    }
    const prior = attempt.current
    const same =
      prior &&
      Object.entries(input).every(([key, value]) => prior[key as keyof typeof input] === value)
    const request = same ? prior : { ...input, operation_id: crypto.randomUUID() }
    attempt.current = request
    setPreview(request)
  }
  async function apply() {
    if (!preview || busy) return
    setBusy(true)
    setError(null)
    try {
      saveOperation(storageKey, preview)
      await adjustAdminWorkspaceCredits(workspaceId, preview)
      attempt.current = null
      saveOperation(storageKey, null)
      setUncertain(false)
      setPreview(null)
      setAmount('')
      setNote('')
      setMessage('크레딧을 반영했습니다.')
      onChanged()
    } catch (cause) {
      setError(
        cause instanceof ApiError
          ? cause.message
          : '결과를 확인하지 못했습니다. 같은 요청으로 다시 시도해 주세요.',
      )
      let confirmedAbsent = false
      if (cause instanceof ApiError && cause.status === 409) {
        try {
          const result = await getAdminBillingRequest(workspaceId, preview.operation_id)
          if (result.status === 'completed') {
            saveOperation(storageKey, null)
            attempt.current = null
            setUncertain(false)
            setPreview(null)
            setError(null)
            setMessage('크레딧을 반영했습니다.')
            onChanged()
            return
          }
        } catch (lookupError) {
          confirmedAbsent = lookupError instanceof ApiError && lookupError.status === 404
        }
        onChanged()
      }
      const unknownResult =
        !(cause instanceof ApiError) ||
        cause.status === 0 ||
        cause.status >= 500 ||
        (cause.status === 409 && !confirmedAbsent)
      setUncertain(unknownResult)
      if (!unknownResult) saveOperation(storageKey, null)
      if (cause instanceof ApiError && (cause.status === 422 || confirmedAbsent)) {
        setPreview(null)
        attempt.current = null
        onChanged()
      }
    } finally {
      setBusy(false)
    }
  }
  return (
    <form
      aria-label="크레딧 부여 및 조정"
      onSubmit={prepare}
      className="flex flex-col gap-3 rounded-xl border border-border p-4"
    >
      <h4 className="font-semibold">크레딧 지급·회수·복구</h4>
      <p className="text-sm text-muted-foreground">
        조정은 현재 시각에 기록됩니다. 현금 결제·환불과 별개이며 처리 중 확보한 크레딧은 회수할 수
        없습니다.
      </p>
      <fieldset disabled={busy || uncertain} className="flex flex-wrap gap-3">
        <label className="field">
          조정 종류
          <select
            value={kind}
            onChange={(e) => {
              setKind(e.target.value as typeof kind)
              resetPreview()
            }}
          >
            <option value="grant">지급</option>
            <option value="withdraw">회수</option>
            <option value="restore">크레딧 복구</option>
          </select>
        </label>
        <label className="field">
          크레딧 수량
          <input
            type="number"
            step="0.1"
            min="0.1"
            required
            value={amount}
            onChange={(e) => {
              setAmount(e.target.value)
              resetPreview()
            }}
          />
        </label>
        <label className="field">
          메모 (필수)
          <input
            required
            maxLength={200}
            value={note}
            onChange={(e) => {
              setNote(e.target.value)
              resetPreview()
            }}
          />
        </label>
      </fieldset>
      {uncertain && (
        <p role="status">
          반영 여부를 아직 확인하지 못했습니다. 중복 지급을 막기 위해 입력을 유지합니다. 같은 요청을
          다시 확인해 주세요.
        </p>
      )}
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      {message && <p role="status">{message}</p>}
      {preview ? (
        <div className="space-y-2" aria-label="조정 미리보기">
          <p>
            보유 {formatCredits(preview.expected_balance)} →{' '}
            {formatCredits(preview.expected_balance + preview.credits)} · 처리 중 확보{' '}
            {formatCredits(preview.expected_reserved)} (유지) · 사용 가능{' '}
            {formatCredits(preview.expected_balance - preview.expected_reserved)} →{' '}
            {formatCredits(preview.expected_balance + preview.credits - preview.expected_reserved)}
          </p>
          <p>
            변동 {preview.credits > 0 ? '+' : ''}
            {formatCredits(preview.credits)} · {preview.note}
          </p>
          <Button type="button" loading={busy} onClick={() => void apply()}>
            확인 후 크레딧 반영
          </Button>
        </div>
      ) : (
        <Button type="submit" className="self-start">
          변경 내용 확인
        </Button>
      )}
    </form>
  )
}
