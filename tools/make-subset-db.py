#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从完整诗库派生一个「测试期子集库」，供 App 的下载链路（检查 / 下载 / 校验 / 安装 /
热重载）在低成本下跑通。

为什么要从 poetry.db 派生，而不是跑上游 poetry-pipeline 的 ``build.py --limit``：

1. ``--limit`` 是按**集合顺序**截断的，不是按 ``score``。而 App 的 `featured()` 是
   ``ORDER BY score DESC``、主题 chip 是 LIKE 检索——取「源文件前 N 条」会得到一批
   同作者的近重复篇目，界面看起来像坏了，真出问题时也分不清是 UI bug 还是数据 bug。
   本脚本按 ``score DESC, id ASC`` 取每来源前 N 条，App 看起来与全量库一样正常。
2. ``--limit`` 仍然会为全部 13,755 位作者建行（``build.py`` 里
   ``author_ids`` 索引的是**完整**作者列表），产出约 5.6 MB 且大量 ``n_poems=0`` 的空作者；
   本脚本只保留被引用的作者，产出约 2 MB。
3. 行拷贝天然与线上库同构：schema、五个索引、``sqlite_stat1`` 逐字节一致，不会因为
   上游某次改动而在无关的地方产生差异。
4. ``--limit`` 要重新解析 1.2 GB 语料，本脚本是秒级的。

``build.py --limit 200 --db-only`` 只建议用来**做一次交叉验证**（diff 两份库的 sqlite_master
与 meta），不要拿它当构建路径。

用法::

    python tools/make-subset-db.py \
        --src E:/chinese-poetry-master/poetry-pipeline/dist/poetry.db \
        --out E:/AndroidPro/Poetry/dist-subset/poetry.db \
        --per-source 200

**绝不要对 --src 原地执行**：脚本第一步是 ``shutil.copy2``，所有改动都发生在副本上。
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import sqlite3
import sys
from datetime import datetime

# 与 PoetryDatabase.HOT_KEYWORDS 一致；子集里任何一个命中为 0，
# 首页「热门搜索」chip 点下去就是空白，看起来像 App 坏了。
HOT_KEYWORDS = ["中秋", "明月", "李白", "苏轼", "思乡", "边塞", "田园", "宋词", "元曲", "离骚"]

# 与 Theme.defaults() 一致
THEME_KEYWORDS = ["月", "山", "江", "花", "酒", "春", "秋", "雪", "归", "别", "塞", "田"]

EXPECTED_SCHEMA_VERSION = "3.0"
EXPECTED_TABLE_COUNT = 17  # sources 表的行数；子集刻意保留全部来源
MAX_OUTPUT_BYTES = 8 * 1024 * 1024

# 与 ApiConfig.BASE_URL 对齐的占位值。换服务器时两处一起改：ApiConfig 决定去哪里取
# version.json，这里的 url 决定 version.json 指向哪里下载 poetry.db。
DEFAULT_URL = "https://api.example.com/poetry/poetry.db"


