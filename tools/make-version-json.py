#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""为一份已经做好的 ``poetry.db`` 生成它的 ``version.json`` —— App 判断「要不要下载、
下载到的这份装不装得上」的唯一依据。

# 为什么单独拎成一个脚本

子集库由上游管线抽样器 ``poetry-pipeline/make_sample_db.py`` 产出（见 DATABASE.md §4.2），
它**不产** ``version.json``；旧脚本 ``make-subset-db.py`` 里那半截职责随着它的模型在
schema 3.4 上整体作废（母库 ``poems`` 表已无 ``author_id``）一起没了。所以这里只保留
"给成品库配一份清单"这一件事，输入就是成品库本身。

# built_at 是这份清单里唯一有耦合的字段（也是这里最容易写错的一个）

``built_at`` 要**同时**满足两条约束，方向还相反：

1. 与库自身 ``meta.built_at`` **相等**。``DbInstaller.isForeign()`` 拿库里读出来的
   ``meta.built_at`` 和上次安装时存下的 ``manifest.built_at`` 逐字符比，不等就判定
   「这份库不是我装的」并**立刻重新下载**——写不一致的表现是每次进 App 都重下一遍。
2. 与用户**已装**那份库的 ``meta.built_at`` 相比**严格更大**。
   ``DbManifest.isNewerThan()`` 是字典序比较（``builtAt.compareTo(localBuiltAt) > 0``），
   不大于就回「已是最新」，新库永远推不下去。

所以清单里的 ``built_at`` 不能另取一个时间，只能**照搬被发布那份库自己的值**；
真正要操心的是那个值够不够新。而 ``meta.built_at`` 是从母库整份抄过来的
（``make_sample_db.py`` 第 5 步），母库不重建它就不变——同一个母库连抽两次、
``--per-group`` 从 100 改成 200，抽出来的库内容完全不同，``built_at`` 却一模一样，
第 2 条就不成立了，而且是**静默失效**：线上清单看着正常，客户端就是说「已是最新」。

抽样器每次运行都会写一个当次的 ``sample_built_at``（``time.strftime("%Y-%m-%dT%H:%M:%S")``，
与 ``built_at`` 同格式、同一个本地时间基准，所以字典序比较有意义）。本脚本就把它
**回写进 ``meta.built_at``**，让库里那个"版本号"真正代表"这份库是什么时候抽出来的"。

回写的是 ``sample_built_at`` 而不是 ``now``：这样脚本**幂等**——同一份库跑多少次，
``built_at`` / ``sha256`` / ``version.json`` 都不变。若用 ``now``，重跑一次就会让
字节数和摘要都变，于是"上传完再跑一次脚本"这种再正常不过的操作，都会造出一份与
线上文件对不上的清单。

# 刻意不写 url 字段

``DbManifest.parse()`` 在 ``url`` 为空时回退到「清单同级的那份 poetry.db」，
线上现在就是这么跑的。写死 url 反而把清单和服务器路径绑在一起，换服务器要重发版。

用法::

    python tools/make-version-json.py --db dist-server/poetry.db
    python tools/make-version-json.py --db dist-server/poetry.db --check   # 只看不写
