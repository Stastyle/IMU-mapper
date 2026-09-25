package com.stastyle.imumapper.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure parsing of the GitHub "latest release" JSON. Works on the JSON tree instead of data classes
 * so that GitHub adding, renaming or dropping fields never breaks deserialisation; the few fields we
 * need are read defensively and everything else is ignored. No Android types, so it runs in plain
 * JVM unit tests.
 */
object ReleaseParser {

    /** The release plus, when the APK asset carries no digest, where a SHA256SUMS.txt asset lives. */
    data class Parsed(val release: ReleaseInfo, val sha256SumsUrl: String?)

    /**
     * What a release body turned out to be. A release without an APK is kept apart from garbage
     * because the updater must report it as a failure rather than as "up to date".
     */
    sealed interface Outcome {
        data class Release(val parsed: Parsed) : Outcome

        /** A well-formed release (`tag_name` present) whose assets hold no downloadable `.apk`. */
        data class NoApk(val tagName: String) : Outcome

        /** Not JSON, not an object, or an object without a `tag_name`. */
        data object NotARelease : Outcome
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Null when the JSON is not a release object or the release has no APK asset; see [classify]. */
    fun parse(body: String): Parsed? = (classify(body) as? Outcome.Release)?.parsed

    fun classify(body: String): Outcome {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return Outcome.NotARelease
        val tag = root.string("tag_name")?.trim().orEmpty()
        if (tag.isEmpty()) return Outcome.NotARelease
        val assets = (root["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val apk = pickApkAsset(assets) ?: return Outcome.NoApk(tag)
        val apkUrl = apk.string("browser_download_url").orEmpty()
        if (apkUrl.isEmpty()) return Outcome.NoApk(tag)
        val digest = parseDigest(apk.string("digest"))
        val sumsUrl = if (digest == null) {
            assets.firstOrNull { it.string("name").equals(SHA256SUMS_NAME, ignoreCase = true) }
                ?.string("browser_download_url")
        } else {
            null
        }
        val release = ReleaseInfo(
            tagName = tag,
            version = tag.removePrefix("v").removePrefix("V"),
            name = root.string("name")?.takeIf { it.isNotBlank() } ?: tag,
            notes = root.string("body").orEmpty(),
            apkUrl = apkUrl,
            apkSizeBytes = apk.long("size") ?: 0L,
            apkSha256 = digest,
            publishedAt = root.string("published_at").orEmpty(),
            htmlUrl = root.string("html_url").orEmpty(),
        )
        return Outcome.Release(Parsed(release, sumsUrl))
    }

    /**
     * The asset to install: an `.apk`, preferring one whose name contains "imu-mapper" so a stray
     * extra APK (a debug build attached by hand, say) does not win over the release artefact.
     */
    fun pickApkAsset(assets: List<JsonObject>): JsonObject? {
        val apks = assets.filter { it.string("name")?.lowercase()?.endsWith(".apk") == true }
        return apks.firstOrNull { it.string("name")?.lowercase()?.contains("imu-mapper") == true }
            ?: apks.firstOrNull()
    }

    /** "sha256:<hex>" -> lower-case hex; anything else (other algorithms, garbage) -> null. */
    fun parseDigest(digest: String?): String? {
        val text = digest?.trim() ?: return null
        val hex = text.substringAfter("sha256:", "").trim().lowercase()
        return hex.takeIf { isSha256Hex(it) }
    }

    /**
     * Finds the hash for [fileName] in `sha256sum` output ("<hex>  path/to/file" per line). Only the
     * base name is compared because the release workflow hashes `dist/<file>`.
     */
    fun findInSha256Sums(sums: String, fileName: String): String? {
        val wanted = fileName.substringAfterLast('/').lowercase()
        for (line in sums.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val hex = trimmed.substringBefore(' ').lowercase()
            if (!isSha256Hex(hex)) continue
            // sha256sum prints two spaces (or " *" for binary mode) between hash and name.
            val path = trimmed.substring(hex.length).trim().removePrefix("*")
            if (path.substringAfterLast('/').lowercase() == wanted) return hex
        }
        return null
    }

    fun isSha256Hex(text: String): Boolean = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }

    private fun JsonObject.string(key: String): String? {
        val element: JsonElement = this[key] ?: return null
        if (element is JsonNull) return null
        val primitive: JsonPrimitive = (element as? JsonPrimitive) ?: return null
        return if (primitive.isString) primitive.content else primitive.content.takeIf { it != "null" }
    }

    private fun JsonObject.long(key: String): Long? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return runCatching { element.jsonPrimitive.content.toLong() }.getOrNull()
    }

    /** The `assets` array of a release body, for callers that want to inspect asset selection. */
    fun assetsOf(body: String): List<JsonObject> =
        runCatching { json.parseToJsonElement(body).jsonObject["assets"]?.jsonArray }
            .getOrNull()
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

    const val SHA256SUMS_NAME = "SHA256SUMS.txt"
}
