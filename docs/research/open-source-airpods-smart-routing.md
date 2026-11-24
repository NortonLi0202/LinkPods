# 开源 AirPods AACP / Smart Routing 实现调研报告

> 本文档基于开源项目源码、协议逆向记录及公开 iOS `audioaccessoryd` 反编译参考的一手证据编写，用于分析 LinkPods 在配对 iPhone 播放直播（如 `tv.danmaku.bilianime` live）时，平板端自动触发 `TAKE`（发送 `Hijackv2`）后约 1.5–2 秒被 iPhone 以 `Hijackv2`（`localscore=301`）反抢所有权的根因与工程对策。

## 1. 一手源码与参考仓库清单

以下所有结论均直接核对自对应提交（commit）的原始源码或议题记录：

| 项目 / 仓库 | 平台 / 语言 | 核对提交 (SHA) | 核心文件 |
| --- | --- | --- | --- |
| `librepods-org/librepods` (Android) | Android / Kotlin | `89356c08` | [`AACPManager.kt`](https://github.com/librepods-org/librepods/blob/89356c08cea3db0b6b54792d9329d09a87cc85fa/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt)、[`AirPodsService.kt`](https://github.com/librepods-org/librepods/blob/89356c08cea3db0b6b54792d9329d09a87cc85fa/android/app/src/main/java/me/kavishdevar/librepods/services/AirPodsService.kt)、[`MediaController.kt`](https://github.com/librepods-org/librepods/blob/89356c08cea3db0b6b54792d9329d09a87cc85fa/android/app/src/main/java/me/kavishdevar/librepods/utils/MediaController.kt)、Issues `#446` / `#782` / `#921` |
| `librepods-org/librepods` (`linux/rust` 分支) | Linux / Rust | `672e65ad` | [`linux-rust/src/bluetooth/aacp.rs`](https://github.com/librepods-org/librepods/blob/672e65ad36eebf21ff1c1a508066f9197ee56d17/linux-rust/src/bluetooth/aacp.rs)、[`linux-rust/src/devices/airpods.rs`](https://github.com/librepods-org/librepods/blob/672e65ad36eebf21ff1c1a508066f9197ee56d17/linux-rust/src/devices/airpods.rs)、[`linux-rust/src/media_controller.rs`](https://github.com/librepods-org/librepods/blob/672e65ad36eebf21ff1c1a508066f9197ee56d17/linux-rust/src/media_controller.rs) |
| `madeitdisfar/podsd` | Linux / Rust | `acf5fcea` | [`src/bluetooth/relay.rs`](https://github.com/madeitdisfar/podsd/blob/acf5fcea751cf017ed991cc70ddcdc8b0d555ed0/src/bluetooth/relay.rs)、[`src/switching.rs`](https://github.com/madeitdisfar/podsd/blob/acf5fcea751cf017ed991cc70ddcdc8b0d555ed0/src/switching.rs)、[`src/bluetooth/aacp.rs`](https://github.com/madeitdisfar/podsd/blob/acf5fcea751cf017ed991cc70ddcdc8b0d555ed0/src/bluetooth/aacp.rs) |
| `HanreichC/MyPods` | Linux / C++ | `e8756b09` | [`AapAudioSwitchCapability.cpp`](https://github.com/HanreichC/MyPods/blob/e8756b091aef77fcca9aa38f4532d41f74a71212/core/src/device/capabilities/aap/AapAudioSwitchCapability.cpp)、[`docs/airpods-switching.md`](https://github.com/HanreichC/MyPods/blob/e8756b091aef77fcca9aa38f4532d41f74a71212/docs/airpods-switching.md) |
| `zackb/tether` | Linux / C++ | `00344ae4` | [`src/core/src/bluetooth/airpods.cpp`](https://github.com/zackb/tether/blob/00344ae4dc85dba842a529433388c986109051c9/src/core/src/bluetooth/airpods.cpp)、[`docs/BLUETOOTH.md`](https://github.com/zackb/tether/blob/00344ae4dc85dba842a529433388c986109051c9/docs/BLUETOOTH.md) |
| `Weverses/OPAirPods` | Android / Java | `599ca981` | [`Routing.java`](https://github.com/Weverses/OPAirPods/blob/599ca981f8aed4d87cbffc9bfe45559585376a91/app/src/main/java/com/tlsu/pods/Routing.java)、[`BluetoothBackend.java`](https://github.com/Weverses/OPAirPods/blob/599ca981f8aed4d87cbffc9bfe45559585376a91/app/src/main/java/com/tlsu/pods/BluetoothBackend.java) |
| `hugo2006alm/airpods-switch` | Windows / C# | `7f402055` | [`AacpProtocol.cs`](https://github.com/hugo2006alm/airpods-switch/blob/7f40205518df76c27b8cba8f377800bb9034b42c/src/AirPodsSwitch.Core/Protocol/AacpProtocol.cs)、[`docs/AACP_SWITCHING.md`](https://github.com/hugo2006alm/airpods-switch/blob/7f40205518df76c27b8cba8f377800bb9034b42c/docs/AACP_SWITCHING.md) |
| `sidnvy/OpenPodsMax` | Linux / C | `806c98ce` | [`linux/backend/src/smart_routing.c`](https://github.com/sidnvy/OpenPodsMax/blob/806c98ce19b18032a247fdd5356b8dce1c5223f4/linux/backend/src/smart_routing.c) |
| Apple `audioaccessoryd` 反编译参考 | iOS / ObjC++ | `90aa0cfe` / `e26ed456` | [`audioaccessoryd_14.mm`](https://github.com/EthanArbuckle/iPhone18-3_26.1_23B85_Restore/blob/90aa0cfe59d9682b4265e1354c8b19ec3c7823ab/usr/libexec/audioaccessoryd/audioaccessoryd_14.mm)、[`BTSmartRoutingWxDevice.m`](https://github.com/EthanArbuckle/iPhone17-1_18.2_22C152_Restore/blob/e26ed4563f78871c59d2d96856756a65d62517e5/usr/libexec/audioaccessoryd/BTSmartRoutingWxDevice.m) |
| LinkPods（调研基线） | Android / Java | `fdec576` | `RoutingPackets.java`、`HandoffService.java` |
| LinkPods（PR #27 当前实现） | Android / Java | `617a371` 及本次审查修正 | [RoutingPackets.java](../../apps/android/src/local/airpods/handoffprobe/RoutingPackets.java#L47-L70)、[HandoffService.java](../../apps/android/src/local/airpods/handoffprobe/HandoffService.java#L994-L1025) |

---

## 2. OPACK 抢占与路由请求负载 (`0x0010` / `0x0011`)

### 2.1 `Hijackv2` vs `ManualRoute` 与分数字段语义

在苹果 `audioaccessoryd`（`EthanArbuckle/iPhone18-3_26.1_23B85_Restore` [`audioaccessoryd_14.mm`](https://github.com/EthanArbuckle/iPhone18-3_26.1_23B85_Restore/blob/90aa0cfe59d9682b4265e1354c8b19ec3c7823ab/usr/libexec/audioaccessoryd/audioaccessoryd_14.mm) 及 `iPhone17-1_18.2_22C152_Restore` [`BTSmartRoutingWxDevice.m`](https://github.com/EthanArbuckle/iPhone17-1_18.2_22C152_Restore/blob/e26ed4563f78871c59d2d96856756a65d62517e5/usr/libexec/audioaccessoryd/BTSmartRoutingWxDevice.m)）中，Smart Routing 存在两条语义截然不同的路径：

1. **`Hijackv2`（自动抢占路径）**：受 `[BTSmartRoutingDaemon _evaluatorRunTipiElection:nearbyInfoDevicesMap:localScore:]` 与 `_smartRoutingAudioRoutingRequest:withResponseHandler:`（含 `hijackBackoffTicks` 退避计数）仲裁。比较对端与本端的 `localscore` / `audioRoutingScore` / `remotescore`。
2. **`ManualRoute`（显式手动路由路径）**：对应 `BTSmartRoutingWxDevice` 的 `manuallyRouted` / `manualRouteChangeInProgress` 与 `_sendIntendedRouteInfoUpdateToWx:withIntendedRoutingStatus:`（日志 `"SendManualRouteInfoToWx"`），表示用户显式在系统路由菜单中选定设备，不参与常规自动评分重选。

### 2.2 各开源项目的 `0x0010` OPACK 负载对比

所有主流开源实现均源自对 `librepods-org/librepods` `AACPManager.kt` 的逆向移植，但**在关键字段与 OPACK 编码修复上存在重要差异**：

1. **`librepods-org/librepods`（Android `AACPManager.kt:874-1033`；Rust `aacp.rs:1025-1163`）**：
   - `sendHijackRequest` 发送 `0x10` 字典（`0x01 0xE5`）：
     - `localscore`: `100`（`0x30 0x64`）
     - `reason`: `"Hijackv2"`
     - `audioRoutingScore`: `301`（`0x31 0x2D 0x01`）
     - `audioRoutingSetOwnershipToFalse`: `true`（`0x01`）
     - `remotescore`: `0xA5`（OPACK 后向引用第 5 个已入表标量，即 `301`）
   - `sendSmartRoutingShowUI` 发送 `0x10` 字典（`0x01 0xE6`）：
     - `SmartRoutingKeyShowNearbyUI`: `true`
     - `localscore`: `301`（`0x31 0x2D 0x01`）
     - **已知缺陷**：`AACPManager.kt:989` 与 `aacp.rs:1117` 将键和值误拼为 `0x46 "reasonHhijackv2"`（缺少长度字节，导致苹果 OPACK 解码器无法按标准 `reason="hijackv2"` 解析）；`audioRoutingScore = 0xA2`（回指 `301`），`audioRoutingSetOwnershipToFalse = true`，`remotescore = 0xA2`（`301`）。
   - `sendHijackReversed`：用于对端点击横幅“移回”后的反向切换，`audioRoutingSetOwnershipToFalse=true`, `audioRoutingShowReverseUI=true`, `reason="ReverseBannerTapped"`。
   - **重要发现**：`librepods`、`podsd`、`MyPods`、`tether`、`airpods-switch`、`OpenPodsMax` 均**未使用 `"ManualRoute"`**，全部发送 `"Hijackv2"`。

2. **`madeitdisfar/podsd`（`src/bluetooth/relay.rs:75-106, 256-275`）**：
   - 显式指出并修复了 `librepods` 的 OPACK 编码 bug，并在单元测试 `every_message_reads_strictly()` 中用严格 OPACK 解码器验证：
     - `hijack_request`：`localscore=100`, `reason="Hijackv2"`, `audioRoutingScore=301`, `audioRoutingSetOwnershipToFalse=true`, `remotescore=301` (`0xA5`)。
     - `show_nearby_ui`：修复了 `"reason"` / `"hijackv2"` 的标签，实测解码为 `SmartRoutingKeyShowNearbyUI=true`, `localscore=301`, `reason="hijackv2"`, `audioRoutingScore=301`, `audioRoutingSetOwnershipToFalse=true`, `remotescore=301`。

3. **`HanreichC/MyPods`（`AapAudioSwitchCapability.cpp:141-173`）**：
   - 同样修复了 `ShowNearbyUI` 中的 `reason` / `hijackv2` 标签，并在每次 `Claim()` 中按顺序发送三条 `0x10`：`MediaInformation` → `ShowNearbyUI` (`localscore=301`) → `HijackRequest` (`localscore=100`, `audioRoutingScore=301`, `remotescore=301`)。

4. **`Weverses/OPAirPods`（`Routing.java:50-52`）**：
   - 完整实现 OPACK 编码器（`Opack.encode`），`takeOver` 发送 `localscore=100`, `reason="Hijackv2"`, `audioRoutingScore=301`, `audioRoutingSetOwnershipToFalse=true`, `remotescore=301`；`showNearby` 发送 `SmartRoutingKeyShowNearbyUI=true`, `localscore=301`, `reason="Hijackv2"`, `audioRoutingScore=301`, `audioRoutingSetOwnershipToFalse=true`, `remotescore=301`；并单独提供 `reverse()`（`ReverseBannerTapped`）。

5. **`zackb/tether`、`hugo2006alm/airpods-switch`、`sidnvy/OpenPodsMax`**：
   - 三者均未发送 `ShowNearbyUI`，仅发送 `Hijackv2`（`localscore=100`, `audioRoutingScore=301`, `audioRoutingSetOwnershipToFalse=true`, `remotescore=301`）。其中 `airpods-switch` (`AacpProtocol.cs:59-79`) 甚至未发送 `mediaInformation`，在 `docs/AACP_SWITCHING.md:56-62` 明确标注该最小序列为“实验性”。

---

## 3. `mediaInformation`、`ownership` (`0x09` id=6) 与应对 iPhone 活跃会话/直播反抢

### 3.1 `mediaInformation` 与 OPACK 编码细节

1. **键名大小写与 LibrePods 的 3 字节编码错误**：
   - `librepods` Issue `#446` 记录了 Kotlin 与 Rust 之间的键名大小写不一致：`send_media_information_new_device` 使用小写开头的 `playingApp` / `hostStreamingState`（与 `otherDeviceAudioCategory=100`），而 `send_media_information` 使用 PascalCase 的 `PlayingApp` / `HostStreamingState`（与 `otherDeviceAudioCategory=301`）。
   - 更致命的是，`madeitdisfar/podsd`（`src/bluetooth/relay.rs:9-13, 287-302`）实测发现 `librepods` 原版 Kotlin/Rust 的 `createMediaInformationPacket` 存在三处字节错误：`"YES"` 长度 tag 错写为 `0x42`（2 字节而非 `0x43`）、`btName` 前缺少字符串长度 tag、`createAddTiPiDevicePacket` 中 `idleTime` 前多出 `0x69`（Rust `aacp.rs:1179`）。`podsd` 注释明确指出：**“iPhone 无法解析这两条消息，因此从未获知本机正在播放”**（*The iPhone couldn't read either message, so it never learnt whether this computer was playing*）。
   - 调研基线 `fdec576` 的 LinkPods `mediaInformation()` 已使用 `opackString()` 编码字符串，但空闲时仍上报 `playingApp="com.google.ios.youtube"` 和 `otherDeviceAudioCategory=301`。PR #27 已改为空闲时发送 `playingApp="NA"`、`hostStreamingState="NO"` 和 `otherDeviceAudioCategory=100`，播放时发送 `"YES"` 和 `301`。空闲类别与 `OPAirPods`（`Routing.java:47-49`）一致，`tether`（`airpods.cpp:622-626`）的空闲类别为 `AUDIO_CATEGORY_NONE`。[LinkPods `mediaInformation()`](../../apps/android/src/local/airpods/handoffprobe/RoutingPackets.java#L85-L106)

2. **`ownership` (`0x09` id=6) 与 `0x14` Connect Priority List**：
   - 除 `airpods-switch`（极简原型）外，`LibrePods`、`podsd`、`MyPods`、`tether`、`OPAirPods` 均在接管时先写 `0x09` id=6 value=`0x01`，让出时回复 `0x09` id=6 value=`0x00`。
   - `madeitdisfar/podsd`（`src/switching.rs:148-208`、`src/bluetooth/aacp.rs` `send_priority_list`）还实现了 `0x14`（Connect Priority List），按 Mac 行为把当前播放宿主排在首位发送给 AirPods，防止主耳切换或 iPhone 重连时将非苹果设备踢出多点列表。PR #27 尚未实现 `0x14`。

### 3.2 为何 iPhone 直播（`tv.danmaku.bilianime` live）在自动 `TAKE` 后 1.5–2 秒反抢，及开源对策

`SM-X810` 与 iPhone 的既有实测记录显示，iPhone 保持直播播放时会在平板自动 `TAKE` 后约 1.5–2 秒抢回耳机。公开的 `audioaccessoryd` 反编译记录为活跃音频会话的评分提供了参考。[已知问题与真机验收矩阵](../issues/README.md)

1. **iOS 直播会话保持活跃音频上下文与 `localscore=301`**。调研基线 `fdec576` 的 `Hijackv2` 使用 `localscore=100`，未发送 `ShowNearbyUI`。PR #27 在自动接管时先发送 `ShowNearbyUI`，再发送 `Hijackv2`，两者的 `localscore` 均为 `301`。[路由请求编码](../../apps/android/src/local/airpods/handoffprobe/RoutingPackets.java#L47-L70)
2. **接管后的失权处理**。调研基线 `fdec576` 收到 `ownership=false` 时立即让出。PR #27 的自动认领开启 5 秒宽限期，将期间的失权或对端切源留到期满处理。若届时仍未恢复且本机仍在播放，重试一次；若播放已停止或重试后再次确认失权，则让出 A2DP。用户显式发起的对端反向切换可立即让出。[接管与宽限期](../../apps/android/src/local/airpods/handoffprobe/HandoffService.java#L994-L1069)
   - **对比 `HanreichC/MyPods`（`AapAudioSwitchCapability.cpp:349-363, 442-455` 与 `docs/airpods-switching.md` 规则 R3）**：MyPods 作者在真实 AirPods Pro 3 + iPhone 实测中**复现了完全相同的 ~1 秒反抢现象**，并专门设计了 **3 秒宽限期（`grace = busy || NowMs() - takeoverAt < 3000`）**：
     > *"Seen ~1 s after a takeover with the iPhone playing; ignored, it left this computer sure it had them while the AirPods had moved on... An `owns connection 0` in the 3 s grace after a takeover is real: kept, and looked at once the grace is over (claim once more while playing here, let go on the next one)."*
     > 即：在 `takeoverAt` 后 3 秒内收到 `owns connection 0` 不立即让出，而是暂存 `ownsReport = 0`；3 秒宽限期结束后若本机仍在播放，则**再执行一次 `Claim()`（单次二次认领）**；若之后仍失权，才真正 `Release(false)`。
   - **对比 `zackb/tether`（`airpods.cpp:121-128`）**：设置 `CLAIM_SETTLE_MS = 5000`，且在发送 `CLAIM` 时检查 `state.peer_taking_over` 门控，防止在固件/对端切换窗口内冲突。
   - **对比 `madeitdisfar/podsd`（`switching.rs:27, 93, 370-382`）**：区分 `LOST_GRACE = 3s`（真正失权后 3 秒内不自动反抢）与 `RECLAIM_WINDOW = 3s`（固件自发切走时自动夺回一次）。
   - **对比 `LibrePods` Android（`AirPodsService.kt:938-950`、`MediaController.kt:64, 156`）**：无宽限期，收到 `owns=false` 立即 `sendPause()` + `disconnectAudio()` 并设 `recentlyLostOwnership = true`（3 秒内禁止自动 `takeOver`），避免乒乓死循环，但代价是 iPhone 直播会直接抢回。

---

## 4. A2DP 断开、被动重连抑制、HFP 与 L2CAP `ControlSession` 稳定性

| 维度 | `librepods-org/librepods` (Android / Linux-Rust) | `madeitdisfar/podsd` (Linux) | `HanreichC/MyPods` (Linux) | `zackb/tether` (Linux) | `Weverses/OPAirPods` (Android) | `hugo2006alm/airpods-switch` (Windows) & `OpenPodsMax` (Linux) |
| --- | --- | --- | --- | --- | --- | --- |
| **让出时 A2DP 处理** | Android: `setConnectionPolicy(A2DP, 0)`（需 `BLUETOOTH_PRIVILEGED`，无权限则跳过，见 `AirPodsService.kt:2956-2984`）<br>Rust: `deactivate_a2dp_profile()` 将 PipeWire card 设为 `"off"` | `deactivate_a2dp_profile()` 将 PipeWire card 设为 `"off"`，保持 ACL 与 AACP 在线 | `pac->SetCardProfile(card, "off")`，保持蓝牙链路与 AAP 在线（`AapAudioSwitchCapability.cpp:535-545`） | **未处理 A2DP profile 断开/切换**，仅发送 AACP `RELEASE` (`0x09` id=6=`0x00`) | 让出时调用 `yieldAudio` 断开/压制本地音频路由 | **未实现**（`airpods-switch` 文档提及概念；`OpenPodsMax` `smart_routing.c` 仅构包发送） |
| **被动 A2DP 重连 (`ACP:1`) 抑制** | **存在缺陷**：Issue `#921` 指出仅断 A2DP/HFP 而保留 AACP 时，AirPods 约 40 秒后主动发起 `Incoming A2DP` 重连；PR 补丁通过延迟 3s 校验非 LibrePods 主动释放则关闭 AACP，或仅在 A2DP/HFP 已连时挂载 AACP | `on_card()` 等待最多 `OWNER_WAIT=10s` 确认所有权归属，若属对端则立即再设 `"off"`，防止 WirePlumber/蓝牙重连抢占声卡 | `cardEventId` 监听声卡出现/切到 `a2dp` 时，若 `!ownsAudio` 或 `InCase()` 则强制拉回 `"off"`（规则 R5/R6） | **未实现** | 依赖系统级/桥接代理控制路由与媒体会话状态 | **未实现** |
| **HFP 处理** | Android: `HEADSET.setConnectionPolicy(0/100)`（需 `MODIFY_PHONE_STATE`，`AirPodsService.kt:2985-3009, 3057-3088`）。Issue `#782` 实测 fw 9A348 `AT+IPHONEACCEV` 缺 `\r` 致 HFP 每 20s 抖动并触发 40s ACL 断连 | **未主动控制 HFP**（仅管理 PipeWire A2DP profile） | **未主动控制 HFP**，但在收到对端 `sourceType == 1`（通话）时禁止自动抢占（`AapAudioSwitchCapability.cpp:602`） | `docs/BLUETOOTH.md:164-299` 详述了 BlueZ `org.bluez.Telephony1` 与 PipeWire `hfp_hf` 冲突（针对 iPhone 侧），**对 AirPods 端切换未控制 HFP** | 根据通话音频状态协调路由，对端通话时不抢占（`remoteCall(d)`） | **未实现** |
| **L2CAP `ControlSession` 稳定性** | Android/Rust 存在连续发包问题：Issue `#782` 记录 fw 9442752 因未实现 `0x4F` 属性查询且启用 Apple VendorID 钩子，40s 后被耳机以 reason 19/8 踢下线；Issue `#698` 记录 socket 未就绪即发包 | 严格等待握手；记录切源后 30s 内耳机可能扣留设置响应，避免误判掉线重连 | **规则 R12**：必须等待 `aapReady`（最长 5s）再发送 `Claim()`；`KeepAlive()` 防止异步线程悬空 | 严格握手节拍（200ms/600ms/1500ms）与 `CLAIM_SETTLE_MS=5000`；`peer_taking_over` 时禁止发 `CLAIM` 以防耳机直接断链 | 使用 `BluetoothBackend` 串行化 `claim` 队列（phase 状态机 + 5s 超时）与 `peerTakingOver` 门控 | `airpods-switch` 强调按 SDU 边界读取防拆包错误，其余稳定性逻辑未完整落地 |

---

## 5. LinkPods 现状对比表与解决方案建议

### 5.1 LinkPods 与开源实现直接对比

下表以 `fdec576` 为调研基线。PR #27 中的实现来自 [RoutingPackets.java](../../apps/android/src/local/airpods/handoffprobe/RoutingPackets.java#L47-L106) 与 [HandoffService.java](../../apps/android/src/local/airpods/handoffprobe/HandoffService.java#L994-L1069)。

| 特性 / 字段 | LinkPods 调研基线 `fdec576` | LinkPods PR #27 当前实现 | 开源实现参考 |
| --- | --- | --- | --- |
| **自动抢占 `reason`** | `Hijackv2`，显式手动切换用 `ManualRoute` | 自动 `Hijackv2`，显式手动切换用 `ManualRoute` | `podsd`、`MyPods`、`OPAirPods` 和 `LibrePods` 的自动请求均使用 `Hijackv2` |
| **`Hijackv2` 分数** | `localscore=100`、`audioRoutingScore=301`、`remotescore=301` | 三个分数均为 `301` | `Hijackv2` 使用 `100/301/301`，随序列发送的 `ShowNearbyUI` 使用 `localscore=301` |
| **`ShowNearbyUI`** | 未发送 | 在 `Hijackv2` 前发送 `SmartRoutingKeyShowNearbyUI`，自动请求的 `reason` 为 `hijackv2` | `LibrePods`、`podsd`、`MyPods` 和 `OPAirPods` 均在 `Hijackv2` 前发送 `ShowNearbyUI` |
| **`mediaInformation`** | 播放与空闲均发送 `playingApp="com.google.ios.youtube"` 和类别 `301` | 空闲发送 `playingApp="NA"` 和 `otherDeviceAudioCategory=100`，播放时发送类别 `301` | `OPAirPods` 同样区分空闲 `100` 和播放 `301`，`tether` 空闲发送 `AUDIO_CATEGORY_NONE` |
| **失权反抢处理** | 收到 `ownership=false` 立即让出 | 自动接管有 5 秒宽限期，期满仍失权且正在播放时重试一次，重试后再次收到失权或对端切源报告则让出 | `MyPods` 的宽限期为 3 秒，期满可认领一次。`tether` 设 `CLAIM_SETTLE_MS=5000` |
| **A2DP 让出与 `ACP:1` 压制** | 断开 A2DP 并通过 `AudioLinkPolicy` 抑制被动重连 | 断开 A2DP 并通过 `AudioLinkPolicy` 抑制被动重连 | `LibrePods` Android 用 `setConnectionPolicy(0)`，Linux 项目可将 PipeWire 设为 `"off"` |
| **`0x14` Connect Priority List** | 未实现 | 未实现 | `podsd` 将当前播放宿主排在优先级列表首位 |

### 5.2 已采用的策略与实测边界

调研提出的组合策略已在 PR #27 实现。空闲媒体上报使用 `NA` 和类别 `100`，自动接管发送 `ShowNearbyUI` 和评分为 `301` 的 `Hijackv2`，并在 5 秒宽限期内推迟处理失权。期满仍失权且本机在播时重试一次。`ManualRoute` 对应用户显式发起的手动切换。

`SM-X810` 与 iPhone 的既有实测中，普通点播可双向切换。iPhone 直播维持活跃的 `301` 音频会话时，在自动重试或显式 `ManualRoute` 后仍可抢回 AirPods。该场景可先暂停或退出 iPhone 直播，再从平板接管。[已知问题与真机验收矩阵](../issues/README.md)
