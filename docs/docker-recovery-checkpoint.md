# Docker 恢复检查点（2026-09-06）

## 当前状态：未修复完成

- 用户明确确认恢复 Docker DNS `1.1.1.1`、`8.8.8.8` 与原镜像源 `https://docker.xuanyuan.me`；已读取 daemon.json 核对。
- Docker 启动在 `run/sailor-ingest.sock` 失败。先报 AF_UNIX 10048，后来报 mkdir 目录已存在，但目录枚举和文件检查均找不到 run。
- 唯一临时路径的 Windows AF_UNIX bind/listen 测试通过；固定 Docker 路径测试失败。因此不能将启动失败归因于 DNS。
- 尝试保留旧 run 为 `run-before-socket-fix-20260906-193630` 后未解决；不要继续清理数据。
- 日志有恢复出厂设置记录，来源不确定；不能宣称数据卷完整。约 50GB docker_data.vhdx 仍存在，须引擎恢复后核对卷与容器。
- 已退出失败的 Docker 进程，建议用户保存工作后重启 Windows，释放可能的系统残留文件状态。未主动重启 Windows。

## 重启后顺序

1. 启动 Docker，读取最新日志；确认引擎可用，禁止再次恢复出厂设置或删除磁盘。
2. 核对原容器、卷、Kubernetes context；必要时先备份数据，再恢复缺失配置。
3. 保留已确认 DNS、镜像源，分别实测代理开启与关闭时引擎运行、镜像获取；记录网络限制，不能保证外部服务永久可达。
4. 修复并验证 Kubernetes 到 Compose 的独立内部连接，不依赖 Windows 中指向 VMware 网卡的 host.docker.internal 映射；所有方案先探测再应用。
5. 重新运行中间件验收，再恢复 Identity 部署主线。

两个验收目标均尚未完成。不要把配置恢复当作 Docker 网络或 Pod 连通验收通过。
