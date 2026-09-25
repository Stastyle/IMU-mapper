package com.stastyle.imumapper.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemVerTest {

    @Test
    fun parsesCommonForms() {
        assertEquals(SemVer.Version(1, 2, 3, null), SemVer.parse("1.2.3"))
        assertEquals(SemVer.Version(1, 2, 3, null), SemVer.parse("v1.2.3"))
        assertEquals(SemVer.Version(1, 2, 0, null), SemVer.parse("1.2"))
        assertEquals(SemVer.Version(1, 2, 3, "beta.1"), SemVer.parse("1.2.3-beta.1"))
        assertEquals(SemVer.Version(1, 2, 3, "debug"), SemVer.parse("1.2.3-debug"))
        assertEquals(SemVer.Version(1, 2, 3, null), SemVer.parse("1.2.3+build.7"))
        assertNull(SemVer.parse("latest"))
        assertNull(SemVer.parse("1.2.3.4"))
        assertNull(SemVer.parse(""))
    }

    @Test
    fun ordersReleasesAndPreReleases() {
        assertTrue(SemVer.isNewer("1.2.4", "1.2.3"))
        assertTrue(SemVer.isNewer("1.3.0", "1.2.9"))
        assertTrue(SemVer.isNewer("2.0.0", "1.99.99"))
        assertTrue(SemVer.isNewer("1.2.3", "1.2.3-beta.1"))
        assertFalse(SemVer.isNewer("1.2.3", "1.2.3"))
        assertFalse(SemVer.isNewer("1.2.3", "1.2.4"))
        assertFalse(SemVer.isNewer("1.2.3-beta.1", "1.2.3"))
        assertFalse(SemVer.isNewer("garbage", "1.2.3"))
        assertFalse(SemVer.isNewer("1.2.4", "garbage"))
    }

    @Test
    fun debugBuildsAreRecognisedFromTheVersionNameSuffix() {
        // The debug build type appends "-debug"; the default versionName is 0.0.1.
        assertTrue(SemVer.isDebugBuild("0.0.1-debug"))
        assertTrue(SemVer.isDebugBuild("1.2.3-debug"))
        assertTrue(SemVer.isDebugBuild(" 1.2.3-DEBUG "))
        assertFalse(SemVer.isDebugBuild("1.2.3"))
        assertFalse(SemVer.isDebugBuild("1.2.3-beta.1"))
        assertFalse(SemVer.isDebugBuild("debug"))
        // Ordering alone would still call the release newer, which is why the updater checks the suffix first.
        assertTrue(SemVer.isNewer("1.2.3", "1.2.3-debug"))
    }
}
