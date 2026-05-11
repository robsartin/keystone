# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
COPY checkstyle.xml ./
RUN ./mvnw -B -q -DskipTests package spring-boot:repackage
RUN java -Djarmode=tools -jar target/keystone-0.1.0-SNAPSHOT.jar extract --layers --launcher

FROM eclipse-temurin:25-jre
WORKDIR /app
ENV SPRING_PROFILES_ACTIVE=default \
    JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=75"
RUN useradd --system --uid 1001 --create-home --shell /usr/sbin/nologin keystone \
    && mkdir -p /app && chown -R keystone:keystone /app
USER keystone
COPY --from=build --chown=keystone:keystone /workspace/keystone-0.1.0-SNAPSHOT/dependencies/ ./
COPY --from=build --chown=keystone:keystone /workspace/keystone-0.1.0-SNAPSHOT/spring-boot-loader/ ./
COPY --from=build --chown=keystone:keystone /workspace/keystone-0.1.0-SNAPSHOT/snapshot-dependencies/ ./
COPY --from=build --chown=keystone:keystone /workspace/keystone-0.1.0-SNAPSHOT/application/ ./
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
