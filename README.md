# HiCast 4K Receiver (Stage 1 starter)

海思 HiSilicon / Android 13+ 电视端投屏接收器的第一阶段工程。

## 当前代码已包含

- Android TV 电视端深色接收界面。
- SSDP `M-SEARCH` 发现回应及周期性 `ssdp:alive` 通告。
- UPnP MediaRenderer 设备描述与 AVTransport、RenderingControl、ConnectionManager 的基本 SCPD。
- AVTransport SOAP 子集：`SetAVTransportURI`、`Play`、`Pause`、`Stop`、`Seek`、`GetTransportInfo`、`GetPositionInfo`、`GetMediaInfo` 等。
- Media3 / ExoPlayer 播放 HTTP、HTTPS、RTSP 媒体地址（最终格式支持受电视 MediaCodec 和媒体格式约束）。
- 默认 1920×1080 的 adaptive-stream 最大尺寸提示；可以在界面切换到最高 3840×2160 目标。
- 运行时查询电视硬件解码器是否声明支持 HEVC/AVC/VP9/AV1 的 4K@30。

## 重要边界：当前不是“全部协议量产版”

1. **DLNA/腾讯视频**：此版本实现了可被局域网发现的基础 DMR 接收端。腾讯视频能否发现、连接并播放，取决于腾讯视频当前版本使用的控制动作、URI/元数据格式、媒体地址可访问性、编码与 DRM；必须拿目标手机和目标电视实测。不能仅因设备支持 DLNA 就承诺所有腾讯内容都可播放。
2. **默认 1080p / 最高 4K**：页面的 1080p/4K 是 Media3 对自适应流（例如包含多档码率的 HLS/DASH）的选档提示。对固定 MP4/其他单一路径媒体，接收端不能让发送端自动把源文件转成 1080p，也不能将低分辨率内容变成原生 4K。发送应用如果直接决定了 URL/清晰度，接收端未必能改。
3. **事件订阅**：目前 GENA `SUBSCRIBE`/`UNSUBSCRIBE` 只做了基础握手，尚未实现完整 LastChange `NOTIFY` 推送；部分手机端的播放状态同步可能不完整。
4. **后台运行**：当前服务由 `MainActivity` 启动；退出/销毁 Activity 就会停止监听。这是便于先联调的版本。量产版应把 DLNA 网络栈移进 Service，处理进程保活、开机启动、网络变化和系统权限。
5. **安全**：当前是局域网开发样机，没有配对认证。接入产品前应增加会话授权/同网段限制、URI 校验、日志脱敏、超时和请求限流。

## 构建

- Android Studio（支持 AGP 8.13.x）
- JDK 17
- Android SDK Platform 36
- 能访问 Google Maven / Maven Central，以下载 Media3 1.11.1 和 Android Gradle Plugin

使用 Android Studio 打开 `HiCastReceiver` 项目并同步 Gradle。然后选择 `app` 的 debug variant，执行 **Build > Build APK(s)**。APK 预期输出在 `app/build/outputs/apk/debug/app-debug.apk`。

本源码包不内置 Gradle Wrapper 的二进制 JAR。Windows 用户可在项目根目录运行 `powershell -ExecutionPolicy Bypass -File .\tools\bootstrap-gradle-wrapper.ps1`，脚本会下载官方 Gradle 8.13 发行包并生成标准 wrapper 文件。若网络无法访问 `services.gradle.org`，请在可联网的 Gradle 8.13 环境中执行 `gradle wrapper --gradle-version 8.13`。之后使用 `./gradlew :app:assembleDebug`（Windows 为 `gradlew.bat :app:assembleDebug`）。

