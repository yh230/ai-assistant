@echo off
rem 启动 Nacos 单机模式（控制台 http://localhost:8849，服务端口 8848）
rem 前置：设置 JAVA_HOME 指向 JDK 17；设置 NACOS_HOME 指向 Nacos 3.x 安装目录
setlocal

if not defined JAVA_HOME (
    echo [ERROR] 未设置 JAVA_HOME，请指向 JDK 17 后重试。
    exit /b 1
)
if not defined NACOS_HOME (
    echo [ERROR] 未设置 NACOS_HOME，请指向 Nacos 3.x 安装目录（包含 bin\startup.cmd）。
    echo         例如：setx NACOS_HOME "D:\tools\nacos"
    exit /b 1
)
if not exist "%NACOS_HOME%\bin\startup.cmd" (
    echo [ERROR] 未找到 %NACOS_HOME%\bin\startup.cmd，请检查 NACOS_HOME。
    exit /b 1
)

cd /d "%NACOS_HOME%\bin"
call startup.cmd -m standalone
pause
