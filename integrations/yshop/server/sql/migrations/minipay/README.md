# MiniPay 增量脚本

先导入项目原始 `sql/yixiang-drink-open.sql`，再按文件名顺序执行本目录脚本。
这些脚本只新增 MiniPay 集成表，不修改原始 SQL 转储。

生产部署应由发布流水线记录已执行版本；不要在多个实例启动时并发执行。

`V003` 增加 10 分钟地址位置草稿。生产环境需通过
`YSHOP_MINIPAY_AMAP_WEB_KEY` 配置高德 Web 服务逆地理编码 Key；
`YSHOP_MINIPAY_ALLOW_GENERIC_ADDRESS=true` 仅供本地演示，不应在生产启用。

本地真机联调可在仓库根目录执行：

```powershell
.\scripts\configure-minipay-demo-store.ps1 -Latitude <纬度> -Longitude <经度>
```

脚本仅修改本地数据库的测试门店坐标，不属于版本化生产迁移。
