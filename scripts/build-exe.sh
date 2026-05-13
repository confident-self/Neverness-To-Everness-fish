#!/usr/bin/env bash
set -e

APP_NAME="NToE-Fish"
MAIN_CLASS="com.yihuan.fish.FishBotApp"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

cd "$PROJECT_DIR"

echo "=== 1. 编译项目 ==="
mvn clean package -DskipTests

echo ""
echo "=== 2. 准备打包目录 ==="
STAGING="$PROJECT_DIR/staging"
rm -rf "$STAGING"
mkdir -p "$STAGING/lib"

# 主 JAR
cp target/*.jar "$STAGING/"

# 依赖 JAR
cp target/lib/*.jar "$STAGING/lib/"

# 模板图片
cp -r image "$STAGING/image/"

# 图标
cp image/app.ico "$STAGING/"

echo ""
echo "=== 3. 生成 exe ==="
OUTPUT="$PROJECT_DIR/output"
rm -rf "$OUTPUT"

jpackage --type app-image \
  --name "$APP_NAME" \
  --input "$STAGING" \
  --main-jar "yihuan-fish-1.0.0-SNAPSHOT.jar" \
  --main-class "$MAIN_CLASS" \
  --icon "$STAGING/app.ico" \
  --dest "$OUTPUT" \
  --java-options "-Dfile.encoding=UTF-8" \
  --java-options "-Djava.library.path=./lib" \
  --vendor "NToE"

# jpackage 把 --input 的内容放到 app/ 下了, 同时根目录也需要 image/
if [ -d "$OUTPUT/$APP_NAME/app/image" ]; then
  # 移到根目录, 并在 app/ 保留一份（工作目录可能是 app/）
  mv "$OUTPUT/$APP_NAME/app/image" "$OUTPUT/$APP_NAME/image"
  cp -r "$OUTPUT/$APP_NAME/image" "$OUTPUT/$APP_NAME/app/image"
  # 清理没用的 app.ico
  rm -f "$OUTPUT/$APP_NAME/app/app.ico"
fi

# 管理员权限清单: 用户双击 exe 时会自动弹出 UAC 请求
cp "$PROJECT_DIR/scripts/NToE-Fish.exe.manifest" "$OUTPUT/$APP_NAME/NToE-Fish.exe.manifest"

# 使用说明
cp "$PROJECT_DIR/scripts/使用说明.txt" "$OUTPUT/$APP_NAME/使用说明.txt"

# 清理临时目录
rm -rf "$STAGING"

echo ""
echo "=== 完成 ==="
echo "输出目录: $OUTPUT/$APP_NAME/"
echo "可执行文件: $OUTPUT/$APP_NAME/$APP_NAME.exe"
echo "文件大小:"
du -sh "$OUTPUT/$APP_NAME/"
