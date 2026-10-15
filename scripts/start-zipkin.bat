@echo off
rem 启动 Zipkin（链路追踪展示后端）：优先读 ZIPKIN_JAR 环境变量，其次脚本目录 zipkin-server-*.jar
setlocal
if not "%ZIPKIN_JAR%"=="" (
  if exist "%ZIPKIN_JAR%" (
    java -jar "%ZIPKIN_JAR%"
    goto :end
  )
)
for %%f in ("%~dp0zipkin-server-*.jar") do (
  java -jar "%%f"
  goto :end
)
echo zipkin-server jar not found.
echo Download it to this folder or set ZIPKIN_JAR, see: https://zipkin.io/pages/quickstart.html
:end
