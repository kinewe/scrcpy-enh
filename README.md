# scrcpy 音墨自定义版（图片剪贴板增强）

基于 [scrcpy](https://github.com/Genymobile/scrcpy) v4.1 的自定义增强版本，核心能力：**电脑复制图片/文本 → 手机剪贴板自动出现 → 直接粘贴或保存原图**。

## ✨ 核心功能（对比官方）

| 功能 | 官方 | 本版本 |
|---|---|---|
| **图片剪贴板**（复制图片 → 手机剪贴板） | ❌ 无 | ✅ **独有** |
| **剪贴板自动同步**（电脑复制 → 手机自动出现，无需按键） | ❌ 无 | ✅ **独有** |
| **原图无损**（≤192MB 不压缩，256MB 消息上限） | ❌ 官方 256KB 上限 | ✅ **独有** |
| **GIF/PNG/JPEG 原样保留**（伪 .jpg 的 GIF 魔数识别） | ❌ 无 | ✅ **独有** |
| **保存剪贴板原图到相册**（Ctrl+G，原格式保存） | ❌ 无 | ✅ **独有** |
| **Ctrl+V 去重**（只注入 PASTE，不重复推送） | ❌ 每次推送 | ✅ **独有** |
| **微信双写去重**（内容指纹 FNV-1a，2-1 循环修复） | ❌ 无 | ✅ **独有** |
| **拔线自动切无线 + USB 插线监测**（启动.bat） | ❌ 无 | ✅ **独有** |
| 无线地址记忆（config.txt） | ❌ 无 | ✅ **独有** |

## 🚀 快速使用（发布版）

### 方式一：直接使用发布包（推荐）
1. 从 **Releases** 下载 `scrcpy-catgirl-vX.X.zip`
2. 解压到任意目录
3. 双击 **`启动.bat`**（USB 插线优先有线，拔线自动切无线）
4. 使用：

```
电脑复制图片/文本 → 手机剪贴板自动出现
投屏窗口 Ctrl+V   → 粘贴到手机（只注入命令，不重复推送）
投屏窗口 Ctrl+G   → 保存剪贴板原图到手机相册（原格式！GIF 保动画）
MOD+c            → 手机剪贴板 → 电脑（手动反向同步）
```

> MOD = Alt 或 Win 键。Ctrl+G 在任何输入法状态下都可用（Ctrl 组合键不被输入法拦截）。

### 方式二：从源码构建
见下文「从源码构建」。

## 🔧 从源码构建

### 环境要求
| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | 构建 server（Gradle） |
| Android SDK | platform 36 / build-tools 36 | 构建 server |
| MSYS2 | 最新 | 构建 client（Meson + Ninja） |
| 依赖 | SDL3 3.4+ / FFmpeg 6+ | client 运行库 |

### 构建步骤
```bash
# 1. 克隆
git clone git@github.com:kinewe/PC-kinewe-yinmo.git
cd PC-kinewe-yinmo

# 2. 构建 server（Android 端）
cd server
./gradlew assembleRelease
# 产物: server/build/outputs/apk/release/scrcpy-server

# 3. 构建 client（Windows 端）
cd ../app
meson setup build --buildtype=release \
    -Dprebuilt_server=<server路径>/scrcpy-server
ninja -C build
# 产物: app/build/scrcpy.exe
```

## 📁 目录结构
```
├── app/          # Windows client（C，SDL3 + FFmpeg）
├── server/       # Android server（Java，Gradle）
├── dist/         # 发布包（本地构建产物，不入库）
└── 启动.bat       # 一键启动（构建后手动放置到 dist/）
```

## 🛠️ 技术实现要点
- **图片剪贴板**：自定义控制消息 `TYPE_SET_IMAGE_CLIPBOARD`（=23）→ server 写入 device-protected storage（`/data/user_de/0/com.android.shell/files/bugreports/`）→ FileProvider URI + `setPrimaryClip`
- **原图保留**：CF_HDROP 文件路径优先读取（延迟渲染重试），文件头魔数判断真实格式，原字节传输
- **指纹去重**：FNV-1a 64 位哈希（内容/文件元数据），多路径统一，防微信双写
- **保存相册**：控制消息 `TYPE_SAVE_CLIPBOARD_IMAGE_TO_GALLERY`（=24）→ MediaStore insert（Android 10+ 分区存储）
- **消息上限**：控制通道 256MB（双向对称）

## 📜 版本历史
- **v1.0** (2026-08)：图片剪贴板增强首发版（本仓库首个自定义 commit `fe9f701`）

## 🙏 致谢
- [scrcpy](https://github.com/Genymobile/scrcpy)（Genymobile）— 上游项目
- [PR #6676](https://github.com/Genymobile/scrcpy/pull/6676) — 图片剪贴板方案参考
