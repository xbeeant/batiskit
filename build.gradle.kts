import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

group = "io.github.xbeeant"

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // 说明：表结构元数据直接取自 Database 插件的 PSI（DbTable / DasColumn），
    // 不再需要 JDBC 连接与 mybatis-generator-core；文件合并使用平台的 Java/XML PSI，
    // 也不依赖 JavaParser。因此运行期没有任何第三方依赖。

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // 编译平台即兼容下限：插件要能在 2024.1（241）上跑，就必须用 241 的
        // 平台 jar 编译，让 241 之后才出现的 API 在编译期直接报错，而不是等到
        // 用户 IDE 里抛 NoSuchMethodError。verifyPlugin 再按各目标 IDE 做字节码校验。
        intellijIdea("2024.1")

        testFramework(TestFrameworkType.Platform)
        // 时序图测试用 LightJavaCodeInsightFixtureTestCase（java-test-framework 提供）
        testFramework(TestFrameworkType.Plugin.Java)

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.database")
        bundledPlugin("com.intellij.java")
        bundledPlugin("Git4Idea")
    }
}

kotlin {
    // 2024.1 跑在 JBR 17 上（2024.2 才切到 JBR 21）。用 JDK 21 编译出的
    // class 版本 65 在 241 上会直接 UnsupportedClassVersionError，因此字节码
    // 目标必须是 17。Gradle 守护进程仍由 JDK 21 启动（Plugin 2.x / Gradle 9 要求），
    // toolchain 17 由 foojay-resolver 自动解析；CI 的 setup-java 装 21 即可。
    jvmToolchain(17)
}

tasks {
    // 沙箱 IDE 运行期间自动重载插件：开启 idea.auto.reload.plugins 系统属性，
    // 沙箱 IDE 检测到插件产物变化（重新执行 prepareSandbox / buildPlugin 等）即热重载，无需重启 runIde。
    // 注意：结构性改动（plugin.xml 增删扩展点 / 顶层组件、改 since-build）仍需重启沙箱 IDE。
    runIde {
        autoReload = true
    }

    test {
        // 2024.1 Ultimate 自带的 Swagger 插件在 plugin.xml 里声明了
        // testServiceImplementation="com.intellij.swagger.visualEditing.SwVisualEditingActionsTestService"，
        // 但这个类没有打进 IDE 分发包。平台在测试模式会加载它，导致所有启动 IDE 的
        // 重量级测试在 postStartup 阶段抛 ClassNotFoundException（与插件代码无关）。
        // 用平台白名单只加载本插件真正依赖的 bundled 插件，跳过 swagger 等无关插件。
        systemProperty(
            "idea.load.plugins.id",
            listOf(
                "io.github.xbeeant.batiskit",
                "com.intellij.java",
                "com.intellij.database",
                "Git4Idea"
            ).joinToString(",")
        )
    }
}

// Marketplace 的 change-notes 只放要点，别把整份 CHANGELOG 塞进去。
// 配置阶段直接算成 String：用 provider { } 会在配置缓存下于任务执行期求值，
// 捕获 Project 后读不到文件，产物里只剩一行空标题。
val marketplaceChangeNotes: String = run {
    val text = rootProject.file("CHANGELOG.md").takeIf { it.isFile }?.readText().orEmpty()
    val tail = text.substringAfter("## [Unreleased]", "")
    // 找不到下一个版本标题时返回整段；传 "" 会把内容全部吃掉
    val section = tail.substringBefore("\n## [", tail)
    val titles = Regex("^- \\*\\*(.+?)\\*\\*", RegexOption.MULTILINE)
        .findAll(section)
        .map { it.groupValues[1].substringBefore("：").substringBefore(":") }
        .map { it.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
        .distinct()
        .take(8)
        .toList()
    buildString {
        append("<p>Batis Kit ").append(project.version).append("</p>")
        if (titles.isNotEmpty()) {
            append("<ul>")
            titles.forEach { append("<li>").append(it).append("</li>") }
            append("</ul>")
        }
        append("<p>完整更新记录见项目 CHANGELOG.md。</p>")
    }
}

intellijPlatform {
    // Settings 搜索索引：关掉时用户在 Settings 搜索框里搜不到本插件任何设置项。
    // 各 Configurable 都实现了 SearchableConfigurable.getId()，索引会把中文 label 与稳定 id 一起收进去。
    buildSearchableOptions = true

    pluginConfiguration {
        version = project.version.toString()

        ideaVersion {
            // 与 intellijIdea("2024.1") 一致。since-build 由编译平台决定，不能只
            // 放低这个数字：编译平台更高时低版本 IDE 会因缺失平台 API 而崩。
            // 若以后某功能确实只能依赖更高版本的 API，应把编译平台、jvmToolchain、
            // sinceBuild 三者一起上调，而不是在这里虚标兼容范围。
            sinceBuild = "241"
            untilBuild = provider { null }
        }
        // description 仍由 src/main/resources/META-INF/plugin.xml 单独维护（这里只字不写，
        // 否则 patchPluginXml 会把它覆盖成 1.0.0 时代的旧文案）。
        // change-notes 则由 CHANGELOG.md 生成：默认注入整份 80KB 记录会让 Marketplace 页面膨胀，
        // 这里只取 Unreleased 里带标题的前几条要点，完整记录仍留在仓库。
        changeNotes.set(marketplaceChangeNotes)
    }

    pluginVerification {
        // 只验证「兼容下限 241 / 当前主力 253 / 最新 2026.2」三端。
        // 不用默认的 recommended()：它会把 since-build 之后所有 IDE 都拉下来
        // （每个 1GB+ 的 dmg，解压后还要占几倍空间），本地跑一次就要十几 GB。
        // 这个任务只在 CI 里跑；2026.3 GA 后按同样方式补一端即可。
        ides {
            current()                                              // 2024.1：下限
            create(IntelliJPlatformType.IntellijIdea, "2025.3")     // 2025.3：主力
            create(IntelliJPlatformType.IntellijIdea, "2026.2")     // 2026.2：最新
        }
        // 项目历来用了少量平台内部 API（highlightVisitor impl、SimpleDataContext 等），
        // 这些单独跟踪；这里只让真正影响插件能否加载/运行的兼容性问题使构建失败。
        failureLevel.set(listOf(VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS))
    }

    // 签名与发布只从环境变量 / CI secret 读，仓库里不落任何凭据。
    // 本地 buildPlugin 不会触发签名，缺变量时仅 signPlugin / publishPlugin 任务本身不可用。
    signing {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }
    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
        channels.set(listOf("default"))
    }
}
