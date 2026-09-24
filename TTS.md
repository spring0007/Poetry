# 语音（朗读）接入说明

朗读有两条路，App 自己挑，用户不用管：**优先 MultiTTS**（包名 `org.nobody.multitts`，
音色多、音调可调、语音包由它管理）；MultiTTS 不可用时**自动改用随 APK 内置的离线语音**
（sherpa-onnx + 中文 VITS 模型「小雅」，本机合成、不联网、装上就能读）。

判据只有一条：MultiTTS 绑得上且有音色就用它，否则用内置的。两个都不行（例如自己裁掉了
模型文件）才会没有声音，那时状态行会写出原因——见第 3 节。

## 1. 方案演进

| 阶段 | 方案 | 结果 |
|---|---|---|
| v1 | eSpeak-NG 内置离线引擎 | 共振峰合成，机械/洋腔，已删除 |
| v2 | NekoSpeak（系统 TTS 桥接） | APK 装不上，已删除 |
| v3 | tts-server-android（系统 TTS 桥接） | 需用户另装 App 并配音源，门槛高，已删除 |
| v4 | 内置微软 Edge「大声朗读」+ 系统语音兜底 | 音色好，但**必须联网**，且属于内部接口，微软改校验就失效 |
| v5 | Edge 在线 + sherpa-onnx 离线 + 系统兜底 | 三级链路可用，但 APK 涨到 106.8 MB |
| v6 | sherpa-onnx 离线（模型按需下载）+ 系统语音兜底 | 不再有任何在线合成，APK 75.1 MB |
| v7 | 桥接到 MultiTTS，其他引擎全删 | 链路最短，但**强依赖外部 App**：没装就没声音。APK 64.6 MB，包内已无 native 库 |
| **v8（当前）** | **MultiTTS 为主 + 内置小雅自动兜底** | **干净手机装上就能读；装了 MultiTTS 的优先用它。APK 87.7 MB（91,948,105 B）** |

> **为什么又加回 23 MB**（v7 的结论要修正，但不是推翻）：v6 那条路作为**唯一**方案太贵——
> 要自己维护 50 MB 的 `sherpa-onnx.aar`、13 MB 模型分发服务、MD5 校验与原子换目录、
> `network_security_config` 明文放行，换来的却只有「小雅」一个人、音调还不可调。
> 作为**兜底**它才划算：它买到的是「装上就能读」这条底线，而 MultiTTS 在场时用户完全
> 感知不到它。这次也没把 v6 的体积原样搬回来——只带 arm64 一个 ABI（`ndk.abiFilters`），
> 模型用 int8 的，espeak-ng 数据不再需要。代价是承认「音色好不好，由 MultiTTS 决定」。

## 2. 调用链

```
UI（详情页朗读条 / 音色弹层 / 语音设置页）
        │
        ▼
Speaker（门面，com.example.poetry.media）── 记选中音色 + 播放完成看门狗
        │
        ▼
EngineRouter（实现 SpeechEngine）── 选一个引擎对外生效，兜底逻辑全在这里
        │
        ├── MultiTtsEngine（首选）── new TextToSpeech(app, listener, "org.nobody.multitts")
        │                                    │
        │                                    ▼
        │                           MultiTTS（org.nobody.multitts）── 音色 / 语音包 / 合成
        │
        └── SherpaTts（兜底）────── sherpa-onnx + 内置模型，本机合成，AudioTrack 播放
```

三参构造里的引擎包名是关键：**不指定就会绑到设备默认引擎上**，那样等于没换方案。

`SpeechEngine` 这个接口现在是三个类的公共面——两个实现（`MultiTtsEngine` / `SherpaTts`）
加一个路由器（`EngineRouter`）。这份代码里的 TTS 后端已经换过八次（见上表），接口正是
为了让这些更换不必动 UI：v7→v8 这一整次改造，`Speaker` 里只改了**一行**。

