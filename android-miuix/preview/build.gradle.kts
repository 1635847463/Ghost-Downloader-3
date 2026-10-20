import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    sourceSets["main"].kotlin.srcDir("../shared-ui/src")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

compose.desktop {
    application {
        mainClass = "io.github.xiaoyouchr.ghostdownloader.miuix.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Ghost Downloader Miuix"
            packageVersion = "1.0.0"
        }
    }
}

// 无窗口渲染 Miuix 界面截图，产物输出到 android-miuix/screenshots/
tasks.register<JavaExec>("screenshot") {
    group = "verification"
    description = "Headless render Miuix UI screenshots (840x1800)"
    mainClass.set("io.github.xiaoyouchr.ghostdownloader.miuix.ScreenshotKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    systemProperty("skiko.renderApi", "SOFTWARE")
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
}

// 下载引擎冒烟测试：gradlew :preview:engineSmoke -Purl=https://... 
tasks.register<JavaExec>("engineSmoke") {
    group = "verification"
    description = "Probe + download a URL with the built-in engine (no GUI)"
    mainClass.set("io.github.xiaoyouchr.ghostdownloader.miuix.SmokeDownloadKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(
        project.findProperty("url")?.toString()
            ?: "https://raw.githubusercontent.com/XiaoYouChR/Ghost-Downloader-3/main/README.md"
    )
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
}
