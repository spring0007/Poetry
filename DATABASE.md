# 诗库数据库接入说明

本文说明「诗韵」App 如何接入 `E:\chinese-poetry-master\poetry-pipeline\dist` 下的 SQLite 诗库，
以及后台接口的预留位置。

---

## 1. 源数据

```
E:\chinese-poetry-master\poetry-pipeline\dist\
├── poetry.db          110 MB   主库（poems / authors / rhythmics / sources / meta）
├── poetry-strains.db   13 MB   平仄数据（poem_strains：id, data BLOB, len）
└── poetry-checks.db     0 MB   校验表（checks，当前为空）
```

`poetry.db` 实测规模（schema_version 3.0）：

| 表 | 行数 | 说明 |
|---|---|---|
| `poems` | 345,358 | 作品正文 |
| `authors` | 13,755 | 作者 |
| `rhythmics` | 1,462 | 词牌 / 曲牌 |
| `sources` | 17 | 数据来源（体裁与朝代的依据） |

`poetry.db` 主要字段：

```sql
poems(id, author_id, title, rhythmic_id, src_id, body, tags, notes, score, n_char)
authors(id, name, dynasty, desc, n_poems)
sources(id, name)          -- tang-poem / song-poem / song-ci / yuan-qu …
meta(k, v)                 -- schema_version / text_form …
```

### 三个必须遵守的约定

1. **正文是「简体 + 无空格」归一化文本**（`meta.text_form = simplified-nospace`）。
   检索前必须走 `QueryNormalizer` 归一化，否则查不到。
2. **未开启 FTS**（`fts=0`），检索只能 `LIKE`。因此查询**不加 ORDER BY**，
   让 SQLite 扫到 `LIMIT` 条就停，避免 34 万行全表排序。
3. `author_id = 0` 表示佚名；`tags` / `notes` 用 `\u001F`（Unit Separator）分隔多值。

`id` 的高位编码了来源，例如 `0x20000000` → tang-poem、`0x29000000` → song-ci、
`0x32000000` → yuan-qu；朝代与体裁由 `sources.name` 反解，映射见
`data/model/PoemKind.java#fromSource()`。

---

## 2. 投放方式

**不把 110MB 打进 APK。** 三条路，正式那条是「从自己的服务器下载」（见 §4）：

| 场景 | 方式 |
|---|---|
| 正式 | App 内下载，落在 `DatabaseProvider#installTarget()` 解析出的路径 |
| 开发 | `adb push` 到应用专属外部目录（Android 10+ 免权限） |
| 过渡期退路 | ~~`assets/poetry/poetry.db`~~（**已删**，见下方「当前状态」） |

`DatabaseProvider#locate()` 的查找顺序：

1. 应用专属外部目录 `/sdcard/Android/data/<pkg>/files/Poetry/`（adb 投放走这里）
2. 应用私有目录 `/data/data/<pkg>/files/Poetry/`（**下载新装走这里**）
3. `assets/poetry/`（首次启动自动复制到私有目录）

三处都没有时，`PoetryRepository` 自动回退到 `SeedDataSource` 的内置 15 首示例诗词，
所有页面照常可跑通，不会白屏。

### 当前状态

- `app/src/main/assets/poetry/poetry.db` **已删**：工作区和索引里都没有了，`.gitignore` 里
  的 `/app/src/main/assets/poetry/*.db` 挡住以后新放进来的文件。`git ls-files` 返回空即验证。
- **但它还在历史里**：`git rm --cached` 只从索引摘掉，blob 仍挂在 HEAD 上（`git ls-tree -r HEAD`
  能查到），所以 `.git` 现在是 57 MB，**新克隆照样要拖这 57 MB**。仓库只有三个提交，
  真要瘦下来只能重写历史（`git filter-repo --path app/src/main/assets/poetry/ --invert-paths`
  或直接重开仓库），等确认没人克隆过再动手。
- 下载链路（§4）已实现，`:app:assembleDebug` 通过；`ApiConfig.BASE_URL` 还是
  `https://api.example.com/poetry/` 占位值，**填上真实地址就能用**。
- 实测 debug 包（2026-09-24，clean 之后）：**47,482,991 B**。其中
  `assets/tts/aishell3/model.onnx` 27.5 MB + `lib/` 12.9 MB + dex 4.6 MB——
  **语音合计约 85%**，诗库这条线腾出来的 58 MB 已经整块转手给了 TTS（见 TTS.md）。
