import type { ReadingLevel } from '../api/types'

/** A billing step is one tenth of a credit for each 100 source characters. */
export const CREDIT_CHAR_STEP = 100

/** Return the credit charge for a nonnegative source character count. */
export function creditsForCharCount(charCount: number): number {
  if (!Number.isFinite(charCount) || charCount <= 0) {
    return 0
  }
  return Math.ceil(charCount / CREDIT_CHAR_STEP) / 10
}

/** Apply the customer-visible reading-level multiplier in whole tenth-credit units. */
export function creditsForReadingLevel(baseCredits: number, readingLevel: ReadingLevel): number {
  if (!Number.isFinite(baseCredits) || baseCredits <= 0) {
    return 0
  }
  const baseUnits = Math.ceil(baseCredits * 10 - Number.EPSILON)
  const units = readingLevel === 'grade_3_4' ? Math.ceil(baseUnits * 1.2) : baseUnits
  return units / 10
}

/** Credit amounts are wire values in tenths; tolerate binary floating point noise in display. */
export function formatCredits(value: number): string {
  if (!Number.isFinite(value)) {
    return '—'
  }
  const normalized = Math.round((value + Number.EPSILON) * 10) / 10
  return normalized.toLocaleString('ko-KR', {
    maximumFractionDigits: 1,
    minimumFractionDigits: Number.isInteger(normalized) ? 0 : 1,
  })
}

/** Validate an admin-entered credit amount against the tenth-credit wire unit. */
export function isCreditAmount(value: number): boolean {
  if (!Number.isFinite(value) || value === 0) {
    return false
  }
  const scaled = value * 10
  return Math.abs(scaled - Math.round(scaled)) <= Number.EPSILON * Math.max(1, Math.abs(scaled))
}
