import groovy.json.JsonSlurper
import java.util.jar.JarFile

plugins {
    id("java")
}

group = "dev.ifelse.zirion"
val modVersion = providers.gradleProperty("modVersion").get()
version = modVersion

val hytaleVersion = providers.gradleProperty("hytaleVersion").get()
val pluginMainClass = "dev.ifelse.zirion.genesis.GenesisPlugin"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    maven {
        name = "HytaleRelease"
        url = uri("https://maven.hytale.com/release")
        content { includeGroup("com.hypixel.hytale") }
    }
    mavenCentral()
}

val hytaleServerRuntime = configurations.create("hytaleServerRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    compileOnly("com.hypixel.hytale:Server:$hytaleVersion")
    add(hytaleServerRuntime.name, "com.hypixel.hytale:Server:$hytaleVersion")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val metadata = mapOf(
        "modVersion" to modVersion,
        "hytaleVersion" to hytaleVersion,
        "pluginMainClass" to pluginMainClass,
    )
    inputs.properties(metadata)
    filesMatching("manifest.json") { expand(metadata) }
}

tasks.jar {
    archiveBaseName.set("Genesis")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test {
    useJUnitPlatform()
}

val pluginJar = tasks.named<Jar>("jar")

val verifyPluginJar = tasks.register("verifyPluginJar") {
    group = "verification"
    description = "Checks the Hytale manifest, plugin entry point and test NPC resources."
    dependsOn(pluginJar)
    inputs.file(pluginJar.flatMap { it.archiveFile })
    doLast {
        JarFile(pluginJar.get().archiveFile.get().asFile).use { jar ->
            val manifestEntry = checkNotNull(jar.getJarEntry("manifest.json")) {
                "manifest.json must be at the root of the plugin JAR."
            }
            val manifest = jar.getInputStream(manifestEntry).use {
                JsonSlurper().parse(it) as Map<*, *>
            }
            check(manifest["Name"] == "Genesis")
            check(manifest["Version"] == modVersion)
            check(manifest["ServerVersion"] == hytaleVersion)
            check(manifest["Main"] == pluginMainClass)
            check(manifest["IncludesAssetPack"] == true)
            check(jar.getJarEntry(pluginMainClass.replace('.', '/') + ".class") != null) {
                "The manifest Main class is missing from the plugin JAR."
            }
            check(jar.entries().asSequence().none { it.name.startsWith("com/hypixel/hytale/") }) {
                "The plugin JAR must not bundle the Hytale server API."
            }
            check(jar.entries().asSequence().none { it.name.endsWith(".bbmodel") || it.name.startsWith("art/") }) {
                "Blockbench source projects must not be included in the plugin JAR."
            }
            fun readJson(path: String): Map<*, *> {
                val entry = checkNotNull(jar.getJarEntry(path)) { "Missing NPC asset: $path" }
                return jar.getInputStream(entry).use { JsonSlurper().parse(it) as Map<*, *> }
            }
            val role = readJson("Server/NPC/Roles/Genesis/Genesis_TestNPC.json")
            check(role["Appearance"] == "Genesis_TestNPC")
            val model = readJson("Server/Models/Genesis/Genesis_TestNPC.json")
            for (field in listOf("Model", "Texture")) {
                val path = "Common/${model[field]}"
                check(jar.getJarEntry(path) != null) { "Missing model $field resource: $path" }
            }
            val sets = model["AnimationSets"] as Map<*, *>
            val idle = sets["Idle"] as Map<*, *>
            val animations = idle["Animations"] as List<*>
            check(animations.isNotEmpty()) { "The test NPC needs an Idle animation." }
            for (animation in animations) {
                val path = "Common/${(animation as Map<*, *>)["Animation"]}"
                val data = readJson(path)
                check((data["duration"] as Number).toInt() > 0)
                check((data["nodeAnimations"] as Map<*, *>).isNotEmpty())
            }
            for (locale in listOf("en-US", "ru-RU")) {
                val entry = checkNotNull(jar.getJarEntry("Server/Languages/$locale/server.lang"))
                val translations = jar.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                check(translations.lineSequence().any { it.startsWith("genesis.npcRoles.Genesis_TestNPC.name = ") })
            }
        }
    }
}

tasks.check { dependsOn(verifyPluginJar) }

val stagePlugin = tasks.register<Copy>("stagePlugin") {
    group = "hytale"
    description = "Copies Genesis.jar into the isolated development server's mods directory."
    dependsOn(verifyPluginJar)
    from(pluginJar.flatMap { it.archiveFile })
    into(layout.projectDirectory.dir("run/mods"))
    rename { "Genesis.jar" }
}

val hytaleAssetsFile = providers.gradleProperty("hytaleAssets").orNull?.let { file(it) }

tasks.register<JavaExec>("runServer") {
    group = "hytale"
    description = "Runs Hytale $hytaleVersion locally; requires -PhytaleAssets=/path/to/Assets.zip."
    dependsOn(stagePlugin)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    classpath = hytaleServerRuntime
    mainClass.set("com.hypixel.hytale.Main")
    // JavaExec uses the classpath, so the server JAR's Enable-Native-Access is not applied.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir(layout.projectDirectory.dir("run"))
    standardInput = System.`in`

    doFirst {
        val assetsFile = hytaleAssetsFile
            ?: throw GradleException("Pass -PhytaleAssets=/absolute/path/to/0.7.0/Assets.zip.")
        if (!assetsFile.isFile) {
            throw GradleException("Hytale Assets.zip does not exist: $assetsFile")
        }
        args("--assets", assetsFile.absolutePath, "--disable-sentry", "--bind", "127.0.0.1:5520")
    }
}
