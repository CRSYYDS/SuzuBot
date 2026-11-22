# PicoOnebot ARM64 运行器原型

该原型以 [Armbat v0.1.4](https://github.com/dima-xd/armbat/tree/65863e1fad5971f4724b89d07f999f2d8895db4b) 为基座，独立构建。手机安装的是 ARM64 宿主。通过系统文件选择器导入的 PicoOnebot APK 保存在宿主的私有目录，由宿主加载。

导入和启动时均校验仓库所用 PicoOnebot 0.16 的 SHA-256。宿主为这个包跳过 Armbat 的 DEX 重写，在清单中定位原有 `com.tencent.qqnt.watch.app.JumpActivity`。运行器和 SuzuBot 分开安装。

## 构建

使用 JDK 17、Android SDK 平台 36、Build Tools `36.0.0`、NDK `29.0.13113456`、CMake `3.31.6` 和 Gradle `9.6.1`。使用专用的上游 checkout 保存宿主补丁。

```powershell
git clone --branch v0.1.4 --depth 1 --recurse-submodules https://github.com/dima-xd/armbat.git D:\tools\armbat-pico
.\compat\arm64-runner\build.ps1 -SourceDir D:\tools\armbat-pico
```

## 真机验收

1. 在手机主用户中使用仓库的 `scripts/install-adb.ps1` 覆盖安装构建出的宿主 APK。
2. 在宿主内用系统文件选择器导入 `app/src/main/assets/runtime/PicoOnebot-0.16.apk`，启动 PicoOnebot。
3. 确认 PicoOnebot 的 `Application`、原生库和 `:MSF` 服务启动，再检查本机 `127.0.0.1:6099` 控制台、扫码登录、`127.0.0.1:3001` 的 OneBot WebSocket 和双向消息。

Armbat 的宿主 Activity 能加载访客 Activity，访客 `:MSF` 等服务的进程路由尚未实现。步骤 3 是完整适配的验收条件。`adb install` 直接安装原样 PicoOnebot APK 在当前纯 ARM64 手机上仍会被 Android 拒绝。
