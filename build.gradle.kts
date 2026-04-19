plugins {
    kotlin("jvm") version "2.3.20"
    application
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(kotlin("stdlib"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

application {
    mainClass.set("MainKt")
}

tasks.test {
    useJUnitPlatform()
}
