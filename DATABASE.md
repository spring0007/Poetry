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

**不建议把 110MB 打进 APK。** 推荐用 adb 投放到应用专属目录（Android 10+ 免权限）：

```bash
adb push E:/chinese-poetry-master/poetry-pipeline/dist/poetry.db \
         /sdcard/Android/data/com.example.poetry/files/Poetry/poetry.db
```

`DatabaseProvider#locate()` 的查找顺序：

1. 应用专属外部目录 `/sdcard/Android/data/<pkg>/files/Poetry/`（**推荐**）
2. 应用私有目录 `/data/data/<pkg>/files/Poetry/`
3. `assets/poetry/`（首次启动自动复制到私有目录，仅适合调试期放小样本）

三处都没有时，`PoetryRepository` 自动回退到 `SeedDataSource` 的内置 15 首示例诗词，
所有页面照常可跑通，不会白屏。

### 当前状态提醒

`app/src/main/assets/poetry/poetry.db`（110MB，已被 git 跟踪）当前会被打进 APK，
导致 debug 包约 **70MB**。若不需要内置，可在 `app/build.gradle.kts` 里排除：

```kotlin
android {
    packaging {
        resources.excludes.add("assets/poetry/*")
    }
}
```

或把该文件从 assets 移出（保留在 dist 目录即可，用 adb 投放）。

---

## 3. 本地封装

| 文件 | 职责 |
|---|---|
| `data/local/DatabaseProvider.java` | 定位 .db、从 assets 复制、返回可用 File |
| `data/local/PoetryDatabase.java` | 只读封装：精选、检索、体裁/朝代/作者/主题筛选、统计、平仄读取 |
| `data/local/SeedDataSource.java` | 无库时的兜底内置诗词 |
| `util/QueryNormalizer.java` | 检索词归一化 + LIKE 转义 |
| `data/PoetryRepository.java` | 统一入口：离线优先、IO 线程调度、结果回调到主线程 |

`PoetryDatabase` 用 `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY)` 打开，
全程只读，不会对源库写 WAL / journal。

---

## 4. 后台接口（预留，尚未接入）

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

## 5. 性能建议

* 34 万行 `LIKE '%x%'` 是主要开销，建议后端就绪后把检索迁到服务端；
  离线场景可考虑给 `poems` 建 FTS5 虚拟表（需另行导出，源库未开）。
* `score`（0–255，均值 173.5）可直接用作「精选」排序依据。
* 平仄在独立库 `poetry-strains.db`，按需懒加载，不要在主列表查询里 JOIN。
