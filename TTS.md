# 语音（eSpeak-NG）接入说明

诗韵 App 的朗读能力**仅由内置的 eSpeak-NG 1.52.0 离线引擎提供**（离线、不依赖系统与网络，
也不调用系统 TTS，保证不同设备音色一致）。本文说明库文件位置、数据文件来源、调用链、
配置项与「打磨男女声 / 消洋腔」的调参建议。

---

## 1. 库文件（已引入项目）

```
app/src/main/jniLibs/
├── arm64-v8a/libttsespeak.so
├── armeabi-v7a/libttsespeak.so
├── x86/libttsespeak.so
└── x86_64/libttsespeak.so
```

* 放在 `jniLibs/<abi>/` 下即可被 AGP 自动打进 APK 的 `lib/<abi>/`，无需额外 gradle 配置。
* 由 `com.reecedunn.espeak.SpeechSynthesis` 的静态代码块加载：
  `System.loadLibrary("ttsespeak")`。

### 包名为什么必须是 `com.reecedunn.espeak`

`.so` 中导出的 JNI 符号是硬编码的：

```
Java_com_reecedunn_espeak_SpeechSynthesis_nativeClassInit
Java_com_reecedunn_espeak_SpeechSynthesis_nativeCreate
Java_com_reecedunn_espeak_SpeechSynthesis_nativeGetAvailableVoices
Java_com_reecedunn_espeak_SpeechSynthesis_nativeGetParameter
Java_com_reecedunn_espeak_SpeechSynthesis_nativeGetVersion
Java_com_reecedunn_espeak_SpeechSynthesis_nativeSetParameter
Java_com_reecedunn_espeak_SpeechSynthesis_nativeSetPunctuationCharacters
Java_com_reecedunn_espeak_SpeechSynthesis_nativeSetVoiceByName
Java_com_reecedunn_espeak_SpeechSynthesis_nativeSetVoiceByProperties
Java_com_reecedunn_espeak_SpeechSynthesis_nativeStop
Java_com_reecedunn_espeak_SpeechSynthesis_nativeSynthesize
```

因此 `app/src/main/java/com/reecedunn/espeak/SpeechSynthesis.java` 的包名、类名、
以及 `private void nativeSynthCallback(byte[])`（JNI 用 `([B)V` 反射查找）都不能改，
否则运行时会 `UnsatisfiedLinkError` / `NoSuchMethodError`。

---

## 2. 数据文件（espeak-ng-data）

库只是引擎，发音数据（`phondata` / `phontab` / `phonindex` / `intonations` / `*_dict` /
`voices` / `lang`）是**构建产物**，源码仓库里没有。本项目的数据来自
**Debian 官方包 `espeak-ng-data 1.52.0+dfsg-5`**（与本地源码 `configure.ac` 里
`AC_INIT([eSpeak NG], [1.52.0]...)` 版本一致）。

已验证：arm64 / armhf / amd64 三个架构包的数据**逐字节相同**，
说明数据与 CPU 架构无关，所有 Android ABI 共用一份即可。

### 内置数据集（精简版）

```
app/src/main/assets/espeak-ng-data/
├── version                 ← 1.52.0，用于安装/升级判定
├── phondata / phonindex / phontab / phondata-manifest / intonations   ← 基础
├── en_dict                 ← espeak 基础资源（上游列为 base resource，必须保留；但英语发音人不在界面中提供）
├── cmn_dict (1.5MB)        ← 普通话
├── yue_dict                 ← 粤语
├── hak_dict                 ← 客家话
├── voices/**                ← 音色变体（!v/f1~f5、!v/m1~m8 等，女声靠 f3 共振峰变体实现）
└── lang/**                  ← 各语种音色定义（中文在 lang/sit/{cmn,yue,hak}）
```

体积：原始 2.92 MB → 打包进 APK 后约 **1.34 MB**。