- **别拿增量构建的 APK 量体积。** AGP 的 zipflinger 在条目被删掉后不回收空间：同一份源码
  增量构建出来是 60,982,485 B，逐条目扫下来其中 **13.5 MB 是纯零字节空洞**，条目数与
  clean 包完全一样。`./gradlew :app:clean :app:assembleDebug` 之后才是真实体积。

---

## 3. 本地封装

| 文件 | 职责 |
|---|---|
| `data/local/DatabaseProvider.java` | 定位 .db、从 assets 复制、算出安装目标、清理陈旧 `.part` |
| `data/local/PoetryDatabase.java` | 只读封装：精选、检索、体裁/朝代/作者/主题筛选、统计、平仄读取 |
| `data/local/DbValidator.java` | 安装前的廉价探针：表结构、schema 主版本、行数、sources 非空 |
| `data/local/DbInstaller.java` | 应用级单例：检查→下载→校验→安装的状态机，自带线程，扇出事件 |
| `data/local/SeedDataSource.java` | 无库时的兜底内置诗词 |
| `util/QueryNormalizer.java` | 检索词归一化 + LIKE 转义 |
| `data/PoetryRepository.java` | 统一入口：离线优先、IO 线程调度、结果回调到主线程 |
| `data/DbStatus.java` / `data/DbStatusListener.java` | 不可变 UI 快照 / 单一回调接口（含 `ReadyWatcher`） |

`PoetryDatabase` 用 `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY)` 打开，
全程只读，不会对源库写 WAL / journal。

### 换库时为什么要 `markStale()` + `reload()`

Linux 的 `rename(2)` 只是把新 inode 挂到那个名字上；**正在运行的 `SQLiteDatabase` 握着旧
inode 的 fd，会一直正常返回旧数据，永不报错**。所以装完库必须显式
`database.markStale()` → `Os.rename(part, target)` → `database.reload()`，而这三步要在
`AppExecutors.io` 上的**同一个任务**里做完：

- 在下载线程上 `close()` 而 IO 线程正 `rawQuery` 时，`SQLiteClosable` 的引用计数会推迟真正的
  关闭，但 `isOpen()` 立刻变 false，后续 query 抛 `IllegalStateException`（现有查询都包在
  `try/catch` 里，不会崩，但界面会闪空）。
- `installGeneration` 只用来**自愈**（别的线程抢在前面看到新文件时，下一次 `ensureOpen()`
  能自己修好）。真正干活的是那次显式 `reload()`，不是 generation——别当冗余删掉。

安装路径用 `Os.rename()` 而不是 `delete()` + `renameTo()`：前者原子，后者中间有一瞬间
`locate()` 返回 null，`ensureOpen()` 会把 `ready` 置 false，所有界面静默退回 15 首示例。
不用 `File.renameTo()` 是因为它失败只返回 `false`，不抛异常（`copyFromAssets()` 里现成的那个
保持不动，别去动能跑的代码）。

---

## 4. 从自己的服务器下载诗库

### 怎么激活

**只改一行**：`data/remote/ApiConfig.java` 的 `BASE_URL`。

```java
public static final String BASE_URL = "https://<你的域名>/poetry/";
// DbDownloadPolicy.MANIFEST_URL = BASE_URL + "version.json"，全工程只此一处要填
```

然后把 §4.2 的两个文件传到该目录下即可，不需要发版。

### 4.1 服务端放什么

```
https://<host>/poetry/
├── version.json      约 300 B，App 每次检查只拉这个
└── poetry.db         诗库本体（测试期是子集，2.2 MB）
```

`version.json`：

```json
{ "schema_version": "3.0", "built_at": "2026-09-24T12:19:18",
  "bytes": 2310144, "sha256": "<64位小写十六进制，指未压缩的服务端文件>",
  "is_subset": true, "n_poems": 2062,
  "url": "https://<host>/poetry/poetry.db",
  "note": "测试期子集库" }
```

`sha256` 必须是**未压缩文件**的。App 请求时带 `Accept-Encoding: identity`——否则中间有
gzip 代理时 `Content-Length`、`Range` 偏移和这个 sha 全都指向压缩后的字节，整套校验静默错位。

