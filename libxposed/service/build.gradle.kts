plugins {
    alias(libs.plugins.agp.lib)
}

android {
    namespace = "io.github.libxposed.service"

    sourceSets {
        getByName("main") {
            setRoot("service/service/src/main")
            aidl.directories += "service/interface/src/main/aidl"
        }
    }

    buildFeatures {
        buildConfig = false
        resValues = false
        aidl = true
    }
}

dependencies {
    compileOnly(libs.androidx.annotation)
}
