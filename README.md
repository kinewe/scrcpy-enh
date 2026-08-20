# 🖥️📱 scrcpy-ez — 轻松易用的安卓投屏增强版

> **[English](README_EN.md) | 中文**

> 基于 [scrcpy 4.1](https://github.com/Genymobile/scrcpy) 的增强构建，面向「电脑 ↔ Android 移动设备」投屏协同场景。**即开即投，无需折腾。**

> ✨ **本项目由 AI 全程开发**（代码、文档均由 AI 编写）
> ⚠️ **测试平台**：仅 Windows 11 实测（其他版本未经验证）

---

## 🚀 快速开始

### 首次连接

**第一步：开放权限**

进入手机/平板的**开发者模式**后，打开 **USB 调试**开关，并允许通过 USB 调试模拟点击行为（例如小米设备还需打开 **USB 调试（安全设置）**）。

<p align="center"><img src="images/setup-permissions.jpeg" alt="开放权限" width="600"></p>

**第二步：连接电脑**

用 USB 线将电脑与手机（平板）连接，启动 **`投屏启动.bat`**：

- 🔌 **继续插线** → USB 模式投屏（更高的规格）
- 📡 **拔掉 USB 线** → 自动切换无线模式投屏

### 后续连接

- ✅ 若已进行过 USB 连接，之后无需再经由 USB 线连接——相同局域网下启动 `投屏启动.bat` 即可对上次设备发起无线投屏
- 🔄 支持 USB 热插拔，会自动重启并在稍后切换有线和无线模式
- 💡 更换投屏设备或网络环境变化时：按首次连接步骤用 USB 线连接一次即可

---

## ✨ 主要特性

### ⌨️ 打字友好

无需额外设置，即可直接在投屏窗口利用电脑键盘输入。

<p align="center"><img src="images/typing-1.jpeg" alt="打字友好 1" width="360"></p>

<p align="center"><img src="images/typing-2.jpeg" alt="打字友好 2"></p>

> 注：至少为 **Android 13+** 支持 UHID 键盘，低于该版本将无法直接输入。

### 🖼️ 图片剪贴板

吸取了 scrcpy PR #6676 功能——不止是文本，电脑端到移动端复制的**各类图片**也能进入移动端设备剪贴板。

<p align="center"><img src="images/clipboard-1.jpeg" alt="图片剪贴板" width="1100"></p>

在支持剪贴板发送图片的应用里可以**粘贴与发送**（输入栏长按并选粘贴 / Ctrl+V），例如微信：

<p align="center"><img src="images/clipboard-wechat.jpeg" alt="微信粘贴示例" width="600"></p>

> 注：移动端应用发送剪贴板图片时可能进行裁切与转化，直接粘贴会模糊（动图不动等）——此时按 **Ctrl+G** 将该图保存至手机（平板）相册，再打开相册用原图发送即可。（保存相册功能 Android 7.0+ 可用，但剪贴板可能不显示。）

<p align="center"><img src="images/clipboard-gallery.jpeg" alt="保存相册示例" width="700"></p>

> 保存后点击相册找图发送！

### 🎯 画面跟手感优化

利用**自适应帧率/码率**，让画面较为跟手（非游戏水准，并非为此设计），等待感大幅降低。使用中瞬时帧率下降、画面变模糊是**正常现象**（系统按负载自动调节）。

> 注：较旧设备（Android 9 或以下）将固定低刷新率与码率。

### 🎮 快捷键

| 快捷键 | 功能 |
|---|---|
| **Ctrl+F** | 投屏参数控件开关（控件可按住 **Alt** 拖动位置）|
| **Ctrl+G** | 将从电脑复制的图保存至手机（平板）相册 |
| **Ctrl+H** | 投屏期间将手机（平板）黑屏以省电（设备可能进入省电模式限制刷新率）|
| **Ctrl+T** | 投屏窗口置顶开关（或按住 **Alt** 点击参数控件左侧指示灯，效果相同）|
| **Alt+F** | 全屏 |

> 💡 **置顶指示**：参数控件左侧的指示灯——置顶时亮橙灯，未置顶为灰点。按住 **Alt** 点击指示灯可激活/取消窗口置顶，与 **Ctrl+T** 效果相同，再次点击可取消置顶。

<p align="center"><img src="images/overlay-indicator-1.png" alt="置顶指示灯 1" width="380"></p>

<p align="center"><img src="images/overlay-indicator-2.png" alt="置顶指示灯 2" width="380"></p>

---

## 📜 版本特性

| 版本 | 特性 |
|---|---|
| **v1.8** | Ctrl+T 窗口置顶开关、参数控件左侧发光指示灯（置顶亮橙灯/未置顶灰点，按住 Alt 点击指示灯也可切换）|
| **v1.7** | ABR 操作/无操作双阈值（看视频不再锁 1M——无操作放宽阈值、操作回灵敏）、低码率保护 |
| **v1.6** | ABR 延迟基线归零（负值污染修复）、单轮动画突发检测（平板卡顿根治）、码率恢复提速、bat 全面修复（乱码/向导/键位）|
| **v1.5** | TSF 空文档屏蔽（输入法不抢键、切回应用永远中文）、老设备兼容（Android 9 以下自动保守档）|
| v1.4 | GL 满档减帧修复（120 档真满血）、fps 档位全链路生效、overlay S 字形 + Ctrl+F 开关 |
| v1.3.1 | 投屏状态控件（实际/档位 fps + 实时码率 + USB/WIFI）|
| v1.3 | ABR 双维自适应（码率+帧率）、90fps 缓冲搭档档 |
| v1.2 | uhid 键盘伪装中文输入、图片剪贴板同步（PR #6676）、Ctrl+G 存相册 |
| v1.1 | 投屏循环 + USB/WiFi 自动切换 |
| v1.0 | 首个增强构建 |

---

## 🛠️ 构建（源码）

```bash
# Server（Android 端）：
cd server && export JAVA_HOME=/path/to/jdk17
gradle --no-daemon assembleRelease
# 产物 → 复制为 dist/scrcpy-server

# Client（Windows 端）：
cd build && PATH=/f/msys64/mingw64/bin:$PATH ninja
# 产物 → 复制为 dist/scrcpy.exe
```

---


## 🙏 致谢

- [scrcpy](https://github.com/Genymobile/scrcpy)（Genymobile）—— 本项目的基石，Apache License 2.0 许可
- [yume-chan 的 PR #6676（Support image clipboard）](https://github.com/Genymobile/scrcpy/pull/6676) —— 图片剪贴板功能借鉴自该 PR

## 📄 License

Apache License 2.0（继承自 scrcpy 官方）。