## 3. 自动兜底（v8 的核心）

`EngineRouter` 是 UI 眼里的唯一引擎，用谁由它决定：

```
prepare(ctx, cb):
    1. multi.prepare(...)          // 未安装时它压根不构造 TextToSpeech，代价很低
    2. multi 就绪 → active = multi
       否则 → offline.prepare(...)
    3. 谁就绪用谁；都不行 active 留空（isReady() == false）
    4. 在 active 上重下 setListener + 最后一次的 TtsConfig + 选中的音色
```

- **换引擎要重新下发参数**：`active` 一变，语速 / 音量 / 音色都得重下，否则用户会看到
  滑杆「跳回默认值」。路由器缓存着最后一次的 `TtsConfig` 就是为这个。
- **切换时先停旧引擎**：`select(next)` 会对旧引擎 `stop()`，否则上一条的尾巴会和新的
  第一段叠在一起。
- **迟到的回调要丢**：两个引擎的 listener 都经 `Forwarder` 转发，来源不是当前 `active`
  的回调直接丢弃。
- **切换时机**：`VoiceSettingsActivity.onResume()` 会再调一次 `Speaker.prepare()`，所以
  **运行中去系统里启用 MultiTTS、切回 App 就自动切回 MultiTTS**，不用重启。

`statusText()` 的报告顺序：当前引擎自己的说明 → 否则内置引擎的说明 → 否则
`tts_engine_none`（把 MultiTTS 那句更可行动的文案包在里面，同时如实提到内置的也不行）。

### 内置引擎 `SherpaTts`

| 事项 | 做法 |
|---|---|
| 音色 | 只有一个，id `sherpa-xiao-ya`（沿用 v6 的老 id，老配置正好又对上） |
| 模型 | `assets/tts/xiao_ya_int8/` 里 6 个文件（onnx / tokens.txt / lexicon.txt / phone.fst / date.fst / number.fst） |
| 首次启动 | 整目录拷到 `filesDir/tts_model/`，成功后写 `.ready` 标记，只拷一次（约 21 MB） |
| 合成 | 单线程 executor 上 `tts.generate(段, 0, rate)`——**第三参是 speed**（越大越快），语速直接透传 |
| 播放 | `AudioTrack(MODE_STREAM, 16bit, mono)`，float→short 边写边放；音量走 `setVolume()` |
| 停止 | `pause()` + `flush()` 会打断阻塞中的 `write()`；`generation` 轮次计数让旧任务自己退出 |
| 读完 | 写完还要 `drain()` 等缓冲区放干净再回调——直接 `release()` 会把最后约 1 秒切掉 |

**为什么模型要拷出来而不是直接读 assets**：三个规则 FST（`phone/date/number.fst`）是当
**路径参数**传给 native 的，assets 里的路径 JNI 侧未必认得准。拷一次是稳的，代价是首启
21 MB 的一次性 IO，放在后台线程。资源直读留作后续简化，需真机验证后再动。

**为什么 native 库必须打进 APK**：Android 10 起应用不能 `dlopen` 自己数据目录里的 `.so`
（W^X 限制）。所以「模型可按需下载」成立，so 不成立——`libsherpa-onnx*.so`、
`libonnxruntime.so` 只能随包分发，压缩存放（`useLegacyPackaging`）让下载量少约 19 MB。

**模型文件缺失是正常路径，不是崩溃**：`assets/tts/` 被 `.gitignore` 忽略，新克隆必然
没有。探测不到就报「不可用（缺少模型文件）」，如实显示。加载失败（native 层抛的是
`Error`，已按 `Throwable` 捕获）同理，报出原因而不是崩。

## 4. 四种状态（MultiTTS 分支）

绑定结果不是「成功 / 失败」两态。对用户来说下面四件事意味着完全不同的下一步动作，
所以 `MultiTtsEngine.statusText()` 把它们分开报：

