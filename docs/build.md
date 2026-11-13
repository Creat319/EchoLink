# 构建与运行

## 1. 环境要求

| 组件 | 本机实测版本 | 说明 |
| --- | --- | --- |
| Windows | 10 22H2 (19045) | 事件驱动环回需要 10 1703+ |
| .NET SDK | 10.0.400 | 目标框架 `net8.0-windows`；运行需 .NET 8 Desktop Runtime（或自包含发布） |
| Android SDK | platforms 34/35/36、build-tools 34/36 | `ANDROID_HOME` 或 `android/local.properties` |
| NDK | 28.2.13676358 | 已在 `app/build.gradle.kts` 的 `ndkVersion` 中固定 |
| CMake | 3.22.1 | `externalNativeBuild.cmake.version` |
| JDK | 21 (Temurin) | Gradle 8.14 + AGP 8.7.3 |
| Gradle | 8.14（wrapper 自动下载） | |

`pc/NuGet.config` 显式声明了 nuget.org（本机原本一个源都没有，会导致 `NU1100` 还原失败）。

## 2. PC 端

```powershell
# 只构建
dotnet build pc\EchoLink.slnx -c Release

# 构建 + 发布到 artifacts\pc（含诊断工具）
.\scripts\build-pc.ps1

# 自包含单文件（目标机不需要装 .NET 运行时，约 150 MB）
.\scripts\build-pc.ps1 -SelfContained
```

产物：

```
artifacts\pc\EchoLink.App.exe            WPF 仪表盘（Material Design in XAML）
artifacts\pc\EchoLink.Diagnostics.exe    诊断工具
```

界面是 **WPF + MaterialDesignThemes 5.3.1**，MVVM 结构（`ViewModels/MainViewModel.cs`）。
和 WinForms 一样只需要 .NET 8 Desktop Runtime，自包含发布同样可用。

> 注意：`pc/Directory.Build.props` 里 **不能** 打开 `InvariantGlobalization`。
> WPF 的 Binding 会调用 `XmlLanguage.GetSpecificCulture()` 解析 `en-US`，
> invariant 模式下会抛 `Cannot find non-neutral culture related to 'en-us'`，窗口直接起不来。

解决方案文件是 `.slnx`（.NET 10 SDK 默认格式，VS 2022 17.14+ / Rider 支持）。
老工具链可以直接构建单个项目：`dotnet build pc\src\EchoLink.App\EchoLink.App.csproj`。

## 3. Android 端

```powershell
.\scripts\build-android.ps1            # debug APK（调试签名，可直接安装）
.\scripts\build-android.ps1 -Release   # release APK（未签名）
.\scripts\build-android.ps1 -Test      # 只跑单元测试
```

脚本会：写 `local.properties`（若缺）→ `gradlew` → 打印 APK 路径。
产物：`android\app\build\outputs\apk\debug\app-debug.apk`，脚本会同时复制一份到
`artifacts\echolink-arm64-debug.apk`（约 13.3 MB，**含三种 ABI**）。
注意 `artifacts/` 不入库，它是构建产物目录；要分发请用 GitHub Releases 挂 APK。

**文件名里的 `arm64` 是历史遗留**，现在这三种 ABI 都在一个包里，系统会自己挑对应的
`.so`：

| ABI | 用途 | 自研引擎 .so | 16 KB 页对齐 |
| --- | --- | --- | --- |
| `arm64-v8a` | 主流手机 | 57 KB | ✅（`0x4000`） |
| `armeabi-v7a` | 老 32 位手机 / 廉价平板 | 23 KB | 不适用（32 位只需 4 KB） |
| `x86_64` | 模拟器、少数平板 | 56 KB | ✅（`0x4000`） |

> 为什么不改名：版本接口里的下载地址写的是 `echolink-arm64-debug.apk`，
> 改名会让所有客户端的「立即下载」变成 404。要改名必须同时升级服务端与客户端。

装的时候看 `native-code` 是否包含目标 ABI：

```powershell
aapt2 dump badging .\artifacts\echolink-arm64-debug.apk | Select-String "native-code"
# native-code: 'arm64-v8a' 'armeabi-v7a' 'x86_64'
```

### 3.1 安装

```powershell
C:\Android\Sdk\platform-tools\adb.exe install -r .\artifacts\echolink-arm64-debug.apk
```

手机必须先开 **开发者选项 → USB 调试**。只开“USB 网络共享”时 `adb devices` 是空的
（本机就踩到了这个：RNDIS 适配器在、网关能通、但 adb 看不到设备）。

### 3.1.1 发新版时要同步的三件事

客户端内置版本自检（见 CHANGELOG v0.3.1），发版时这三处必须一起改，否则用户端行为会不一致：

