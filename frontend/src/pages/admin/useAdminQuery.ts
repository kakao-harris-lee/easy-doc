import { useCallback, useSyncExternalStore } from 'react'

const EVENT = 'easydoc:admin-query'
function subscribe(callback: () => void) {
  window.addEventListener('popstate', callback)
  window.addEventListener(EVENT, callback)
  return () => {
    window.removeEventListener('popstate', callback)
    window.removeEventListener(EVENT, callback)
  }
}
export function useAdminQuery(): [
  URLSearchParams,
  (values: Record<string, string | undefined>) => void,
] {
  const search = useSyncExternalStore(
    subscribe,
    () => window.location.search,
    () => '',
  )
  const update = useCallback((values: Record<string, string | undefined>) => {
    const next = new URLSearchParams(window.location.search)
    for (const [key, value] of Object.entries(values)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    window.history.pushState(
      window.history.state,
      '',
      `${window.location.pathname}?${next}${window.location.hash}`,
    )
    window.dispatchEvent(new Event(EVENT))
  }, [])
  return [new URLSearchParams(search), update]
}
export function adminPageNumber(value: string | null) {
  const number = Number(value)
  return Number.isSafeInteger(number) && number > 0 ? number : 1
}

/** Invalid direct-link dates must never reach toISOString or the date inputs. */
export function adminDate(value: string | null): string | undefined {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return undefined
  const instant = Date.parse(`${value}T00:00:00Z`)
  return Number.isFinite(instant) && new Date(instant).toISOString().slice(0, 10) === value
    ? value
    : undefined
}
