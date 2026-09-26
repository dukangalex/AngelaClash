# Angela Clash 维护说明

Angela Clash 是独立客户端，不是官方 Clash Meta / CMFA / mihomo 的产品名。
维护目标：内核长期跟随官方 MetaCubeX/mihomo；App 只维护组链体验、运行时覆盖与发布。

姐妹产品：[AngelaBox](https://github.com/dukangalex/AngelaBox)（sing-box 内核）。两套产品共用同一套产品边界，内核互不等价。

## 产品边界（必须遵守）

Angela Clash = 官方 mihomo 内核 + **模块化链式出站覆盖层** + 面向普通用户的操作界面。

- 不重新设计 mihomo，不替换内核，不另做代理协议栈。
- 组链优先使用官方 `dialer-proxy` / 出站嵌套能力，在导入或启动时改运行时配置，不改订阅原文。
- 冲突即停：与官方配置模型无法兼容时停止发版，而不是在内核里开特例。
- 增加的功能只为降低日常操作成本，不为单一订阅商或个人配置定制。
- Fail Closed：链路失败不得静默落到 DIRECT。

## 仓库分工

| 仓库 | 分支 | 职责 |
|------|------|------|
| [dukangalex/mihomo](https://github.com/dukangalex/mihomo) | `chain-dev` | Clash 内核。`main` 保持上游默认分支，不要在上面开发，也不要把它 merge 进来 |
| [dukangalex/AngelaClash](https://github.com/dukangalex/AngelaClash) | `dev` | Angela Clash Android 客户端。`main` 只保留官方 |
| [dukangalex/mihomo-core](https://github.com/dukangalex/mihomo-core) | `main` | 旧的空说明仓，已停用。内核以 `dukangalex/mihomo` 的 `chain-dev` 为准 |

| 项目 | 值 |
|------|-----|
| 应用名 | Angela Clash |
| 包名 | `io.chainbox.clash` |
| 更新源 | 仅本仓库 Releases |
| 内部代码包 | `com.github.kr328.clash`（上游遗留，不对外、不整包重命名） |
| 姐妹应用包名 | `io.chainbox.app`（AngelaBox / sing-box） |

## 对外身份

- 对外产品名、README、About、Release、APK 文件名都是 Angela Clash。
- App 帮助与损坏页的 GitHub 链接指向 `dukangalex/AngelaClash`。
- 不走 F-Droid / 官方 MetaCubeX 更新源。
- 不得用官方名称或标志上架应用商店。
- 不整包重命名 `com.github.kr328.clash`，以免失去与上游合并的能力。

## 内核同步

官方上游：`https://github.com/MetaCubeX/mihomo`

CMFA 上游文档指定内核来自 `Alpha`（主线）与 `android-open` 合并后的 `android-real`。本项目当前对齐官方 **Alpha** `f103639`（含 **v1.19.31**），客户端子模块指向 `v1.19.31-chain.1`（`4e6f2eef`）。

1. 只把官方 `Alpha` merge 进 `dukangalex/mihomo` 的 `chain-dev`。
2. 只解决与链式出站覆盖层、`ANGELABOX.md`、`Makefile` 的 `chain-dev` 版本行相关的冲突。
3. 子模块 `core/src/foss/golang/clash` 指向 `https://github.com/dukangalex/mihomo.git` 的 `chain-dev`。合入内核后同步 `core/src/foss/golang` 与 `core/src/main/golang` 的 `go.mod` / `go.sum`。
4. 发版记录 `version.properties` 里的内核 commit SHA。
5. 禁止 merge 上游或本 fork 的 `main`。那条分支不是内核。

```bash
git clone -b chain-dev https://github.com/dukangalex/mihomo.git
cd mihomo
git remote add upstream https://github.com/MetaCubeX/mihomo.git
git fetch upstream Alpha
git merge upstream/Alpha
```

## App 同步

官方上游：`https://github.com/MetaCubeX/ClashMetaForAndroid`

```bash
git remote add upstream https://github.com/MetaCubeX/ClashMetaForAndroid.git
git fetch upstream
git checkout dev
git merge upstream/main
```

冲突时以 Angela Clash 为准：包名、显示名、组链、更新链接、`version.properties`、本目录文档。

## 发版

1. 改 `version.properties`（`VERSION_NAME` 与 tag 一致，`VERSION_CODE` 必须递增）。
2. 使用现有 Actions 构建 meta/alpha Release，产物按 `Angela-Clash-*` 命名。
3. 发版说明必须包含：内核 commit SHA、官方基线分支、是否启用链式覆盖层。

Secrets 与上游相同：签名仓库的 `signing.properties` / keystore。

## 能力边界

- 支持：官方 Mihomo 协议与规则；通过 `dialer-proxy` 做入口→落地。
- 规划中：跨配置选择落地、订阅更新后保持链路、仪表路径显示（对齐 AngelaBox）。
- 不支持冒充官方；不向官方仓库提交 AngelaBox 产品补丁。
