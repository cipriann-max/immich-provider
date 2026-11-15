package dev.immich.picker.data

import dev.immich.picker.BuildConfig
import dev.immich.picker.api.ServerVersionResponseDto

/**
 * Severity of the difference between the server the user is connected to and the
 * server version this build's API client was generated from.
 */
enum class VersionSeverity {
    /** Same major version. Nothing to tell the user. */
    OK,

    /** Server is a newer major version. Keep working, but warn. */
    SERVER_NEWER_MAJOR,

    /** Patch/minor drift inside the same major. Informational only. */
    SERVER_DIFFERENT_MINOR,

    /** Server older than the version this build was tested against. Ask to update. */
    SERVER_OLDER,
}

data class VersionAssessment(
    val severity: VersionSeverity,
    val testedVersion: String,
    val serverVersion: String,
    val message: String?,
) {
    /** True for anything the user should be told about. */
    val isWarning: Boolean get() = severity != VersionSeverity.OK
}

/**
 * Compares server version against [BuildConfig.TESTED_IMMICH_VERSION].
 *
 * Rules from the task:
 *  - same major  -> normal operation
 *  - newer major -> keep working + warn (server ahead of the client)
 *  - older       -> warn the user to update the server
 */
object VersionGate {

    val testedVersion: String = BuildConfig.TESTED_IMMICH_VERSION
    val testedMajor: Int = testedVersion.substringBefore('.').toIntOrNull() ?: 0

    fun assess(server: ServerVersionResponseDto): VersionAssessment {
        val serverString = format(
            server.major.toInt(),
            server.minor.toInt(),
            server.patch.toInt(),
            server.prerelease?.toInt(),
        )
        return assess(
            server.major.toInt(),
            server.minor.toInt(),
            server.patch.toInt(),
            serverString,
        )
    }

    fun assess(serverVersionString: String?): VersionAssessment {
        if (serverVersionString.isNullOrBlank()) {
            return VersionAssessment(
                severity = VersionSeverity.OK,
                testedVersion = testedVersion,
                serverVersion = "unknown",
                message = null,
            )
        }
        val parts = serverVersionString.trim().removePrefix("v").split('.', '-', '+')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return assess(major, minor, patch, serverVersionString)
    }

    fun assess(major: Int, minor: Int, patch: Int, serverString: String): VersionAssessment {
        val tested = testedVersion.split('.').mapNotNull { it.toIntOrNull() }
        val testedMinor = tested.getOrNull(1) ?: 0
        val testedPatch = tested.getOrNull(2) ?: 0

        val severity = when {
            major > testedMajor -> VersionSeverity.SERVER_NEWER_MAJOR
            major < testedMajor -> VersionSeverity.SERVER_OLDER
            minor > testedMinor -> VersionSeverity.SERVER_DIFFERENT_MINOR
            minor < testedMinor -> VersionSeverity.SERVER_OLDER
            patch < testedPatch -> VersionSeverity.SERVER_DIFFERENT_MINOR
            else -> VersionSeverity.OK
        }

        val message = when (severity) {
            VersionSeverity.OK -> null
            VersionSeverity.SERVER_NEWER_MAJOR ->
                "Your server runs Immich $serverString, which is newer than the " +
                    "Immich $testedVersion this build was made for. It should keep " +
                    "working, but some things may break. Update the app when a new " +
                    "version is available."
            VersionSeverity.SERVER_DIFFERENT_MINOR ->
                "Your server runs Immich $serverString; this build was tested against " +
                    "Immich $testedVersion. Minor differences are expected to be harmless."
            VersionSeverity.SERVER_OLDER ->
                "Your server runs Immich $serverString, which is older than the " +
                    "Immich $testedVersion this build was made for. Please update your " +
                    "Immich server."
        }

        return VersionAssessment(severity, testedVersion, serverString, message)
    }

    fun format(major: Int, minor: Int, patch: Int, prerelease: Int?): String =
        if (prerelease != null) "$major.$minor.$patch-rc.$prerelease" else "$major.$minor.$patch"
}
