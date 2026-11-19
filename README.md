# 跨设备剪贴板 · ClipboardBridge

在 Windows、Android 手机和平板之间同步剪贴板。复制文字或图片后，可以在另一台设备直接粘贴。也可以手动发送文字、一次选择多张图片，或从历史记录找回复制过的内容。

## 下载

| 平台 | 安装包 | 系统要求 |
| --- | --- | --- |
| Windows | [下载便携版 EXE](https://github.com/TuYun0906/ClipboardBridge/releases/latest/download/Clipboard-Windows.exe) | Windows 10 或 11，.NET Framework 4.8 或更新版本 |
| Android | [下载 APK](https://github.com/TuYun0906/ClipboardBridge/releases/latest/download/Clipboard-Android.apk) | Android 10 或更新版本 |

查看 [所有版本及更新日志](https://github.com/TuYun0906/ClipboardBridge/releases)。

## 快速上手

1. 让设备连接同一个 Wi-Fi 或局域网。
2. 选一台设备作为主机。在 Windows 打开「设置」查看地址并复制配对码。使用 Android 当主机时，在「连接」打开「作为主机」，再查看地址并复制配对码。
3. 在其他设备输入主机地址和配对码，保存连接。在 Windows 连接手机时，先勾选「连接到 Android 主机」。在 Android 作为客户端时，关闭「作为主机」和「公网模式」。若 Windows 防火墙询问，允许应用在当前受信任的专用网络接收入站连接。
4. 看到「已连接」后，先手动发送一段文字，在另一台设备粘贴验证。开启自动发送后，也可以直接复制。图片粘贴需要目标应用支持。

## 主要功能

- **复制和发送**：自动同步文字和图片，也可手动发送。选图支持多选，开启「发送前压缩图片」可减少流量。系统剪贴板保留最后收到的一张图。
- **共享历史**：支持搜索、按日期和类型筛选、预览、编辑文字和删除。历史编辑和删除会在设备之间同步，重复文字或图片保留最新一条。想逐张找回批量发送的图片，请在发送端开启「历史记录保存图片」。实际压缩过的图片会显示压缩后占原体积的百分比，例如「已压缩至原70%」。历史默认每页 50 条，可点击底部的每页条数切换。
- **后台使用**：Windows 关闭窗口后可从系统托盘恢复，也可以设置开机自启。Android 使用前台服务保持连接，保留当前输入法。
- **截图与拍照**：Android 可自动发送新保存的截图和相机照片。对端确认写入剪贴板后显示「发送图片成功」。华为设备和 Android 10 可在「同步与历史」点「允许显示底部发送提示」，完成系统悬浮窗授权，启用底部圆角提示。

Android 可在「同步与历史」启用「截屏发送悬浮窗」。允许显示悬浮窗后，首次点「截屏」需通过系统屏幕捕获确认，返回原应用后再点一次即可发送。悬浮窗会显示「发送中」，收到对端回执后播放约 0.6 秒的「发送成功」动画。按住并移动悬浮窗可调整位置，软件会记住拖动后的位置。系统结束捕获会话后，下次需重新授权。

## Android 权限

手动发送、接收剪贴板以及作为主机均可直接使用。要在其他应用中复制并自动发出，请启动 Shizuku，在剪贴板应用开启自动发送，再点「授权 Shizuku 后台同步」。没有可用的 Shizuku 服务时，自动读取会在剪贴板应用获得焦点时触发。开启截图与拍照自动发送需要允许读取照片。 [Shizuku 安装与启动指南](https://shizuku.rikka.app/guide/setup/)

## 跨网络连接

- **Tailscale**：让设备加入同一个 Tailscale 私有网络。在 Windows 主机勾选「使用 Tailscale 监听」，将显示的地址和配对码填到 Android 客户端，并开启「公网模式」。 [Tailscale 安装指南](https://tailscale.com/download)
- **TCP 内网映射**：关闭 Windows 的「使用 Tailscale 监听」。与剪贴板运行在同一台电脑上的 SakuraFrp 或 frpc 可将公网 TCP 端口转发到 `127.0.0.1:47832`。Android 客户端开启「公网模式」，填写公网地址及端口和原配对码。 [frp TCP 配置](https://gofrp.org/en/docs/features/tcp-udp/)

设备连接使用 TLS 和配对码认证。配对码可用于加入主机，请仅分享给受信任的设备。两端应用内的「如何上手」也提供配对和连接步骤。

## 从源码构建

需要 Windows、PowerShell 7、JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。先用 `./windows/build.ps1` 构建 Windows 版。Android 先运行 `./android/fetch-deps.ps1`，再运行 `./android/build.ps1`，通过 `-AndroidJar`、`-BuildTools` 和 `-Jdk` 指定相应路径。发布包由 GitHub Actions 在推送版本标签后自动构建。
