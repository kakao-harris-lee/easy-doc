import type { IllustrationGenerationConfiguration } from './types'

/** IllustrationGenerationJobCreateRequest.configuration_revision in easy-doc-v1.yaml. */
export async function illustrationConfigurationRevision(
  configuration: IllustrationGenerationConfiguration,
): Promise<string> {
  const encodeList = (values: string[]) =>
    values.map((value) => `${value.length}:${value};`).join('')
  const canonical = `${encodeList(configuration.scenes)}|${encodeList(configuration.preserved_facts)}|${configuration.alt_text}`
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(canonical))
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}
