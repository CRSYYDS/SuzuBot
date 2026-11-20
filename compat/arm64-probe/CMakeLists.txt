# Standalone Android arm64-v8a probe build.
#
# Not part of the Android Gradle build: never included by settings.gradle.kts,
# adds no native code to SuzuBot, ships nothing in an APK. Only builds a
# throw-away executable plus liba32android.so in a caller-chosen build dir.
#
# Entry point is build.ps1; the raw command is in README.md.

cmake_minimum_required(VERSION 3.24)
project(pico_arm64_probe LANGUAGES C CXX)

if(NOT ANDROID OR NOT CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
	message(FATAL_ERROR "Android arm64-v8a only: use the NDK toolchain and -DANDROID_ABI=arm64-v8a.")
endif()
if(NOT ANDROID_PLATFORM_LEVEL OR ANDROID_PLATFORM_LEVEL LESS 24)
	message(FATAL_ERROR "API 24+ required: add -DANDROID_PLATFORM=android-24.")
endif()

# Upstream gates its test tree on both options; neither is wanted here.
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(LIBA32ANDROID_BUILD_TESTS OFF CACHE BOOL "" FORCE)
# Android forbids W+X JIT mappings; same value upstream CI uses for Android.
set(DYNARMIC_ENABLE_NO_EXECUTE_SUPPORT ON CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(liba32android
	GIT_REPOSITORY https://github.com/millesant/liba32android.git
	GIT_TAG 57adc7252715a71a8e8492520aa8cc2629b74879
	GIT_SHALLOW FALSE
)
FetchContent_MakeAvailable(liba32android)

if(NOT TARGET liba32android)
	message(FATAL_ERROR "liba32android target missing after FetchContent.")
endif()

add_executable(pico_arm64_probe pico_arm64_probe.cpp)
target_compile_features(pico_arm64_probe PRIVATE cxx_std_20)
target_link_libraries(pico_arm64_probe PRIVATE liba32android)
# The probe deliberately includes liba32android private headers (ELF/compat path).
target_include_directories(pico_arm64_probe PRIVATE "${liba32android_SOURCE_DIR}/src")

# 16 KiB ELF alignment for 16 KB page-size devices; $ORIGIN finds the runtime
# .so sitting next to the probe after both are pushed to the device.
target_link_options(pico_arm64_probe PRIVATE
	"-Wl,-z,max-page-size=16384"
	"-Wl,-z,common-page-size=16384"
	"-Wl,-rpath,\$ORIGIN"
)
