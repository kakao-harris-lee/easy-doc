import { useContext } from 'react'
import { AuthContext } from '../../auth/context'

export function useAdminOperationKey(workspace: string, action: string) {
  const auth = useContext(AuthContext)
  return `admin-operation:${auth?.user?.id ?? 'anonymous'}:${workspace}:${action}`
}
export function readOperation<T>(key: string): T | null {
  try {
    const value: unknown = JSON.parse(sessionStorage.getItem(key) ?? 'null')
    return value && typeof value === 'object' ? (value as T) : null
  } catch {
    return null
  }
}
export function saveOperation(key: string, value: unknown | null) {
  // Fail closed before sending: a request that cannot survive reload must not be submitted.
  if (value === null) sessionStorage.removeItem(key)
  else sessionStorage.setItem(key, JSON.stringify(value))
}
