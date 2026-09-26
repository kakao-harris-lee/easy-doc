import { useState } from 'react'
import type {
  ActionGuideAnalysis,
  ActionGuideSourceAnchor,
  CorrectGuideAnalysisRequest,
  ExtractedGuideAction,
  GuideInformation,
} from '../api/types'
import { Button } from './ui/Button'

function EvidenceEditor({
  label,
  value,
  units,
  onChange,
}: {
  label: string
  value: ActionGuideSourceAnchor[]
  units: ActionGuideAnalysis['source_units']
  onChange: (next: ActionGuideSourceAnchor[]) => void
}) {
  return (
    <fieldset className="space-y-2">
      <legend>{label}</legend>
      {value.map((anchor, index) => (
        <div key={index} className="space-y-2 border-l-2 border-border pl-3">
          <label className="block">
            원문 부분 선택
            <select
              aria-label={`${label} ${index + 1} 원문 부분`}
              className="min-h-11 w-full rounded-md border border-input p-2"
              value={anchor.source_unit_indexes[0] ?? ''}
              onChange={(event) => {
                const unit = units.find((entry) => entry.id === Number(event.target.value))
                if (unit)
                  onChange(
                    value.map((entry, at) =>
                      at === index ? { source_unit_indexes: [unit.id], quote: unit.text } : entry,
                    ),
                  )
              }}
            >
              <option value="">선택해 주세요</option>
              {units.map((unit) => (
                <option key={unit.id} value={unit.id}>
                  원문 부분 {unit.id + 1}: {unit.text.slice(0, 70)}
                </option>
              ))}
            </select>
          </label>
          <label className="block">
            근거 문구
            <textarea
              aria-label={`${label} ${index + 1} 근거 문구`}
              className="w-full rounded-md border border-input p-2"
              value={anchor.quote}
              onChange={(event) =>
                onChange(
                  value.map((entry, at) =>
                    at === index ? { ...entry, quote: event.target.value } : entry,
                  ),
                )
              }
            />
          </label>
          <Button
            className="min-h-11"
            variant="outline"
            onClick={() => onChange(value.filter((_, at) => at !== index))}
          >
            근거 삭제
          </Button>
        </div>
      ))}
      <Button
        className="min-h-11"
        variant="outline"
        onClick={() => onChange([...value, { source_unit_indexes: [], quote: '' }])}
      >
        근거 추가
      </Button>
    </fieldset>
  )
}

function InformationEditor({
  label,
  value,
  units,
  onChange,
}: {
  label: string
  value: GuideInformation
  units: ActionGuideAnalysis['source_units']
  onChange: (next: GuideInformation) => void
}) {
  return (
    <fieldset className="space-y-2">
      <legend className="font-medium">{label}</legend>
      <select
        aria-label={`${label} 정보 상태`}
        className="min-h-11 w-full rounded-md border border-input p-2"
        value={value.status}
        onChange={(event) => {
          const status = event.target.value as GuideInformation['status']
          onChange({
            status,
            text: status === 'present' || status === 'needs_review' ? (value.text ?? '') : null,
            evidence: status === 'present' || status === 'needs_review' ? value.evidence : [],
          })
        }}
      >
        <option value="present">원문에 있음</option>
        <option value="not_in_source">원문에 안내 없음</option>
        <option value="not_applicable">해당 없음</option>
        <option value="needs_review">확인 필요</option>
      </select>
      {(value.status === 'present' || value.status === 'needs_review') && (
        <>
          <textarea
            aria-label={`${label} 내용`}
            className="w-full rounded-md border border-input p-2"
            value={value.text ?? ''}
            onChange={(event) => onChange({ ...value, text: event.target.value })}
          />
          <EvidenceEditor
            label={`${label} 근거`}
            value={value.evidence}
            units={units}
            onChange={(evidence) => onChange({ ...value, evidence })}
          />
        </>
      )}
    </fieldset>
  )
}

