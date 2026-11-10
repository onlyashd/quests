package org.ferabrasil.quests

import org.ferabrasil.quests.app.AppInfo
import kotlin.test.Test
import kotlin.test.assertTrue

class AppInfoTest {
    @Test
    fun versionIsTheReleaseFormat() {
        // Same grammar as .cicd/bump-version.sh: MAJOR.MINOR.PATCH[-(canary|alpha|beta|rc).N]
        val semver = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-(canary|alpha|beta|rc)\.(0|[1-9]\d*))?""")
        assertTrue(semver.matches(AppInfo.version), "unexpected version '${AppInfo.version}'")
    }
}
