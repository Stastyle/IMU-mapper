package com.stastyle.imumapper.update

import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseNotesTest {

    @Test
    fun stripsGitHubGeneratedNotes() {
        val md = """
            ## What's Changed
            * Fix **heading** drift by @someone in https://github.com/Stastyle/IMU-mapper/pull/7
            * Add `SHA256SUMS.txt` to releases

            **Full Changelog**: https://github.com/Stastyle/IMU-mapper/compare/v1.0.0...v1.1.0
        """.trimIndent()
        val expected = """
            What's Changed
            • Fix heading drift by @someone in https://github.com/Stastyle/IMU-mapper/pull/7
            • Add SHA256SUMS.txt to releases

            Full Changelog: https://github.com/Stastyle/IMU-mapper/compare/v1.0.0...v1.1.0
        """.trimIndent()
        assertEquals(expected, ReleaseNotes.plainText(md))
    }

    @Test
    fun handlesLinksImagesQuotesRulesAndFences() {
        val md = "# Title\r\n\r\n> quoted [link text](https://x) and ![shot](https://img)\r\n---\r\n" +
            "1) first\r\n2. second _em_ and *more*\r\n  - nested\r\n```\r\ncode *stays*\r\n```\r\n" +
            "<!-- hidden -->\r\n<b>bold</b> <https://auto.link>\r\n\r\n\r\n\r\nend"
        val expected = "Title\n\nquoted link text and shot\n1. first\n2. second em and more\n    - nested\n" +
            "code *stays*\n\nbold https://auto.link\n\nend"
        assertEquals(expected, ReleaseNotes.plainText(md))
    }

    @Test
    fun leavesPlainTextAndMathAlone() {
        assertEquals("", ReleaseNotes.plainText("   \n"))
        assertEquals("2 * 3 = 6 and snake_case_name", ReleaseNotes.plainText("2 * 3 = 6 and snake_case_name"))
        assertEquals("plain sentence.", ReleaseNotes.plainText("plain sentence."))
    }
}
