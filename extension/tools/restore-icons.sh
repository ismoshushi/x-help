#!/bin/bash
# 还原插件图标：仓库里图标以 .b64 文本存放（推送通道不支持二进制），
# clone 后运行一次本脚本即可生成 icons/*.png，然后在 chrome://extensions 加载。
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)"
for b64 in "$DIR"/icons/icon*.png.b64; do
  [ -f "$b64" ] && base64 -d "$b64" > "${b64%.b64}" && echo "restored: $(basename "${b64%.b64}")"
done
