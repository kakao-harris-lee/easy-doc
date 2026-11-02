import { useEffect, useState } from 'react'
import { getIllustrationImage } from '../api/client'

/** Loads a catalog illustration through the authenticated API client. */
export function IllustrationImage({
  imageUrl,
  alt,
  className,
  width,
  height,
}: {
  imageUrl: string
  alt: string
  className?: string
  width?: number
  height?: number
}) {
  const [url, setUrl] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    let objectUrl: string | null = null
    setUrl(null)
    void getIllustrationImage(imageUrl, controller.signal)
      .then((blob) => {
        if (controller.signal.aborted) return
        objectUrl = URL.createObjectURL(blob)
        setUrl(objectUrl)
      })
      .catch(() => {
        // Missing/expired authorization or an unavailable asset leaves the image concealed.
      })
    return () => {
      controller.abort()
      if (objectUrl !== null) URL.revokeObjectURL(objectUrl)
    }
  }, [imageUrl])

  if (url === null) return null
  return <img src={url} alt={alt} width={width} height={height} className={className} />
}
