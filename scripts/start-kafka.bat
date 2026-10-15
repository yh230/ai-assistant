@echo off
rem 启动 Kafka（KRaft 单机）：探测 KAFKA_HOME 或脚本目录 kafka_* 子目录
setlocal enabledelayedexpansion
set "KH=%KAFKA_HOME%"
if "%KH%"=="" (
  for /d %%d in ("%~dp0kafka_*") do set "KH=%%d"
)
if "%KH%"=="" (
  echo Kafka not found. Download 3.x+ from https://kafka.apache.org/downloads
  echo Unzip here as kafka_2.13-3.x.y or set KAFKA_HOME.
  goto :end
)
if not exist "%KH%\config\kraft\server.properties" (
  echo kafka storage format required once...
  for /f %%u in ('"%KH%\bin\windows\kafka-storage.bat" random-uuid') do set "UUID=%%u"
  call "%KH%\bin\windows\kafka-storage.bat" format -t !UUID! -c "%KH%\config\kraft\server.properties"
)
call "%KH%\bin\windows\kafka-server-start.bat" "%KH%\config\kraft\server.properties"
:end
