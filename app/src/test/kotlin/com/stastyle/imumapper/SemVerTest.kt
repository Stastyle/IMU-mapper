package com.stastyle.imumapper

import com.stastyle.imumapper.update.SemVer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemVerTest {
    @Test
    fun parsesCommonForms() {
        assertEquals(SemVer.Version(1, 2, 3, null), SemVer.parse("v1.2.3"))
        assertEquals(SemVer.Version(1, 2, 0, null), SemVer.parse("1.2"))
        assertEquals(SemVer.Version(0, 0, 1, "debug"), SemVer.parse("0.0.1-debug"))
        assertNull(SemVer.parse("latest"))
        assertNull(SemVer.parse("1.2.3.4"))
    }

    @Test
    fun ordersVersions() {
        assertTrue(SemVer.isNewer("v1.0.1", "1.0.0"))
        assertTrue(SemVer.isNewer("2.0.0", "1.9.9"))
        assertTrue(SemVer.isNewer("1.0.0", "1.0.0-beta.1"))
        assertFalse(SemVer.isNewer("1.0.0", "1.0.0"))
        assertFalse(SemVer.isNewer("0.9.0", "1.0.0"))
        assertFalse(SemVer.isNewer("garbage", "1.0.0"))
        // A debug build of the same number is not older than the release.
        assertFalse(SemVer.isNewer("0.0.1", "0.0.1-debug") && SemVer.isNewer("0.0.1-debug", "0.0.1"))
    }
}