"""

import argparse
import hashlib
import json
import os
import sqlite3
import sys
import time

# 与 DbValidator.REQUIRED_TABLES 一致——这里当着发布闸门用。
# 缺 poem_extras 的库（schema 3.0 的老库就是）在真机上是**致命**的：所有查询都会因为
# 缺列静默返回空列表，界面看起来只是"没数据"。发布前挡在这里，好过线上一边报
# "诗库下载失败"一边查不出原因。
REQUIRED_TABLES = ("poems", "authors", "rhythmics", "sources", "poem_extras", "meta")

# built_at 的比较是字典序，格式一变比较就失去意义——必须与库里的写法完全一致。
TIME_FMT = "%Y-%m-%dT%H:%M:%S"

CHUNK = 1 << 20


def parse_args():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(
        description="为成品诗歌库生成 version.json（App 的更新清单）")
    ap.add_argument("--db", default=os.path.join(os.path.dirname(here), "dist-server", "poetry.db"),
                    help="成品库路径，默认 ../dist-server/poetry.db")
    ap.add_argument("--out", default="",
                    help="清单输出路径，默认与 --db 同级写 version.json")
    ap.add_argument("--note", default="",
                    help="写进清单 note 字段的说明；默认按库里的抽样口径自动生成")
    ap.add_argument("--check", action="store_true",
                    help="只报告会写成什么，不修改库、不写文件")
    return ap.parse_args()


def open_ro(path):
    return sqlite3.connect("file:%s?mode=ro" % path.replace("\\", "/"), uri=True)


def read_meta(conn):
    return dict(conn.execute("SELECT k, v FROM meta").fetchall())


def check_tables(conn, path):
    present = {r[0] for r in
               conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    missing = [t for t in REQUIRED_TABLES if t not in present]
    if missing:
        raise SystemExit(
            "这份库 App 装不上：缺少表 %s\n"
            "  DbValidator.REQUIRED_TABLES 要求 %s。\n"
            "  缺 poem_extras 多半是 schema 3.0 的老库——App 的查询会因为缺列静默返回空列表，\n"
            "  所以它在安装前就会被拒。用 poetry-pipeline/make_sample_db.py 重出一份。"
            % ("、".join(missing), "/".join(REQUIRED_TABLES)))
    if conn.execute("SELECT COUNT(*) FROM sources").fetchone()[0] <= 0:
        raise SystemExit("这份库 sources 是空的，DbValidator 会拒绝安装：%s" % path)


def resolve_built_at(conn, meta, check):
    """决定 ``meta.built_at`` 该是什么，并在需要时回写。

    返回 ``(built_at, 是否需要回写)``。``--check`` 下只报告、不动库。
    """
    sample_built_at = (meta.get("sample_built_at") or "").strip()
    current = (meta.get("built_at") or "").strip()

    if sample_built_at:
        if sample_built_at == current:
            return current, False
        if check:
            return sample_built_at, True    # 会改，但 --check 下不动它
        # 只动 meta 一行。写完必须 VACUUM：sha256 是逐字节的，页里留着改行前的
        # 碎片会让"同一份库两次跑出不同摘要"。
        conn.execute("UPDATE meta SET v = ? WHERE k = 'built_at'", (sample_built_at,))
        conn.commit()
        conn.isolation_level = None
        conn.execute("PRAGMA page_size=8192")   # VACUUM 按当前连接的 page_size 重建整库
        conn.execute("VACUUM")
        after = conn.execute("SELECT v FROM meta WHERE k = 'built_at'").fetchone()
        if not after or after[0] != sample_built_at:
            raise SystemExit("回写 meta.built_at 失败，库没有落成预期状态")
        return sample_built_at, True

    # 不是抽样库（比如以后要发布的完整库）：没有 build 时间可用，只能沿用库自己的值。
    if current:
        return current, False

    # 连 built_at 都没有：App 那边 isForeign() 会因此永远为真、无限重下，等于这份库废了。
    # 补一个当次的，但要让调用方知道——这是母库的缺陷，不该在这里被悄悄抹平。
    stamped = time.strftime(TIME_FMT)
    print("⚠ 库里没有 built_at（母库没有写），已补 %s。请确认母库的构建流程。" % stamped,
          file=sys.stderr)
    if not check:
        conn.execute("INSERT OR REPLACE INTO meta VALUES('built_at', ?)", (stamped,))
        conn.commit()
        conn.isolation_level = None
        conn.execute("PRAGMA page_size=8192")
        conn.execute("VACUUM")
    return stamped, not check


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(CHUNK), b""):
            h.update(block)
    return h.hexdigest()


def default_note(meta):
    if meta.get("sample") == "1" or meta.get("subset") == "1":
        per_group = meta.get("sample_per_group", "?")
        return "测试期子集库：每（朝代,类型）最多 %s 首" % per_group
    return ""


def main():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):
            pass

    args = parse_args()
    db = os.path.abspath(args.db)
    if not os.path.isfile(db):
        raise SystemExit("找不到库：%s" % db)

    # --check 也要能看到库的真实结构，但绝不动它，所以只读打开。
    conn = open_ro(db) if args.check else sqlite3.connect(db)
    try:
        check_tables(conn, db)
        meta = read_meta(conn)
        n_poems = conn.execute("SELECT COUNT(*) FROM poems").fetchone()[0]
        built_at, changed = resolve_built_at(conn, meta, args.check)
    finally:
        conn.close()

    schema_version = (meta.get("schema_version") or "").strip()
    if not schema_version:
        raise SystemExit("库里 meta 没有 schema_version，清单无法描述它：%s" % db)
    if not built_at:
        raise SystemExit("没能确定 built_at：%s" % db)

    doc = {
        "schema_version": schema_version,
        "built_at": built_at,
        "bytes": os.path.getsize(db),
        "sha256": sha256_of(db),          # 未压缩字节的摘要：App 拉下来按 identity 校验
        # 参与"是否最新"的身份判定：服务端把它改成 false 就是测试转生产，App 不必发版。
        "is_subset": meta.get("subset") == "1" or meta.get("sample") == "1",
        "n_poems": n_poems,
        "note": args.note or default_note(meta),
        # 不写 url —— 见文件头。
    }

    out = args.out or os.path.join(os.path.dirname(db), "version.json")
    if args.check:
        print("--check：没有修改库，也不会写文件。")
    else:
        with open(out, "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, indent=2)
            f.write("\n")

    print("库   %s" % db)
    print("     %s  %d 首" % (schema_version, n_poems))
    if changed:
        print("     meta.built_at  %s → %s（%s）"
              % (meta.get("built_at") or "(空)", built_at,
                 "回写" if not args.check else "待回写"))
    print("清单 %s%s" % (out, "" if not args.check else "  ← 未写"))
    for k in ("schema_version", "built_at", "bytes", "sha256", "is_subset", "n_poems", "note"):
        print("     %-14s %s" % (k, doc[k]))
    if changed and not args.check:
        print("\n已把 meta.built_at 回写成当次抽样时间（改了 bytes/sha256，需重新上传这份库）")


if __name__ == "__main__":
    main()