def parse_args():
    here = os.path.dirname(os.path.abspath(__file__))
    default_out = os.path.join(os.path.dirname(here), "dist-subset", "poetry.db")
    p = argparse.ArgumentParser(description="从完整诗库派生测试用子集库")
    p.add_argument("--src", required=True, help="完整库路径（只读，不会被改动）")
    p.add_argument("--out", default=default_out, help="输出路径，默认 dist-subset/poetry.db")
    p.add_argument("--per-source", type=int, default=200,
                   help="每个 src_id 按 score 取前 N 条（默认 200，约 2 MB）")
    p.add_argument("--min-hits", type=int, default=20,
                   help="每个搜索词/主题词在子集里至少要有多少条命中（默认 20）；"
                        "不够会从全库里按 score 补几条，免得 chip 点下去是空白")
    p.add_argument("--keep-all-authors", action="store_true",
                   help="保留全部作者（默认只留被引用的；全留会让文件大三倍）")
    p.add_argument("--url", default=DEFAULT_URL,
                   help="服务端 poetry.db 的最终地址，写进 version.json 的 url 字段"
                        "（默认是与 ApiConfig.BASE_URL 对齐的占位值）")
    p.add_argument("--no-version-json", action="store_true",
                   help="不生成 version.json（默认与 poetry.db 一起生成，放在它旁边）")
    p.add_argument("--built-at", default=None,
                   help="固定 meta.built_at（ISO-8601，形如 2026-09-24T12:00:00）。"
                        "默认取当前时间，于是每次重建的 sha256 都不同；需要可复现构建时用这个")
    return p.parse_args()


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def rescue(conn, keywords, minimum):
    """给「按 score 取前 N」够不着的关键词补几条，让首页/分类页的 chip 点下去有东西。

    实测：每个来源按 score 取前 200 后，「宋词」「元曲」在子集里命中为 0，而
    「苏轼」「思乡」「边塞」各只剩 1 条。这两组词正是首页「热门搜索」与分类页主题
    chip 的字面内容——**子集本身没问题，是采样把它采没了**。但测试期的目的是验证下载
    链路，chip 返回空会让人分不清是链路错了还是数据少了，所以按关键词补到 min_hits。

    代价可以忽略：22 个词 × 最多 25 条，撑死几百 KB 里的几行。

    先做便宜的「够不够」判断（只扫已选中的 1932 行），不够才去全表扫——
    全表 LIKE 每次约 115 MB，能少扫就少扫。
    """
    cur = conn.cursor()
    adjusted = {}
    for kw in keywords:
        have = cur.execute("""
            SELECT COUNT(*) FROM poems p
            WHERE p.id IN (SELECT id FROM keep_poems)
              AND (p.title LIKE ? OR p.body LIKE ?)
        """, ("%" + kw + "%",) * 2).fetchone()[0]
        if have >= minimum:
            continue
        # 与 search() 一致：作者名也算命中（「李白」「苏轼」这类词靠的就是作者名）。
        # 同分优先取 id 小的，保证可复现。
        rows = cur.execute("""
            SELECT p.id FROM poems p LEFT JOIN authors a ON a.id = p.author_id
            WHERE p.id NOT IN (SELECT id FROM keep_poems)
              AND (a.name LIKE ? OR p.title LIKE ? OR p.body LIKE ?)
            ORDER BY p.score DESC, p.id ASC LIMIT ?
        """, ("%" + kw + "%",) * 3 + (minimum - have,)).fetchall()
        if rows:
            cur.executemany("INSERT INTO keep_poems(id) VALUES(?)", rows)
        adjusted[kw] = len(rows)
    return adjusted


