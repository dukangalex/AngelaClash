# Angela Clash

**Angela Clash** 是基于 [mihomo](https://github.com/MetaCubeX/mihomo)（Clash Meta）内核的 Android 代理客户端。官方内核保持完整。链式出站沿用官方 `dialer-proxy`，不另做协议栈。独立的入口/落地界面还没做，见 [docs/USER_GUIDE.md](docs/USER_GUIDE.md)。

本项目与 MetaCubeX 及官方 Clash Meta for Android 无从属或授权关系，不得使用官方名称及标志进行商业发布或应用商店上架。

- 发行版：[Releases](https://github.com/dukangalex/ClashMetaForAndroid/releases)
- 构建：[Actions](https://github.com/dukangalex/ClashMetaForAndroid/actions)
- 使用说明：[docs/USER_GUIDE.md](docs/USER_GUIDE.md)
- 身份表：[docs/IDENTITY.md](docs/IDENTITY.md)
- 维护说明：[docs/MAINTENANCE.md](docs/MAINTENANCE.md)
- 姐妹产品（sing-box）：[dukangalex/AngelaBox](https://github.com/dukangalex/AngelaBox)

## 项目标识

| 项目 | 值 |
|------|-----|
| 应用名称 | Angela Clash |
| 应用包名 | `io.chainbox.clash` |
| 客户端仓库 | [dukangalex/ClashMetaForAndroid](https://github.com/dukangalex/ClashMetaForAndroid)（分支 `dev`） |
| 内核仓库 | [dukangalex/mihomo](https://github.com/dukangalex/mihomo)（分支 `chain-dev`，不是 `main`） |
| 更新检查 | 仅本仓库 GitHub Releases |
| 安装包 | `Angela-Clash-*.apk` |

内部 Java/Kotlin 包名仍为 `com.github.kr328.clash`（上游遗留），不对外、不整包重命名，以便继续合并官方提交。

## 上游内核

| 项目 | 值 |
|------|-----|
| 官方上游 | [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo) 分支 **Alpha**。不要跟踪它的 `main` |
| 本项目内核 | [dukangalex/mihomo](https://github.com/dukangalex/mihomo) 分支 **`chain-dev`**，tag `v1.19.31-chain.1` |
| 已同步基线 | 官方 **Alpha** `f103639`（2026-09-25，已包含 tag **v1.19.31** 及其后的 Alpha 提交） |
| 客户端版本 | 见 `version.properties` 的 `VERSION_NAME`（当前 **1.0.1**） |

本 fork 的 `main` 不是内核，那是上游默认分支上的另一份内容。内核只在 `chain-dev`。

### 同步更新策略

1. **跟随官方，不替换内核。** 在官方 mihomo 之上提供模块化链式出站与普通用户界面。
2. **内核：** `git fetch upstream Alpha`，merge 进 `dukangalex/mihomo` 的 `chain-dev`。禁止 merge `main`。只解决与链式覆盖层和身份文件相关的冲突。
3. **App：** `git fetch` 官方 `MetaCubeX/ClashMetaForAndroid`，merge 进本仓库 `dev`。冲突以 Angela Clash 为准（包名、显示名、组链、更新检查、发版说明）。
4. **Fail Closed：** 链路失败必须报错并停止启动，不得静默落到 DIRECT。
5. **先验证再合入。** 官方新版本发布后，先把链式出站与运行时覆盖做稳，再合入更新的官方提交。
6. **功能范围。** 本项目增加的能力只为降低日常操作成本，不改变官方配置模型。

细节见 [docs/MAINTENANCE.md](docs/MAINTENANCE.md)。

## 架构

官方 mihomo 内核保持完整。链式出站优先使用官方 `dialer-proxy`：现在写在配置里，之后才会在启动时改运行时配置。不改订阅文件，不替换内核。独立组链界面尚未提供。

```
设备 → 入口节点 → 落地节点 → 目的站
```

## 构建

1. `git submodule update --init --recursive`
2. 安装 OpenJDK 21、Android SDK、CMake、Golang
3. 在项目根目录创建 `local.properties`（`sdk.dir=...`）与 `signing.properties`
4. `./gradlew app:assembleMetaRelease`

产物文件名以 `Angela-Clash-` 开头。

可选 `local.properties`：

```properties
custom.application.id=io.chainbox.clash
remove.suffix=true
```

## 致谢

Angela Clash 建立在上游开源工作之上：

- [mihomo](https://github.com/MetaCubeX/mihomo)，由 MetaCubeX 维护的 Clash Meta 内核
- [Clash Meta for Android](https://github.com/MetaCubeX/ClashMetaForAndroid)，本客户端的上游界面与服务框架

上述致谢不构成从属、授权或官方认可。

## 许可

本仓库继承上游 [GPL-3.0](LICENSE)。上游代码版权归属原作者。Angela Clash 为独立衍生工作，不代表上游项目。
