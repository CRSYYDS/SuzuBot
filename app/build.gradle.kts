import java.util.Properties

plugins {
	id("com.android.application")
	kotlin("android")
	// Kotlin 2.0 起，Compose 编译器是独立插件，不再用 composeOptions
	id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 签名信息从 keystore.properties 读取（该文件不进版本库）。
 *
 * 仓库里只放 keystore.properties.example 作模板；
 * 本地/CI 各自放自己的 keystore.properties。
 */
val keystoreProps = Properties().apply {
	val f = rootProject.file("keystore.properties")
	if (f.exists()) f.inputStream().use { load(it) }
}

/** 是否具备正式签名条件。 */
val hasReleaseSigning = keystoreProps.getProperty("storeFile")?.let {
	rootProject.file(it).exists()
} == true

android {
	namespace = "com.crsyyds.suzubot"
	compileSdk = 35

	defaultConfig {
		applicationId = "com.crsyyds.suzubot"
		minSdk = 24          // Android 7.0
		targetSdk = 35

		// versionName 是给人看的语义化版本；
		// versionCode 用构建时间戳，保证每次构建唯一（避免 ColorOS 报「已安装相同版本」）
		versionName = "1.1.0"
		versionCode = (System.currentTimeMillis() / 60_000L).toInt()

		vectorDrawables { useSupportLibrary = true }
	}

	signingConfigs {
		create("release") {
			if (hasReleaseSigning) {
				storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
				storePassword = keystoreProps.getProperty("storePassword")
				keyAlias = keystoreProps.getProperty("keyAlias")
				keyPassword = keystoreProps.getProperty("keyPassword")
			}
		}
	}

	buildTypes {
		debug {
			isMinifyEnabled = false
			applicationIdSuffix = ".debug"
		}
		release {
			// 首版先关混淆，等分发稳定后再考虑开 R8
			isMinifyEnabled = false
			isShrinkResources = false
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro"
			)
			if (hasReleaseSigning) {
				signingConfig = signingConfigs.getByName("release")
			}
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	kotlinOptions {
		jvmTarget = "17"
	}

	buildFeatures {
		compose = true
		buildConfig = true
	}

	packaging {
		resources {
			excludes += setOf(
				"/META-INF/{AL2.0,LGPL2.1}",
				"META-INF/DEPENDENCIES",
				"META-INF/LICENSE*",
				"META-INF/NOTICE*"
			)
		}
	}

	// 产物名带上版本号，便于发 Release 时区分
	applicationVariants.all {
		outputs.all {
			val v = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
			v.outputFileName = if (buildType.name == "release") {
				"SuzuBot-v${versionName}.apk"
			} else {
				"SuzuBot-${buildType.name}.apk"
			}
		}
	}
}

dependencies {
	val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
	implementation(composeBom)

	implementation("androidx.core:core-ktx:1.13.1")
	implementation("androidx.activity:activity-compose:1.9.3")
	implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
	implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
	implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

	implementation("androidx.compose.ui:ui")
	implementation("androidx.compose.ui:ui-graphics")
	implementation("androidx.compose.ui:ui-tooling-preview")
	implementation("androidx.compose.material3:material3")
	implementation("androidx.compose.material:material-icons-extended")

	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

	debugImplementation("androidx.compose.ui:ui-tooling")
}