def prune(conn, per_source, keep_all_authors, min_hits, built_at=None):
    cur = conn.cursor()

    # ---- 1. 每来源按 score 取前 N 条 --------------------------------------
    # 注意：src_id 4..17 的 score 全是 0，此时 ORDER BY 退化为按 id 取最早的若干条，
    # 这是稳定且可复现的；这些来源本来就不参与「精选」排序。
    cur.execute("DROP TABLE IF EXISTS keep_poems")
    cur.execute("""
        CREATE TEMP TABLE keep_poems AS
        SELECT id FROM (
            SELECT id, row_number() OVER (
                       PARTITION BY src_id ORDER BY score DESC, id ASC) AS rn
            FROM poems)
        WHERE rn <= ?
    """, (per_source,))

    # ---- 1b. 关键词兜底 ---------------------------------------------------
    rescued = rescue(conn, HOT_KEYWORDS + THEME_KEYWORDS, min_hits)

    cur.execute("DELETE FROM poems WHERE id NOT IN (SELECT id FROM keep_poems)")

    # ---- 2. 只保留被引用的作者 -------------------------------------------
    # 不删作者的后果：朝代宫格是按 SUM(authors.n_poems) 算的，留着大量 n_poems=0 的空作者
    # 只是浪费空间；但**保留 n_poems>0 却已无作品的作者会让宫格数字撒谎**，所以两者都删。
    if not keep_all_authors:
        cur.execute("""
            DELETE FROM authors WHERE id NOT IN
                (SELECT DISTINCT author_id FROM poems WHERE author_id <> 0)
        """)

    # ---- 3. 重算 n_poems --------------------------------------------------
    # 完整库里 authors.n_poems 是**源语料**的篇数，SUM(n_poems)=353,922 而实际只有
    # 345,358 首诗（差 2.5%），朝代宫格本来就偏大。子集里改成「实际保留的篇数」，
    # 让宫格数字加得起来，也让它和 listByDynasty 的结果对得上。
    cur.execute("""
        UPDATE authors SET n_poems =
            (SELECT COUNT(*) FROM poems p WHERE p.author_id = authors.id)
    """)

    # ---- 4. meta ----------------------------------------------------------
    # built_at 必须是 ISO-8601 且与完整库同格式（原值是 2026-09-19T14:24:57）：检查更新时
    # 要拿它和 version.json 的 built_at 做**字典序**比较，格式一变比较就失去意义。
    n_poems = cur.execute("SELECT COUNT(*) FROM poems").fetchone()[0]
    now = built_at or datetime.now().strftime("%Y-%m-%dT%H:%M:%S")
    meta = {
        "subset": "1",
        "n_poems": str(n_poems),
        "n_authors": str(cur.execute("SELECT COUNT(*) FROM authors").fetchone()[0]),
        "built_at": now,
        # note 是**写死的 schema 说明**（dynasty/kind 由 id 高位反解、body 兼作检索列……），
        # 子集全部保留这些性质，所以不要覆盖它——覆盖是净损失。子集自己的说明另起一个键。
        "subset_note": "测试期子集库：每个 src_id 按 score 取前 %d 条，作者只留被引用的；"
                       "另按关键词兜底补了 %d 条，涉及 %d 个关键词，"
                       "保证首页热门搜索与主题 chip 点下去不为空。"
                       % (per_source, sum(rescued.values()), len(rescued)),
        # strains_pkg 保持原值不动。平仄包是按 poems.id 关联的独立库，而子集的 id 是完整库 id
        # 的子集，所以完整平仄包对子集**依然有效**，改成 '0' 反而是说谎。
    }
    for k, v in meta.items():
        cur.execute("INSERT INTO meta(k, v) VALUES(?, ?)"
                    " ON CONFLICT(k) DO UPDATE SET v = excluded.v", (k, v))
    cur.execute("DROP TABLE IF EXISTS keep_poems")

    conn.commit()
    return rescued


