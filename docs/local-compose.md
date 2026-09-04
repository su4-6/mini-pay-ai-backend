# 当前本地容器环境

根目录 `docker-compose.yml` 是统一入口：它复用创建当前 MiniPay 容器的 `compose.yaml`，并补入原先通过单独命令启动的 yshop JAR 与外卖 H5。

## 事实来源

- Docker 容器标签显示，MySQL、Redis、RabbitMQ、Seata 和 8 个 Java 服务均由根目录 `compose.yaml` 创建。
- 8 个 Java 容器内只包含 `/app/app.jar`，没有 `.java` 源码。
- 容器内 8 个 JAR 与本地各模块 `target` JAR 的 SHA-256 已逐一核对并完全一致，因此不存在只保存在镜像里的 Java 修改。
- yshop JAR 与外卖 H5 本来就是宿主机目录的只读挂载，本地文件是唯一源码/构建产物来源。

## 使用方式

仅启动中间件：

```bash
docker compose -f docker-compose.yml up -d mysql redis rabbitmq seata-server
```

构建并启动 Java 服务及中间件：

```bash
docker compose -f docker-compose.yml --profile apps up -d --build
```

本地修改 Java 后重新构建对应服务，例如：

```bash
docker compose -f docker-compose.yml --profile apps up -d --build commerce-service
```

yshop 使用本地 JAR 挂载，先在宿主机打包，再重启容器：

```bash
./mvnw -B -ntp -f ../yshop-drink/yshop-drink-boot3/pom.xml -pl yshop-server -am -DskipTests package
docker compose -f docker-compose.yml restart yshop-bridge
```

查看状态和日志：

```bash
docker compose -f docker-compose.yml ps
docker compose -f docker-compose.yml logs -f
```

停止不会删除数据卷：

```bash
docker compose -f docker-compose.yml down
```

不要在日常开发中使用 `down -v`，它会删除 MySQL、Redis 和 RabbitMQ 的本地数据。

## 当前机器迁移提示

当前的 `minipay-yshop-bridge` 和 `minipay-food-h5` 是用单独 Docker 命令创建的，尚不带 Compose 标签。在它们仍运行时直接首次启动统一 Compose，会因容器同名而拒绝创建，但不会覆盖或删除现有容器。需要迁移到 Compose 管理时，应先确认本地 JAR/H5 产物和数据，再只替换这两个无状态容器。

真实模型 Key、云 AK/SK、生产密码和证书不得写入 Compose；继续通过被 Git 忽略的 `.env` 提供。
