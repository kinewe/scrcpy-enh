@echo off
setlocal enabledelayedexpansion
rem 清理上次 watcher 切换标记（防残留误判"插线切换"）
del /q "%TEMP%\scrcpy_watch_switch.flag" >nul 2>&1
cd /d "%~dp0"

rem ============================================
rem   scrcpy-ez 投屏启动脚本
rem
rem   首次连接：
rem     1. 手机/平板开启开发者模式，打开 USB 调试
rem        （小米设备还需打开「USB 调试（安全设置）」）
rem     2. 用 USB 线连接电脑后双击本脚本
rem     3. 插线 = USB 有线投屏（高规格）；拔线 = 无线投屏
rem
rem   后续连接：
rem     - 相同局域网下直接双击本脚本即可无线连接上次设备
rem     - 更换设备/网络：用 USB 线重新连接一次即可
rem
rem   快捷键：
rem     Ctrl+F  投屏参数控件开关（控件可按住 Alt 拖动位置）
rem     Ctrl+G  将电脑复制的图片保存至手机（平板）相册
rem     Ctrl+H  投屏期间黑屏省电（再次按下恢复亮屏）
rem     Ctrl+T  投屏窗口置顶开关（或按住 Alt 点击参数控件左侧指示灯）
rem     - 电脑复制文本/图片自动同步到手机剪贴板，长按粘贴即可
rem     - 关窗断开；拔线/异常自动重连（USB 优先，无线兜底）
rem


rem 使用本目录内的 server（自定义构建必须，不依赖环境变量）
set "SCRCPY_SERVER_PATH=%~dp0scrcpy-server"

rem 优先使用本目录内的 adb.exe，否则用 PATH 中的 adb
set "ADB=adb"
if exist "%~dp0adb.exe" set "ADB=%~dp0adb.exe"

set "SCRIPT_DIR=%~dp0"
rem 无线地址记忆：优先本目录 config.txt；缺失时回退读取共享 config.txt
rem （dist 上两级 = scrcpy\config.txt，与 手机投屏.bat 共用同一份记忆），
rem 保存时两个文件同步写入，保证两个脚本的记忆始终一致
set "CONFIG_FILE=%SCRIPT_DIR%config.txt"
set "FALLBACK_CONFIG=%SCRIPT_DIR%..\..\config.txt"

rem ----- 串流参数：有线 USB 带宽充足用高规格；无线带宽有限保持低延迟 -----
rem 键盘模式默认 uhid；:detect_keyboard 会按 Android SDK 覆盖，读不到时保持 uhid
set "KEYBOARD=uhid"
set "LEGACY_DEVICE="
rem 有线规格：动态分配（:detect_display_spec 读取设备分辨率/刷新率后覆盖），此值为读取失败时的保底默认
set "USB_ARGS=--keyboard=!KEYBOARD! --video-codec=h264 --video-bit-rate=50M --max-size 2560 --max-fps 120 --video-codec-options="max-b-frames:int=0,bitrate-mode:int=1" --render-driver=direct3d --video-buffer=0"
set "WIFI_ARGS=--keyboard=!KEYBOARD! --video-codec=h264 --video-bit-rate=15M --max-size 1920 --max-fps 60"

rem ===== 自动切换投屏模式参数（与 手机投屏.bat 同步）=====
rem 注：WATCH_TAG 独立命名，避免与 手机投屏.bat 的后台监测进程互相误杀
set "WATCH_TAG=SCRCPY_LAUNCH_USB_WATCH"
set "WATCH_INTERVAL=2"
set "AUTO_RETRY_SEC=2"

rem ============================================
rem   主流程：重置 adb -> 检测 USB -> 有线/无线
rem ============================================
rem 启动后直接进入主流程识别设备；首次连接向导仅在菜单中选择 [2] 时进入
goto :main
:pair_wizard
echo.
echo ===== 首次连接向导 =====
echo.
echo 第一步：手机/平板开放权限
echo   1. 进入设置 - 开发者模式（连点版本号 7 次开启）
echo   2. 打开 USB 调试开关
echo      小米设备还需打开「USB 调试（安全设置）」
echo.
echo 第二步：连接电脑
echo   1. 用 USB 线将电脑与手机（平板）连接
echo   2. 完成后按任意键，脚本将自动检测设备并投屏
echo.
pause
goto :main

