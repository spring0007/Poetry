# 诗库数据库接入说明

本文说明「诗韵」App 如何接入诗库 SQLite 文件（母库由
`E:\chinese-poetry-master\poetry-pipeline` 产出，本机现在拿到的是它的抽样本），
以及后台接口的预留位置。

---

## 1. 源数据

```
E:\chinese-poetry-master\poetry-pipeline\dist\
├── poetry.db          110 MB   主库（poems / authors / rhythmics / sources / poem_extras / meta）
├── poetry-strains.db   13 MB   平仄数据（poem_strains：id, data BLOB, len）
└── poetry-checks.db     0 MB   校验表（checks，当前为空）
```

`poetry.db` 实测规模（schema_version 3.3，母库行数取自抽样库的 `meta.sample_parent_rows`）：

| 表 | 行数 | 说明 |
|---|---|---|
| `poems` | 357,186 | 作品正文 |
| `authors` | 13,755 | 作者 |
| `rhythmics` | 1,462 | 词牌 / 曲牌 |
| `sources` | 17 | 数据来源（体裁与朝代的依据） |
| `poem_extras` | 97,230 | 译文 / 注释 / 简介 / 创作背景 / 赏析 / 配图（**3.3 新增**） |

> 只有 `poems` 和 `poem_extras` 两行是刚量的（取自抽样库的 `meta.sample_parent_rows`
> / `extras_rows`）。其余三行还是 schema 3.0 时代量的母库数字，3.3 没复量过——
> 量级可信，精确值别引用。

`poetry.db` 主要字段：

```sql
poems(id, uid, author_id, author_uid, title, rhythmic_id, src_id, body, tags, score, n_char)
authors(id, uid, name, dynasty, desc, n_poems)
rhythmics(id, name)
sources(id, name)          -- tang-poem / song-poem / song-ci / yuan-qu …
poem_extras(uid PK, translation, notes, introduction, creative_background,
            appreciation, images, updated)
meta(k, v)                 -- schema_version / text_form / sample …
```

### 四个必须遵守的约定

1. **正文是「简体 + 无空格」归一化文本**（`meta.text_form = simplified-nospace`）。
   检索前必须走 `QueryNormalizer` 归一化，否则查不到。
2. **未开启 FTS**（`fts=0`），检索只能 `LIKE`。因此查询**不加 ORDER BY**，
   让 SQLite 扫到 `LIMIT` 条就停，避免 35 万行全表排序。
3. `author_id = 0` 表示佚名（`authors` 里**没有** id 0 那一行，所以按朝代统计时要用
   `LEFT JOIN` 并把这批归到 `unknown`，否则会凭空少掉一大批）；`tags` 用 `\u001F`
   （Unit Separator）分隔多值。
4. **外部数据一律按 `uid` 关联，不要按 `id`**（见下）。

### 富文本：`poem_extras`，按 uid 关联

**`poems` 表没有 `notes` 列**（3.0 有，3.3 删了）。译文 / 注释 / 简介 / 创作背景 /
赏析 / 配图都在 `poem_extras`，主键是 `poems.uid`，不是 `poems.id`：

```sql
SELECT p.id, p.title, e.translation, e.notes, e.creative_background
FROM poems p LEFT JOIN poem_extras e ON e.uid = p.uid;
```

- 用 `LEFT JOIN`：extras 是**逐首可选**的，缺行的作品一样要能列出来（空值由
  `PoetryDatabase.readPoem()` 统一折成空串）。
- `poem_extras.notes` 的**多值分隔符是 `\n`**（一行一条），不是 `tags` 那个 `\u001F`。
  `Poem.getNoteList()` 两种都认，因为内置示例数据仍用 `SEP`。
- `images` 列存的是文件名（如 `ff6d1b0ff8f1f4b5.jpg`），**仓库里没有对应图片资源**，
  App 目前不读它，也没有展示位。

### 为什么是 uid 而不是 id

`id` 的高位编码了来源（如 `0x20000000` → tang-poem），但它**含序号**：母库每做一次
dedup / 重排，整批 id 就会整体位移。实测当前库里 `id` 从 67,108,864 起步、最大到
1.2e9，中间大量空洞——**任何「id 连续 / id 有序 / id 从 1 开始」的假设都是错的**。

- `poems.uid` 是内容派生的稳定 uuid，重建库不变 → 译文、图片这类外部数据挂它。
- `authors.uid` 同理，`poems.author_uid` 是它的伴生列，挂作者维度的外部引用用它。
- 收藏 / 历史仍按 `String.valueOf(poem.getId())` 存键，因为 `UserStore` 存的是
  **Poem 的 JSON 快照**，换库后 id 全变也照样渲染得出来；但那些 id 回查诗库会查不到，
  所以别拿老 id 去 `poemById()` 反查。

