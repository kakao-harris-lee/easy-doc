package kr.easydoc.api.config

import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity

/** Explicit controller headers also protect responses outside the servlet filter chain. */
internal fun ResponseEntity.BodyBuilder.privateResponse(): ResponseEntity.BodyBuilder =
    header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
        .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
