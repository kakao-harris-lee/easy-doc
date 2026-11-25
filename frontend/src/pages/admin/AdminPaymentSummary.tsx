const won = (amount: number) => `${amount.toLocaleString('ko-KR')}원`
export function AdminPaymentSummary({
  month,
  timezone,
  paid,
  refunded,
  events = [],
}: {
  workspaceId: string
  month: string
  timezone: string
  paid: number
  refunded: number
  events?: Array<{ id: string; kind: string; amount_krw: number; occurred_at: string | null }>
}) {
  return (
    <>
      <span>
        {won(paid)} / {won(refunded)}
      </span>
      <ul className="text-xs text-muted-foreground">
        {events.map((event) => (
          <li key={event.id}>
            {event.kind === 'refund' ? '환불' : '결제'} {won(event.amount_krw)} (
            {event.occurred_at
              ? new Intl.DateTimeFormat('sv-SE', {
                  timeZone: timezone,
                  year: 'numeric',
                  month: '2-digit',
                  day: '2-digit',
                }).format(new Date(event.occurred_at))
              : '발생일 확인 필요'}
            )
          </li>
        ))}
      </ul>
      <span className="block text-xs text-muted-foreground">
        {month} 실제 결제 / 환불 · 테스트 제외
      </span>
    </>
  )
}