朝代与体裁由 `sources.name` / `authors.dynasty` 反解，映射见
`data/model/PoemKind.java#fromSource()`；不要从 id 高位去推。

---

## 2. 投放方式

**不把 110MB 打进 APK。** 三条路，正式那条是「从自己的服务器下载」（见 §4）：

| 场景 | 方式 |
|---|---|
| 正式 | App 内下载，落在 `DatabaseProvider#installTarget()` 解析出的路径 |
| 开发 | `adb push` 到应用专属外部目录（Android 10+ 免权限） |
| 过渡期退路 | ~~`assets/poetry/poetry.db`~~（**已删**，见下方「当前状态」；现在又放回来一份，只在本机、不进版本库） |

`DatabaseProvider#locate()` 的查找顺序：

1. 应用专属外部目录 `/sdcard/Android/data/<pkg>/files/Poetry/`（adb 投放走这里）
2. 应用私有目录 `/data/data/<pkg>/files/Poetry/`（**下载新装走这里**）
3. `assets/poetry/`（首次启动自动复制到私有目录）

三处都没有时，`PoetryRepository` 自动回退到 `SeedDataSource` 的内置 15 首示例诗词，
所有页面照常可跑通，不会白屏。

### 当前状态

- `app/src/main/assets/poetry/poetry.db`：**索引里没有，但工作区里有一份**
  （2026-10-08 放入，4,014,080 B，schema 3.3 的 978 首抽样库）。
  `.gitignore` 的 `/app/src/main/assets/poetry/*.db` 挡住它，`git ls-files` 仍返回空，
  所以它只在**本机**生效——装包能直接吃到，但不会进版本库、别人克隆不到。
- **换库后「看不到新数据」先查这两个坑**（踩过）：
  1. `DatabaseProvider.copyFromAssets()` 靠 `copied_poetry.db` 这个偏好位判断「已经拷过」，
     所以**装过旧包的设备永远不会重拷 assets**，看到的一直是老库。
     要 `adb uninstall <pkg>`（或 `pm clear`）之后重装，或者手动删掉私有目录里那份。
  2. `locate()` 的顺序是 **外部目录 → 私有目录 → assets**，之前 `adb push` 过的那份
     `/sdcard/Android/data/<pkg>/files/Poetry/poetry.db` 优先级更高，会把新 assets 顶掉。
- **但它还在历史里**：`git rm --cached` 只从索引摘掉，blob 仍挂在 HEAD 上（`git ls-tree -r HEAD`
  能查到），所以 `.git` 现在是 57 MB，**新克隆照样要拖这 57 MB**。仓库只有三个提交，
  真要瘦下来只能重写历史（`git filter-repo --path app/src/main/assets/poetry/ --invert-paths`
  或直接重开仓库），等确认没人克隆过再动手。
- 下载链路（§4）已实现，`:app:assembleDebug` 通过；`ApiConfig.BASE_URL` 不再是
  `https://api.example.com/poetry/` 占位值，已指向云端后端（`local.properties` 注入，见 §5）。
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

### 3.3 那次改动为什么是「静默空列表」

`POEM_COLUMNS` 里写的是 `p.notes`，3.3 把这列删了 → 每条 SQL 都 `no such column`。
而每个查询各自的 `try/catch` 会把异常记进日志就吞掉，`return new ArrayList<>()`，
**界面表现是「所有列表都空」，不崩、不报错**。以后再遇到「装完库什么都是空的」，
先怀疑这里，别急着怀疑数据没下下来。

富文本现在一律从 `POEM_COLUMNS` + `POEM_FROM` 的
`LEFT JOIN poem_extras e ON e.uid = p.uid` 里取（列表查询也 JOIN：富文本行很小，
换来的是「列表塞进 Intent 的 `Poem`」与「详情页滑页时 `poemById` 重新查的 `Poem`」
形状一致，详情页因此不需要额外查询）。

另外两条与 3.3 绑定的口径：

- **`authors.n_poems` 不可信**——它记的是**母库**篇数（陆游标着 9416，本库只有 7 首；
  303 个作者对不上）。凡「有多少首」都必须现算 `COUNT(*)`：
  `dynastyCategories()` / `topAuthors()` / `countByAuthor()` 都是这么写的。
