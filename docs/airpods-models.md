# 多代 AirPods 型号与现有控制能力

本页记录本轮从 LibrePods 固定提交 `89356c08cea3db0b6b54792d9329d09a87cc85fa` 移植的型号注册表、识别方式和现有控制项门控。它是协议层适配说明，不是真机验收结果，也不扩大手机兼容列表。

## 型号来源

- 型号号来自耳机主动发送的 AACP INFORMATION（`0x1D`）通知，不使用 Android 蓝牙显示名称、配对名称或名称中的 `A####` 子串。
- 注册表逐项对应固定提交的 `android/app/src/main/java/me/kavishdevar/librepods/data/AirPods.kt`。没有加入该 Android 注册表中不存在的 AirPods Max 条目，也没有补猜其他型号号。
- 只有 INFORMATION 专用型号字段与注册表精确匹配时才得到已知型号。未知但格式有效的 `A####` 保留为“未识别型号”，不启用任何仅由型号默认推断的新功能。
- 型号、模式和版本元数据在会话快照中是不可变副本；新会话、断开连接或切换目标会清空，避免上一副耳机的身份泄漏到下一会话。

## 固定注册表

| 注册表 ID | 名称 | 型号号 | 上游能力集合 |
| --- | --- | --- | --- |
| airpods1 | AirPods 1 | A1523, A1722 | （空） |
| airpods2 | AirPods 2 | A2032, A2031 | （空） |
| airpods3 | AirPods 3 | A2565, A2564 | HEAD_GESTURES |
| airpods4 | AirPods 4 | A3053, A3050, A3054 | HEAD_GESTURES, SLEEP_DETECTION, ADAPTIVE_VOLUME |
| airpods4anc | AirPods 4 (ANC) | A3056, A3055, A3057 | LISTENING_MODE, CONVERSATION_AWARENESS, HEAD_GESTURES, ADAPTIVE_AUDIO, SLEEP_DETECTION, ADAPTIVE_VOLUME, STEM_CONFIG |
| airpods5 | AirPods 5 | A3531, A3532, A3533 | LISTENING_MODE, CONVERSATION_AWARENESS, HEAD_GESTURES, ADAPTIVE_AUDIO, SLEEP_DETECTION, ADAPTIVE_VOLUME, STEM_CONFIG |
| airpods5wcc | AirPods 5 with Wireless Charging Case | A3439, A3440, A3441 | LISTENING_MODE, CONVERSATION_AWARENESS, HEAD_GESTURES, ADAPTIVE_AUDIO, SLEEP_DETECTION, ADAPTIVE_VOLUME, STEM_CONFIG, SWIPE_FOR_VOLUME |
| pro1 | AirPods Pro 1 | A2084, A2083 | LISTENING_MODE |
| pro2-lightning | AirPods Pro 2 with Magsafe Charging Case (Lightning) | A2931, A2699, A2698 | LISTENING_MODE, CONVERSATION_AWARENESS, STEM_CONFIG, LOUD_SOUND_REDUCTION, SLEEP_DETECTION, HEARING_AID, ADAPTIVE_AUDIO, ADAPTIVE_VOLUME, SWIPE_FOR_VOLUME, HEAD_GESTURES |
| pro2-usbc | AirPods Pro 2 with Magsafe Charging Case (USB-C) | A3047, A3048, A3049 | 同 Pro 2 Lightning |
| pro3 | AirPods Pro 3 | A3063, A3064, A3065 | LISTENING_MODE, CONVERSATION_AWARENESS, HEAD_GESTURES, STEM_CONFIG, LOUD_SOUND_REDUCTION, PPE, SLEEP_DETECTION, HEARING_AID, ADAPTIVE_AUDIO, ADAPTIVE_VOLUME, SWIPE_FOR_VOLUME, HRM |

上游能力集合中的其余/未接入项只作为注册表事实保留。本应用没有新增空间音频、听力辅助、心率、固件更新或按压手势控制。`STEM_CONFIG`、`HEAD_GESTURES`、`HEARING_AID`、`HRM` 等不会因为型号已知就生成新界面或新写命令。

## 现有控制项如何门控

