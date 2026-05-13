plugins {
    alias(libs.plugins.agp.lib)
}

android {
    namespace = "io.github.libxposed.api"

    sourceSets {
        val main by getting
        main.apply {
            setRoot("api/api/src/main")
        }
    }

    defaultConfig {
        consumerProguardFiles("api/api/proguard-rules.pro")
    }

    buildFeatures {
        buildConfig = false
    }

    androidResources {
        enable = false
    }
}

dependencies {
    compileOnly(libs.androidx.annotation)
}