def check(conn, per_source, rescued, min_hits):
    """全部断言。返回问题列表，空列表表示通过。"""
    cur = conn.cursor()
    problems = []

    def q(sql, args=()):
        return cur.execute(sql, args).fetchone()[0]

    # 1. 五张表的 schema 仍在，且版本仍是 App 认得的那个
    tables = {r[0] for r in cur.execute(
        "SELECT name FROM sqlite_master WHERE type='table'").fetchall()}
    for t in ("poems", "authors", "rhythmics", "sources", "meta"):
        if t not in tables:
            problems.append("缺少表 %s" % t)
    schema = q("SELECT v FROM meta WHERE k='schema_version'")
    if schema != EXPECTED_SCHEMA_VERSION:
        problems.append("schema_version 应为 %s，实际 %s" % (EXPECTED_SCHEMA_VERSION, schema))

    # 2. meta.n_poems 与实际篇数一致（DbValidator 会校验这一条）
    actual = q("SELECT COUNT(*) FROM poems")
    if int(q("SELECT v FROM meta WHERE k='n_poems'")) != actual:
        problems.append("meta.n_poems 与实际篇数不一致")

    # 2b. subset 标记与 built_at 格式。App 靠 subset 区分「测试子集/生产全量」——
    # 子集库在服务端提供完整库时绝不能被判成最新，所以这个标记缺了就是链路判断失效。
    if q("SELECT v FROM meta WHERE k='subset'") != "1":
        problems.append("meta.subset 应为 '1'")
    built_at = q("SELECT v FROM meta WHERE k='built_at'")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}", built_at or ""):
        problems.append("meta.built_at 格式不对：%r" % (built_at,))

    # 3. 朝代宫格：SUM(authors.n_poems) 必须等于「有作者的篇数」，否则宫格数字会撒谎
    total_author_poems = q("SELECT COUNT(*) FROM poems WHERE author_id <> 0")
    sum_n_poems = q("SELECT SUM(n_poems) FROM authors") or 0
    if sum_n_poems != total_author_poems:
        problems.append("SUM(authors.n_poems)=%d 与 author_id<>0 的篇数 %d 不等"
                        % (sum_n_poems, total_author_poems))

    # 4. 外键不悬空
    if q("SELECT COUNT(*) FROM poems p WHERE p.author_id <> 0 AND NOT EXISTS"
         " (SELECT 1 FROM authors a WHERE a.id = p.author_id)"):
        problems.append("有诗指向不存在的作者")
    if q("SELECT COUNT(*) FROM poems p WHERE NOT EXISTS"
         " (SELECT 1 FROM sources s WHERE s.id = p.src_id)"):
        problems.append("有诗指向不存在的来源")
    if q("SELECT COUNT(*) FROM poems p WHERE p.rhythmic_id <> 0 AND NOT EXISTS"
         " (SELECT 1 FROM rhythmics r WHERE r.id = p.rhythmic_id)"):
        problems.append("有诗指向不存在的词牌")
    # 佚名（author_id=0）不应在 authors 里建行，verify_db.py 有同样的断言
    if q("SELECT COUNT(*) FROM authors WHERE id = 0 OR name = '佚名'"):
        problems.append("authors 里不应有佚名")

    # 5. 来源没有丢（体裁大卡的计数靠它）
    n_src = q("SELECT COUNT(*) FROM sources")
    if n_src != EXPECTED_TABLE_COUNT:
        problems.append("sources 应为 %d 行，实际 %d" % (EXPECTED_TABLE_COUNT, n_src))

    # 6. 每个来源都取到了东西，且不超过 per_source（兜底补的那些要算进配额）
    empty = cur.execute("""
        SELECT s.name FROM sources s
        WHERE NOT EXISTS (SELECT 1 FROM poems p WHERE p.src_id = s.id)
    """).fetchall()
    if empty:
        problems.append("以下来源一条都没取到：%s" % ", ".join(r[0] for r in empty))
    # 兜底是按关键词全局补的，条数不多但会压在某个来源上，所以上限要放宽这么多。
    cap = per_source + sum(rescued.values())
    over = cur.execute("""
        SELECT s.name, COUNT(*) FROM poems p JOIN sources s ON s.id = p.src_id
        GROUP BY p.src_id HAVING COUNT(*) > ?
    """, (cap,)).fetchall()
    if over:
        problems.append("以下来源超出上限 %d：%s" % (cap, over))

    # 7. 检索词必须命中（首页热门搜索 chip / 分类页主题 chip 点下去不能是空的）
    for label, kws in (("热门搜索词", HOT_KEYWORDS), ("主题词", THEME_KEYWORDS)):
        for kw in kws:
            n = q("SELECT COUNT(*) FROM poems p LEFT JOIN authors a ON a.id = p.author_id"
                  " WHERE a.name LIKE ? OR p.title LIKE ? OR p.body LIKE ?",
                  ("%" + kw + "%",) * 3)
            if n <= 0:
                problems.append("%s「%s」在子集里命中为 0（chip 点下去是空白）" % (label, kw))
            elif n < min_hits and kw not in rescued:
                problems.append("%s「%s」只有 %d 条，低于下限 %d 且没走兜底"
                                % (label, kw, n, min_hits))

    return problems


