#!/bin/bash
set -e
cd /root/workspace/WristControl
F="/storage/emulated/0/Documents/workspace/git_token.txt"
TOKEN=$(tr -d "\r\n" < "$F")
git rm -q --cached gradle/wrapper/gradle-wrapper.properties 2>/dev/null || true
rm -rf gradle
git add -A
git -c user.email="code787@users.noreply.github.com" -c user.name="code787" commit -q -m "fix(ci): 用 setup-gradle 代替缺失的 gradlew"
git remote set-url origin "https://code787:$TOKEN@github.com/code787/ds1.git"
git push origin main 2>&1 | sed -E "s#https://[^@]*@#https://***@#g" | tail -4
git remote set-url origin "https://github.com/code787/ds1.git"
echo "已推送并清除 token"