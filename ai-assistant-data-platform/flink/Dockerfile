FROM flink:1.19-scala_2.12-java17
WORKDIR /opt/flink/usrlib
COPY jobs/ /opt/flink/usrlib/jobs/
COPY sql/ /opt/flink/usrlib/sql/
# 本轮不构建、不启动；仅保留可部署镜像定义。