**男声 / 女声共振峰变体**：espeak 用 `voices/!v/` 下的文件改变音色质感——
`m1–m8` 是不同男声共振峰，`f1–f5` 是女声。代码在
`EspeakEngine#addFormantVariants()` 里为每个中文语种自动构造
`cmn+m1…m5` / `cmn+f1…f5`（粤语、客家话同理）共 **每个语种 5 男声 + 5 女声** 的发音人，
`+` 组合名由 espeak 解析为「加载该语种 + 套用该共振峰变体」。用户在「发音人」列表里可直接选择
「普通话 · 男声 · 浑厚 / 女声 · 清脆」等。变体档位标签（低沉/浑厚/清亮/明亮、柔和/温婉/清脆/甜美）
是代码里 `VARIANT_INFO` 映射的中文描述，仅作挑选参考，实际音色以听感为准。

**不提供英语 / 拉丁转写**：`EspeakEngine#filterVoices()` 在枚举时跳过 `en`（中文诗词 App 只需中文），
并排除 `cmn-Latn-pinyin`、`yue-Latn-jyutping` 这类拉丁转写变体（它们按拼音/粤拼读罗马字，不是中文发音）。
`en_dict` 作为 espeak 基础资源仍保留在数据目录中，不影响初始化。

释放逻辑：`EspeakData.ensureInstalled()` 首次启动时把 assets 复制到
`filesDir/espeak-ng-data/`（带 `.tmp/.old` 原子切换 + version 比对），
复制过程跑在后台线程，不阻塞启动。

### 需要更多语种

把完整 `espeak-ng-data` 放到应用专属外部目录即可（免权限，无需改代码/重装）：

```bash
adb push espeak-ng-data /sdcard/Android/data/com.example.poetry/files/espeak-ng-data
```

查找顺序：`外部目录` → `filesDir（assets 释放）`，逻辑见 `EspeakData#resolveDir`。
设置页底部有「重载语音数据」按钮，可强制重新释放。

---

## 3. 调用链

```
UI（详情页朗读条 / 音色弹层 / 语音设置页）
        │
        ▼
Speaker（门面，com.example.poetry.media）
        │
        ▼
EspeakEngine（tts，内置 espeak-ng 离线引擎，唯一实现）
        │
        ▼
com.reecedunn.espeak.SpeechSynthesis（JNI）
                │  nativeSynthesize（阻塞，内部 espeak_Synchronize）
                ▼
SynthCallback → onSynthDataReady(byte[] PCM16) → AudioTrack 播放
              → onSynthDataComplete()          → 释放资源 + onDone 回调
```

关键约束：

1. `nativeSynthesize` 是**同步阻塞**调用，必须在后台线程执行（`EspeakEngine` 内部单线程池）；
   音频数据通过回调逐块吐出，边合成边播放。
2. `espeak_Initialize(path)` 的 `path` 必须是 **`espeak-ng-data` 的父目录**，
   原生侧会自行拼接 `/espeak-ng-data`（源码 `common.c` 中的 `"%s/espeak-ng-data"`）。
3. espeak 的默认音色是**英语**，中文诗词必须显式切换到普通话，
   否则读不出汉字 —— `EspeakEngine#pickDefaultVoice()` 负责在未配置时自动选中 `cmn`。

### 参数量程换算

| 配置项 | 对外单位 | espeak 参数 | 换算 | 默认 |
|---|---|---|---|---|
| 语速 | 倍率 0.6–1.8 | `espeakRATE`（80–449 wpm） | `175 × 倍率` | 0.9（偏慢更清晰） |
| 音调 | 倍率 0.5–1.5 | `espeakPITCH`（0–100） | `50 × 倍率` | 1.0 |
| 音量 | 倍率 0.0–1.0 | `espeakVOLUME`（0–200） | `100 × 倍率` | 0.9 |
| 语调幅度 | 倍率 0.5–1.5 | `espeakPITCH_RANGE`（0–100） | `50 × 倍率` | 1.0 |
| 停顿 | 档位 0–10 | `espeakWORD_GAP`（0 = 无） | 直接传档位 | 0 |

> **消「洋腔 / 平调」调参建议**：eSpeak-NG 是共振峰合成，中文天生带机械/洋腔。
> 手感优先调这几项：① 在「发音人」里换 `m*`/`f*` 变体挑出最自然的男/女声；
> ② 语速降到 0.8–0.9 减少连读生硬感；③ 语调幅度调到 1.1–1.3 让四声更清楚（过高会变唱腔）；
> ④ 女声想更亮可把音调提到 1.1–1.2，男声想更低沉可降到 0.8–0.9；
> ⑤ 诗词吟诵可在「停顿」加 1–3 档（中文按字断词，档位过高会一字一顿）。