:main

rem ----- 1. 重置 adb 服务，清理僵死状态 -----
echo.
echo [键位] Ctrl+F 参数控件开关（按住 Alt 可拖动控件位置）^| Ctrl+G 复制图片存相册
echo          Ctrl+H 黑屏省电 ^| Ctrl+T 窗口置顶 ^| Alt+F 切换全屏
echo.
echo [1] 重置 adb 服务...
"!ADB!" kill-server >nul 2>&1
"!ADB!" start-server >nul 2>&1
if errorlevel 1 (
    echo [失败] adb 启动失败，请确认 adb 可用
    pause
    call :adb_cleanup
    exit /b 1
)

rem ----- 2. 检测 USB 设备（serial 不含冒号且不含 _adb-tls 即为 USB）-----
echo [2] 检测 USB 设备...
set "USB_DEV="
set "USB_HINT="
for /f "skip=1 tokens=1,2" %%a in ('"!ADB!" devices 2^>nul') do (
    if "%%b"=="device" (
        rem 无线设备 serial 为 IP:端口，含冒号，排除
        echo %%a | findstr /i ":" >nul 2>&1
        if errorlevel 1 (
            rem 排除 mDNS 自动发现条目（serial 含 _adb-tls）
            echo %%a | findstr /i "_adb-tls" >nul 2>&1
            if errorlevel 1 (
                rem 非 IP 且非 mDNS = USB 设备，取第一个
                if not defined USB_DEV set "USB_DEV=%%a"
            )
        )
    ) else if "%%b"=="unauthorized" (
        rem 插线但未授权（手机弹窗未点"允许"）：不参与投屏选择，但记录以便提示
        echo %%a | findstr /i ":" >nul 2>&1
        if errorlevel 1 (
            echo %%a | findstr /i "_adb-tls" >nul 2>&1
            if errorlevel 1 if not defined USB_HINT set "USB_HINT=%%a"
        )
    ) else if "%%b"=="offline" (
        rem 插线但离线（驱动/连接问题）：同样记录提示
        echo %%a | findstr /i ":" >nul 2>&1
        if errorlevel 1 (
            echo %%a | findstr /i "_adb-tls" >nul 2>&1
            if errorlevel 1 if not defined USB_HINT set "USB_HINT=%%a"
        )
    )
)

if defined USB_DEV (
    echo [OK] 检测到 USB 设备：!USB_DEV!
    set "PICK=!USB_DEV!"
    call :learn_wifi_ip
    goto :do_cast
)

rem 插线但未授权/未就绪：提示用户操作手机（不阻止无线投屏继续）
if defined USB_HINT (
    echo [提示] 检测到 USB 设备（!USB_HINT!）但未授权或未就绪，
    echo       请解锁手机并点击"允许 USB 调试"，插线监测将自动切换有线投屏
    echo.
)
echo [提示] 未检测到 USB 设备，进入无线模式
echo.
goto :wireless_mode

rem ============================================
rem   无线模式：config.txt 持久化 + 扫描 + 配对
rem ============================================
:wireless_mode
echo [3] 无线模式：尝试连接上次保存的地址...
set "WIRE_DEV="
set "LAST_DEVICE="

