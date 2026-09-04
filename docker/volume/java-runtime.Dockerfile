FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY docker/volume/java-volume-entrypoint.sh /usr/local/bin/minipay-java-entrypoint
RUN chmod 0755 /usr/local/bin/minipay-java-entrypoint
ENTRYPOINT ["/usr/local/bin/minipay-java-entrypoint"]
