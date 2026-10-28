import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Исходники движков: byedpi (обход DPI) и hev-socks5-tunnel (VPN → SOCKS).
// По умолчанию лежат в src/main/jni, путь можно переопределить: -PenginesDir=...
val enginesDir: File = (findProperty("enginesDir") as String?)?.let { file(it) } ?: file("src/main/jni")
val byedpiDir = enginesDir.resolve("byedpi")
val hevDir = enginesDir.resolve("hev-socks5-tunnel")

val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val minApi = 24

// Ключ подписи хранится вне репозитория: android/keystore.properties (в .gitignore)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.fuzik.zapret"
    compileSdk = 35
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.fuzik.zapret"
        minSdk = minApi
        targetSdk = 35
        versionCode = 9
        versionName = "1.3.0"
        ndk { abiFilters += abis }
        externalNativeBuild {
            ndkBuild {
                arguments += listOf(
                    "HEV_DIR=${hevDir.absolutePath.replace('\\', '/')}",
                    "APP_PLATFORM=android-$minApi",
                )
            }
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        ndkBuild { path = file("src/main/jni/Android.mk") }
    }

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("byedpi"))

    // byedpi запускается как отдельная программа — ей нужен настоящий файл на диске
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // в проверках на компьютере вызовы android.util.Log просто ничего не делают
    testOptions { unitTests.isReturnDefaultValues = true }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// --- Сборка byedpi как исполняемого файла libciadpi.so для каждой архитектуры ---
val clangTriple = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7a-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
)
val byedpiSources = listOf("packets.c", "main.c", "conev.c", "proxy.c", "desync.c", "mpool.c", "extend.c")

// Наша правка byedpi: при ZAPRET_DROP_QUIC=1 UDP-пакеты на порт 443 (QUIC) не пересылаются.
// Приложения (YouTube) тогда сразу переходят на TCP, где обход DPI работает.
// Правка накладывается на копию исходников — подмодуль остаётся как у автора.
val quicMarker = """
            int offs = s5_get_addr(data, n, &addr, SOCK_DGRAM);
            if (offs < 0) {
                LOG(LOG_E, "udp parse error\n");
                return -1;
            }
""".trimStart('\n')
val quicPatch = quicMarker + """
            static int drop_quic = -1;
            if (drop_quic < 0) {
                drop_quic = getenv("ZAPRET_DROP_QUIC") != NULL;
            }
            if (drop_quic && ntohs(addr.in.sin_port) == 443) {
                continue;
            }
""".trimStart('\n')

val buildByedpi by tasks.registering {
    description = "Собирает byedpi (ciadpi) через NDK"
    inputs.files(byedpiSources.map { byedpiDir.resolve(it) })
    inputs.property("quicPatch", quicPatch)
    val outRoot = layout.buildDirectory.dir("byedpi")
    val srcCopy = layout.buildDirectory.dir("byedpi-src")
    outputs.dir(outRoot)
    doLast {
        val src = srcCopy.get().asFile
        src.deleteRecursively()
        byedpiDir.copyRecursively(src)
        val proxy = src.resolve("proxy.c")
        val code = proxy.readText().replace("\r\n", "\n")
        if (!code.contains(quicMarker)) throw GradleException("byedpi: не найдено место для правки QUIC — проверьте версию")
        proxy.writeText(code.replace(quicMarker, quicPatch))

        val ndk = android.ndkDirectory
        val host = if (System.getProperty("os.name").startsWith("Windows")) "windows-x86_64" else "linux-x86_64"
        val ext = if (host.startsWith("windows")) ".cmd" else ""
        for (abi in abis) {
            val clang = ndk.resolve("toolchains/llvm/prebuilt/$host/bin/${clangTriple[abi]}$minApi-clang$ext")
            val out = outRoot.get().asFile.resolve("$abi/libciadpi.so")
            out.parentFile.mkdirs()
            val cmd = listOf(clang.absolutePath, "-D_DEFAULT_SOURCE", "-I", src.absolutePath,
                "-std=c99", "-O2", "-Wno-unused", "-fPIE", "-pie", "-s", "-o", out.absolutePath) +
                byedpiSources.map { src.resolve(it).absolutePath }
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val log = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() != 0) throw GradleException("byedpi ($abi) не собрался:\n$log")
        }
    }
}
// --- Символические ссылки в исходниках hev ---------------------------------
// Git на Windows без режима разработчика сохраняет симлинки как текстовые файлы
// с путём внутри, и заголовки не находятся. Заменяем такие файлы копиями целей.
val materializeHevSymlinks by tasks.registering {
    description = "Заменяет симлинки в hev-socks5-tunnel копиями файлов (для Windows)"
    doLast {
        val repos = listOf(hevDir) + listOf("src/core", "third-part/hev-task-system", "third-part/yaml", "third-part/lwip")
            .map { hevDir.resolve(it) }.filter { it.resolve(".git").exists() }
        for (repo in repos) {
            val proc = ProcessBuilder("git", "-C", repo.absolutePath, "ls-files", "-s").redirectErrorStream(true).start()
            val links = proc.inputStream.bufferedReader().readLines()
                .filter { it.startsWith("120000 ") }.map { it.substringAfter('\t') }
            proc.waitFor()
            for (path in links) {
                val stub = repo.resolve(path)
                if (!stub.isFile || stub.length() > 512) continue  // уже настоящий файл или ссылка
                val target = stub.parentFile.resolve(stub.readText().trim()).normalize()
                if (target.isFile) target.copyTo(stub, overwrite = true)
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(buildByedpi, materializeHevSymlinks) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
