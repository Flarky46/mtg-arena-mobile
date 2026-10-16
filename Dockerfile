# Builds Forge from a pinned commit, then the Tabletop server that links it.
# Forge's install step leaves the literal ${revision} in its poms; the sed
# below is what makes those jars resolvable.
FROM maven:3.9.9-eclipse-temurin-21 AS forge
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
ARG FORGE_SHA=733a221e35613ec146ae4573df5406661c71c6f3
RUN git init \
    && git remote add origin https://github.com/Card-Forge/forge.git \
    && git fetch --depth 1 origin ${FORGE_SHA} \
    && git checkout --detach FETCH_HEAD
RUN mvn -DskipTests -pl forge-gui -am install \
    && find /root/.m2/repository/forge -name '*.pom' -exec sed -i 's/\${revision}/2.0.16-SNAPSHOT/g' {} +

FROM maven:3.9.9-eclipse-temurin-21 AS app
COPY --from=forge /root/.m2 /root/.m2
WORKDIR /src
COPY server /src/server
RUN mvn -f /src/server/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig libfreetype6 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=forge /src/forge-gui /opt/forge-gui
COPY --from=app /src/server/target/tabletop-1.0.0.jar /opt/tabletop/tabletop.jar
COPY --from=app /src/server/target/lib /opt/tabletop/lib
COPY web /opt/tabletop/web
ENV PORT=8080 \
    HOME=/data \
    FORGE_ASSETS=/opt/forge-gui/ \
    WEB_ROOT=/opt/tabletop/web \
    DATA_DIR=/data
EXPOSE 8080
WORKDIR /opt/tabletop
CMD ["java", "-Djava.awt.headless=true", "-Xmx2g", \
     "--add-opens", "java.base/java.lang=ALL-UNNAMED", \
     "--add-opens", "java.base/java.time=ALL-UNNAMED", \
     "--add-opens", "java.base/java.text=ALL-UNNAMED", \
     "--add-opens", "java.base/java.util=ALL-UNNAMED", \
     "--add-opens", "java.base/java.util.regex=ALL-UNNAMED", \
     "--add-opens", "java.base/java.util.stream=ALL-UNNAMED", \
     "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED", \
     "--add-opens", "java.desktop/javax.imageio.spi=ALL-UNNAMED", \
     "-cp", "tabletop.jar:lib/*", "app.mtg.Main"]
