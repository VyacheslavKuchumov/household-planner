FROM sbtscala/scala-sbt:eclipse-temurin-21_1.x AS build
WORKDIR /workspace
COPY build.sbt ./
COPY project ./project
COPY backend ./backend
RUN sbt "backend/Universal/stage"

FROM eclipse-temurin:21-jre-alpine
WORKDIR /opt/app
RUN apk add --no-cache su-exec && addgroup -S app && adduser -S -G app app
COPY --from=build --chown=app:app /workspace/backend/target/universal/stage/ ./
COPY --chmod=755 backend/entrypoint.sh /opt/app/entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["/opt/app/entrypoint.sh"]