rem ----- 3a. 读取无线地址记忆（本目录 config.txt 优先，缺失则回退上级共享 config.txt）并尝试连接 -----
set "CONFIG_SRC="
if exist "%CONFIG_FILE%" set "CONFIG_SRC=%CONFIG_FILE%"
if not defined CONFIG_SRC if exist "%FALLBACK_CONFIG%" set "CONFIG_SRC=%FALLBACK_CONFIG%"
if defined CONFIG_SRC (
    set /p LAST_DEVICE=<"!CONFIG_SRC!"
    if not "!LAST_DEVICE!"=="" (
        echo [提示] 尝试连接：!LAST_DEVICE!
        rem adb connect 的失败详情是 UTF-8 中文且走 stdout，在 GBK 代码页(936)下显示为乱码，
        rem 这里吞掉原始输出，成功/失败统一由下面的 adb devices 验证，并显示本脚本自己的提示
        "!ADB!" connect !LAST_DEVICE! >nul 2>&1
        echo [提示] 已尝试连接，正在验证设备状态...
        rem 验证连接成功（adb devices 中有该设备且状态为 device）
        for /f "skip=1 tokens=1,2" %%a in ('"!ADB!" devices 2^>nul') do (
            if "%%a"=="!LAST_DEVICE!" if "%%b"=="device" set "WIRE_DEV=!LAST_DEVICE!"
        )
        if defined WIRE_DEV (
            echo [OK] 连接成功：!WIRE_DEV!
        ) else (
            echo [提示] 连接失败或设备未就绪：!LAST_DEVICE!（请确认同一局域网且无线调试端口已开启）
        )
    )
) else (
    echo [提示] 未找到 config.txt（本目录与上级目录均无）
)

rem ----- 3b. 若 config 地址无效，扫描已有无线设备（IP 条目优先 + 去重）-----
if not defined WIRE_DEV (
    echo [3] 扫描已有无线设备（IP 条目优先，自动去重）...
    set "IP_COUNT=0"
    for /f "skip=1 tokens=1,2" %%a in ('"!ADB!" devices 2^>nul') do (
        if "%%b"=="device" (
            set "SERIAL=%%a"
            rem 只收 IP:端口 格式条目
            echo !SERIAL! | findstr /r "^[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*:" >nul 2>&1
            if not errorlevel 1 (
                rem 同一手机 IP 条目 + mDNS 条目并存时，只保留第一个（按 IP 前缀去重）
                for /f "tokens=1 delims=:" %%k in ("!SERIAL!") do set "IPKEY=%%k"
                set "MARK=SEEN_!IPKEY!"
                if not defined !MARK! (
                    set "!MARK!=1"
                    set /a IP_COUNT+=1
                    set "IP_!IP_COUNT!=!SERIAL!"
                )
            )
        )
    )
    if !IP_COUNT! GTR 0 set "WIRE_DEV=!IP_1!"
)

rem ----- 3c. 仍无可用设备 -> 引导用户配对 -----
if not defined WIRE_DEV (
    echo.
    echo [提示] 未检测到已连接的无线设备！
    echo.
    echo   请确认：
    echo     1. 首次连接请用 USB 线连接电脑
    echo     2. 无线连接需同一 WiFi，且之前已用 USB 连接过
    echo     3. 详细配对向导[2]
    echo.
    choice /c 123 /n /m "请选择 [1]重新检测 [2]配对向导 [3]退出："
    if errorlevel 3 (
        call :adb_cleanup
        exit /b 1
    )
    if errorlevel 2 goto :pair_wizard
    goto :main
)

set "PICK=!WIRE_DEV!"
rem 保存条件：本目录 config.txt 缺失（首次连接或从上级回退读取后落盘），或地址有变化
set "NEED_SAVE="
if not exist "%CONFIG_FILE%" set "NEED_SAVE=1"
if not "!PICK!"=="!LAST_DEVICE!" set "NEED_SAVE=1"
if defined NEED_SAVE (
    > "%CONFIG_FILE%" echo !PICK!
    rem 上级共享 config.txt 存在则同步写入，与 手机投屏.bat 的记忆保持一致
    if exist "%FALLBACK_CONFIG%" (
        > "%FALLBACK_CONFIG%" echo !PICK!
    )
    echo [OK] 已保存无线地址到 config.txt：!PICK!
)
goto :do_cast

