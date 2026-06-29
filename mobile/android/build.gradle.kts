plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// REMOVED: prepareAndroidRecoverySource (scripts/apply_android_0_7_8_patch.py).
// That script existed to re-apply the "Open CR / Analyze" + cross-frame-bridge
// (crossFrameReport/FrameReportNormalizer) wiring if MainActivity.kt ever
// regressed back to an older state missing it. That entire architecture has
// now been deliberately removed (single Open CR Results button, single
// top-frame-only fetch/parse pipeline -- see runMode's comment in
// MainActivity.kt for why), so the script's "after" state no longer exists
// in source and it can only ever fail. The script file itself is left in
// place for history/reference but is no longer wired into the build.
