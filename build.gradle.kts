plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    kotlin("plugin.jpa") version "2.2.21"
    id("org.springframework.boot") version "4.0.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "org.example"
version = "0.0.1-SNAPSHOT"
description = "transactionTest"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

val kotestVersion = "5.9.1"
val kotestSpringExtensionVersion = "1.3.0"

dependencies {
    // 1. Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // 2. Kotlin
    // Spring Data 가 Kotlin 클래스의 주 생성자를 찾을 때 kotlin-reflect 를 사용한다.
    // 없으면 리포지토리 초기화 시점에 ClassNotFoundException: kotlin.reflect.full.KClasses 로 기동이 실패한다.
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // 3. Database
    runtimeOnly("com.h2database:h2")

    // 4. Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")

    // 5. Kotest
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.kotest.extensions:kotest-extensions-spring:$kotestSpringExtensionVersion")

}

// Hibernate 는 지연 로딩 프록시를 만들 때 엔티티 클래스를 상속한다.
// Kotlin 클래스는 기본이 final 이라 프록시를 만들 수 없고, 그러면 @ManyToOne(fetch = LAZY) 가
// 조용히 즉시 로딩으로 동작한다. allopen 으로 @Entity 클래스를 열어 둬야 LAZY 가 실제로 걸린다.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