`url` 放在 payload 里，以后换 CDN 不用发版。静态服务器需要支持 `Range`（续传）并返回
`Accept-Ranges: bytes`；不支持也不会坏，只是每次从头下。**但服务器「忽略 Range 却返回 200」
必须处理**——往半成品后面追加完整响应体会得到一个比预期更长的损坏文件，`HttpDownloader`
检测到 `rangeRequested && code == 200` 时会删掉 `.part` 重新开始。

### 4.2 测试期的子集库怎么造

```bash
python -X utf8 tools/make-subset-db.py \
    --src E:/chinese-poetry-master/poetry-pipeline/dist/poetry.db \
    --out dist-server/poetry.db \
    --per-source 200 \
    --url https://<你的域名>/poetry/poetry.db
```

产出 `dist-server/poetry.db`（2,310,144 B / 2,062 首 / 309 作者）和 `dist-server/version.json`，
并把 sha256 填好。`dist-server/` 已 gitignore（重建一次 sha 就变，不该进版本库）。

脚本做的是**行拷贝**（`shutil.copy2` 后按 `src_id` 取 `score` 前 N），不是重新跑
`poetry-pipeline`，所以 schema、索引、`sqlite_stat1` 与线上库逐字节同构，秒级完成。
**绝不能对 `dist/poetry.db` 原地执行。**

`--min-hits 20` 会按 `HOT_KEYWORDS` / `Theme.defaults()` 兜底补行，保证首页热门搜索和主题
chip 点下去不为空（本次补了 130 条）。目标规模：`--per-source 50 → 625 首 / 1.5 MB`，
`200 → 2,062 首 / 2.2 MB`，`1000 → 5,612 首 / 3.5 MB`。

### 4.3 状态机

`IDLE / CHECKING / NEEDS_DOWNLOAD / WAITING_NETWORK / DOWNLOADING / VERIFYING / INSTALLING /
INSTALLED / FAILED`。`UP_TO_DATE` 是 `IDLE` 的子状态（只在用户强制检查时提示一次）。

界面只有两处：**我的 → 诗库状态**那一行（`MineFragment`）和**关于 → 检查更新**按钮
（`AboutActivity`）。四个列表页（发现 / 分类 / 列表 / 书架）不显示进度，只在
`ReadyWatcher` 看到 `localReady` 由 false 翻成 true 时重新查询一遍。

两条必须守住的规矩：

1. **注册一次（`onViewCreated`/`onCreate`）、渲染每次事件**，两者分开写。把
   `addDbStatusListener` 塞进 `setupX()` 里，`onResume` 会再注册一次而 `onDestroyView` 只摘
   一个，**每转一次屏泄漏一个监听器**。
2. **只在跃迁时重载，不在每个进度回调上重载**（用 `ReadyWatcher`，不是裸的
   `DbStatusListener`），否则下载过程中 RecyclerView 会被重建上百次。

### 4.4 触发策略

```java
// data/remote/DbDownloadPolicy.java
public static final Trigger TRIGGER = Trigger.MANUAL_ONLY;   // 测试期
public static final long CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
```

测试期是 `MANUAL_ONLY`：正在验证链路，手动触发能让每种失败模式随时复现。
链路通过后改成 `AUTO_WIFI_PROMPT_CELLULAR`（需要 `ACCESS_NETWORK_STATE`，manifest 里已有；
缺了不崩——`DbDownloadPolicy` 里 try/catch 之后按「可以下」处理）。

**下载不随界面销毁而取消。** 它是应用级、一次性的操作，归属 `DbInstaller`（只持有
`getApplicationContext()`），用户切标签页就把它掐掉是敌意行为。取消只有两个触发点：用户点
「取消」、或显式调 `cancelDbDownload()`；取消后 `.part` 留在磁盘上以便续传。

### 4.5 版本判定

```
local = database.readMeta()                       // 从活着的句柄读，不要另开一个 SQLiteDatabase
local == null                                   → NEEDS_DOWNLOAD (MISSING)
主版本(local.schema) != 主版本(SCHEMA_VERSION)   → NEEDS_DOWNLOAD (SCHEMA)
prefs.installed_sha 与 local 对不上              → NEEDS_DOWNLOAD (FOREIGN)   // 开发时 adb push 过
manifest.sha256 == prefs.installed_sha          → UP_TO_DATE
manifest.isSubset == local.isSubset && builtAt <= local.builtAt → UP_TO_DATE
否则                                             → NEEDS_DOWNLOAD
```

