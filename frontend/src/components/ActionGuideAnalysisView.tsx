import type { ActionGuideAnalysis, ActionGuideSourceAnchor, GuideInformation } from '../api/types'

export function ActionGuideEvidence({ evidence }: { evidence: ActionGuideSourceAnchor[] }) {
  if (evidence.length === 0) return <p className="text-sm">원문 근거 확인 필요</p>
  return (
    <details className="text-sm">
      <summary className="min-h-11 cursor-pointer py-3">원문 근거 보기</summary>
      {evidence.map((anchor, index) => (
        <blockquote key={index} className="mb-2 whitespace-pre-wrap border-l-2 border-primary pl-3">
          원문 부분 {anchor.source_unit_indexes.map((unit) => unit + 1).join(', ')}: {anchor.quote}
        </blockquote>
      ))}
    </details>
  )
}

function Information({ label, value }: { label: string; value: GuideInformation }) {
  const status = {
    present: '',
    not_in_source: '원문에 안내 없음',
    not_applicable: '해당 없음',
    needs_review: '확인 필요',
  }[value.status]
  return (
    <div className="space-y-1">
      <p className="font-semibold">{label}</p>
      {status && <p>{status}</p>}
      {value.text && <p className="whitespace-pre-wrap">{value.text}</p>}
      {(value.status === 'present' || value.status === 'needs_review') && (
        <ActionGuideEvidence evidence={value.evidence} />
      )}
    </div>
  )
}

export function ActionGuideAnalysisView({ analysis }: { analysis: ActionGuideAnalysis }) {
  const suitability = {
    guide: '안내문',
    non_guide: '안내문이 아닌 글',
    mixed: '안내와 다른 내용이 섞인 글',
    uncertain: '안내문인지 확인 필요',
  }[analysis.suitability]
  return (
    <div className="space-y-5">
      <section aria-label="안내문 판단" className="space-y-2">
        <h3 className="font-semibold">안내문 판단</h3>
        <p>{suitability}</p>
        <p className="whitespace-pre-wrap">{analysis.reason}</p>
        <ActionGuideEvidence evidence={analysis.evidence} />
        {analysis.action_presence === 'none' && <p>원문에서 할 일을 찾지 못했습니다.</p>}
        {analysis.action_presence === 'uncertain' && <p>원문에서 할 일을 더 확인해야 합니다.</p>}
      </section>
      {analysis.actions.length > 0 && (
        <section aria-label="원문에서 찾은 행동" className="space-y-3">
          <h3 className="font-semibold">원문에서 찾은 행동</h3>
          <p className="text-sm">
            할 일이 하나여도 정상입니다. 원문에 순서가 없으면 순서를 정하지 않습니다. ‘원문에 안내
            없음’은 실제로 필요 없다는 뜻이 아닙니다.
          </p>
          <ul className="space-y-3">
            {analysis.actions.map((action) => (
              <li key={action.id} className="space-y-3 rounded-md border border-border p-4">
                <Information label="할 일" value={action.instruction} />
                <Information label="행동할 사람" value={action.actor} />
                <Information label="안내의 대상" value={action.beneficiaries} />
                {action.conditions.map((condition, index) => (
                  <Information key={index} label="조건과 주의할 점" value={condition} />
                ))}
                <Information label="기한" value={action.deadline} />
                <Information label="준비할 것" value={action.preparation} />
                <Information label="문의할 곳" value={action.contact} />
                {action.after_action_ids.length > 0 && (
                  <div className="space-y-2">
                    <p className="font-semibold">원문에서 먼저 하도록 안내한 일</p>
                    <ul>
                      {action.after_action_ids.map((id) => (
                        <li key={id}>
                          {analysis.actions.find((entry) => entry.id === id)?.instruction.text ??
                            '연결된 행동 확인 필요'}
                        </li>
                      ))}
                    </ul>
                    <ActionGuideEvidence evidence={action.order_evidence} />
                  </div>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}
      <section aria-label="원문 전체 대조" className="space-y-2">
        <h3 className="font-semibold">원문 전체 대조</h3>
        <p className="text-sm">
          원문과 행동 목록을 비교해 빠진 행동이나 조건이 있는지 확인해 주세요. 대응 표시가 있어도
          뜻이 모두 보존되었다는 의미는 아닙니다.
        </p>
        <ul className="space-y-2">
          {analysis.source_units.map((unit) => {
            const coverage = analysis.coverage.find((entry) => entry.source_unit_id === unit.id)
            return (
              <li key={unit.id} className="rounded-md bg-secondary p-3">
                <p className="text-sm">
                  원문 부분 {unit.id + 1} ·{' '}
                  {coverage?.status === 'action'
                    ? '행동과 연결됨'
                    : coverage?.status === 'context'
                      ? '배경과 설명'
                      : '확인 필요'}
                </p>
                <p className="whitespace-pre-wrap">{unit.text || '(빈 줄)'}</p>
                {coverage?.action_ids.map((id) => (
                  <p key={id} className="mt-1 text-sm">
                    연결된 행동:{' '}
                    {analysis.actions.find((action) => action.id === id)?.instruction.text ??
                      '확인 필요'}
                  </p>
                ))}
              </li>
            )
          })}
        </ul>
      </section>
    </div>
  )
}