rem ============================================
rem   动态规格分配：读取设备分辨率（wm size）与峰值刷新率（peak_refresh_rate），
rem   按 分辨率×刷新率 估算有线 bit-rate；失败回退默认 50M/2560/120fps
rem ============================================
rem ============================================
rem   键盘模式自适应：Android 13+ 用 uhid，Android 12 及以下回退 sdk
rem   注意：不使用 aoa —— Windows 下 adb 已独占 USB 设备，
rem   scrcpy 会拒绝 --keyboard=aoa（仅 OTG 模式可用）
rem   兼容视频档/legacy server 只针对 Android 10 以下（SDK<29），
rem   避免把 Android 11/12 这类较新设备误判成老设备
rem ============================================
:detect_keyboard
set "KEYBOARD=uhid"
set "LEGACY_DEVICE="
set "SDK_VER="
set "SDK_IS_NUM="
rem 命令必须把 !ADB! 用引号包住：路径含空格，未加引号时 FOR /F 只会执行到第一个空格
for /f "tokens=1" %%v in ('"!ADB!" -s !PICK! shell getprop ro.build.version.sdk 2^>nul') do if not defined SDK_VER set "SDK_VER=%%v"
rem 只信任纯数字版本号：getprop 失败/异常输出时保持默认 uhid，高版本零污染
if defined SDK_VER for /f "delims=0123456789" %%d in ("!SDK_VER!") do if not "%%d"=="" set "SDK_IS_NUM=1"
if not defined SDK_IS_NUM if defined SDK_VER (
    if !SDK_VER! lss 33 set "KEYBOARD=sdk"
    if !SDK_VER! lss 29 set "LEGACY_DEVICE=1"
)
rem SDK 键盘走 adb 注入，USB/无线全版本可用，是 Windows 下老设备的唯一通用回退
echo [键盘模式] Android SDK=!SDK_VER! -^> !KEYBOARD! legacy=!LEGACY_DEVICE!
exit /b

:detect_display_spec
set "DEV_RES="
set "DEV_W="
set "DEV_H="
for /f "tokens=3 delims=: " %%a in ('"!ADB!" -s !USB_DEV! shell wm size 2^>nul') do set "DEV_RES=%%a"
if defined DEV_RES (
    for /f "tokens=1,2 delims=x" %%a in ("!DEV_RES!") do (
        set "DEV_W=%%a"
        set "DEV_H=%%b"
    )
)
set "DEV_FPS="
for /f "delims=" %%a in ('"!ADB!" -s !USB_DEV! shell settings get system peak_refresh_rate 2^>nul') do set "DEV_FPS=%%a"
if "!DEV_FPS!"=="null" set "DEV_FPS="
if not defined DEV_FPS set "DEV_FPS=60"
for /f "tokens=1 delims=." %%a in ("!DEV_FPS!") do set "DEV_FPS=%%a"
if not defined DEV_H (
    rem 读取/解析失败：回退默认有线规格；老设备用兼容档
    if defined LEGACY_DEVICE (
        set "USB_ARGS=--keyboard=!KEYBOARD! --video-codec=h264 --video-bit-rate=6M --max-size 720 --max-fps 24 --render-driver=direct3d --video-buffer=0"
        set "SPEC_INFO=兼容模式 h264/6M/720/24fps（老设备）"
    ) else (
        set "USB_ARGS=--keyboard=!KEYBOARD! --video-codec=h264 --video-bit-rate=50M --max-size 2560 --max-fps 120 --video-codec-options="max-b-frames:int=0,bitrate-mode:int=1" --render-driver=direct3d --video-buffer=0"
        set "SPEC_INFO=设备规格读取失败，使用默认规格 h264/50M/2560/120fps"
    )
    exit /b 0
)
rem 计算：长边 -> max-size（上限 2560）；fps 上限 120；bit-rate 按 分辨率×刷新率 分级估算（clamp 15..80M）
if !DEV_W! GEQ !DEV_H! (set "DEV_LONG=!DEV_W!") else (set "DEV_LONG=!DEV_H!")
set "MAX_SIZE=!DEV_LONG!"
if !DEV_LONG! GTR 2560 set "MAX_SIZE=2560"
if !DEV_FPS! GTR 120 set "DEV_FPS=120"
set /a "BR=!DEV_W!*!DEV_H!/2073600"
if !BR! LSS 1 set "BR=1"
set /a "BR=!BR!*!DEV_FPS!/60"
if !BR! LSS 1 set "BR=1"
set /a "BR=!BR!*15"
if !BR! LSS 15 set "BR=15"
if !BR! GTR 80 set "BR=80"
set "USB_BITRATE=!BR!M"
rem 键盘模式已在 do_cast 开头统一检测，这里复用结果（避免重复输出 [键盘模式]）
if defined LEGACY_DEVICE (
    if !SDK_VER! lss 29 (
        set "MAX_SIZE=720"
        set "DEV_FPS=24"
        set "USB_BITRATE=6M"
        set "USB_ARGS=--keyboard=!KEYBOARD! --video-codec=h264 --video-bit-rate=!USB_BITRATE! --max-size !MAX_SIZE! --max-fps !DEV_FPS! --render-driver=direct3d --video-buffer=0"
        set "SPEC_INFO=兼容模式 h264/6M/720/24fps（Android 8 及更早设备）"
    ) else (
        if !DEV_FPS! GTR 30 set "DEV_FPS=30"
        set "USB_ARGS=--keyboard=!KEYBOARD! --video-codec=h264 --video-bit-rate=!USB_BITRATE! --max-size !MAX_SIZE! --max-fps !DEV_FPS! --video-codec-options="max-b-frames:int=0,bitrate-mode:int=1" --render-driver=direct3d --video-buffer=0"
        set "SPEC_INFO=检测到设备 !DEV_W!x!DEV_H!@!DEV_FPS!Hz，有线规格 h264/!USB_BITRATE!/!MAX_SIZE!/!DEV_FPS!fps（老设备限 30fps）"
    )
) else (
    set "USB_ARGS=--keyboard=!KEYBOARD! --video-codec=h264 --video-bit-rate=!USB_BITRATE! --max-size !MAX_SIZE! --max-fps !DEV_FPS! --video-codec-options="max-b-frames:int=0,bitrate-mode:int=1" --render-driver=direct3d --video-buffer=0"
    set "SPEC_INFO=检测到设备 !DEV_W!x!DEV_H!@!DEV_FPS!Hz，有线规格 h264/!USB_BITRATE!/!MAX_SIZE!/!DEV_FPS!fps"
)
exit /b 0