- **`poem_extras.images` 不用**，`poems.author_uid` 也只是存着备用（作者维度的外部引用用它）。

`DbValidator.REQUIRED_TABLES` 因此把 `poem_extras` 也算进「本 App 的库」的判据：
3.0 的老库主版本号同样是 3，`schemaCompatible()` 拦不住它，只有在**安装前**查表清单
才挡得下来（否则装上去就是上一段那种静默空列表）。

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

**只改一行**：`local.properties`（不进版本库）里的 `com.example.poetry.API_BASE_URL`，
由 `app/build.gradle.kts` 读出来注入 `BuildConfig.API_BASE_URL`，`ApiConfig.BASE_URL`
只负责补尾斜杠、校验合法性：

```properties
# 换后端只改这一行；用域名而不是 IP:端口，理由见 §5
com.example.poetry.API_BASE_URL=http://shiyun.rundefit.com/
```

清单地址 = `ApiConfig.BASE_URL + ApiConfig.API_MANIFEST`（即 `static/version.json`），
全工程只此一处要填。然后把 §4.2 的两个文件传到后端 `static/` 下即可，不需要发版。

### 4.1 服务端放什么

```
<BASE_URL>static/
├── version.json      约 300 B，App 每次检查只拉这个
└── poetry.db         诗库本体（当前 1878 首样本库，约 5.8 MiB）
```

路径是 `ApiConfig.BASE_URL` + `ApiConfig.API_MANIFEST`（`static/version.json`）拼出来的，
换服务器只改 `local.properties` 一行，见 §5。当前线上是 `http://shiyun.rundefit.com/static/`。

`version.json`（`tools/make-version-json.py` 的实际产出）：

```json
{ "schema_version": "3.4", "built_at": "2026-10-10T09:20:24",
  "bytes": 6037504, "sha256": "1d8c926e…（64位小写十六进制，指未压缩的服务端文件）",
  "is_subset": true, "n_poems": 1878,
  "note": "测试期子集库：每（朝代,类型）最多 200 首" }
```

`schema_version` 只比**主版本号**（`3.3` / `3.4` / `3.9` 互通，`4.0` 拒绝），
规则见 `PoetryDatabase.Meta#schemaCompatible()`。

`built_at` 是这份清单里最容易写错的一个字段，它被两条方向相反的约束夹着：

| 约束 | 出处 | 要求 |
|---|---|---|
| 与**被发布那份库**的 `meta.built_at` | `DbInstaller.isForeign()` | 必须**相等**，否则每次都当外来库重下一遍 |
| 比**用户已装那份库**的 `meta.built_at` | `DbManifest.isNewerThan()` | 必须**严格更大**（字典序），否则永远回「已是最新」 |

所以它不能另取一个时间，只能照搬被发布那份库自己的值；而那个值是从母库整份抄过来的，
母库不重建就不变——同一母库换个 `--per-group` 重抽，内容全变而 `built_at` 不动，
第 2 条就**静默失效**。`tools/make-version-json.py` 为此会把抽样器当次写的
`sample_built_at` 回写进 `meta.built_at`，因此**它是会改库的**：跑完要点是重新上传
`poetry.db`（`bytes` / `sha256` 都变了）。格式必须是 `YYYY-MM-DDTHH:MM:SS` 且与库内同基准，
格式一变字典序就比较不出谁新。

`sha256` 必须是**未压缩文件**的。App 请求时带 `Accept-Encoding: identity`——否则中间有
gzip 代理时 `Content-Length`、`Range` 偏移和这个 sha 全都指向压缩后的字节，整套校验静默错位。
**反代那层必须显式 `gzip off`**：宝塔/Nginx 的全局配置常默认开着 gzip，而它只对
`Accept-Encoding` 里带 gzip 的客户端生效，所以 curl 直连测是好的、App 却是坏的。

`url` 是**可选**字段，`DbManifest.parse()` 在它为空时回退到「清单同级的那份 `poetry.db`」。
现在刻意不写：写死反而把清单和服务器路径绑在一起，换 CDN 要重新发清单。
（写上去也支持，那种情况下换地址同样不用发版。）

静态服务器需要支持 `Range`（续传）并返回 `Accept-Ranges: bytes`；不支持也不会坏，只是每次
从头下。**但服务器「忽略 Range 却返回 200」必须处理**——往半成品后面追加完整响应体会得到一个
比预期更长的损坏文件，`HttpDownloader` 检测到 `rangeRequested && code == 200` 时会删掉
`.part` 重新开始。

### 4.2 测试期的子集库怎么造

