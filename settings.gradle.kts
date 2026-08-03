plugins {
    // 로컬에 JDK 17 이 없으면 Gradle 이 자동으로 내려받아 사용하도록 한다.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "transactionTest"
