import { getGeneratedIllustrationImage } from '../api/client'

type ImageEntry = {
  controller: AbortController
  references: number
  status: 'loading' | 'loaded' | 'failed'
  objectUrl: string | null
  promise: Promise<string>
}

export type ImageLease = {
  promise: Promise<string>
  release: () => void
}

const entries = new Map<string, ImageEntry>()

function cacheKey(conversionId: string, assetId: string) {
  return `${conversionId}\0${assetId}`
}

function dispose(entry: ImageEntry) {
  entry.controller.abort()
  if (entry.objectUrl !== null) {
    URL.revokeObjectURL(entry.objectUrl)
    entry.objectUrl = null
  }
}

export function invalidateFailedGeneratedIllustrationImage(conversionId: string, assetId: string) {
  const key = cacheKey(conversionId, assetId)
  const entry = entries.get(key)
  if (entry?.status !== 'failed') return
  entries.delete(key)
  dispose(entry)
}

export function acquireGeneratedIllustrationImage(
  conversionId: string,
  assetId: string,
): ImageLease {
  const key = cacheKey(conversionId, assetId)
  let entry = entries.get(key)
  if (!entry) {
    const controller = new AbortController()
    const nextEntry: ImageEntry = {
      controller,
      references: 0,
      status: 'loading',
      objectUrl: null,
      promise: Promise.resolve(''),
    }
    nextEntry.promise = getGeneratedIllustrationImage(conversionId, assetId, controller.signal)
      .then((blob) => {
        if (controller.signal.aborted || entries.get(key) !== nextEntry) {
          throw new DOMException('이미지 요청 범위가 변경되었습니다.', 'AbortError')
        }
        const objectUrl = URL.createObjectURL(blob)
        nextEntry.objectUrl = objectUrl
        nextEntry.status = 'loaded'
        return objectUrl
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted && entries.get(key) === nextEntry) {
          nextEntry.status = 'failed'
        }
        throw error
      })
    entry = nextEntry
    entries.set(key, entry)
  }

  entry.references += 1
  let released = false
  return {
    promise: entry.promise,
    release: () => {
      if (released) return
      released = true
      entry.references -= 1
      if (entry.references > 0) return
      if (entries.get(key) === entry) entries.delete(key)
      dispose(entry)
    },
  }
}