| 位置 | 要改什么 |
| --- | --- |
| 代码里的版本号 | `pc/Directory.Build.props` 的 `<Version>`、`android/app/build.gradle.kts` 的 `versionName`/`versionCode` |
| 版本接口 | `http://carovo.shop/api/version.php` 的返回值改成同一个版本号（接口由项目维护者控制） |
| 下载地址 | 上传 `winclient.zip` 与 APK 覆盖 `http://el.carovo.shop/` 下同名文件 |

接口返回的版本**低于或等于**客户端时不会提示（这是刻意的：服务端回滚不该弹"有新版本"）。
所以忘了改接口的后果是"没人收到更新提示"，而不是"所有人都被提示更新"。

> 已知的两个服务端小毛病（2026-11 实测）：
> - APK 的 `Content-Type` 返回 `text/plain`，部分浏览器会当文本显示而不是下载；
>   建议改成 `application/vnd.android.package-archive`。
> - 版本接口与下载页都是明文 HTTP，可被中间人篡改。影响面限于"弹一个假提示"，
>   但正解是给域名上 HTTPS。

### 3.2 release 签名

`app/build.gradle.kts` 没有配置签名，`assembleRelease` 产出的是未签名 APK。自己签名：

```powershell
# 生成 keystore（一次）
& "C:\Program Files\Eclipse Adoptium\jdk-21\bin\keytool.exe" -genkeypair -v `
    -keystore echolink.jks -keyalg RSA -keysize 2048 -validity 10000 -alias echolink

# 签名 + 对齐
$bt = "C:\Android\Sdk\build-tools\36.0.0"
& "$bt\apksigner.bat" sign --ks echolink.jks --out EchoLink-release.apk `
    android\app\build\outputs\apk\release\app-release-unsigned.apk
```

## 4. 运行

### 4.1 推荐拓扑（本机喇叭不出声）

```
Windows 默认输出 = VB-Cable Input（虚拟声卡）
PC 端「输出设备」= 入梦扬声器 (VB-Audio Cable A)   ← 抓它的环回
手机：开 USB 网络共享 → 打开 EchoLink → 开始接收
PC 端：手机地址「自动发现」→ 启动
```

虚拟声卡不是必须的：直接抓扬声器环回也能用，只是本机喇叭会同时出声（想让本机静音，见
[architecture.md](architecture.md) §2.4，**不要用系统静音**）。

### 4.2 首次连接

1. PC 端点「启动」，日志应出现：保活流 → 路径 A/B 结果 → 环回抓取模式 → 探测地址 → `已连通`
2. 手机端会自动播一声 1 kHz 测试音，弹「听到了吗？」——**请如实回答**，这是唯一能发现静默重路由的手段
3. 手机界面「水位闭环」卡里应看到水位贴住目标、比率在 ±0.1% 内小幅摆动

### 4.3 端口

| 用途 | 端口 |
| --- | --- |
| 音频（手机监听） | UDP 47810 |
| 回报（PC 绑定，同时是音频源端口） | UDP 47811 |

Windows 防火墙首次运行会弹窗，**必须允许专用网络**，否则收不到回报（音频是单向的，仍能出声）。

## 5. 诊断工具

```powershell
.\artifacts\pc\EchoLink.Diagnostics.exe devices    # 列端点 + 混音格式 + 引擎默认周期
.\artifacts\pc\EchoLink.Diagnostics.exe targets    # USB 共享候选地址（评分 + 网关）
.\artifacts\pc\EchoLink.Diagnostics.exe capture --device "Cable" --seconds 5 --out dump.wav
.\artifacts\pc\EchoLink.Diagnostics.exe echo --seconds 30          # 完整 PC 链路自检
.\artifacts\pc\EchoLink.Diagnostics.exe sink --port 47810          # 只验证收包
.\artifacts\pc\EchoLink.Diagnostics.exe send --to 192.168.96.150   # 只验证手机收
```

`capture` 会自动通过保活流播一个测试音，所以 WAV 里应当有真实波形（峰值 > 0.01）。

## 6. 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| `NU1100: 无法解析 NAudio` | 缺少 package source；`pc/NuGet.config` 已修 |
| 日志出现 `路径 A 失败 0x88890021` | 正常，驱动不支持环回小周期，已自动回退 |
| 手机不出声但 PC 端显示「已连通」 | 检查媒体音量、输出路由（蓝牙）、是否被静音；重放测试音并人工确认 |
| PC 端一直「等待手机回报」 | 手机没启动接收 / 端口不对 / 防火墙拦了入站 UDP |
| `adb devices` 为空 | 手机没开 USB 调试 |
| Gradle 报 NDK 版本不符 | 装 `ndk;28.2.13676358`，或改 `app/build.gradle.kts` 的 `ndkVersion` |
