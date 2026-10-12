@echo off
rem 启动 Nacos 单机模式（控制台 http://localhost:8849，服务端口 8848）
set JAVA_HOME=C:\kaifa\jdk17
cd /d C:\kaifa\nacos\bin
call startup.cmd -m standalone
pause