export function ActionGuideAnalysisCorrection({
  analysis,
  disabled,
  onSave,
  onCancel,
}: {
  analysis: ActionGuideAnalysis
  disabled: boolean
  onSave: (body: CorrectGuideAnalysisRequest) => Promise<void>
  onCancel: () => void
}) {
  const [draft, setDraft] = useState(analysis)
  function editAction(id: string, update: (action: ExtractedGuideAction) => ExtractedGuideAction) {
    setDraft((current) => ({
      ...current,
      actions: current.actions.map((action) => (action.id === id ? update(action) : action)),
    }))
  }
  const missing = (): GuideInformation => ({ status: 'not_in_source', text: null, evidence: [] })
  return (
    <fieldset disabled={disabled} className="space-y-4 rounded-md border border-primary p-4">
      <legend className="font-semibold">행동 분석 수정</legend>
      <p>
        원문에 있는 내용과 근거로 고쳐 주세요. 저장하면 이전 검토 확인과 안내 초안은 다시 확인해야
        합니다.
      </p>
      <label className="block">
        문서 판단
        <select
          className="min-h-11 w-full rounded-md border border-input p-2"
          value={draft.suitability}
          onChange={(event) =>
            setDraft({
              ...draft,
              suitability: event.target.value as ActionGuideAnalysis['suitability'],
            })
          }
        >
          <option value="guide">안내문</option>
          <option value="mixed">안내와 다른 내용이 섞인 글</option>
          <option value="non_guide">안내문이 아닌 글</option>
          <option value="uncertain">확인 필요</option>
        </select>
      </label>
      <label className="block">
        행동 유무
        <select
          className="min-h-11 w-full rounded-md border border-input p-2"
          value={draft.action_presence}
          onChange={(event) =>
            setDraft({
              ...draft,
              action_presence: event.target.value as ActionGuideAnalysis['action_presence'],
            })
          }
        >
          <option value="found">행동 있음</option>
          <option value="none">행동 없음</option>
          <option value="uncertain">확인 필요</option>
        </select>
      </label>
      <label className="block">
        판단 이유
        <textarea
          className="w-full rounded-md border border-input p-2"
          value={draft.reason}
          onChange={(event) => setDraft({ ...draft, reason: event.target.value })}
        />
      </label>
      <EvidenceEditor
        label="문서 판단 근거"
        value={draft.evidence}
        units={draft.source_units}
        onChange={(evidence) => setDraft({ ...draft, evidence })}
      />
      {draft.actions.map((action, at) => (
        <fieldset key={action.id} className="space-y-3 rounded-md bg-secondary p-3">
          <legend>행동 항목 {at + 1}</legend>
          {(
            [
              { key: 'instruction', label: '할 일' },
              { key: 'actor', label: '행동할 사람' },
              { key: 'beneficiaries', label: '안내의 대상' },
              { key: 'deadline', label: '기한' },
              { key: 'preparation', label: '준비할 것' },
              { key: 'contact', label: '문의할 곳' },
            ] as const
          ).map(({ key, label }) => (
            <InformationEditor
              key={key}
              label={label}
              value={action[key]}
              units={draft.source_units}
              onChange={(value) =>
                editAction(action.id, (current) => ({ ...current, [key]: value }))
              }
            />
          ))}
          {action.conditions.map((condition, index) => (
            <div key={index}>
              <InformationEditor
                label={`조건과 주의할 점 ${index + 1}`}
                value={condition}
                units={draft.source_units}
                onChange={(value) =>
                  editAction(action.id, (current) => ({
                    ...current,
                    conditions: current.conditions.map((entry, pos) =>
                      pos === index ? value : entry,
                    ),
                  }))
                }
              />
              <Button
                className="min-h-11"
                variant="outline"
                onClick={() =>
                  editAction(action.id, (current) => ({
                    ...current,
                    conditions: current.conditions.filter((_, pos) => pos !== index),
                  }))
                }
              >
                이 조건 삭제
              </Button>
            </div>
          ))}
          <Button
            className="min-h-11"
            variant="outline"
            onClick={() =>
              editAction(action.id, (current) => ({
                ...current,
                conditions: [...current.conditions, { status: 'present', text: '', evidence: [] }],
              }))
            }
          >
            조건 추가
          </Button>
          <fieldset>
            <legend>원문에 먼저 하도록 명시된 행동만 선택</legend>
            {draft.actions
              .filter((entry) => entry.id !== action.id)
              .map((entry) => (
                <label key={entry.id} className="flex min-h-11 items-center gap-2">
                  <input
                    type="checkbox"
                    checked={action.after_action_ids.includes(entry.id)}
                    onChange={(event) =>
                      editAction(action.id, (current) => ({
                        ...current,
                        after_action_ids: event.target.checked
                          ? [...current.after_action_ids, entry.id]
                          : current.after_action_ids.filter((id) => id !== entry.id),
                      }))
                    }
                  />
                  {entry.instruction.text || '내용을 입력하지 않은 행동'}
                </label>
              ))}
          </fieldset>
          <EvidenceEditor
            label="행동 순서 근거"
            value={action.order_evidence}
            units={draft.source_units}
            onChange={(evidence) =>
              editAction(action.id, (current) => ({ ...current, order_evidence: evidence }))
            }
          />
          <Button
            className="min-h-11"
            variant="outline"
            onClick={() =>
              setDraft((current) => ({
                ...current,
                actions: current.actions
                  .filter((entry) => entry.id !== action.id)
                  .map((entry) => ({
                    ...entry,
                    after_action_ids: entry.after_action_ids.filter((id) => id !== action.id),
                  })),
                coverage: current.coverage.map((unit) => ({
                  ...unit,
                  action_ids: unit.action_ids.filter((id) => id !== action.id),
                })),
              }))
            }
          >
            이 행동 삭제
          </Button>
        </fieldset>
      ))}
      <Button
        className="min-h-11"
        variant="outline"
        onClick={() =>
          setDraft((current) => ({
            ...current,
            actions: [
              ...current.actions,
              {
                id: crypto.randomUUID(),
                instruction: { status: 'present', text: '', evidence: [] },
                actor: missing(),
                beneficiaries: missing(),
                conditions: [],
                deadline: missing(),
                preparation: missing(),
                contact: missing(),
                after_action_ids: [],
                order_evidence: [],
              },
            ],
          }))
        }
      >
        원문에서 빠진 행동 추가
      </Button>
      <h4 className="font-semibold">원문 전체의 분류와 행동 연결</h4>
      {draft.coverage.map((coverage, index) => (
        <fieldset
          key={coverage.source_unit_id}
          className="space-y-2 rounded-md border border-border p-3"
        >
          <legend>원문 부분 {coverage.source_unit_id + 1}</legend>
          <p className="whitespace-pre-wrap">
            {draft.source_units.find((unit) => unit.id === coverage.source_unit_id)?.text}
          </p>
          <select
            aria-label={`원문 부분 ${coverage.source_unit_id + 1} 분류`}
            className="min-h-11 w-full rounded-md border border-input p-2"
            value={coverage.status}
            onChange={(event) =>
              setDraft({
                ...draft,
                coverage: draft.coverage.map((entry, pos) =>
                  pos === index
                    ? { ...entry, status: event.target.value as typeof entry.status }
                    : entry,
                ),
              })
            }
          >
            <option value="action">행동</option>
            <option value="context">배경과 설명</option>
            <option value="needs_review">확인 필요</option>
          </select>
          {draft.actions.map((action) => (
            <label key={action.id} className="flex min-h-11 items-center gap-2">
              <input
                type="checkbox"
                checked={coverage.action_ids.includes(action.id)}
                onChange={(event) =>
                  setDraft({
                    ...draft,
                    coverage: draft.coverage.map((entry, pos) =>
                      pos === index
                        ? {
                            ...entry,
                            action_ids: event.target.checked
                              ? [...entry.action_ids, action.id]
                              : entry.action_ids.filter((id) => id !== action.id),
                          }
                        : entry,
                    ),
                  })
                }
              />
              {action.instruction.text || '내용을 입력하지 않은 행동'}
            </label>
          ))}
        </fieldset>
      ))}
      <div className="flex flex-wrap gap-2">
        <Button
          className="min-h-11"
          onClick={() => {
            void onSave({
              expected_content_revision: analysis.based_on_content_revision,
              expected_analysis_revision: analysis.analysis_revision,
              expected_review_revision: analysis.review_revision ?? 0,
              suitability: draft.suitability,
              action_presence: draft.action_presence,
              reason: draft.reason,
              evidence: draft.evidence,
              actions: draft.actions,
              coverage: draft.coverage,
            })
          }}
        >
          분석 수정 저장
        </Button>
        <Button className="min-h-11" variant="outline" onClick={onCancel}>
          수정 취소
        </Button>
      </div>
    </fieldset>
  )
}
