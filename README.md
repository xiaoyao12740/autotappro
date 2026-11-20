# 🖱️ AutoTapPro

**一个本地运行的安卓自动点击器：录制点击 / 长按 / 滑动序列并按节奏回放**
**A local Android auto-clicker that records tap / long-press / swipe sequences and replays them on schedule.**

[![License: Non-Commercial](https://img.shields.io/badge/License-Non--Commercial-red)](./LICENSE)
[![Platform: Android](https://img.shields.io/badge/Android-9%20%2B-3DDC84?logo=android&logoColor=white)]()
[![Language](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin&logoColor=white)]()
[![Network: none](https://img.shields.io/badge/Network-offline-lightgrey)]()

> ⚠️ **本软件仅供个人学习与非商业用途 / For personal & non-commercial use only.**
> 完整条款见 [`LICENSE`](./LICENSE)。无网络权限、无广告、纯本地运行。

---

## ⬇️ 一键下载 / Quick Download

> 不想看源码？直接到 [Releases 发行页](https://github.com/xiaoyao12740/autotappro/releases/latest) 按设备下载 APK，开箱即用；想二次开发见文末「基于源码开发」章节。

| 你的设备 | 推荐下载 | 说明 |
|---|---|---|
| 📱 Android 9+（推荐） | [AutoTapPro-Android-v1.0.3-release.apk](https://github.com/xiaoyao12740/autotappro/releases/download/v1.0.3/AutoTapPro-Android-v1.0.3-release.apk) | 正式签名包，日常使用 |
| 🧪 调试排障 | [AutoTapPro-Android-v1.0.3-debug.apk](https://github.com/xiaoyao12740/autotappro/releases/download/v1.0.3/AutoTapPro-Android-v1.0.3-debug.apk) | 含调试广播入口与诊断日志，仅供排障 |

- 全部资产、版本说明与 SHA-256 校验值见 [v1.0.3 Release](https://github.com/xiaoyao12740/autotappro/releases/tag/v1.0.3)。
- 安装前在系统设置允许“未知来源 / 安装未知应用”；首次使用依次授予悬浮窗、无障碍、电池优化白名单。

---

## ✨ 功能一览

### 取点与录制
- 全屏透明捕获层拦截一次触摸，识别 **点击 / 长按 / 滑动 / 双击**，并记录起点、滑动终点、按下到抬起时长。
- 多点位序列，单条配置上限 100 步；点位间隔、触摸时长、重复次数可逐点配置。
- 录制时忽略自身合成的回放手势，避免回放动作被二次录进序列。
- 多点触控按手指分别配对：双指同时按下各记各的点，不会交叉误判成跨屏滑动。

### 回放
- **三种通道模式**，可随配置持久化选择：
  - 无障碍通道：`AccessibilityService.dispatchGesture()` 派发手势，免 Root，默认通道；
  - Root Shell 通道：`su -c input tap / swipe`，在已 Root 设备上作为备选；
  - 自动模式：无障碍已连接优先，不可用时自动降级 Root；也可强制锁定无障碍或 Root 单通道，不可用明确报错、不静默降级。
- **三条授权路径相互独立**：手动跳转系统无障碍设置自行授权；Root 一键把本应用绑定进无障碍；Root 直连回放不经无障碍直接派发。
- **单次 / 循环**两种运行模式；支持暂停、恢复、独立悬浮急停。
- 自然化参数（默认全部关闭）：随机延迟、坐标随机偏移、滑动时长抖动；关闭时严格按原始坐标与间隔回放。
- 一套点位配置可保存、另存、载入、删除，以 JSON 存在 App 私有目录。

### 自适应与稳健性
- 屏幕宽高实时取自 `DisplayMetrics.getRealMetrics()`；视图尺寸、距离阈值按 `dp × density` 折算，适配不同分辨率与屏幕密度。
- 横竖屏切换 / 尺寸变化时重测尺寸并重建悬浮元素；服务声明 `configChanges` 避免重建丢失。
- 点位延迟等待分片可中断，暂停 / 停止在数十毫秒内响应；无可用通道时启动与急停按钮置灰并红警，不进入伪装运行。

---

## 🎨 界面样式

- **主界面**：浅色卡片式布局，三张权限状态卡（悬浮窗 / 无障碍 / 电池优化白名单），分别显示已授予 / 未授予并跳转系统设置；下方为通道状态、运行状态、点位计数，以及配置列表（载入 / 删除）、保存 / 另存为入口。
- **悬浮控制条**：屏幕侧边半透明深色窄条，可折叠为小圆钮；按钮为完整圆形、图标居中，可紧贴侧边不被裁切。展开后竖排按钮：开始 / 暂停 `▶`、录制 `● / ■`、停止 `⏹`、点位显隐（简笔画眼睛：睁眼无斜杠=可见，闭眼带斜杠=隐藏）、添加 `＋`、删除 `－`、关闭 `✕`。折叠态单击开始 / 暂停，长按展开面板；拖拽松手后可自动吸附到屏幕侧边（主界面开关控制）。
- **点位气泡**：屏幕上每个点位为镂空圆形标记，绿色描边 + 浅色编号；可一键隐藏全部点位气泡，手操时不被标记遮挡。运行 / 暂停态气泡置为不可触摸，让回放手势穿透到下层目标界面；空闲态恢复可拖动、长按编辑。
- **参数弹窗**：点击点位气泡弹出参数面板，可编辑动作类型、起止坐标、延迟、触摸时长、重复次数、随机偏移与随机延迟。
- **应用图标**：白底绿色虚线瞄准镜圆环 + 十字准星。

---

## 🧱 技术栈

- Kotlin 1.9.24 + Gradle 8.10.2 + AGP 8.5.2；JDK 17。
- compileSdk / targetSdk 34，minSdk 28（Android 9+）。
- 依赖：AndroidX AppCompat、Material、ConstraintLayout、RecyclerView、Kotlinx Coroutines（均 Apache-2.0）。
- 不声明 `INTERNET` 权限；序列以 JSON 存于 App 私有目录，不上传任何数据。

## 📂 仓库结构

```
AutoTapPro/
├─ README.md / README_EN.md      双语说明（你正在看）
├─ LICENSE                       非商业开源协议
├─ CHANGELOG.md                  更新记录
├─ settings.gradle / build.gradle / gradle.properties
└─ app/
   ├─ build.gradle               模块构建与签名占位配置
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/aitap/autotappro/
      │  ├─ channel/             手势通道：无障碍 / Root Shell / 通道解析 / Root 探测
      │  ├─ core/                回放引擎、协调器、配置存档、运行状态
      │  ├─ model/               点位、配置、动作类型、通道枚举
      │  ├─ service/             无障碍服务、悬浮服务、调试广播接收器
      │  └─ ui/                  主界面与悬浮层（面板 / 气泡 / 捕获层 / 参数弹窗）
      └─ res/                    布局、颜色、图标与无障碍配置
```

## 🛠️ 基于源码开发（开发者）

克隆本仓库后，按以下环境构建；普通使用者无需看本节，直接从顶部下载 APK 即可。

环境：JDK 17、Android SDK 34、build-tools 34.0.0、Gradle 8.10.2。

```bash
# 调试包（使用本机默认调试密钥）
gradle assembleDebug

# 发布包：需在本机 gradle.properties 或环境变量提供签名库与口令（见 app/build.gradle 注释），
# 签名库本身不随源码分发。
gradle assembleRelease
```

## 🔗 参考与第三方声明

- 无障碍自动点击器的悬浮面板、点位标记、双通道回放等为该类工具的通用交互与架构模式；本项目代码独立编写，**未复制任何第三方应用的专有源码**。
- 运行时仅依赖上文列出的 Apache-2.0 开源库，各库许可与源码以其官方仓库为准。

## 🧭 已知限制 / Roadmap

- 无障碍通道只下发 UI 状态事件，录制走悬浮层取点式；Root 通道可进一步接入 evdev 全局触摸录制（规划中）。
- 回放阶段多点位按记录间隔顺序派发，暂不还原多指严格同时按下。
- 部分系统界面的无障碍信息被屏蔽时，需按像素坐标定位；具体兼容性以真机表现为准。

## ⚖️ 协议

非商业开源，详见 [`LICENSE`](./LICENSE)。**禁止商用**；本软件按现状提供，作者不承担操作损失责任。
