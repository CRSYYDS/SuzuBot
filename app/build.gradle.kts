plugins {
	id("com.android.application")
	kotlin("android")
	// Kotlin 2.0 起，Compose 编译器是独立插件，不再用 composeOptions
	id("org.jetbrains.kotlin.plugin.compose")
}

android {
	namespace = "dev.qqbot.wrapper"
	compileSdk = 35

	defaultConfig {
		applicationId = "dev.qqbot.wrapper"
		minSdk = 24          // Android 7.0
		targetSdk = 35

		// 每次构建自动递增 versionCode：避免 OPPO/ColorOS 报「已安装相同版本」
		// 用构建时间（分钟级）保证唯一且单调递增
		val stamp = (System.currentTimeMillis() / 60_000L).toInt()
		versionCode = stamp
		versionName = "0.1.${stamp % 10000}"

		vectorDrawables { useSupportLibrary = true }
	}

	buildTypes {
		debug {
			isMinifyEnabled = false
			applicationIdSuffix = ".debug"
		}
		release {
			// 首版先关混淆，等链路跑通再开，免得真机验证时被 R8 坑
			isMinifyEnabled = false
			isShrinkResources = false
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro"
			)
			// 未配置正式签名前用 debug 签名，方便自测；发布前请替换
			signingConfig = signingConfigs.getByName("debug")
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

	// 便于区分产物名（固定文件名，避免版本号变化导致路径改变）
	applicationVariants.all {
		outputs.all {
			val v = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
			v.outputFileName = "SuzuBot-${buildType.name}.apk"
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
