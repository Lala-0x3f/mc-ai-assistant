@echo off
echo 开始构建 MC AI Assistant 插件...

REM 检查 Maven 是否可用
mvn --version >nul 2>&1
if %errorlevel% neq 0 (
    echo 错误: Maven 未安装或不在 PATH 中
    echo 请确保 Maven 已正确安装并添加到系统 PATH
    pause
    exit /b 1
)

echo Maven 已找到，开始构建...

REM 清理并构建项目
mvn clean package

if %errorlevel% equ 0 (
    echo.
    echo 构建成功！
    echo 插件文件位于: target\mc-ai-assistant-1.0.0.jar
    echo.
    echo 安装说明:
    echo 1. 将 JAR 文件复制到 PaperMC 服务器的 plugins 目录
    echo 2. 重启服务器
    echo 3. 编辑 plugins\McAiAssistant\config.yml 配置文件
    echo 4. 设置您的 API 密钥和其他配置
) else (
    echo.
    echo 构建失败！请检查错误信息。
)

pause
