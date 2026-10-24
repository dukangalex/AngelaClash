import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    kotlin("android")
    kotlin("kapt")
    id("com.android.application")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("com.android.tools.build:apksig:8.8.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    compileOnly(project(":hideapi"))

    implementation(project(":core"))
    implementation(project(":service"))
    implementation(project(":design"))
    implementation(project(":common"))

    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.coordinator)
    implementation(libs.androidx.recyclerview)
    implementation(libs.google.material)
    implementation(libs.quickie.bundled)
    implementation(libs.androidx.activity.ktx)
}

tasks.getByName("clean", type = Delete::class) {
    delete(file("release"))
}

val geoFilesDownloadDir = "src/main/assets"
val geoChecksumFile = rootProject.file("gradle/geo-files.sha256")

task("downloadGeoFiles") {

    val geoFilesUrls = mapOf(
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb" to "geoip.metadb",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat" to "geosite.dat",
        // "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/country.mmdb" to "country.mmdb",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb" to "ASN.mmdb",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/BundleMRS.7z" to "BundleMRS.7z",
    )

    // These files ship inside the APK and drive routing decisions, but the
    // "latest" release tag is mutable. Pin their hashes in gradle/geo-files.sha256
    // so a compromised or silently updated upstream cannot change the bundle.
    // Refresh on purpose with: ./gradlew app:downloadGeoFiles -PgeoRefresh=true
    val refreshChecksums = (project.findProperty("geoRefresh") as String?)?.toBoolean() == true

    doLast {
        fun readChecksums(): Map<String, String> {
            if (!geoChecksumFile.isFile) return emptyMap()
            return geoChecksumFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.split(Regex("""\s+"""), limit = 2)
                    if (parts.size == 2) parts[1].trim() to parts[0].trim().lowercase() else null
                }
                .toMap()
        }

        fun sha256(path: java.nio.file.Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        val expected = readChecksums()
        val computed = LinkedHashMap<String, String>()

        geoFilesUrls.forEach { (downloadUrl, outputFileName) ->
            val url = URL(downloadUrl)
            val outputPath = file("$geoFilesDownloadDir/$outputFileName")
            outputPath.parentFile.mkdirs()
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            val tempPath = Files.createTempFile(outputPath.parentFile.toPath(), "$outputFileName.", ".download")

            try {
                connection.connect()
                val statusCode = connection.responseCode
                if (statusCode !in 200..299) {
                    throw GradleException("Failed to download $outputFileName: HTTP $statusCode")
                }

                connection.inputStream.use { input ->
                    Files.newOutputStream(tempPath).use { output -> input.copyTo(output) }
                }
                if (Files.size(tempPath) == 0L) {
                    throw GradleException("Downloaded $outputFileName is empty")
                }

                val actual = sha256(tempPath)
                if (refreshChecksums) {
                    computed[outputFileName] = actual
                } else {
                    val want = expected[outputFileName]
                        ?: throw GradleException(
                            "No pinned checksum for $outputFileName. " +
                                "Run ./gradlew app:downloadGeoFiles -PgeoRefresh=true and commit gradle/geo-files.sha256."
                        )
                    if (!want.equals(actual, ignoreCase = true)) {
                        throw GradleException(
                            "Checksum mismatch for $outputFileName: pinned $want, downloaded $actual. " +
                                "If the upstream update is intended, refresh with -PgeoRefresh=true and review the diff."
                        )
                    }
                }

                try {
                    Files.move(
                        tempPath,
                        outputPath.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tempPath, outputPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                println("$outputFileName downloaded to $outputPath (sha256 $actual)")
            } finally {
                connection.disconnect()
                Files.deleteIfExists(tempPath)
            }
        }

        if (refreshChecksums) {
            val header = "# SHA-256 of the geo data bundled into the APK.\n" +
                "# Refresh with ./gradlew app:downloadGeoFiles -PgeoRefresh=true and review the diff.\n"
            geoChecksumFile.writeText(
                header + computed.entries.joinToString("") { (name, hash) -> "$hash  $name\n" }
            )
            println("Updated ${geoChecksumFile.absolutePath}")
        }
    }
}

afterEvaluate {
    val downloadGeoFilesTask = tasks["downloadGeoFiles"]

    tasks.forEach {
        if (it.name.startsWith("assemble")) {
            it.dependsOn(downloadGeoFilesTask)
        }
    }
}

tasks.getByName("clean", type = Delete::class) {
    delete(file(geoFilesDownloadDir))
}

// apksig uses Java 8 APIs; retain API 21 support through core-library desugaring.
android {
    compileOptions { isCoreLibraryDesugaringEnabled = true }
}
