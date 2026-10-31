package org.kundakarlab.nimsresults

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Fingerprint of the current installed APK's public signing certificate.
 * Not a private key; safe to include in redacted diagnostics for upgrade audit.
 */
object ApkSigningIdentity {
    @Suppress("DEPRECATION")
    fun sha256(context: Context): String = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        else pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        val cert = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            info.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray()
        else info.signatures?.singleOrNull()?.toByteArray()
        if (cert == null) "unavailable" else MessageDigest.getInstance("SHA-256")
            .digest(cert).joinToString("") { "%02X".format(it) }
    }.getOrDefault("unavailable")
}
