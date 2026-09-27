# Angela Clash 更新说明

## 1.0.5

- 客户端 `1.0.5`（`10005`）
- 内核仍是 `chain-dev` @ `fd6cf5e`
- 证书 SHA-256 不变：`82:D0:DB:00:7C:5E:AF:C3:7E:12:6B:4F:DC:16:66:0D:7C:F2:43:4C:7A:15:E6:10:F4:B4:3F:D6:42:69:43:DC`

首页改成卡片网格和悬浮底栏，默认深色。代理页右上角进入规则开关。更多页按查看、设置、其他分组。系统显式选项仍在首页，优先级不变。

## 1.0.4


- 客户端 `1.0.4`（`10004`）
- 内核 [dukangalex/mihomo](https://github.com/dukangalex/mihomo) `chain-dev` @ `fd6cf5e`，官方基线 MetaCubeX/mihomo Alpha `f103639`（v1.19.31）
- 链式出站仍用官方 `dialer-proxy`
- 证书 SHA-256：`82:D0:DB:00:7C:5E:AF:C3:7E:12:6B:4F:DC:16:66:0D:7C:F2:43:4C:7A:15:E6:10:F4:B4:3F:D6:42:69:43:DC`

导入不再因为注释里的 `main` 或策略组引用了不存在的节点而失败。内置 HiClash 覆写脚本（原作者 AIsouler）。仪表盘上的系统显式选项在脚本和订阅之后生效，冲突时以开关为准。脚本入口和链式代理只在启用时出现。日志随内核打开。应用图标为透明底四色井号。

## 发版记录要求

本文件供发版工作流读取。发布说明应同时写明：

- 客户端 tag / VERSION_NAME
- 内核仓库与 commit SHA
- 官方上游分支（当前跟踪 MetaCubeX/mihomo Alpha）
