FROM python:3.13-alpine

WORKDIR /app
COPY server.py /app/server.py
RUN addgroup -S callback && adduser -S -G callback callback \
    && mkdir -p /data \
    && chown -R callback:callback /data /app/server.py \
    && chmod 500 /app/server.py

USER callback
EXPOSE 8099
CMD ["python", "/app/server.py"]
