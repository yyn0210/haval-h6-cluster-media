#!/bin/bash
# 一键构建（Git Bash / WSL）
set -e
cd "$(dirname "$0")"
python build_apk.py "$@"
