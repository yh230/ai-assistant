@echo off
rem 一键启动后端：五业务服务 + 网关（每个服务独立窗口；需先启动 Nacos 并建好四个库）
set JAVA_HOME=C:\kaifa\jdk17
set MVN=C:\kaifa\apache-maven-3.9.16\bin\mvn.cmd
cd /d %~dp0..\ai-assistant-backend

start "auth-service(9200)"        cmd /k "%MVN% -pl services/auth-service -am spring-boot:run"
start "system-service(9201)"      cmd /k "%MVN% -pl services/system-service -am spring-boot:run"
start "hospital-service(9202)"    cmd /k "%MVN% -pl services/hospital-service -am spring-boot:run"
start "registration-service(9203)" cmd /k "%MVN% -pl services/registration-service -am spring-boot:run"
start "ai-chat-service(9204)"     cmd /k "%MVN% -pl services/ai-chat-service -am spring-boot:run"
start "gateway(8080)"             cmd /k "%MVN% -pl gateway -am spring-boot:run"
echo 已启动全部后端进程（各服务独立窗口），请等待日志出现 Started 后再访问网关 http://localhost:8080
pause