| 状态 | 判定 | `isReady()` | 界面文案（`strings.xml`） |
|---|---|---|---|
| 未安装 | `PackageManager` 查不到 `org.nobody.multitts` | false | 还没装 MultiTTS（`tts_engine_missing`） |
| 装了但没启用 | 查得到包，但 init 回调 `ERROR` | false | 请到系统「文字转语音」里启用（`tts_engine_disabled`） |
| 绑上了但没音色 | `voiceMap.isEmpty()` | false | 还没有语音包，先去 MultiTTS 导入（`tts_engine_no_voice`） |
| 就绪 | 绑上且有音色 | true | `MultiTTS 已就绪 · N 个音色`（`tts_engine_ready`） |

前两种现在都被兜底接住了：状态行会改成「正在使用内置离线语音（小雅）」，
上面这四句只在**内置引擎也不可用**时才会露出来（这时的出口是语音设置页那三个按钮）。

「没装」这一步特意**不构造 `TextToSpeech`**：构造出来的实例只会回一个 `ERROR`，
白白多一次绑定失败。

`prepare()` 每次都会重读音色列表，所以用户去 MultiTTS 里装完语音包、切回本 App
（`VoiceSettingsActivity.onResume()` 会再调一次 `prepare()`）就能看到新音色。

有个已知的系统脾气：刚绑定成功时 `getVoices()` 偶尔返回空集。直接报「没有语音包」会
误导用户去装包，而且这个错误状态永远不会自愈，所以绑上但音色为空时**延迟 600 ms 再问
一次**，问完才通知界面。

## 5. 音色

`engine.getVoices()` 拿到的是系统 TTS 暴露的音色集合，MultiTTS 把自己的发音人注册在里面。

- 先按中文 locale 过滤（`zh / zho / cmn / yue / wuu / hak`）——诗词 App 里刷出一列
  英文音色没有意义；
- 若一个中文音色都没有（用户只装了外语包），**退回全部**，至少让用户看得见选得中；
- `Voice` 字段映射：`id` / `name` = `voice.getName()`，`desc` = locale 的 `toLanguageTag()`，
  `tag1` = 「MultiTTS」，`tag2` = 「中文」/「其它」。
- 兜底时列表里只有一项：「小雅（内置）」，`tag1` = 「内置离线」。`VoiceSheet` 在
  只有一种声音时会显示一行说明（`voice_sheet_single` / `voice_sheet_empty`），
  讲清楚为什么就这么几个。

`voice.getName()` 是 MultiTTS 自己的音色名，换语音包后可能变。配置里存的是这个字符串，
老版本存下的 `sherpa-xiao-ya` 在 MultiTTS 里必然匹配不上，所以三处显示音色名的地方
（`MineFragment.currentVoiceName()`、`DetailActivity.updateVoiceLabel()`、
`VoiceSettingsActivity.reloadVoices()`）都在匹配失败时退回 `voice_default_name`
（「默认音色」），**不要把内部 id 当成名字显示给用户**；语音设置页还会顺手把第一个可用
音色回写进配置，自愈一次。

## 6. 参数映射

| 配置项 | 范围 / 默认 | 去向（MultiTTS） |
|---|---|---|
| 语速 | 0.6–1.8（见 `TtsConfig.RATE_DEFAULT`） | `TextToSpeech.setSpeechRate()` |
| 音调 | 0.5–1.5 | `TextToSpeech.setPitch()`——**v7 起这个滑杆真的生效了**，v6 的 VITS 没有音高参数 |
| 音量 | 0.0–1.0 | 每次 `speak()` 放进 Bundle 的 `KEY_PARAM_VOLUME`，不需要重新合成 |
| 发音人 | MultiTTS 音色名 | `setVoice()`，命中 `voiceMap` 才生效，否则保持原音色 |

**兜底到内置引擎时**：语速 → `generate()` 的 speed 参数（要重新合成）；音量 →
`AudioTrack.setVolume()`；**音调无对应参数，滑杆不生效**——VITS 没有独立音高，这是 v6
就有的限制，不是这次引入的，`tts_multi_hint` 里也写明了一句。

