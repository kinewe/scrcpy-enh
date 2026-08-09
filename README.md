# scrcpy-catgirl（音墨自定义版）

基于 [scrcpy 4.1](https://github.com/Genymobile/scrcpy) 的自定义构建，专为「电脑 ↔ Android 移动设备」的高效协同打造。

> 当前主线分支：`feat/uhid-external-keyboard`（uhid 键盘伪装外部物理键盘里程碑）

---

## ✨ 核心功能

### 1. 🎹 uhid 键盘伪装 —— 电脑输入被移动设备认作「外部物理键盘」

**里程碑特性（v1.2）**：让 Android 把 scrcpy 的 UHID 虚拟键盘当作**真实的外部物理键盘**（与蓝牙/USB 键盘同等对待），实现「在投屏窗口里用电脑键盘直接打中文」。

**原理**（三处关键伪装）：

| 伪装项 | 实现 | 效果 |
|---|---|---|
| bus 总线 | `UHID_CREATE2.bus = 0x03`（USB） | 系统不再当作 VIRTUAL 虚拟设备（0x06 会被 ROM 静默禁用；0x05 蓝牙伪装因无蓝牙地址也会被禁） |
| 设备 ID | `vendor 0x0022 / product 0x5081`（小米焦点笔 ID） | 系统加载厂商键盘映射（`Vendor_0022_Product_5081.kcm`） |
| 设备名 | `Xiaomi Keyboard` | 输入法识别为物理键盘 |

**效果**：`dumpsys input` 显示 `Classes: KEYBOARD | ALPHAKEY | EXTERNAL`、`IsExternal: true`——所有输入法（百度/讯飞/Gboard）对它的行为与真实蓝牙键盘**完全一致**（候选栏/软键盘形态由输入法自身决定，与设备无关）。

**为什么不用真蓝牙**：uhid 无法伪造蓝牙栈身份（配对记录/蓝牙地址），但实验证明**输入法只认「EXTERNAL 外部键盘」这一层**，bus=0x03 即可获得完整待遇。

### 2. 📋 图片剪贴板全家桶（v1.0）

- 电脑复制（文本/图片）→ 手机剪贴板**自动同步**，投屏里长按即可粘贴
- `Ctrl+V` 只注入 PASTE 命令（去重，不重复推送）
- 手机端生成唯一文件名，微信/QQ 预览不错位
- 图片原样推送（无损），GIF/WebP/JPEG 不重编码

### 3. 🔄 投屏自动切换体系（v1.1）

- **有线模式**：插线即用，高规格串流（h264/50M/2560/120fps）
- **无线模式**：拔线自动切换，低延迟串流（h264/15M/1920/60fps）
- **WiFi IP 自动学习**：插线时学习设备 WiFi IP 存入 `config.txt`，拔线后用真实 IP 无线连接
- **tcpip 端口检查**：判断无线调试端口（5555）是否实际开启，设备重启后自动重新 `adb tcpip 5555`（修复拔线断连）
- **USB 插线监测**：无线投屏时每 2 秒检测插线（独立隐藏进程，~10MB 内存 / ~0% CPU），检测到即自动切换有线

---

## 🚀 使用

### 双击启动

```
启动.bat            # 有线/无线自动切换 + uhid 键盘（推荐）
手机投屏.bat        # 备用脚本（逻辑一致）
```

### 手动启动 uhid 模式

```bash
scrcpy.exe --keyboard=uhid --serial <设备序列号> \
  --video-codec=h264 --video-bit-rate=50M --max-size=2560 --max-fps=120
```

### ⚠️ 电脑侧注意事项

- **键盘布局**：使用 uhid 打中文时，电脑键盘布局请切换为「英语（美国）美式键盘」，避免电脑输入法（如搜狗）拦截按键（自动切换布局功能开发中）
- **小米便签**：该 App 会拦截物理键盘按键为快捷键，测试请用微信/浏览器等普通输入框

---

## 🛠 构建

### 环境

- JDK 17（server 构建）
- Android SDK（platform 36 / build-tools）
- MSYS2 + mingw-w64（client 构建，ninja）

### 构建

```bash
# client
cd app && ninja

# server
./gradlew build
```

### 关键源码位置

| 文件 | 说明 |
|---|---|
| `server/.../control/UhidManager.java` | UHID 设备创建，bus 伪装（0x03） |
| `app/src/uhid/keyboard_uhid.c` | 键盘设备 ID/名称伪装 |
| `app/src/input_manager.c` | 剪贴板同步、快捷键处理 |
| `packaging/启动.bat` | 投屏主脚本 |

---

## 📦 版本历史

- **v1.2（进行中）**：uhid 键盘伪装外部物理键盘（bus 0x03 + 焦点笔 ID）
- **v1.1**：有线/无线自动切换、WiFi IP 学习、tcpip 死循环修复、bat 纳入版本管理
- **v1.0**：图片剪贴板增强 + 剪贴板自动同步 + 保存相册

---

## 📄 License

基于 scrcpy（Apache 2.0）二次开发。