rem ============================================
rem   投屏循环（自动切换投屏模式）
rem   scrcpy 退出后自动重新检测设备：
rem   拔线 -> 自动切无线；插线 -> 自动切有线
rem ============================================
:do_cast
rem 首次投屏推送当前电脑剪贴板（启动推送）；模式切换/重连（再次进入 do_cast）
rem 时传 --no-clipboard-push-on-start，避免手机剪贴板被相同内容反复占满
if not defined FIRST_CAST_DONE (
    set "FIRST_CAST_DONE=1"
    set "CLIP_START_PUSH="
) else (
    set "CLIP_START_PUSH=--no-clipboard-push-on-start"
)
echo.
echo ===== 开始投屏：!PICK! =====
echo   提示：关闭窗口即断开；Alt+f 切换全屏
echo.
call :stop_usb_watch
call :detect_keyboard
if defined LEGACY_DEVICE (
    if !SDK_VER! lss 29 (
        set "WIFI_ARGS=--keyboard=!KEYBOARD! --video-codec=h264 --video-bit-rate=4M --max-size 720 --max-fps 24"
    ) else (
        set "WIFI_ARGS=--keyboard=!KEYBOARD! --video-codec=h264 --video-bit-rate=8M --max-size 1080 --max-fps 30"
    )
) else (
    set "WIFI_ARGS=--keyboard=!KEYBOARD! --video-codec=h264 --video-bit-rate=15M --max-size 1920 --max-fps 60"
)
set "CAST_ARGS=!WIFI_ARGS!"
echo !PICK! | findstr /i ":" >nul 2>&1
if not errorlevel 1 (
    echo [自动切换] 无线投屏中，已开启 USB 插线监测（每 !WATCH_INTERVAL! 秒检测一次）
    echo [流畅] 无线模式：带宽有限，已启用低延迟串流（h264/15M/1920/60fps，剪贴板自动同步（电脑复制即达手机））
    call :start_usb_watch
) else (
    rem 有线 USB 连接：动态读取设备分辨率/刷新率，按设备能力分配规格（读取失败回退默认）
    call :detect_display_spec
    set "CAST_ARGS=!USB_ARGS!"
    echo [高清] 有线模式：!SPEC_INFO!（低延迟优化，剪贴板自动同步（电脑复制即达手机））
)
rem 所有设备统一使用定制 scrcpy-server：server 内对 SDK<29（Android 10 以下）
rem 已自动关闭 ABR，保留图片剪贴板等定制功能。若某台老设备仍异常，可手动
rem 切回最后兜底：把下一行改成 set "SCRCPY_SERVER_PATH=%~dp0scrcpy-server-legacy"
set "SCRCPY_SERVER_PATH=%~dp0scrcpy-server"
if defined LEGACY_DEVICE (
    echo [兼容] 老设备使用定制 server（已自动关闭 ABR，保留图片剪贴板）
)
rem ----- 保存当前代码页（scrcpy 可能改成 UTF-8），退出后恢复避免中文乱码 -----
for /f "tokens=2 delims=:" %%c in ('chcp') do set "OLD_CP=%%c"
set "OLD_CP=!OLD_CP: =!"
if not defined OLD_CP set "OLD_CP=936"
"%~dp0scrcpy.exe" --serial !PICK! !CAST_ARGS! !CLIP_START_PUSH! %*
set "CAST_RC=!ERRORLEVEL!"
chcp !OLD_CP! >nul 2>&1
call :stop_usb_watch
echo.
rem ----- 按退出码区分：0=正常关闭窗口（投屏结束，退出循环）；非0=异常断开（自动重连）-----
if "!CAST_RC!"=="0" (
    rem 区分"用户关窗"与"watcher 切换（插线）"：watcher 温和关闭 scrcpy 前会写标记文件
    if exist "%TEMP%\scrcpy_watch_switch.flag" (
        del /q "%TEMP%\scrcpy_watch_switch.flag" >nul 2>&1
        echo [自动切换] 检测到 USB 插线，切换至有线投屏...
        goto :main
    )
    echo [提示] 已检测到窗口关闭（退出码 0），投屏已结束，退出投屏循环
    call :adb_cleanup
    timeout /t 1 /nobreak >nul
    exit /b 0
)
echo [提示] 检测到连接断开（退出码 !CAST_RC!），!AUTO_RETRY_SEC! 秒后自动重连...
rem 无线场景：退出码非 0 时先确认设备是否仍在线（adb 状态为 device）。
rem 若设备已离线，说明投屏连接已结束（如用户关窗时无线连接已断开、设备掉线），
rem 直接退出投屏循环，避免无限自动重连导致"关窗后 bat 不退出"
echo !PICK! | findstr /i ":" >nul 2>&1
if not errorlevel 1 (
    set "STILL_THERE="
    set "USB_BACK="
    for /f "skip=1 tokens=1,2" %%a in ('"!ADB!" devices 2^>nul') do (
        if "%%a"=="!PICK!" if "%%b"=="device" set "STILL_THERE=1"
        rem 插线切换场景：无线可能暂时离线，若 USB 已接入则继续重连走有线
        if not defined USB_BACK if not "%%b"=="" (
            echo %%a | findstr /i ":" >nul 2>&1
            if errorlevel 1 (
                echo %%a | findstr /i "_adb-tls" >nul 2>&1
                if errorlevel 1 set "USB_BACK=%%a"
            )
        )
    )
    if not defined STILL_THERE (
        if not defined USB_BACK (
            echo [提示] 无线设备 !PICK! 已离线，投屏连接已断开，退出投屏循环
            call :adb_cleanup
            timeout /t 1 /nobreak >nul
            exit /b 0
        )
        echo [提示] 无线设备已离线，但检测到 USB 设备（!USB_BACK!），继续重连以切换有线投屏
    ) else (
        echo [提示] 无线设备 !PICK! 仍在线，尝试自动重连...
    )
)
echo.
rem 自动重连：不按键则等待后自动重新检测并投屏
choice /c qr /n /t !AUTO_RETRY_SEC! /d r /m "[自动切换] !AUTO_RETRY_SEC! 秒后自动重新检测并投屏 Q=退出循环 R=立即重投："
if errorlevel 2 goto :main
if errorlevel 1 goto :menu
rem ============================================
rem   投屏结束菜单
rem ============================================
:menu
echo.
echo ============================================
echo   投屏已结束，请选择：
echo ============================================
choice /c 123 /n /m "[1] 重新投屏 [2] 配对向导 [3] 退出："
if errorlevel 3 (
    call :adb_cleanup
    exit /b 0
)
if errorlevel 2 goto :pair_wizard
goto :main

