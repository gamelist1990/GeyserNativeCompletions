plugins { java }
group = "com.pexserver"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://libraries.minecraft.net/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
val geyserVersion = "2.11.3-SNAPSHOT"
val cloudburstVersion = "3.0.0.Beta13-20260917.001841-27"
dependencies {
    compileOnly("com.mojang:brigadier:1.3.10")
    testImplementation("com.mojang:brigadier:1.3.10")
    compileOnly("io.papermc.paper:paper-api:26.2.build.130-stable")
    compileOnly("org.geysermc.geyser:core:$geyserVersion") { isTransitive = false }
    compileOnly("org.geysermc.geyser:api:$geyserVersion")
    compileOnly("org.cloudburstmc.protocol:bedrock-connection:$cloudburstVersion")
    testImplementation("org.cloudburstmc.protocol:bedrock-connection:$cloudburstVersion")
    testImplementation("io.netty:netty-transport:4.2.7.Final")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.withType<JavaCompile> { options.encoding = "UTF-8"; options.release.set(25) }
tasks.processResources { filesMatching("plugin.yml") { expand("version" to project.version) } }
tasks.test { useJUnitPlatform() }
tasks.jar { manifest.attributes["paperweight-mappings-namespace"] = "mojang" }

val fixture by sourceSets.creating
configurations[fixture.compileOnlyConfigurationName].extendsFrom(configurations.compileOnly.get())
tasks.register<Jar>("fixtureJar") {
    dependsOn(tasks.named(fixture.classesTaskName))
    from(fixture.output)
    archiveBaseName.set("NativeCompletionFixture")
}