**两件事、两个工具**（分布在两个仓库里）：抽样器出库，本仓库的脚本配清单。

**① 出库** —— 用上游管线自带的抽样器，按 `(朝代, 类型)` 分组，每组最多 N 首：

```bash
cd E:/chinese_poetry_iterate/poetry-pipeline
python -X utf8 make_sample_db.py \
    --db dist/poetry.db \
    --out E:/AndroidPro/Poetry/dist-server/poetry.db \
    --per-group 200 --seed 42
```

固定 `--seed` 保证可复现（同一条命令跑两次得到逐字节相同的库，「第 37 条不对」这种描述才有
意义）。组内**先取带扩展资料的**再随机补足，让译文/赏析/配图面板在样本库上真的有内容可渲染。
当前 `--per-group 200` 得到 **15 组 / 1878 首 / 1330 条扩展资料 / 17 个来源 / 约 5.8 MiB**；
`0` 表示不限（全量 357186 首、约 115 MiB，不要）。

母库 id / uid **沿用原值**，所以平仄包 `poetry-strains.db` 照旧能按 id `ATTACH` 上来，
不必单独出一份简化平仄库。`meta` 里会写上 `sample=1`（App 认的样本标记就是它，不是旧的
`subset=1`），以及 `sample_parent` / `sample_per_group` / `sample_seed` 等口径记录。

> **不要 `--out` 指到 `dist/poetry.db` 自己**：抽样器会 `ATTACH` 源库，原地执行等于自己读自己。
> 也不要再拿 `tools/make-subset-db.py` 去跑——它已随 schema 3.4 一起删除：它的每条 SQL 都在
> `JOIN poems.author_id`，而 3.4 起母库只有 `author_uid`；它也不拷 `poem_extras`，
> 产出物会被 `DbValidator` 以「诗库缺少表: poem_extras」直接拒掉。

**② 配清单** —— 抽样器**不产** `version.json`：

```bash
python -X utf8 tools/make-version-json.py --db dist-server/poetry.db
```

从库里读 `meta` 填 `schema_version` / `n_poems` / `is_subset`，算 `bytes` 与未压缩文件的
`sha256`，**并把抽样器写的 `sample_built_at` 回写成 `meta.built_at`**（为什么必须如此见 §4.1
的 `built_at` 那条）。回写会改字节，所以顺序是「先跑它、再上传」，反过来清单就对不上文件。
脚本还会在写清单**之前**按 `DbValidator.REQUIRED_TABLES` 挡一道——发布闸门放在这里，
比线上出现「下载成功但装不上」再回头查便宜得多。`--check` 只报告不落盘。

先跑 `--check` 看一遍再落盘是个好习惯；同一份库重复跑是**幂等**的（`built_at` 取的是当次
抽样时间而不是当前时间），所以「上传完手滑又跑了一次」不会造出一份与线上文件对不上的清单。

产物落在 `dist-server/`，已 gitignore（重建一次 sha 就变，不该进版本库）。传上服务器后要核
一遍 `Content-Length == bytes` 且 `sha256sum` 与清单一致，见 §4.1。

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
   库里那一侧的标记有两个名字：老管线写 `meta.subset='1'`，新管线（3.3）写 `meta.sample='1'`
   （并附带 `sample_parent_rows` / `sample_per_group` / `sample_seed` 等，够人看，
   App 不读）。`PoetryDatabase.Meta` 把两者折成同一个 `subset` 布尔——
   不折的话，新库会被 `DbInstaller.isForeign()` 判成「库来源不明」，每次检查都要求重下。
2. **FOREIGN 规则**把 SharedPreferences 当作可失效的缓存。没有它，adb push 一个完整库盖掉
   下载来的子集后，prefs 会一直骗 App 说「已最新」。
3. **schema 主版本闸门**在安装前拦住 `4.0` 的库，报「诗库版本不兼容，请升级 App」，
   而不是三个界面之后才抛 `SQLiteException`。

`is_subset` **不作为期望值**——同一个 APK 要能同时吃下 2 MB 和 115 MB 两种形态。

### 4.6 真机自测清单

1. 卸载重装 → 冷启动：走示例数据，我的页显示「开始下载」→ 点它 → 看到进度 → 完成后变
   「已载入本地诗库」，**列表立刻从 15 首变成新库的篇数**（当前发布的样本库是 1878 首，
   以线上 `version.json` 的 `n_poems` 为准）。数字没变就是 `ensureOpen()` 的早退
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

## 5. 后台接口（已接入）