1. **`is_subset` 参与身份判定。** 服务端换成完整库时，哪怕子集库的 `built_at` 更新也绝不能算
   「已是最新」。**测试转生产就是把服务端的 `is_subset` 改成 `false`——不发版、不重装、APK 不动。**
2. **FOREIGN 规则**把 SharedPreferences 当作可失效的缓存。没有它，adb push 一个完整库盖掉
   下载来的子集后，prefs 会一直骗 App 说「已最新」。
3. **schema 主版本闸门**在安装前拦住 `4.0` 的库，报「诗库版本不兼容，请升级 App」，
   而不是三个界面之后才抛 `SQLiteException`。

`is_subset` **不作为期望值**——同一个 APK 要能同时吃下 2 MB 和 115 MB 两种形态。

### 4.6 真机自测清单

1. 卸载重装 → 冷启动：走示例数据，我的页显示「开始下载」→ 点它 → 看到进度 → 完成后变
   「已载入本地诗库」，**列表立刻从 15 首变成 2,062 首**。数字没变就是 `ensureOpen()` 的早退
   没被 generation 戳破。
2. 下到一半 `adb shell am force-stop`，重启：应显示旧/示例状态，`.part` 要么续传要么被 gc；
   再点下载应能成功，**绝不能出现被截断的库被装上**。
3. 服务端把 `Content-Length` 故意改短：应报校验失败，而不是静默安装。
4. 换个不支持 Range 的服务器（或去掉 `Accept-Ranges`）并预置一个 `.part`：应干净重下，
   最终长度等于 `bytes`。
5. `version.json` 里 `bytes` 改成一个超大值：应报空间不足落进 `FAILED`，不是崩溃。
6. 把库里 `meta.schema_version` 手改成 `4.0` 再发布：应拒绝安装并提示版本不兼容。
7. 连续转屏 3 次再完成一次安装：每个界面只重载一次（数日志行），没有监听器泄漏。
8. `adb push` 一个完整库到外部目录：应触发 FOREIGN 规则要求重下，而不是显示「已最新」。
9. 用明文 http 测试时，在 API 30+ 设备上跑通（API 24 的设备通过不代表没问题，
   明文是 API 28 起才默认禁止的；本地回环地址已在 `network_security_config.xml` 开口）。
10. 断网启动：应停在示例数据，不弹错、不卡启动。

---

## 5. 后台接口（预留，尚未接入）

> 这一节说的是 **JSON 业务接口**（检索、译文、赏析、云端音色），跟 §4 的「下载诗库文件」
> 是两码事。§4 已经能用，这里还全是占位。别因为 `ENABLED = false` 就以为下载也没通——
> 下载走的是 `DbDownloadPolicy` / `HttpDownloader`，不看这个开关。

后端还没建立，因此整套远程层是**占位实现**，开关默认关闭：

```java
// data/remote/ApiConfig.java
public static final boolean ENABLED = false;
```

| 文件 | 职责 |
|---|---|
| `data/remote/PoetryApi.java` | 接口契约：检索、详情、译文、赏析、每日推荐、热词、音色、云端 TTS、书架同步、埋点 |
| `data/remote/RemotePoetrySource.java` | 空实现，所有方法回调 `ApiException.NOT_IMPLEMENTED` |
| `data/remote/ApiConfig.java` | `BASE_URL` / `ENABLED` / 超时等集中配置 |
| `data/remote/ApiCallback.java` / `ApiException.java` | 回调与错误码 |

**接入步骤**（后端就绪后）：

1. 在 `ApiConfig` 填 `BASE_URL`，把 `ENABLED` 改为 `true`；
2. 用 Retrofit/OkHttp 实现 `PoetryApi`（或让 `RemotePoetrySource` 直接发请求）；
3. UI 与 `PoetryRepository` 无需改动——目前依赖后台的译文、赏析、云端音色
   在界面上显示「后台接口接入后显示完整内容」。

---

## 6. 性能建议

* 34 万行 `LIKE '%x%'` 是主要开销，建议后端就绪后把检索迁到服务端；
  离线场景可考虑给 `poems` 建 FTS5 虚拟表（需另行导出，源库未开）。
* `score`（0–255，均值 173.5）可直接用作「精选」排序依据。
* 平仄在独立库 `poetry-strains.db`，按需懒加载，不要在主列表查询里 JOIN。
