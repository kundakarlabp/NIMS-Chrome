plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

val prepareAndroidSource by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir.parentFile.parentFile)
    commandLine(
        "bash",
        "-lc",
        "python3 scripts/fix_materializer_raw_string.py && python3 scripts/apply_android_0_8_1_patch.py"
    )
}

subprojects {
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(rootProject.tasks.named("prepareAndroidSource"))
    }
}
