package proton.android.pass.data.api.url

import proton.android.pass.common.api.flatMap
import java.net.URI
import java.net.URISyntaxException

object UrlSanitizer {
    fun sanitize(url: String): Result<String> {
        if (url.isBlank()) return Result.failure(IllegalArgumentException("url cannot be empty"))
        if (url.all { !it.isLetterOrDigit() })
            return Result.failure(IllegalArgumentException("url cannot be all symbols"))

        // If it doesn't have a scheme, add https://
        val urlWithScheme = if (!url.contains("://")) {
            "https://$url"
        } else {
            url
        }

        return try {
            val parsed = URI(urlWithScheme)
            if (parsed.host == null) return Result.failure(IllegalArgumentException("url cannot be parsed"))
            val meaningfulSection = "${parsed.scheme}://${parsed.host}${parsed.path}"
            Result.success(meaningfulSection)
        } catch (e: URISyntaxException) {
            Result.failure(e)
        }
    }

    fun getProtocol(url: String): Result<String> = sanitizeAndParse(url).map { it.scheme }
    fun getDomain(url: String): Result<String> = sanitizeAndParse(url).map { it.host }

    private fun sanitizeAndParse(url: String): Result<URI> = sanitize(url).flatMap {
        try {
            Result.success(URI(it))
        } catch (e: URISyntaxException) {
            Result.failure(e)
        }
    }
}
