# Glissway

[下载 APK](https://github.com/qingye060324/Glissway/releases/latest) · [源码](https://github.com/qingye060324/Glissway)

Glissway 是通过手机 Roll / Pitch 姿态控制 MIDI 的 Android 应用。支持 Edit / Live、Material You 动态配色、即时响应气泡、MIDI CC、14-bit Pitch Bend 和本地命名预设。

## 安装与运行

- 从 GitHub Releases 下载 `Glissway-debug.apk` 安装到 Android 8.0 或更新版本；Android 12+ 使用系统 Monet 色板。
- 或用 Android Studio 打开项目，使用 JDK 17、Android SDK 35 构建运行；命令行为 `./gradlew assembleDebug`。
- `design/ui-preview.html` 可直接打开，也可在项目根目录运行 `python3 -m http.server 8765`，访问 `http://localhost:8765/design/ui-preview.html`。请将同目录的 `glissway-presets.js` 一并保留。
- 浏览器 MIDI 使用支持 Web MIDI 的浏览器。BLE / RTP-MIDI 使用 Android 内置的原生传输，浏览器页面不会建立或模拟这两类连接。

## 演奏与编辑

- **Live**：点击底部 X / Y 参数绑定控制器。每个控制器独立选择 Roll、Pitch 或 Manual。姿态直接改变输出，不加入平滑、惯性或延迟。
- **Edit**：上下拖动推子发送手动值；点击控制器名称编辑类型、MIDI 通道、CC 编号、范围、角度、反转和启用状态。右侧加号最多添加 32 个控制器。
- **Pitch Bend**：将 Output Type 选择为 Pitch Bend。输出标准 MIDI 1.0 消息，范围 0–16383，中心 8192，数据顺序 LSB / MSB。Live 显示 -100% / 0% / +100%。
- Pitch Bend Sensitivity 只改变倾角映射增益，不发送 RPN，也不改变乐器内部半音弯曲范围。半音范围请在接收乐器中设置。
- 同一输出通道只允许一个启用的 Pitch Bend 控制器；遇到冲突请调整通道或关闭另一个映射。Reset to Center 立即发送 8192；下一次姿态变化继续控制。
- 底部准星校准当前姿态。右上角设置调整主题和气泡方向；这些视觉参数不会因切换 CC / Pitch Bend 改变求解算法。

## Presets

Edit 底部书签按钮打开 Presets：

- **Save As**：输入新名称，保存并选为当前预设。
- **Save**：明确覆盖当前预设；调整参数只保存工作草稿，不覆盖已保存版本。
- **Load**：先选中列表中的预设，再加载。存在修改时提供 Save / Discard / Cancel。
- **Rename / Duplicate / Delete**：对列表选中项操作；复制后可输入新名称，删除前确认。

名称支持中文、英文、Emoji 等 Unicode；空名、规范化后重名会被拒绝。重命名不改变内部 ID。首次存在 Default 预设；删除最后一个预设会重建 Default。

预设保存控制器集合、顺序、手动值、CC / Pitch Bend 映射、X/Y 绑定及演奏配置。实时姿态值不会标记为未保存。重启恢复上次预设及未保存草稿。主题、连接会话、实时传感器和临时校准不进入预设。加载时先归中旧 Pitch Bend，再同步当前姿态或 Edit 手动输出；不会断开或重建 MIDI 连接。

## MIDI 连接

Edit 底部地球按钮打开原生连接界面。启动 BLE 后，在电脑或其他接收端的 MIDI 设置中连接手机；真正订阅 MIDI 通知后胶囊才发绿光。

RTP-MIDI 输入电脑局域网 IP 和 AppleMIDI 控制端口（通常 5004，数据端口为 5005）。Windows 先在 rtpMIDI 创建并启用会话；macOS 在音频 MIDI 设置 → MIDI 工作室 → 网络中启用会话。允许局域网 UDP 控制端口及数据端口通过防火墙；在 DAW 中选中对应 MIDI 输入。

原有 BLE 广播、GATT、AppleMIDI 握手、时钟同步、队列及会话实现保留。两种连接沿用原应用的切换方式，选择另一种会停止原连接。两种传输均可发送 CC 与 Pitch Bend。任一输出实际可用即显示绿色胶囊光晕；连接中、失败或全部断开不发光。保持前台使用，切到后台会归中并停止连接，恢复后手动重新连接。

## 配置迁移与项目结构

- 应用显示、JavaScript 全局对象、事件和新存储均使用 Glissway：`Glissway`、`GlisswayMidi`、`glissway-midi`、`glissway-pose`、`glissway-settings`、`glissway-presets`。
- 为支持原应用覆盖升级，保留 Android applicationId / Java package `com.example.tiltmidi` 与旧 SharedPreferences `config/maps`。原映射首次导入 Default，原 SharedPreferences 不删除。
- 唯一保留的旧参考品牌字符串是一次性网页存储迁移键 `tilt-midi-nuectrl-v4`。成功复制到新键并建立 `glissway-settings-migration-backup` 后删除旧键；这是避免旧网页配置丢失所需的兼容查找，不作为品牌展示或运行接口。
- 不同来源的 Android APK 如果签名不同，不能直接覆盖安装。需要保留原设备配置时，请使用原签名构建升级包；卸载会删除本地数据。
- `design/ui-preview.html` 与 `design/glissway-presets.js` 是共享界面源文件。Gradle 的 `syncGlisswayUi` 在构建前自动复制到 `app/src/main/assets`，无需手工维护两个版本。
- `MainActivity.java` 负责可信本地 WebView、Monet、传感器、旧配置导入及桥接；`BleMidi.java` 和 `RtpMidi.java` 继续承担原有连接与传输。

## 应用图标与安全区

应用图标来自 `design/ChatGPT Image 2026年10月15日 23_00_11.png`，包含常规、自适应和 Android 13+ 单色主题资源。

Android 由原生 `WindowInsets` padding 统一处理状态栏、挖孔、横屏边缘与键盘；WebView 设置 `inset-native` 标记后将网页安全区归零，避免重复留白。浏览器继续保留 `env(safe-area-inset-*)`。

## 本次验证范围

已通过 Debug APK 构建、APK 签名与资源核对、原有五项气泡回归检查、浏览器预设操作及重启恢复、MIDI CC/Pitch Bend 字节检查、响应式布局和浅深色检查。临时验证工具未加入项目依赖，未新增测试框架或模拟传输产品功能。

当前环境没有连接真实 Android 手机与 MIDI 接收端，本次新增 Pitch Bend 尚需在你的 BLE / RTP-MIDI 接收设备上联机确认。原有 CC 联机能力沿用已有实现。

## License

GPL-3.0-or-later，见 `LICENSE`。Copyright (C) 2026 qingye060324。
