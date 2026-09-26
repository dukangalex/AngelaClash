# Angela Clash 身份表

和 [AngelaBox](https://github.com/dukangalex/AngelaBox) 同一套仓库规则：`main` 只留官方，产品差异只活在 overlay 分支。不改写 git 历史。

## 产品

| 项 | 值 |
|---|---|
| 产品名 | Angela Clash |
| 不得使用的产品名 | mihomo、Clash Meta、Meta |
| 许可 | GPL-3.0（继承上游）。下游产品名不得包含 `mihomo` |
| 与官方关系 | 独立衍生，不冒充 MetaCubeX / CMFA |

## Android

| 项 | 值 |
|---|---|
| 包名 | `io.chainbox.clash` |
| 不得使用 | `com.github.metacubex.clash.meta`（官方包名，不能互相覆盖） |
| 内部代码包 | `com.github.kr328.clash`（上游遗留，不整包重命名） |
| 安装包 | `Angela-Clash-<version>.apk` |
| 仓库 | [dukangalex/AngelaClash](https://github.com/dukangalex/AngelaClash) 分支 `dev` |
| 上游 | `MetaCubeX/ClashMetaForAndroid`，单向 merge 进 `dev` |
| 版本 | `version.properties` 的 `VERSION_NAME` / `VERSION_CODE` |
| 姐妹应用 | AngelaBox `io.chainbox.app`，可并存 |

## 内核

| 项 | 值 |
|---|---|
| 仓库 | [dukangalex/mihomo](https://github.com/dukangalex/mihomo) 分支 `chain-dev` |
| 当前 tag | `v1.19.31-chain.1`（`4e6f2eefbb2ae4e130f15f36c13fa6af7ce6545e`） |
| 基线 | 官方 Alpha `f103639c808d93a2c34cae56757b458862871b22`（含 v1.19.31） |
| 上游 | `MetaCubeX/mihomo` 的 **`Alpha`** |
| 禁止 | 把 `main` merge 进 `chain-dev`（`main` 不是内核） |
| Go module | 保持 `github.com/metacubex/mihomo`，否则 Android JNI 对不上 |

## 仓库拓扑

| 仓库 | 分支 | 职责 |
|---|---|---|
| dukangalex/mihomo | `chain-dev` | 内核跟踪。`main` 不动 |
| dukangalex/AngelaClash | `dev` | Android 客户端。`main` 只进官方 |
| dukangalex/AngelaBox | `dev` | sing-box 客户端，不是本产品 |
| dukangalex/sing-box | `chain-dev` | sing-box 内核，不是本内核 |
| dukangalex/mihomo-core | `main` | 已停用的说明仓 |