| LinkPods 现有控制 | 设置 ID | 型号门控 |
| --- | --- | --- |
| 噪声控制（关闭/通透/降噪） | `0x0D` | LISTENING_MODE |
| 单耳降噪 | `0x1B` | LISTENING_MODE |
| 自适应噪声模式 | `0x0D` 值 4 | LISTENING_MODE 且 ADAPTIVE_AUDIO |
| 个性化音量 | `0x26` | ADAPTIVE_VOLUME |
| 对话感知 | `0x28` | CONVERSATION_AWARENESS |
| 音量滑动 | `0x25` | SWIPE_FOR_VOLUME |
| 滑动时长 | `0x23` | SWIPE_FOR_VOLUME |
| 自动入耳检测 | `0x0A` | 上游没有对应能力位，保留所有已知型号的既有报告驱动行为 |
| 麦克风 | `0x01` | 同上 |
| 提示音音量 | `0x1F` | 同上 |

门控在两个位置同时生效：

1. 快照/UI：已知型号不支持时显示“此型号不支持”，与“尚未读取”分开；支持的型号仍必须收到当前会话的实际设置回报才可操作。
2. 命令队列：即使先收到不该出现的设置报告，已知型号也会拒绝该写入。例如 AirPods 1/2/3/4（无 ANC）不会因为收到 ANC/自适应/对话感知/滑动报告而获得相应命令；Pro 1 不会因为报告而获得自适应、对话感知或滑动。

未知型号不做型号门控，继续沿用原行为：必须由耳机在当前会话报告过该设置，才可写；报告过的模式仍按原有规则确认。型号已知不等于设置已读取，也不等于写入已确认。

## INFORMATION 帧布局与解析边界

按固定提交 `AACPManager.kt` 的 `parseInformationPacket` 兼容逻辑：

- 帧头为 `04 00 04 00 1D 00`，重复的 `04 00 04 00` 头会被规范化。
- 帧头后先是二进制前缀，直到第一个 `00`。
- 第一个 `00` 后的第一个以 `00` 结尾的元数据字符串属于前缀，上游解析器丢弃它。
- 其后固定顺序为：name、model number、manufacturer、serial number、version 1、version 2；更后的 hardware revision、updater identifier、左右耳序列号、version 3 等字段不读取。
- 解析器必须消费 serial number 才能到达 version 1/version 2，但消费后立即丢弃，不放入解析结果、快照或日志；序列号绝不会被当作品牌版本或型号。型号只取第二个后缀字段（model number），并单独校验 `A` + 四位数字。名称或序列号中出现类似型号的字符串不会被搜索为身份。
- 空包、过短、错误帧头、错误操作码、缺少前缀终止符、缺少 name/model/manufacturer/serial、型号字段不合格式、字段超长等情况返回无法识别；现有有效身份不会被畸形包擦除。合成 fixture 使用 `SERIAL-TEST-ONLY` 作为独立序列号，版本为 `8B123`/`8B124`，并断言版本不等于序列号。
- 主机测试使用按上述上游解析布局构造的合成 fixture，并明确标注为合成布局，不伪装成真机抓包。未识别的布局不会猜测字段，也不会回退到任意 A-number 子串扫描。
- 会话诊断对 INFORMATION 只输出操作码、长度、解析出的型号和两个版本；不会输出原始帧，也不输出 serial 字段。其他操作码的既有诊断不变。

## 未完成与不声明事项

- 没有做物理耳机验证；不同固件是否发送相同布局、是否在所有型号上回报同一设置，仍需逐型号真机验收。不能从本页或主机测试推断某型号已在真机上可用。
- 蓝牙桥接、Root/临时 Root、路由所有权、包名、SDK 下限和 build101 均未改变；型号适配不代表手机兼容性扩大。
- AirPods Max 不在固定 Android 注册表中，因此仍按未知型号处理，不标记为已支持。若以后要适配，需提供固定上游源码证据并单独评审。
- 耳机显示名称可以任意重命名，不作为型号依据；Pro 3 产品插图只在型号字段精确识别为 Pro 3 时显示，其他/未知型号使用通用原生文字展示。

## 来源与许可

移植的注册表和 INFORMATION 兼容逻辑来自 LibrePods 固定提交 `89356c08cea3db0b6b54792d9329d09a87cc85fa`：

- `android/app/src/main/java/me/kavishdevar/librepods/data/AirPods.kt`
- `android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt`

LibrePods 以 GPL-3.0-or-later 发布；LinkPods 保留 GPL-3.0 许可与来源声明。详细第三方说明见 [THIRD_PARTY.md](../THIRD_PARTY.md)。