:learn_wifi_ip
echo.
echo [学习] 有线模式：检测手机 WiFi IP...
set "WIFI_IP="
rem 主方案：ip -4 addr show 中 wlan 接口的 inet 地址（先剥离 < > 避免重定向解析，
rem 排除 127.0.0.1；tun*/rmnet* 接口不含 wlan 天然排除）
for /f "tokens=*" %%i in ('"!ADB!" -s !USB_DEV! shell ip -4 addr show 2^>nul') do (
    set "LINE=%%i"
    set "LINE=!LINE:<=!"
    set "LINE=!LINE:>=!"
    echo !LINE! | findstr /i "inet" >nul 2>&1
    if not errorlevel 1 (
        echo !LINE! | findstr /i "wlan" >nul 2>&1
        if not errorlevel 1 (
            echo !LINE! | findstr /i "127.0.0.1" >nul 2>&1
            if errorlevel 1 if not defined WIFI_IP (
                for /f "tokens=2 delims= " %%a in ("!LINE!") do set "WIFI_IP=%%a"
            )
        )
    )
)
rem 备选方案：ip route 中 wlan 路由的 src 源 IP
if not defined WIFI_IP (
    for /f "tokens=*" %%i in ('"!ADB!" -s !USB_DEV! shell ip route 2^>nul') do (
        set "LINE=%%i"
        echo !LINE! | findstr /i "wlan" >nul 2>&1
        if not errorlevel 1 (
            for /f "tokens=1-9" %%a in ("!LINE!") do (
                if "%%a"=="src" if not defined WIFI_IP set "WIFI_IP=%%b"
                if "%%b"=="src" if not defined WIFI_IP set "WIFI_IP=%%c"
                if "%%c"=="src" if not defined WIFI_IP set "WIFI_IP=%%d"
                if "%%d"=="src" if not defined WIFI_IP set "WIFI_IP=%%e"
                if "%%e"=="src" if not defined WIFI_IP set "WIFI_IP=%%f"
                if "%%f"=="src" if not defined WIFI_IP set "WIFI_IP=%%g"
                if "%%g"=="src" if not defined WIFI_IP set "WIFI_IP=%%h"
                if "%%h"=="src" if not defined WIFI_IP set "WIFI_IP=%%i"
            )
        )
    )
)
if not defined WIFI_IP (
    echo [提示] 未检测到 WiFi IP（手机可能未连接 WiFi），跳过学习，继续有线投屏
    echo.
    exit /b 0
)
rem 去除 /24 前缀
for /f "tokens=1 delims=/" %%a in ("!WIFI_IP!") do set "WIFI_IP=%%a"
echo [OK] 检测到手机 WiFi IP：!WIFI_IP!
rem ===== 判断是否需重启 adbd（tcpip）：config 缺失或 IP 变化才执行 =====
rem 读取当前已保存的无线地址
set "SAVED_IP="
if exist "%CONFIG_FILE%" (
    set /p SAVED_IP=<"%CONFIG_FILE%"
)
set "NEED_TCPIP="
if not defined SAVED_IP set "NEED_TCPIP=1"
if not "!SAVED_IP!"=="!WIFI_IP!:5555" set "NEED_TCPIP=1"
rem 即使 IP 未变：设备重启后 adbd 的无线调试端口会丢失（5555 不再监听），
rem 必须检查端口实际状态，未开启则重新执行 tcpip；端口已开才跳过（避免无谓重启 adbd）
set "TCP_PORT="
for /f "delims=" %%p in ('"!ADB!" -s !USB_DEV! shell getprop service.adb.tcp.port 2^>nul') do set "TCP_PORT=%%p"
if not "!TCP_PORT:~0,4!"=="5555" set "NEED_TCPIP=1"
if defined NEED_TCPIP (
    echo [学习] 开启无线调试端口（adb tcpip 5555）...
    "!ADB!" -s !USB_DEV! tcpip 5555 >nul 2>&1
    rem tcpip 会重启手机端 adbd（USB 短暂断开约 5-6 秒），等待设备恢复再投屏
    echo [学习] 等待设备重新上线（最多 15 秒）...
    for /l %%w in (1,1,15) do (
        set "BACK="
        for /f "skip=1 tokens=1,2" %%a in ('"!ADB!" devices 2^>nul') do (
            if "%%a"=="!USB_DEV!" if "%%b"=="device" set "BACK=1"
        )
        if defined BACK (
            echo [OK] 设备已恢复（约 %%w 秒）
            goto :learn_wait_done
        )
        ping -n 2 127.0.0.1 >nul
    )
    goto :learn_wait_done
) else (
    echo [学习] WiFi IP 未变化（!SAVED_IP!），跳过 tcpip（避免重启 adbd 断连）
)
:learn_wait_done
if defined NEED_TCPIP if not defined BACK (
    echo [提示] 等待超时，设备未恢复，继续尝试投屏（重连时将自动跳过 tcpip）
)
rem 保存到 config.txt（共享 config.txt 存在时同步写入）
> "%CONFIG_FILE%" echo !WIFI_IP!:5555
if defined FALLBACK_CONFIG if exist "%FALLBACK_CONFIG%" (
    > "%FALLBACK_CONFIG%" echo !WIFI_IP!:5555
)
echo [OK] 已保存无线地址到 config.txt：!WIFI_IP!:5555
echo.
exit /b 0

