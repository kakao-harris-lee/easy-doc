import type { ExportFormat } from '../api/types'

export function downloadLabel(format: ExportFormat, dirty: boolean): string {
  return `${dirty ? '저장하고 ' : ''}${format.toUpperCase()}로 내려받기`
}
