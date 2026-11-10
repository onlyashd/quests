package org.ferabrasil.quests.app

/**
 * Facts about the running app build. The version is this platform's entry in versions.properties
 * (`android=` or `ios=`), the same string as the release tag's `<version>` part, pre-release
 * suffix included (e.g. `0.2.0-rc.1`). Settings shows it so bug reports can name the exact build.
 */
object AppInfo {
    val version: String get() = platformAppVersion
}

internal expect val platformAppVersion: String
