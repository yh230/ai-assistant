@echo off
rem 一键启动后端：五业务服务 + 网关（每个服务独立窗口；需先启动 Nacos 并建好四个库）
rem 前置：设置 JAVA_HOME 指向 JDK 17；Maven 使用仓库自带 mvnw.cmd，找不到时回退 PATH 中的 mvn
setlocal

if not defined JAVA_HOME (
    echo [ERROR] 未设置 JAVA_HOME，请指向 JDK 17 后重试。
    echo         例如：setx JAVA_HOME "C:\Program Files\Java\jdk-17"
    exit /b 1
)

cd /d "%~dp0..\ai-assistant-backend"

if exist "mvnw.cmd" (
    set "MVN=%CD%\mvnw.cmd"
) else (
    where mvn >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] 未找到 Maven。请安装 Maven 3.9+ 并加入 PATH，或保留仓库自带的 mvnw.cmd。
        exit /b 1
    )
    set "MVN=mvn"
)

start "auth-service(9200)"         cmd /k ""%MVN%" -pl services/auth-service -am spring-boot:run"
start "system-service(9201)"       cmd /k ""%MVN%" -pl services/system-service -am spring-boot:run"
start "hospital-service(9202)"     cmd /k ""%MVN%" -pl services/hospital-service -am spring-boot:run"
start "registration-service(9203)" cmd /k ""%MVN%" -pl services/registration-service -am spring-boot:run"
start "ai-chat-service(9204)"      cmd /k ""%MVN%" -pl services/ai-chat-service -am spring-boot:run"
start "gateway(8080)"              cmd /k ""%MVN%" -pl gateway -am spring-boot:run"
echo 已启动全部后端进程（各服务独立窗口），请等待日志出现 Started 后再访问网关 http://localhost:8080
pause
