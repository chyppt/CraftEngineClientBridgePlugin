import java.io.File

plugins {
    java
}

group = "cc.jadebridge"
version = "1.0.0"
description = "CraftEngine content synchronization for optional client mods."

repositories {
    // 国内镜像优先，加速依赖下载
    maven("https://maven.aliyun.com/repository/public/")
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.momirealms.net/releases/")
}

// 用显式参数复现各服务端 / CE 版本的编译，避免在发布时只改版本声明。
val paperApiVersion = providers.gradleProperty("paperApiVersion").orElse("1.20-R0.1-SNAPSHOT")
val craftEngineVersion = providers.gradleProperty("craftEngineVersion").orElse("26.7")
val targetJava = providers.gradleProperty("targetJava").orElse("17").map(String::toInt)

dependencies {
    // 最低 Paper API 编译，避免无意引入较新服务端方法。
    compileOnly("io.papermc.paper:paper-api:${paperApiVersion.get()}")
    // CraftEngine 官方 Maven 仓库（compileOnly，运行时由服务器上的 CraftEngine 提供）
    compileOnly("net.momirealms:craft-engine-core:${craftEngineVersion.get()}")
    compileOnly("net.momirealms:craft-engine-bukkit:${craftEngineVersion.get()}")
    testImplementation("net.momirealms:craft-engine-core:26.9.1")
    testImplementation("org.mockito:mockito-core:5.22.0")
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // 工具链使用 JDK 25；生产字节码由 targetJava 单独控制。
    // 生产类保持 Java 17 字节码，测试使用 JDK 25 的测试依赖与集合 API。
    options.release.set(if (name == "compileTestJava") 25 else targetJava.get())
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

// 测试核心开关隔离与失效通知，不加载 Bukkit 世界或创建网络连接。

tasks.test {
    useJUnitPlatform()
    // CE 染色/锻造实现初始化依赖完整 CE 与 Minecraft 运行库。
    // 用路径列表显式提供运行库，避免依赖开发机的客户端目录。
    val runtimeClasspath = providers.gradleProperty("ceTestRuntimeClasspath").orNull
    if (runtimeClasspath != null) {
        classpath += files(runtimeClasspath.split(File.pathSeparator))
    } else {
        exclude("**/DyeRecipeExportTest.class", "**/TrimRecipeExportTest.class")
    }
    providers.gradleProperty("pdJar").orNull?.let { systemProperty("cebridge.pdJar", it) }
}


// API JVM requirement is independent from Java 17 output bytecode.
configurations.compileClasspath {
    attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
}
