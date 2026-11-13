# 剪贴板 · 0.3.0

Windows 便携版与 Android 安装包在 [Releases](https://github.com/TuYun0906/ClipboardBridge/releases) 下载。

## 发布新版本

提交本次改动后，在对应提交上创建并推送 `v主版本.次版本.修订号` 标签。GitHub Actions 会验证传输协议、构建双端程序、签名 APK，并把安装包上传到该标签的 Release。Android 发布签名由仓库的 Actions secrets 提供。

本地构建使用 Windows PowerShell 7、JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。先运行 `android/fetch-deps.ps1`，再运行 `windows/build.ps1` 和 `android/build.ps1`。Android 脚本需传入 SDK 的 `android.jar`、Build Tools 目录和 JDK 根目录。

## 局域网配对

1. 在 Windows 10 或 11 上运行 `Clipboard-Windows.exe`。程序使用 .NET Framework 4.8 或更新版本。在 Android 10 或以上的手机上安装并打开 `Clipboard-Android.apk`。
2. 让手机与电脑加入同一个局域网。在 Windows 窗口点「设置」，记下显示的地址，然后点「复制配对码」。
3. 在手机的「连接」填写电脑地址和配对码，关闭「公网模式」，点「保存连接」。两端显示「已连接」后即可发送。若 Windows 防火墙询问，允许应用在当前受信任的专用网络接收入站连接。
4. 每个 Windows 实例同时连接一台 Android 设备。切换手机和平板时，先断开当前设备，再打开另一台上的剪贴板应用。

## Android 后台自动发送

先从官方渠道安装 Shizuku，并按设备系统版本启动。 [Shizuku 下载](https://shizuku.rikka.app/download/)

- **华为 nova 9 SE（Android 10）**：在开发者选项开启 USB 调试，用数据线连接电脑。在 Shizuku 的「通过连接电脑启动」查看启动命令，按官方说明用 Windows 上的 ADB 执行。 [通过电脑启动](https://shizuku.rikka.app/guide/setup/#start-by-connecting-to-a-computer)
- **小米 Pad 7（Android 15）**：在开发者选项开启无线调试。在 Shizuku 里按「配对」提示输入系统显示的配对码，再返回 Shizuku 点「启动」。 [通过无线调试启动](https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging)

完成局域网配对后，在剪贴板应用开启自动发送，点「授权 Shizuku 后台同步」，并在授权弹窗允许访问。开关文字变成「自动发送剪贴板」后，即可隐藏浮窗并退到后台。手机重启后，先确认 Shizuku 显示正在运行。若服务已停止，按上面对应的方式重新启动。

## 复制、发送和浮窗

- Windows 开启「自动发送刚复制的内容」后，复制文字或图片即可发送。手机收到后，直接使用目标应用的系统粘贴操作。
- Android 保留现有输入法。后台自动发送生效时，在其他应用复制文字或图片，电脑即可直接粘贴。如果设置显示「打开软件时自动发送剪贴板」，手机复制后需打开本软件触发发送。两端也可以用「发送文字」和「选择图片」手动发送。
- Android 开启「自动发送截图与拍照」时选择允许访问全部照片。保存到系统截图或相机目录的新图片会发送到电脑。能否粘贴图片取决于目标应用支持的剪贴板格式。
- Windows 点窗口右上角的关闭按钮后继续在托盘运行。左键单击蓝色剪贴板图标恢复窗口，右键菜单提供「显示窗口」和「退出」。开启「设置 → 开机自启」后，登录 Windows 时应用直接在托盘启动。移动便携版 EXE 后，请从新位置运行并重新保存开机自启设置。
- Android 拖动浮窗标题可以移动，拖右下角改变尺寸，点「−」收成小球，点击小球恢复窗口。关闭「显示浮窗」后连接继续运行，可从常驻通知或应用图标返回设置。

## 剪贴板历史

Windows 在主窗口直接搜索历史，Android 点「历史记录」后搜索。文字按内容匹配，图片可搜「图片」。Windows 点记录旁的「复制」，Android 轻点记录，即可把内容放回本机剪贴板。

历史保存在各自设备上，每端最多保存 100 条或 64 MiB，超限时移除最早的记录。两端默认关闭「历史记录保存图片」。开关只影响以后是否存入图片，已保存的图片仍可搜索和复制。Android 退到后台时，本机剪贴板历史随已授权的自动发送一起记录。

## 跨网络连接

1. 在电脑和手机安装 Tailscale 并加入同一个私有网络。 [Windows 安装说明](https://tailscale.com/docs/install/windows) · [Android 安装说明](https://tailscale.com/docs/install/android)
2. 在 Windows 设置勾选「公网模式（Tailscale）」并保存。重新打开设置，记下显示的 Tailscale IPv4 地址。
3. 在手机切到「公网模式」，填写该地址并保存。继续使用同一配对码。Tailscale 的连接在无法直连时可以走中继。 [直连与中继](https://tailscale.com/docs/reference/device-connectivity)
4. 如果连接不上，检查两端是否在线，以及 Windows 防火墙是否允许手机访问 TCP `47832`。可将入站规则的远程地址限制为手机的 Tailscale 地址。切回局域网时，两端都关闭公网模式。

## 测试范围

后台收发测试使用 Android 10 和 Android 15 模拟器。测试覆盖 Shizuku 启用后在后台收发文字和图片，以及关闭自动发送后停止转发。Windows 本机测试覆盖隐藏浮窗后的剪贴板同步。安卓还测试了手动发送、截图和相机目录中新增图片的后台发送，以及浮窗拖动、缩放、收球与恢复。华为和小米的实体机及异地公网连接尚未实测。
