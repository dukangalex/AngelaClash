# Angela Clash 维护说明

Angela Clash 是独立客户端，不是官方 Clash Meta / CMFA / mihomo 的产品名。
维护目标：内核长期跟随官方 MetaCubeX/mihomo；App 只维护组链体验、运行时覆盖与发布。

姐妹产品：[AngelaBox](https://github.com/dukangalex/AngelaBox)（sing-box 内核）。两套产品共用同一套产品边界，内核互不等价。

## 产品边界（必须遵守）

Angela Clash = 官方 mihomo 内核 + **模块化链式出站覆盖层** + 面向普通用户的操作界面。

- 不重新设计 mihomo，不替换内核，不另做代理协议栈。
- 组链优先使用官方 `dialer-proxy`。首页「链式代理」把入口和落地显式列出来（分组 / 节点 / 订阅，落地可跨配置）。选择存在本机，启动时写成 `chain.json`，不改订阅原文。
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

正式 App 只以 Mihomo 已发布的稳定标签为基线。当前官方基线是 `v1.19.32` @ `88dcbf7f1614a67c3b36b848ee3592dfa92ada36`；App 子模块固定在 fork tag `v1.19.32-chain.1` @ `64cf6238976881dbf950f80b96df9d77caa435b2`。fork 的 `chain-dev` 可保留官方 Alpha 在该稳定 tag 之后的 4 个提交（用于开发/测试），但 App 不得指向包含这些提交的分支头。

1. 稳定 App 更新从上一个稳定 fork tag 建立独立同步分支，再 merge 明确选定的官方 Mihomo 正式发布 tag；不要从含 Alpha 提交的 `chain-dev` 头创建稳定 App tag。
2. 只解决与链式出站覆盖层、`ANGELABOX.md`、`Makefile` 版本行相关的冲突；验证后给稳定合并 commit 创建 fork tag。
3. App 子模块 `core/src/foss/golang/clash` 必须指向 fork 的稳定 tag 与精确 commit SHA，不能跟随 `chain-dev` 分支头。`chain-dev` 可继续保留 Alpha 开发/测试提交；如需集成稳定更新，再将已验证的稳定线合入 `chain-dev`。
4. 依照 App 子模块的稳定 commit 同步 `core/src/foss/golang` 与 `core/src/main/golang` 的 `go.mod` / `go.sum`。
5. `version.properties` 记录 fork 稳定 tag、精确 commit SHA 和官方基线 tag 的 commit SHA。
6. 禁止 merge 上游或本 fork 的 `main`。那条分支不是内核。

```bash
git clone https://github.com/dukangalex/mihomo.git
cd mihomo
git remote add upstream https://github.com/MetaCubeX/mihomo.git
git fetch origin refs/tags/v1.19.31-chain.1:refs/tags/v1.19.31-chain.1
git checkout -b sync/stable-v1.19.32 v1.19.31-chain.1
git fetch upstream refs/tags/v1.19.32:refs/tags/v1.19.32
git merge --no-ff v1.19.32
# 验证稳定合并 commit 后，再创建 v1.19.32-chain.1；不要从 Alpha 分支头打稳定 tag。
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
2. 使用现有 Actions 构建 meta/alpha Release，产物按 `Angela-Clash-*` 命名。正式包必须用 `docs/SIGNING.md` 里的那把钥匙，禁止退回调试证书。
3. 发版说明必须包含：内核 commit SHA、官方基线分支、是否启用链式覆盖层、以及 APK 证书 SHA-256 是否仍是签名文档里的那一串。

GitHub secrets：`SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。钥匙和 `signing.properties` 不进仓库。

## 能力边界

- 支持：官方 Mihomo 协议与规则；通过 `dialer-proxy` 做入口→落地。
- 规划中：跨配置选择落地、订阅更新后保持链路、仪表路径显示（对齐 AngelaBox）。
- 不支持冒充官方；不向官方仓库提交 AngelaBox 产品补丁。
