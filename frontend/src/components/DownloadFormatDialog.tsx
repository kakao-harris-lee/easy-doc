import { useId, useState } from 'react'

import type { ExportFormat } from '../api/types'
import { downloadLabel } from './downloadLabel'
import { Button } from './ui/Button'
import { ModalDialog } from './ui/Dialog'

const FORMAT_DETAILS: Record<ExportFormat, string> = {
  hwpx: '한글 문서 · 원본 서식을 최대한 유지',
  docx: '워드 문서 · 원본 서식을 최대한 유지',
  txt: '글만 · 서식 없음',
}

interface DownloadFormatDialogProps {
  open: boolean
  formats: readonly ExportFormat[]
  dirty: boolean
  onClose: () => void
  onConfirm: (format: ExportFormat) => void
}

export function DownloadFormatDialog({
  open,
  formats,
  dirty,
  onClose,
  onConfirm,
}: DownloadFormatDialogProps) {
  const ids = useId()
  const titleId = `${ids}-title`
  const descriptionId = `${ids}-description`

  return (
    <ModalDialog
      open={open}
      onClose={onClose}
      labelledBy={titleId}
      describedBy={descriptionId}
      className="max-sm:items-end max-sm:p-0 max-sm:[&>[role=dialog]]:max-w-none max-sm:[&>[role=dialog]]:rounded-b-none max-sm:[&>[role=dialog]]:pb-[max(1.5rem,env(safe-area-inset-bottom))]"
    >
      <FormatChoice
        formats={formats}
        dirty={dirty}
        titleId={titleId}
        descriptionId={descriptionId}
        onClose={onClose}
        onConfirm={onConfirm}
      />
    </ModalDialog>
  )
}

function FormatChoice({
  formats,
  dirty,
  titleId,
  descriptionId,
  onClose,
  onConfirm,
}: Omit<DownloadFormatDialogProps, 'open'> & { titleId: string; descriptionId: string }) {
  const name = useId()
  const [selected, setSelected] = useState<ExportFormat | undefined>(formats[0])

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        if (selected !== undefined) {
          onConfirm(selected)
        }
      }}
    >
      <h2 className="m-0 text-xl font-bold text-foreground" id={titleId}>
        내려받을 형식
      </h2>
      <p className="mt-2 mb-4 text-sm text-muted-foreground" id={descriptionId}>
        {dirty
          ? '저장하지 않은 수정이 있으면 먼저 저장한 뒤 내려받습니다.'
          : '내려받을 파일 형식을 고르세요.'}
      </p>
      <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
        <legend className="sr-only">형식</legend>
        {formats.map((format) => (
          <label
            key={format}
            className="flex min-h-11 cursor-pointer flex-wrap items-baseline gap-x-3 gap-y-0 rounded-md border border-input px-3 py-2 has-[:checked]:border-primary has-[:checked]:bg-secondary"
          >
            <input
              type="radio"
              name={name}
              value={format}
              checked={selected === format}
              onChange={() => setSelected(format)}
              className="mt-1 size-4"
              data-dialog-autofocus={format === formats[0] ? '' : undefined}
            />
            <span className="font-semibold text-foreground">{format.toUpperCase()}</span>
            <span className="text-sm text-muted-foreground">{FORMAT_DETAILS[format]}</span>
          </label>
        ))}
      </fieldset>
      <div className="mt-5 flex justify-end gap-2 max-sm:flex-col-reverse">
        <Button type="button" variant="secondary" onClick={onClose}>
          취소
        </Button>
        <Button type="submit" disabled={selected === undefined}>
          {selected === undefined ? '내려받기' : downloadLabel(selected, dirty)}
        </Button>
      </div>
    </form>
  )
}
