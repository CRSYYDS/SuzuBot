# ARM64 转译探针

独立探针使用固定版本的 [liba32android](https://github.com/millesant/liba32android/tree/57adc7252715a71a8e8492520aa8cc2629b74879) 执行合成 ARM32 指令。给定一个 APK 路径时，还会只读清点其中的 ARM32 原生库。探针与 SuzuBot APK 分开构建。

## 在 Windows 构建

需要 `ANDROID_HOME` 下的 Android SDK、NDK `27.2.12479018`、CMake `3.31.6` 和 Ninja，以及 Boost 头文件。

```powershell
.\compat\arm64-probe\build.ps1 -BuildDir D:\tmp\pico-probe -BoostIncludeDir D:\tools\boost\include
```

也可以用 `-SdkRoot` 指定 SDK。如果已下载固定版本的源代码与子模块，传入 `-Liba32SourceDir` 和 `-DynarmicSourceDir` 可以使用本地缓存。

## 在纯 ARM64 Android 上运行

在构建目录执行以下命令。若要清点 APK 的原生库，把设备上已存在的 APK 路径作为探针的唯一参数。

```sh
adb shell mkdir -p /data/local/tmp/pico-probe
adb push pico_arm64_probe liba32android.so libc++_shared.so /data/local/tmp/pico-probe/
adb shell chmod 755 /data/local/tmp/pico-probe/pico_arm64_probe
adb shell /data/local/tmp/pico-probe/pico_arm64_probe
```

探针覆盖合成指令执行和可选的 APK 库扫描。PicoOnebot 的 Java 宿主、JNI、多进程启动、扫码登录和后台收发需要独立验证。