> 这一节说的是 **JSON 业务接口**（检索、译文、赏析、云端音色），跟 §4 的「下载诗库文件」
> 是两码事。两者的开关也是分开的：关掉 `ENABLED` 只影响本节，§4 的下载走
> `DbDownloadPolicy` / `HttpDownloader`，不看它。

后端已经建起来了（`E:/Poetry_Server`，Go），客户端这套远程层是**真的在跑**：

| 文件 | 职责 |
|---|---|
| `data/remote/PoetryApi.java` | 接口契约。**阻塞式**、失败抛 `ApiException`；方法名与后端路由一一对应 |
| `data/remote/HttpPoetryApi.java` | 唯一实现，全部端点在这里拼地址（`ApiConfig.API_*`） |
| `data/remote/ApiClient.java` | `HttpURLConnection` + 熔断 + 401 自动续签；`asObject` / `asArray` 兜住非预期负载 |
| `data/remote/JsonMapper.java` | JSON ↔ 模型（含 `toPageOfPoems` 等分页形状） |
| `data/remote/ApiConfig.java` | `BASE_URL` / `ENABLED` / 超时与熔断参数 / 全部端点常量 |
| `data/remote/ApiException.java` / `Jwt.java` / `LoginResult.java` | 错误码（含 `VIP_REQUIRED`）、JWT 到期时间解析、登录结果 |

**地址哪来的**：不在版本库里。`app/build.gradle.kts` 从 `local.properties` 读
`com.example.poetry.API_BASE_URL`（按包名做命名空间，优先于通用的 `API_BASE_URL`）
注入成 `BuildConfig`，默认值是 `http://10.0.2.2:8000/`（`API_ENABLED` 默认 `true`），
也就是「模拟器访问宿主机上的本地后端」，clone 下来直接能联调。连线上后端时把那行改成
`com.example.poetry.API_BASE_URL=http://shiyun.rundefit.com/` 即可——**用域名，不用 IP:端口**：
后端换机器、换端口都只改 DNS 与 nginx，APK 不用重发；界面与 manifest 里也不会留下一个
可被反编译读到的裸地址。

```properties
# local.properties（不进版本库）
com.example.poetry.API_BASE_URL=http://10.0.2.2:8000/
com.example.poetry.API_ENABLED=true
```

**取数次序**（`PoetryRepository` 是唯一分流点）：

* **列表**（体裁 / 朝代 / 作者 / 检索 / 精选）——**本地先出图，远端回来替换第一页**。
  本地库永远能秒开，网络只负责「把更全的那份补上」，断了、慢了都不影响首屏。
* **单篇**（详情 / 译文 / 赏析 / 平仄）——**远端优先 → 本地库 → 内置示例**三级回落。
* 远端不可达时不弹错：连续 3 次失败即熔断 30 秒，期间请求快速失败、本地库接管
  （参数见 `ApiConfig`；连接超时 3 秒是刻意压短的）。

**付费接口的闸门在服务端**：`POST /v1/tts/synthesize` 要求**已登录 + 会员**，
未登录 `1002`、非会员 `4003`（客户端映射成 `ApiException.VIP_REQUIRED`）；
`GET /v1/tts/voices` 的 `locked` 字段同样由服务端按请求里的票现算。
客户端不判会员——它只缓存服务端下发的 `Member`（见 `TTS.md` 开头那段）。

---

## 6. 性能建议

* 35 万行 `LIKE '%x%'` 是主要开销。检索已经**先在本地出图、远端再替换第一页**
  （见 §5 的取数次序），所以本地这一遍不能省；真要提速得给 `poems` 建 FTS5 虚拟表
  （需另行导出，源库未开）。
* `score`（0–255）可直接用作「精选」排序依据。母库均值 173.5，而当前 1878 首样本
  均值只有 **60.3**（1878 首里 1287 首是 0）——抽样按「朝代/体裁分组先取带资料的、再随机
  补足」来，`score` 分布不再代表母库，所以**别拿样本库的分数分布去调阈值**，先在母库上量。
* 母库里有 5 首「一整本书算一首」的超长作品（古文观止 141,014 字、文字蒙求 39,964、
  唐诗三百首 25,056、幼学琼林 21,368、千家诗 10,020），分数都是 0，所以「精选」天然
  不会选到；但「随机换一首」会，因此 `PoetryDatabase.MAX_RANDOM_N_CHAR = 2000` 把它们
  挡在随机池外（搜索/列表命中的仍然照常打开——那是用户自己要看的）。
* 平仄在独立库 `poetry-strains.db`，按需懒加载，不要在主列表查询里 JOIN。
