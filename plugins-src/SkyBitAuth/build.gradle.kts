plugins { java }

group = "sk.skybit"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:4.1.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:4.1.0-SNAPSHOT")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}