两个实现细节：

- 部分引擎在 `setVoice()` 之后会把语速 / 音调重置成默认值，所以 `setVoice()` 成功后
  要再 `applyPending()` 一次；
- 音频流固定用 `KEY_PARAM_STREAM = STREAM_MUSIC`，跟着媒体音量走。

配置 JSON 存在 `UserStore` 的 `tts_config` 键。v6 那个「允许系统语音兜底」的独立开关
（`SharedPreferences("poetry_voice").tts_allow_system`）已随兜底引擎一起删除，v8 也**没有
加回来**：这次兜底是自动的，不给用户开关，UI 上不暴露「正在用哪个引擎」的选择。

`Speaker` 自己持一份 `TtsConfig current`，`setRate()` 是「改这个字段再整体 apply」，
而不是「新建一份只塞了 rate 的配置」——后者会把音调和音量冲回默认值，是 v6 遗留的一个
小 bug（拖语速滑杆时音调会跳回 1.0）。

## 7. 分句与播放完成

`TextToSpeech.getMaxSpeechInputLength()` 是 4000 字，而详情页是把整首诗
（`body.replace("\n", "。")`）一次丢进来的，长诗会被静默截断。

- 按 `。！？；…` 切开（换行也当句末），每段目标 ≤1000 字，单句本身超长就硬切；
- 全是标点或空白的片段不送去合成；
- 第一段 `QUEUE_FLUSH`（冲掉上一轮的残留），其余 `QUEUE_ADD`；
- utterance id 形如 `poetry-<轮次>-<序号>`，**只有第一段的 `onStart` 算「开始朗读」，
  只有最后一段的 `onDone` 算「读完了」**；
- 任一段返回非 `SUCCESS`（或 `onError` 回调）立即 `stop()` 并报错；
- `onStop` 有意不回调上层：停止是用户主动动作，由 `stop()` 负责收尾，否则界面会再
  「播完一次」。

分句这件事两个引擎**共用**：内置引擎直接调 `MultiTtsEngine.split()`，语义与上面逐条对齐
（首段报 `onStart`、末段报 `onDone`、出错立即停），界面侧才不会被两种实现咬到。

**看门狗**：MultiTTS 是第三方引擎，不保证可靠地回 `UtteranceProgressListener`。
真不回的话详情页的 `playing` 会永远停在 true，播放键卡在暂停图标上——这是最刺眼的
坏结果。所以 `Speaker.speak()` 同时按 `正文字数 × 300 ms + 6 s` 设一个兜底 `onDone`，
被真正的 `onDone` / `onError` / `stop()` / 下一次 `speak()` 取消。
时长本来就是估算（详情页进度条用的也是 `nChar × 260 ms`），晚一点复位不影响观感。

内置引擎的完成回调是**我们自己发的**（写完 + drain 干净再回调），所以兜底模式下
这条不该依赖看门狗——实测时若发现它也在等看门狗，说明 drain 或回调那一段有问题。

## 8. 语音包导入：为什么只能做到「转交」

MultiTTS 的语音包放在它自己的外部存储目录
`/Android/data/org.nobody.multitts/files/voice`——**第三方 App 没有写权限**，
它也没有公开的导入 API。所以本 App 能做的极限是「把用户挑中的文件交给 MultiTTS」，
真正落盘安装必须由它完成。

`media/VoicePackImporter.handOff()` 的当前行为，按顺序试：

1. `ACTION_VIEW` + `application/zip` + `setPackage(org.nobody.multitts)`，
   `resolveActivity()` 能解析就发出去 → `HANDED_OFF`；
2. 解析不到 → 打开 MultiTTS 主界面，由文案引导用户自己点 ⋮ → 导入数据 → `OPENED_APP`；
3. MultiTTS 没装 → `NOT_INSTALLED`；装了但打不开 → `NO_APP`。

