# AirPods 原生自动切换与跨平台方案

核对日期为 2026-11-24。目标是同一副 AirPods 在 iPhone 和 Galaxy Tab 之间切换，iPhone 保持正常蓝牙配对且无需安装 LinkPods。应用于 B 站直播时，需要分别观察耳机从哪台设备出声、iPhone 播放器是否暂停，以及已经生效的交接能否保持。[LinkPods ADR-0001](../adr/0001-galaxy-initiated-audio-handoff.md) · [LinkPods ADR-0002](../adr/0002-bidirectional-handoff-and-yielded-link.md)

## 苹果原生体验

在支持的 AirPods 和使用同一 Apple 账户的苹果设备上，新设备开始播放或进入通话时可以自动接管。例如 Mac 正听音乐，iPhone 开始播客，AirPods 会切到 iPhone。iPhone 或 iPad 失去耳机时可出现切换通知，用户可以点按切回。Apple 也列出通话中的保护行为和关闭自动切换的设置。对于 Android，Apple 的配对指南覆盖普通蓝牙听音和通话。公开文档没有给出同账户原生切换时，B 站直播播放器必然暂停的承诺。[Apple 自动切换指南](https://support.apple.com/guide/airpods/switch-airpods-between-apple-devices-dev228ba3df8/web) · [支持范围与通话规则](https://support.apple.com/en-gb/104988) · [配对非 Apple 设备](https://support.apple.com/en-gb/guide/airpods/dev499c9718b/web)

## 保留 AirPods 的跨平台实现

| 方案 | 已公开的行为 | 与 iPhone 直播有关的证据 |
| --- | --- | --- |
| LibrePods（Android） | 通过 AirPods 专有协议支持两台设备保持连接与音频切换，Android 接管时可让 iPhone 显示 `Move to iPhone`。 | 项目演示和说明覆盖切换与横幅，未给出 iPhone 上 B 站直播暂停的验证。[LibrePods 多设备说明](https://github.com/librepods-org/librepods#multi-device-connectivity) |
| podsd（Linux） | 检测 Linux 本地音频起播后接管。iPhone 接管时，podsd 暂停 Linux 本地播放器并关闭本地音频配置文件。 | 文档明确了 Linux 端可以暂停自己的播放器，也记录过误恢复远程 Spotify 控制后，iPhone 立即接回耳机。该例涉及 Spotify Connect，与 B 站直播不同。[podsd 切换说明](https://github.com/madeitdisfar/podsd#switching-between-devices) |
| LinkPods（Galaxy Tab） | 已知问题记录中，普通点播视频之间可切换，iPhone 显示 `Move to iPhone`。 | 特定 SM-X810 与 iPhone 组合中，B 站直播继续活跃，平板短暂接管后约两秒被 iPhone 接回。自动请求和手动切换均观察到该现象。结果支持对本场景的描述，iOS 路由仲裁与 B 站播放器各自的作用仍待对照。[设备记录](../issues/README.md) · [既有 AACP 实现调研](open-source-airpods-smart-routing.md) |

## 其他品牌如何处理两端同时播

| 方案 | 触发与优先级 | 对旧播放器的公开行为 |
| --- | --- | --- |
| Galaxy Buds Auto Switch | 受支持的 Galaxy 设备登录同一三星账户。三星明确举例，手机播放音乐时，平板开始播放 YouTube 可以接管耳机。来电也可以触发切换。 | 三星指南描述了耳机输出转移，示例未说明旧播放器会怎样处理。该自动切换的手机与平板属于 Galaxy 生态。[Samsung Auto Switch](https://www.samsung.com/us/support/answer/ANS10002627/) |
| Google Pixel Buds Multipoint | 可同时连接 iOS、Android 等蓝牙主机。两边都是媒体时，当前音乐或视频优先，用户先暂停当前端。电话或视频通话优先于媒体。 | Google 明确写到，另一端来电可暂停当前音乐，通话结束后可点耳机恢复。Google 另有基于同一 Google 账户和 Fast Pair 的 Android Audio Switch，切换有通知且可以撤回。[Google Pixel Buds 帮助](https://support.google.com/googlepixelbuds/answer/12319417?hl=en-CA) |
| Sony WF-1000XM5 Multipoint | 两台设备同时连接，第二端开始播放时仍听到第一端。第一端停止后才听到第二端。 | 文档要求停止第一端的播放。如果第一端停播后仍发出无声信号，Sony 还提供退出应用或选定音源的处理步骤。[Sony 多点连接](https://helpguide.sony.net/mdr/2963/v1/en/contents/TP1001106282.html) · [Sony 无声排查](https://helpguide.sony.net/mdr/2963/v1/en/contents/TP1000780030.html) |

## 为什么要分别验收切换和暂停

音频输出变化与遥控暂停是两条路径。Apple 说明，耳机断连且造成输出丢失时，`AVPlayer` 会自动暂停。应用自己的播放器可监听 `AVAudioSession` 的路由变化并处理。AirPods 自动交接时，B 站直播是否收到相同的路由事件，以及播放器如何响应，公开资料没有说明。[Apple 音频路由变化文档](https://developer.apple.com/documentation/avfaudio/responding-to-audio-route-changes)

蓝牙 AVRCP 有明确的 Pause 命令。Apple 的 Media Player 文档说明，应用可为远程媒体命令注册处理器，由当前 Now Playing 应用接收相应事件，部分系统播放器会自动处理。这可以解释为什么值得单独核对 iPhone 的耳机按键和控制中心暂停。LinkPods 的 AirPods 接管记录只能确认路由变化和已观察到的播放器状态，现有证据未区分暂停请求未发出、未送达、应用未处理或播放器随后恢复。[Bluetooth SIG AVRCP 1.6.3](https://files.bluetooth.com/wp-content/uploads/dlm_uploads/2024/10/AVRCP_v1.6.3.pdf) · [Apple 外部播放控制事件](https://developer.apple.com/documentation/mediaplayer/handling-external-player-events-notifications) · [Apple `pauseCommand`](https://developer.apple.com/documentation/mediaplayer/mpremotecommandcenter/pausecommand) · [LinkPods 设备记录](../issues/README.md)

## 原生对照与验收记录

用同一台 iPhone、同一副 AirPods 和同一版本的 B 站分别测点播和直播。开始时在 iPhone 上确认 AirPods 实际有声。目标端播放一段可辨认的不同音频。每轮记录 AirPods 在开始后一到两秒及三十秒时的实际音源、iPhone 上原播放是否暂停、是否改为外放、是否出现切换横幅及是否接回耳机。三十秒沿用仓库的双直播验收时长。[LinkPods ADR-0001](../adr/0001-galaxy-initiated-audio-handoff.md)

| 对照 | 看什么 | 当前证据 |
| --- | --- | --- |
| iPhone B 站点播、直播 → 同账户 iPad 或 Mac 新播放 | 分别记录原生切换后点播和直播能否自动暂停，以及新音源能否持续。 | Apple 文档覆盖原生切换体验，尚未在本项目复核这两种 B 站播放。 |
| iPhone 单独使用 AirPods，分别点播、直播 | 先用 AirPods 触控暂停，再用 iPhone 控制中心暂停，观察按钮是否存在、播放器是否实际停止或继续。分别运行两次以免互相影响。 | 尚未验证。此步检查 iPhone 播放器对两种暂停入口的响应。 |
| iPhone B 站点播、直播 → Galaxy Tab 新播放 | 用与原生组一致的观察项，分别记录已切换、旧播放器已暂停、是否三十秒内被接回。 | 已有普通点播可切换及直播约两秒被接回的记录，缺少与原生组同条件的对照。[设备记录](../issues/README.md) |

若同账户 iPad 或 Mac 切换后，iPhone 直播仍播放但耳机稳定留在新设备，原生对照的直播目标就是保持交接。若原生能暂停直播且同一播放器也响应 AirPods 暂停，Galaxy 仍无法暂停时，差异落在交接和暂停的触发或传递路径。以上为对照结果的解释，本文未执行真机测试。