---

## 4. 持久化

单份 JSON 快照存在 `UserStore` 的 `tts_config` 键：

```json
{"engine":"espeak","voice":"cmn","rate":0.9,"pitch":1.0,"volume":0.9,"pitchRange":1.0,"wordGap":0}
```

* 改动即刻写入（`VoicSettingsActivity`、详情页、"我的"页语速滑杆都会落盘）；
* 启动时 `PoetryApp.onCreate()` 读取并下发给引擎；
* 各 setter 做区间收敛，`fromJson()` 解析失败或字段缺失时返回
  `TtsConfig.defaults()`（引擎 espeak / 语速 0.9 / 音调 1.0 / 音量 0.9 / 语调幅度 1.0 / 停顿 0）；
* 新增的 `pitchRange` / `wordGap` 字段向后兼容：旧配置缺这两个 key 时按默认值补齐，不报错。
* 兼容旧版本：早期 `voice_id` / `speech_rate` 两个独立 key 会在首次读取时
  迁移进新结构并清除旧 key（`UserStore#migrateLegacyVoiceSettings`）。

---

## 5. 降级与兜底

本项目只使用内置 espeak-ng 离线引擎，**不提供系统 TTS 兜底**（避免不同设备音色差异）。
引擎不可用时朗读功能直接提示，但不影响浏览、搜索、收藏、书架等其它功能。

| 故障 | 处理 | 表现 |
|---|---|---|
| `.so` 缺失 / ABI 不匹配 | 静态块 catch `UnsatisfiedLinkError`，`isLibraryLoaded()=false` | 朗读提示「未加载 espeak-ng 原生库」 |
| 数据缺失 | 设置页显示「语音数据未就绪」，提供重载按钮 | 朗读提示「语音数据未就绪」 |
| 初始化失败 | 记录 `failureReason`，`speak()` 直接回调 `onError` | 朗读提示「espeak-ng 初始化失败」 |
| 配置损坏 | `TtsConfig.fromJson()` 回落默认值 | 恢复默认语音设置 |
| 选了不存在的音色 | 匹配不到就保留当前音色 | 列表按实际数据过滤，不出现失效项 |
| 缺少词典的语种 / mbrola | `filterVoices()` 剔除 | 列表里不会出现 |
| 英语发音人 | `filterVoices()` 跳过 `en` | 界面只提供中文（普通话/粤语/客家话）的男声与女声 |
| 拉丁转写变体（pinyin/jyutping） | `filterVoices()` 跳过含 `Latn` 的项 | 不会误当成中文发音人 |

详情页在 `isReady()==false` 时提示「当前设备不支持语音合成」，
其余功能（浏览、搜索、收藏、书架）完全不受影响。

---

## 6. 界面入口

| 位置 | 入口 | 能力 |
|---|---|---|
| 作品详情页 | 底部朗读条 ▶ / ⏸ | 播报正文；点「音色 · X」打开音色弹层 |
| 作品详情页 | 音色弹层 | 选发音人、试听、底部「语音设置」 |
| 我的页 | 朗读偏好 → 语音设置 | 发音人（每语种 5 男声 + 5 女声）、语速、音调、音量、**语调幅度、停顿**、试听、恢复默认、重载数据 |
| 我的页 | 语速滑杆 | 快速调语速（写入同一份配置） |

---

## 7. 自测清单

1. 安装后首次进入「我的 → 语音设置」，引擎状态应从「正在准备语音引擎…」变为「就绪 · 1.52.0」；
2. 发音人列表首项应为「普通话 · 男声」之类的中文音色；每种中文应能看到 5 个男声、5 个女声变体；
3. 点「试听」应听到「关关雎鸠，在河之洲」；
4. 切到不同 `m*`/`f*` 变体、调语调幅度 / 音调 / 音量后重新试听，应有可感知的音色与语气变化；
5. 杀进程重进，配置保持（日志 `Speaker` / `EspeakEngine` tag 可看到 voice 与参数）；
6. 手动删掉 `filesDir/espeak-ng-data` 后进入设置页，应自动重新释放。
