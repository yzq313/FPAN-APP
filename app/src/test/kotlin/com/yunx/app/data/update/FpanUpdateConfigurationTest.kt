package com.yunx.app.data.update

import com.yunx.app.util.AppLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FpanUpdateConfigurationTest {
    @Test
    fun updateAndProjectLinksUseFpanRepository() {
        assertEquals("yzq313/FPAN-APP", UpdateChecker.APP_REPO)
        assertEquals("https://github.com/${UpdateChecker.APP_REPO}", AppLinks.GITHUB_REPO)
        assertEquals("github.com/${UpdateChecker.APP_REPO}", AppLinks.GITHUB_REPO_DISPLAY)
    }

    @Test
    fun releaseTagMatchesInstalledVersion() {
        assertEquals(0, UpdateChecker.compareVersions("1.3.1", "v1.3.1"))
        assertTrue(UpdateChecker.compareVersions("v1.3.1", "1.1") > 0)
    }
}
