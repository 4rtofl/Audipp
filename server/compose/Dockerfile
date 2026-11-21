FROM docker.io/library/eclipse-temurin:17.0.21_9-jre-noble@sha256:0622c0abe931a737f8c2d9f08113e8148af287254aafae69eff5393fe96de3d7
WORKDIR /opt/audipp
COPY . .
USER 10001:10001
ENTRYPOINT ["/opt/audipp/bin/audipp-server"]