方法里留了 TODO：等确认 MultiTTS 实际暴露的是哪个 action（`ACTION_SEND` 还是
`ACTION_VIEW`）、或者某个可显式启动的导入 Activity 之后，**只替换这一段**——
`Result` 枚举与界面文案都不用改，这就是当初留这一层而不是干脆不做的原因。

文件选择器的 MIME 过滤放开了（`{"*/*"}`）：zip 的 MIME 各家实现不一致，经聊天软件
转发后常常退化成通配 MIME，卡死过滤反而选不中。

## 9. 权限与包可见性

`AndroidManifest.xml` 里：

- `android.permission.INTERNET` **保留**，但当前朗读、诗库全在本地，它只是
  `data/remote/` 那层后台接口占位的许可，注释已改；`network_security_config.xml`
  （v6 为模型分发服务放行明文 HTTP）已删除——v8 的模型是打进包里的，不需要网络；
- 新增 `<queries>`。Android 11 起的包可见性限制下，不声明这两条就既查不到 MultiTTS
  装了没有（`getPackageInfo` 抛 `NameNotFoundException`），也绑不上它注册的 TTS 服务
  （三参构造直接回调 `ERROR`）：

  ```xml
  <queries>
      <intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>
      <package android:name="org.nobody.multitts" />
  </queries>
  ```

「打开系统文字转语音设置」的 action（`com.android.settings.TTS_SETTINGS`）**没有公开
SDK 常量**（`android.provider.Settings` 里那个是 `@hide` 的），所以 `Speaker` 里写字面量，
并且先 `resolveActivity()` 判一次——各家 ROM 未必都认，认不出就如实返回 false、界面弹
「打不开」提示，而不是静默失败。

## 10. 体积账

| 版本 | APK | 说明 |
|---|---|---|
| v4（纯在线） | 62.6 MB | — |
| v5（模型内置） | 106.8 MB | so 30 MB + 模型 13 MB |
| v6（模型按需下载 + 去掉 Edge） | 75.1 MB | 模型移出 −13 MB，so 改压缩 −19 MB |
| v7（MultiTTS 桥接） | 64.6 MB | AAR、语音模型、全部 native 库与 espeak 数据一并删除，包内已无任何 `.so` |
| **v8（MultiTTS + 内置兜底）** | **87.7 MB** | **加回 sherpa 四个 so 与 21 MB 模型，只带 arm64 一个 ABI，+23 MB** |

v8 新增部分的**压缩后**占用（`unzip -v` 实测）：模型 13.6 MB（onnx 已是 int8，压不动）
+ 四个 so 12.3 MB（未压缩共 30.4 MB，靠 `useLegacyPackaging` 压到这个数）≈ 25.9 MB；
APK 净增 23 MB。

> 附带更正：改造前我给出的估算是「+32 MB / 约 97 MB」，偏高；实测 87.7 MB。
> 另一处要更正的是 `generate()` 的第三个参数——它是 **speed**（越大越快），不是
> `lengthScale`，所以语速是直接透传而不是取倒数。这两条都已按实测改正在正文里。

剩下的大头仍是 `assets/poetry/poetry.db`（115.9 MB 未压缩，占解压后体积的九成）。
若还想瘦身，把它改成首次启动下载即可降到 20 MB 以内——代价是首次进 App 必须联网。

### 实测更正（2026-09-24，诗库移出 APK 之后）

上面那句「20 MB 以内」**没有兑现**：诗库确实搬走了，但腾出来的空间被语音吃掉了。
clean 构建的 debug 包 **47,482,991 B**，组成是

| 条目 | 压缩后 |
|---|---|
| `assets/tts/aishell3/model.onnx` | 27.5 MB |
| `lib/` 四个 so | 12.9 MB |
| dex | 4.6 MB |
| 其余（res / arsc / pinyin.txt …） | 2.5 MB |

