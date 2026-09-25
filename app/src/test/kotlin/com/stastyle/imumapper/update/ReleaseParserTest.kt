package com.stastyle.imumapper.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReleaseParserTest {

    private val digestRelease = """
        {
          "url": "https://api.github.com/repos/Stastyle/IMU-mapper/releases/1",
          "html_url": "https://github.com/Stastyle/IMU-mapper/releases/tag/v1.2.3",
          "tag_name": "v1.2.3",
          "name": "IMU Mapper v1.2.3",
          "draft": false,
          "prerelease": false,
          "published_at": "2026-09-20T10:11:12Z",
          "author": {"login": "someone", "id": 1},
          "assets": [
            {
              "name": "SHA256SUMS.txt",
              "content_type": "text/plain",
              "size": 98,
              "digest": null,
              "browser_download_url": "https://github.com/Stastyle/IMU-mapper/releases/download/v1.2.3/SHA256SUMS.txt"
            },
            {
              "name": "imu-mapper-v1.2.3.apk",
              "content_type": "application/vnd.android.package-archive",
              "size": 12345678,
              "digest": "sha256:AB12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34",
              "browser_download_url":
                "https://github.com/Stastyle/IMU-mapper/releases/download/v1.2.3/imu-mapper-v1.2.3.apk"
            }
          ],
          "body": "## What's Changed\n* Fix heading drift by @someone in https://github.com/Stastyle/pull/7\n"
        }
    """.trimIndent()

    @Test
    fun parsesReleaseWithDigest() {
        val parsed = assertNotNull(ReleaseParser.parse(digestRelease))
        val r = parsed.release
        assertEquals("v1.2.3", r.tagName)
        assertEquals("1.2.3", r.version)
        assertEquals("IMU Mapper v1.2.3", r.name)
        assertEquals("https://github.com/Stastyle/IMU-mapper/releases/tag/v1.2.3", r.htmlUrl)
        assertEquals("2026-09-20T10:11:12Z", r.publishedAt)
        assertEquals(12345678L, r.apkSizeBytes)
        assertEquals("ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34", r.apkSha256)
        assertEquals("imu-mapper-v1.2.3.apk", r.apkFileName)
        assertEquals(
            "https://github.com/Stastyle/IMU-mapper/releases/download/v1.2.3/imu-mapper-v1.2.3.apk",
            r.apkUrl,
        )
        // The digest is enough; the sums file is not needed.
        assertNull(parsed.sha256SumsUrl)
    }

    @Test
    fun fallsBackToSumsFileWhenDigestMissing() {
        val body = digestRelease.replace(
            "\"digest\": \"sha256:AB12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34\"",
            "\"digest\": null",
        )
        val parsed = assertNotNull(ReleaseParser.parse(body))
        assertNull(parsed.release.apkSha256)
        assertEquals(
            "https://github.com/Stastyle/IMU-mapper/releases/download/v1.2.3/SHA256SUMS.txt",
            parsed.sha256SumsUrl,
        )
    }

    @Test
    fun missingOptionalFieldsAreTolerated() {
        val body = """
            {"tag_name":"v0.1.0","assets":[{"name":"app.apk","browser_download_url":"https://x/app.apk"}]}
        """.trimIndent()
        val parsed = assertNotNull(ReleaseParser.parse(body))
        val r = parsed.release
        assertEquals("v0.1.0", r.name)
        assertEquals("", r.notes)
        assertEquals("", r.htmlUrl)
        assertEquals(0L, r.apkSizeBytes)
        assertNull(r.apkSha256)
        assertNull(parsed.sha256SumsUrl)
        assertEquals("app.apk", r.apkFileName)
    }

    @Test
    fun nullBodyIsEmptyNotes() {
        val body =
            """{"tag_name":"v0.1.0","body":null,"assets":[{"name":"a.apk","browser_download_url":"https://x"}]}"""
        assertEquals("", assertNotNull(ReleaseParser.parse(body)).release.notes)
    }

    @Test
    fun releaseWithoutApkIsNull() {
        val body = """{"tag_name":"v1.0.0","assets":[{"name":"notes.txt","browser_download_url":"https://x/n.txt"}]}"""
        assertNull(ReleaseParser.parse(body))
        assertNull(ReleaseParser.parse("""{"tag_name":"v1.0.0"}"""))
        assertNull(ReleaseParser.parse("""{"message":"Not Found"}"""))
        assertNull(ReleaseParser.parse("not json at all"))
        assertNull(ReleaseParser.parse("[]"))
    }

    @Test
    fun prefersImuMapperApkOverOtherApks() {
        val body = """
            {"tag_name":"v2.0.0","assets":[
              {"name":"debug-build.apk","browser_download_url":"https://x/debug-build.apk","size":1},
              {"name":"IMU-Mapper-v2.0.0.apk","browser_download_url":"https://x/IMU-Mapper-v2.0.0.apk","size":2},
              {"name":"other.apk","browser_download_url":"https://x/other.apk","size":3}
            ]}
        """.trimIndent()
        val r = assertNotNull(ReleaseParser.parse(body)).release
        assertEquals("https://x/IMU-Mapper-v2.0.0.apk", r.apkUrl)
        assertEquals(2L, r.apkSizeBytes)
    }

    @Test
    fun fallsBackToFirstApkWhenNoneIsNamedImuMapper() {
        val body = """
            {"tag_name":"v2.0.0","assets":[
              {"name":"readme.md","browser_download_url":"https://x/readme.md"},
              {"name":"first.APK","browser_download_url":"https://x/first.APK"},
              {"name":"second.apk","browser_download_url":"https://x/second.apk"}
            ]}
        """.trimIndent()
        assertEquals("https://x/first.APK", assertNotNull(ReleaseParser.parse(body)).release.apkUrl)
        assertEquals(2, ReleaseParser.assetsOf(body).count { it["name"].toString().lowercase().contains(".apk") })
    }

    @Test
    fun parsesDigests() {
        val hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        assertEquals(hex, ReleaseParser.parseDigest("sha256:$hex"))
        assertEquals(hex, ReleaseParser.parseDigest(" sha256:${hex.uppercase()} "))
        assertNull(ReleaseParser.parseDigest(null))
        assertNull(ReleaseParser.parseDigest(""))
        assertNull(ReleaseParser.parseDigest("md5:abc"))
        assertNull(ReleaseParser.parseDigest("sha256:tooshort"))
        assertNull(ReleaseParser.parseDigest(hex))
    }

    @Test
    fun findsHashInSha256SumsOutput() {
        val hexApk = "1111111111111111111111111111111111111111111111111111111111111111"
        val hexOther = "2222222222222222222222222222222222222222222222222222222222222222"
        // Exactly what the release workflow writes: sha256sum over dist/<file>.
        val sums = "$hexApk  dist/imu-mapper-v1.2.3.apk\n$hexOther *dist/other.bin\n\n"
        assertEquals(hexApk, ReleaseParser.findInSha256Sums(sums, "imu-mapper-v1.2.3.apk"))
        assertEquals(hexApk, ReleaseParser.findInSha256Sums(sums, "IMU-MAPPER-V1.2.3.APK"))
        assertEquals(hexOther, ReleaseParser.findInSha256Sums(sums, "other.bin"))
        assertNull(ReleaseParser.findInSha256Sums(sums, "missing.apk"))
        assertNull(ReleaseParser.findInSha256Sums("garbage line\n", "imu-mapper-v1.2.3.apk"))
        assertNull(ReleaseParser.findInSha256Sums("", "imu-mapper-v1.2.3.apk"))
    }
}
