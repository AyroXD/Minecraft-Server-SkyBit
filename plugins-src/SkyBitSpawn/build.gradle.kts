plugins { java }

group = "sk.skybit"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(files("../../runtime/downloads/Citizens-2.0.43-b4232.jar"))
    compileOnly("me.clip:placeholderapi:2.12.3")
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}