语音占 **85%**，而其中模型一项就占 58%。另外模型从 `xiao_ya_int8`（int8，13.6 MB）
换成了 `aishell3`（fp32，30.5 MB 未压缩 / 27.5 MB 压缩）——这才是体积比预测高 14 MB 的原因。
所以 TTS.md 这篇文章里围绕「小雅」写的选型与自测清单（§1、§11）**已经对不上代码**，
要按 aishell3 的 174 个发音人重写。

## 11. 自测清单

需要真机。**第 1–6 条本机无法覆盖**：

1. **干净手机（没装 MultiTTS）**：装完直接能读——状态行显示「正在使用内置离线语音
   （小雅）」，音色列表里是「小雅（内置）」且只有这一项，试听有声。
2. 详情页整首朗读：长诗不截断（验证分句），停止**立即**静音（验证 `pause()+flush()`
   打断了阻塞写入），读完按钮自动复位（**不该等看门狗**，见第 7 节末）。
3. 兜底模式下拖**音调**滑杆无变化（预期，不是 bug）；拖**语速**滑杆应改变朗读快慢。
4. 装了 MultiTTS 的手机：状态行显示「MultiTTS 已就绪 · N 个音色」，音色列表全是
   MultiTTS 的发音人，**不应出现「小雅（内置）」**。
5. 装了 MultiTTS 但没在系统「文字转语音」里启用：应走兜底（状态行是内置语音那条）。
6. 运行中去系统里启用 MultiTTS，切回 App 的语音设置页：状态行**自动**变成 MultiTTS
   就绪、音色列表刷新（验证 `onResume → prepare → 重新选引擎` 这条链）。
7. **未装且模型被裁掉**（把 `assets/tts/` 删掉再打包）：状态行应报「内置离线语音不可用
   （缺少模型文件）」，不崩。
8. 设置页状态显示「未安装」时，音色列表为空也不该崩；
   「系统语音设置」按钮跳不动时要弹提示而不是没反应。
9. 语音设置页 →「导入语音包」：未装 → 提示去装；装了 → 要么被 MultiTTS 接住，
   要么打开 MultiTTS 并给出引导文案。
10. 在 MultiTTS 里导入一个新语音包，切回 App 的语音设置页：音色列表应刷新出新音色。
11. 老配置（音色存着 `sherpa-xiao-ya`）：干净手机上应**正好命中**内置音色，不再显示
    「默认音色」；在 MultiTTS 手机上匹配不上 → 显示「默认音色」，不把内部 id 印出来。
12. 播完后按钮自动复位。若要等约「字数 × 0.3 s」才复位，说明在靠看门狗兜着——
    MultiTTS 分支下属正常观察结果，内置分支下见第 7 节末。

## 12. 已知限制

- **兜底只有一个人**：内置引擎就是「小雅」一个音色，音调也不可调。想换音色只能装
  MultiTTS——这是设计上的分工，不是待办；
- **新克隆的仓库跑 `tools/fetch-tts-deps.sh` 之前编译不过**：`app/libs/*.aar` 与
  `app/src/main/assets/tts/` 都不入库（体积大且可重新下载），脚本要先跑一次；
- **pinyin 版 VITS 未在真机验证过**：模型给的是 `lexicon.txt` + 三个规则 FST，没有
  `dict/` 也没有 espeak-ng 数据，`dictDir`/`dataDir` 都传空串。理论上够用（v6 就是这么
  跑的），但这条要真机确认；万一加载失败，表现是状态行报出原因而不是崩；
- 音色名是 MultiTTS 的字面量，换语音包后可能失效，用户需要重选一次；
- 播放完成依赖 MultiTTS 的 `UtteranceProgressListener`，它不回就只能靠看门狗估算时长；
- 导入语音包只能「转交」，无法静默安装（原因见第 8 节）；
- 语音包本身从哪来，本 App 不管也不分发。
