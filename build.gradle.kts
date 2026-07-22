plugins {
    java
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 26.2 API. The ".build.+" resolves to the latest published build for 26.2.
    // Pin it (e.g. 26.2.build.60) once you have a working build if you want reproducibility.
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}

// Minecraft 26.x class files require Java 25.
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}
