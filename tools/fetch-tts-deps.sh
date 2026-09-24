#!/usr/bin/env bash
#
# 取内置离线语音（sherpa-onnx + aishell3 中文语音包）的两个制品。
#
# 这两个文件都不进 git（见 .gitignore）：47.8 MB 的 AAR 和 30 MB 的模型装进仓库
# 只会让每次 clone 都慢一遍。代价是——**新克隆的仓库在跑完本脚本之前编译不过**
# （:app:assembleDebug 会因为找不到 app/libs/*.aar 失败）。
#
# 用法：bash tools/fetch-tts-deps.sh
# 可重复执行：已经下好且字节数正确的文件会直接跳过。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AAR="$ROOT/app/libs/sherpa-onnx-1.13.8.aar"
MODEL_TAR="$ROOT/app/libs/vits-icefall-zh-aishell3.tar.bz2"
MODEL_DIR="$ROOT/app/src/main/assets/tts/aishell3"
# 只有一个语音包的年代留下的，代码里已经不读了（SherpaTts 会在首次加载时清掉
# filesDir 里的那份拷贝，但 assets 里这份得手动删），顺手收拾干净
LEGACY_DIR="$ROOT/app/src/main/assets/tts/xiao_ya_int8"

AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
AAR_SIZE=50129134
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-icefall-zh-aishell3.tar.bz2"
MODEL_SIZE=31559701

# github.com:443 在开发机上实测超时（curl 退出码 28），raw.githubusercontent.com 与
# hub.gitmirror.com 也不可达，所以只列这三个实测返回 206 的镜像。
MIRRORS=(
    "https://ghfast.top/"
    "https://ghproxy.net/"
    "https://gh-proxy.com/"
)

# 归档里套了一层同名目录
INNER="vits-icefall-zh-aishell3"

# 只取推理真正要用的 8 个文件，**不带 rule.far**：那是全量 AISHELL-3 录音的检索库，
# 180 MB，只有训练 / 评测用得上，官方推理命令也没引用它。带上它 APK 就多 180 MB。
# 这份清单要和 VoicePacks.AISHELL3.files 一致。
MODEL_FILES=(
    "model.onnx"
    "tokens.txt"
    "lexicon.txt"
    "speakers.txt"
    "phone.fst"
    "date.fst"
    "number.fst"
    "new_heteronym.fst"
)

# 解包后每个文件应有的字节数。只信字节数——镜像偶尔会在中途断开却仍返回 0。
# 这份表比「校验压缩包大小」更贴近真正要用的东西：就算归档本身完整，
# 取错文件（例如漏了 lexicon.txt）在这里也会现形。
declare -A MODEL_BYTES=(
    ["model.onnx"]=30482262
    ["tokens.txt"]=1671
    ["lexicon.txt"]=2042943
    ["speakers.txt"]=1392
    ["phone.fst"]=88630
    ["date.fst"]=59154
    ["number.fst"]=64482
    ["new_heteronym.fst"]=21974
)

size_of() { wc -c < "$1" 2>/dev/null | tr -d ' '; }

# fetch <url> <out> <expected-bytes>
fetch() {
    local url="$1" out="$2" expect="$3" mirror got
    for mirror in "${MIRRORS[@]}"; do
        echo "  试 ${mirror}"
        if curl -fL --retry 2 --retry-delay 2 -m 900 -o "$out.part" "${mirror}${url}"; then
            got="$(size_of "$out.part")"
            if [ "$got" = "$expect" ]; then
                mv "$out.part" "$out"
                return 0
            fi
            echo "    字节数不符（$got != $expect），疑似截断，换下一个镜像"
        else
            echo "    下载失败，换下一个镜像"
        fi
        rm -f "$out.part"
    done
    return 1
}

mkdir -p "$ROOT/app/libs" "$MODEL_DIR"

echo "== sherpa-onnx AAR ($AAR_SIZE B)"
if [ -f "$AAR" ] && [ "$(size_of "$AAR")" = "$AAR_SIZE" ]; then
    echo "  已存在，跳过"
else
    fetch "$AAR_URL" "$AAR" "$AAR_SIZE" || { echo "AAR 下载失败：三个镜像都没成功"; exit 1; }
fi
echo "  $(sha256sum "$AAR" | cut -d' ' -f1)  $(basename "$AAR")"

echo "== aishell3 语音包 ($MODEL_SIZE B)"
if [ -f "$MODEL_TAR" ] && [ "$(size_of "$MODEL_TAR")" = "$MODEL_SIZE" ]; then
    echo "  已存在，跳过"
else
    fetch "$MODEL_URL" "$MODEL_TAR" "$MODEL_SIZE" || { echo "模型下载失败：三个镜像都没成功"; exit 1; }
fi
echo "  $(sha256sum "$MODEL_TAR" | cut -d' ' -f1)  $(basename "$MODEL_TAR")"

echo "== 解包 8 个文件到 app/src/main/assets/tts/aishell3/"
if [ -f "$MODEL_DIR/model.onnx" ] && [ "$(size_of "$MODEL_DIR/model.onnx")" = "${MODEL_BYTES[model.onnx]}" ]; then
    echo "  已解包，跳过"
else
    # 先试列一次：被截断的 bz2 在这一步就会报
    # "bzip2: Compressed file ends unexpectedly"，不会留下半套模型。
    tar -tjf "$MODEL_TAR" > /dev/null
    # 只解这几个成员，避免把 180 MB 的 rule.far 也摊到磁盘上。
    # 成员名写错时 tar 会报错退出（比默默少文件好），下面的字节数校验再兜一层。
    members=()
    for f in "${MODEL_FILES[@]}"; do
        members+=("$INNER/$f")
    done
    tmp="$(mktemp -d)"
    tar -xjf "$MODEL_TAR" -C "$tmp" "${members[@]}"
    for f in "${MODEL_FILES[@]}"; do
        mv -f "$tmp/$INNER/$f" "$MODEL_DIR/$f"
    done
    rm -rf "$tmp"
fi

# 归档里没有、但代码要读的那个文件（发音人名单）——少了它 SherpaTts 认不出任何
# 发音人，会整个语音包判为不可用，所以单独提一句
echo "== 校验"
missing=0
for f in "${MODEL_FILES[@]}"; do
    if [ ! -f "$MODEL_DIR/$f" ]; then
        echo "  缺少 $f"
        missing=1
    elif [ "$(size_of "$MODEL_DIR/$f")" != "${MODEL_BYTES[$f]}" ]; then
        echo "  $f 字节数不符（$(size_of "$MODEL_DIR/$f") != ${MODEL_BYTES[$f]}）"
        missing=1
    fi
done
[ "$missing" = 0 ] || { echo "模型文件不全，assets 目录不能用"; exit 1; }
ls -1 "$MODEL_DIR"

if [ -d "$LEGACY_DIR" ]; then
    echo "== 清掉旧的小雅模型 $LEGACY_DIR"
    rm -rf "$LEGACY_DIR"
fi

# 模型 tar 留着没用（解包后 assets 就是唯一入口），删掉省 30 MB 磁盘。
rm -f "$MODEL_TAR"

echo
echo "完成。两个制品均不进 git，本次改动后请确认 git status 里没有它们。"
