// 依赖仓库：默认走官方源（Google Maven / Maven Central / Plugin Portal）。
// 中国大陆网络下可设环境变量 MILENOTE_ALIYUN=1 优先走阿里云镜像。
pluginManagement {
    val useAliyun = System.getenv("MILENOTE_ALIYUN") != null
    repositories {
        if (useAliyun) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val useAliyun = System.getenv("MILENOTE_ALIYUN") != null
    repositories {
        if (useAliyun) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "MileNote"
include(":app")
include(":domain")
