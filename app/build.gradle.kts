plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.asaf400.handsfreecontrols"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.asaf400.handsfreecontrols"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val compileXposedStubs = tasks.register<JavaCompile>("compileXposedStubs") {
    source = fileTree("xposed-stubs") { include("**/*.java") }
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("xposed-stubs"))
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
}

val xposedStubsJar = tasks.register<Jar>("xposedStubsJar") {
    dependsOn(compileXposedStubs)
    from(compileXposedStubs.flatMap { it.destinationDirectory })
    archiveFileName.set("xposed-stubs.jar")
    destinationDirectory.set(layout.buildDirectory.dir("xposed-libs"))
}

dependencies {
    compileOnly(files(xposedStubsJar))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}