:start_usb_watch
call :stop_usb_watch
start "!WATCH_TAG!" /b powershell -NoProfile -WindowStyle Hidden -Command "$WATCH_TAG='!WATCH_TAG!';$adb='!ADB!';while($true){if(-not(Get-Process scrcpy -ErrorAction SilentlyContinue)){break};$u=& $adb devices 2>$null|Select-Object -Skip 1|Where-Object{$_ -match '\S+\s+(device|unauthorized|offline)\s*$' -and $_ -notmatch ':' -and $_ -notmatch '_adb-tls' -and $_ -notmatch 'emulator'};if($u){Set-Content -Path "$env:TEMP\scrcpy_watch_switch.flag" -Value 1;$wp=Get-Process scrcpy -ErrorAction SilentlyContinue|Where-Object{$_.MainWindowHandle -ne 0};if($wp){[void]$wp.CloseMainWindow()};$dl=(Get-Date).AddSeconds(5);while((Get-Process scrcpy -ErrorAction SilentlyContinue) -and (Get-Date)-lt $dl){Start-Sleep -Milliseconds 200};Get-Process scrcpy -ErrorAction SilentlyContinue|Stop-Process -Force -ErrorAction SilentlyContinue;break};Start-Sleep -Seconds !WATCH_INTERVAL!}"
exit /b

rem ============================================
rem   停止 USB 插线监测（按命令行标记精确清理，
rem   排除自身 PID，避免误杀其他 powershell）
rem ============================================
:stop_usb_watch
powershell -NoProfile -Command "Get-WmiObject Win32_Process | Where-Object { $_.CommandLine -match '!WATCH_TAG!' -and $_.ProcessId -ne $PID } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }" >nul 2>&1
exit /b

rem ============================================
rem   退出前清理 adb server（确保后台干净）
rem ============================================
:adb_cleanup
"!ADB!" kill-server >nul 2>&1
exit /b