## 安装与日志

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.hicastreceiver/.MainActivity
adb logcat -s HiCastDLNA
```

电视与手机必须处于可互通的同一局域网；路由器的 AP/client isolation、访客 Wi-Fi、VLAN、防火墙都可能阻断 SSDP 多播或控制连接。界面启动后，再从手机腾讯视频/相册中点击投屏并查找 `HiCast 4K Receiver`。

## 建议第一轮联调顺序

1. PC/手机能够访问电视的 `http://电视IP:18080/device.xml`。
2. SSDP 浏览器工具能搜索到 MediaRenderer，服务描述 URL 均返回 XML。
3. 使用 DLNA 测试工具投送普通无 DRM 的 H.264/AAC MP4，验证播放、暂停、停止、Seek。
4. 测 HEVC Main/Main10、4K@30、4K@60、HDR、不同封装、超长播放与断网恢复。
5. 最后再测腾讯视频的设备发现和真实会员内容，记录具体 SOAP action、媒体 URL 类型以及错误日志。

## 量产前协议工作项

- GENA `SUBSCRIBE` 回调校验、LastChange 生成与事件 `NOTIFY`。
- 针对腾讯视频的真实设备描述、SOAP 动作和 DIDL-Lite 元数据兼容测试。
- 稳定服务端口、设备 UUID 持久化、多个网络接口与 Wi-Fi/Ethernet 切换。
- 将协议服务移入独立 Service；在固件中完成开机自启和后台运行策略。
- 评估海思平台的系统播放器/厂商 MediaCodec、DRM/Widevine、HDCP、HDR 输出和 4K60 管线。
- Miracast：核查并集成系统 Wi-Fi Display/WFD Sink、Wi-Fi Direct、RTSP/RTP、HDCP 及厂商 HAL；普通应用层实现不足以替代系统栈。
- Google Cast：设备没有 GMS 时，不能把 Cast Connect 依赖直接加进 APK 就视为支持。需要确认该电视产品的 Google Cast 认证/运行环境和开发者配置；官方 Android TV Receiver 方案依赖 Cast Connect 组件与 Cast 设备配置。
- AirPlay：当前按需求暂不实现。后续需评估符合 Apple 许可/认证条件的接收器方案，不建议依赖未授权的私有协议仿实现作为量产承诺。

## 当前网络端点

- Device Description: `/device.xml`
- AVTransport SCPD: `/upnp/avtransport.xml`
- RenderingControl SCPD: `/upnp/renderingcontrol.xml`
- ConnectionManager SCPD: `/upnp/connectionmanager.xml`
- SOAP endpoints: `/upnp/control/avtransport`、`/upnp/control/renderingcontrol`、`/upnp/control/connectionmanager`
- SSDP: `239.255.255.250:1900/UDP`
- HTTP: `18080/TCP`

## 使用 GitHub Actions 云端构建 APK（无需本机安装 Android Studio）

工程已包含 `.github/workflows/build-apk.yml`。将整个 `HiCastReceiver` 目录提交到 GitHub 仓库后，GitHub Actions 会自动构建 Debug APK，并把它作为构建产物上传。

### 操作步骤

1. 在 GitHub 新建一个仓库，例如 `HiCastReceiver`。
2. 将本目录中的**全部文件**上传到仓库根目录；确认 `.github/workflows/build-apk.yml` 也已提交。
3. 推送到 `main` 或 `master` 分支会自动触发构建。也可以进入仓库的 **Actions** 页面，选择 **HiCast Android APK**，点击 **Run workflow** 手动启动。
4. 打开最新一次 workflow 运行记录，滚动到 **Artifacts**，下载 `hicast-debug-apk`。
5. 解压产物 ZIP，里面的 `app-debug.apk` 即为调试安装包。

### 构建环境说明

- JDK 17
- Gradle 8.13
- Android SDK Platform 36 / Build Tools 36.0.0
- 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

这是 **Debug APK**，用于内部安装和调试。正式发布时还应配置 Release 签名；不要把签名密钥或密码直接提交到公开仓库。该 workflow 负责编译打包，不代表已经在海思电视实机验证投屏协议、腾讯视频兼容性或 4K 解码性能。

若构建失败，请打开该次 Actions 运行记录中的 `Build debug APK` 日志，按第一个实际编译错误修复；不要只根据最后一行 `Gradle task failed` 判断原因。
