plugins { id("com.android.library") }
android {
    namespace = "dev.amenhancer.host.applemusic"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":core"))
    api(project(":host-api"))
    implementation(project(":hook-runtime"))
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("io.github.libxposed:service:102.0.0")
    implementation("org.luckypray:dexkit:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("io.github.proify.lyricon:provider:0.1.70")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
