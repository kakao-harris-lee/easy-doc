/** 서비스 운영을 시작한 첫 달. 관리자 조회 범위의 하한이다. */
export const SERVICE_START_MONTH = '2026-09'

/** Server default report timezone. The summary displays the effective configured timezone. */
export function currentMonth(timezone = 'Asia/Seoul'): string {
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
  }).format(new Date())
}
export function validMonth(value: string | null, maximum = currentMonth()): string | null {
  return value &&
    /^\d{4}-(0[1-9]|1[0-2])$/.test(value) &&
    value >= SERVICE_START_MONTH &&
    value <= maximum
    ? value
    : null
}
export function shiftMonth(month: string, delta: number): string {
  const [year = 1, number = 1] = month.split('-').map(Number)
  const absolute = Math.max(12, Math.min(119999, year * 12 + number - 1 + delta))
  return `${Math.floor(absolute / 12)
    .toString()
    .padStart(4, '0')}-${((absolute % 12) + 1).toString().padStart(2, '0')}`
}
