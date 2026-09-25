package com.stastyle.imumapper.update

import com.stastyle.imumapper.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Reads the newest release of the GitHub repository through the public REST API. The repository is
 * public so no token is used; unauthenticated calls are limited to 60 per hour per IP, which is why
 * a 403 with rate-limit headers gets its own message.
 */
class GitHubUpdateChecker(
    private val repo: String = BuildConfig.GITHUB_REPO,
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val apiBaseUrl: String = "https://api.github.com",
    private val client: OkHttpClient = defaultClient(),
) : UpdateChecker {

    override suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val body = get("$apiBaseUrl/repos/$repo/releases/latest", accept = "application/vnd.github+json")
            ?: return@withContext null
        val parsed = ReleaseParser.parse(body) ?: return@withContext null
        val release = parsed.release
        if (release.apkSha256 != null || parsed.sha256SumsUrl == null) return@withContext release
        coroutineContext.ensureActive()
        // GitHub only started attaching digests to assets in 2025; older releases carry the
        // workflow's SHA256SUMS.txt instead. A missing or unreadable sums file just means "no check".
        val sums = runCatching { get(parsed.sha256SumsUrl, accept = "text/plain") }.getOrNull()
        val hex = sums?.let { ReleaseParser.findInSha256Sums(it, release.apkFileName) }
        release.copy(apkSha256 = hex)
    }

    /** Body of a successful GET, null for 404, [UpdateCheckException] for anything else. */
    private fun get(url: String, accept: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "IMU-Mapper/$appVersion")
            .build()
        val response: Response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw UpdateCheckException("Could not reach GitHub: ${e.message ?: "network error"}", e)
        }
        response.use { r ->
            when {
                r.isSuccessful -> return r.body?.string() ?: ""
                r.code == 404 -> return null
                r.code == 403 && r.header("x-ratelimit-remaining") == "0" -> {
                    throw UpdateCheckException("GitHub API rate limit reached; try again ${resetHint(r)}")
                }
                r.code == 403 || r.code == 429 -> {
                    throw UpdateCheckException("GitHub refused the request (HTTP ${r.code})")
                }
                else -> throw UpdateCheckException("GitHub answered HTTP ${r.code}")
            }
        }
    }

    private fun resetHint(response: Response): String {
        val resetEpochS = response.header("x-ratelimit-reset")?.toLongOrNull() ?: return "later"
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(resetEpochS * 1000))
        return "after $time"
    }

    companion object {
        private const val TIMEOUT_S = 10L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }
}