def write_version_json(out_path, manifest_url, sha256, size):
    """在 poetry.db 旁边写 version.json——它是 App 判断「要不要下载」的唯一依据。

    刻意与 poetry.db **写在同一次运行里**：sha256 必须是刚刚落盘的那份字节的摘要，
    分两步做（先生成库、再手工填 sha）迟早会出现「改了库忘了改 sha」，而那时的表现是
    下载完报 CHECKSUM 却查不出原因。
    """
    conn = sqlite3.connect("file:%s?mode=ro" % out_path.replace("\\", "/"), uri=True)
    try:
        meta = dict(conn.execute("SELECT k, v FROM meta").fetchall())
        n_poems = conn.execute("SELECT COUNT(*) FROM poems").fetchone()[0]
    finally:
        conn.close()

    doc = {
        "schema_version": meta.get("schema_version", EXPECTED_SCHEMA_VERSION),
        "built_at": meta.get("built_at", ""),
        "bytes": size,
        "sha256": sha256,
        # is_subset 参与「是否最新」的身份判定：服务端把它改成 false 就是测试转生产，
        # App 不必发版。见 DbDownloadPolicy 的 UP_TO_DATE 规则。
        "is_subset": meta.get("subset", "0") == "1",
        "n_poems": n_poems,
        "url": manifest_url,
        "note": meta.get("subset_note", ""),
    }
    path = os.path.join(os.path.dirname(os.path.abspath(out_path)), "version.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")
    return path, doc


def main():
    # Windows 控制台默认不是 UTF-8，不写这一行的话中文输出在 cmd/PowerShell 里是乱码，
    # 断言失败信息（全是中文）正好是最需要看清的那部分。
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):
            pass

    args = parse_args()
    if not os.path.isfile(args.src):
        print("找不到源库：%s" % args.src, file=sys.stderr)
        return 1
    if os.path.abspath(args.src) == os.path.abspath(args.out):
        print("--out 不能与 --src 相同（源库只读）", file=sys.stderr)
        return 1

    out_dir = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(out_dir, exist_ok=True)

    print("源库   %s (%.2f MB)" % (args.src, os.path.getsize(args.src) / 1048576.0))
    # copy2 而不是 connect(src)：脚本要以读写方式打开副本，直接开源库风险太大。
    shutil.copy2(args.src, args.out)

    conn = sqlite3.connect(args.out)
    try:
        rescued = prune(conn, args.per_source, args.keep_all_authors, args.min_hits,
                        args.built_at)
        # VACUUM 是**必需**的：不 VACUUM 文件不会缩小，"2 MB 子集" 会仍然是 110 MB，
        # 下载永远下不完。ANALYZE 让 sqlite_stat1 反映新规模（查询计划不至于太离谱）。
        conn.isolation_level = None
        conn.execute("VACUUM")
        conn.execute("ANALYZE")
        problems = check(conn, args.per_source, rescued, args.min_hits)
        n_poems = conn.execute("SELECT COUNT(*) FROM poems").fetchone()[0]
        n_authors = conn.execute("SELECT COUNT(*) FROM authors").fetchone()[0]
    finally:
        conn.close()

    size = os.path.getsize(args.out)
    sha = sha256_of(args.out)
    print("输出   %s" % args.out)
    print("规模   poems=%d authors=%d  %.2f MB" % (n_poems, n_authors, size / 1048576.0))
    print("兜底   %s" % ("、".join("「%s」+%d" % (k, v) for k, v in rescued.items()) or "无"))
    print("sha256 %s" % sha)

    if problems:
        print("\n断言失败：", file=sys.stderr)
        for p in problems:
            print("  - %s" % p, file=sys.stderr)
        return 1
    if size > MAX_OUTPUT_BYTES:
        print("\n输出 %.2f MB 超过 %.0f MB 上限——是不是漏了 VACUUM？"
              % (size / 1048576.0, MAX_OUTPUT_BYTES / 1048576.0), file=sys.stderr)
        return 1

    if not args.no_version_json:
        vpath, doc = write_version_json(args.out, args.url, sha, size)
        print("清单   %s" % vpath)
        print("       url=%s" % doc["url"])
        print("       is_subset=%s n_poems=%d bytes=%d"
              % (doc["is_subset"], doc["n_poems"], doc["bytes"]))
        if DEFAULT_URL in doc["url"]:
            print("       ⚠ url 还是占位值（api.example.com）。上传前用 --url 指定真实地址，"
                  "并把 ApiConfig.BASE_URL 改成同一个 host。")

    print("\n全部断言通过。把 poetry.db 与 version.json 一起上传到服务器即可。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
