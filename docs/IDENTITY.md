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
| 正式签名 | 别名 `angelaclash`。证书 SHA-256 见 [SIGNING.md](SIGNING.md)。不是调试证书，也不是上游钥匙 |
| 姐妹应用 | AngelaBox `io.chainbox.app`，可并存 |

## 内核

| 项 | 值 |
|---|---|
| 仓库 | [dukangalex/mihomo](https://github.com/dukangalex/mihomo) 分支 `chain-dev` |
| 当前基线 | 官方稳定 tag `v1.19.33` @ `3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8`（与官方同一 commit） |
| 版本来源 | `version.properties` 的 `KERNEL_TAG` / `KERNEL_COMMIT`，文档不重复硬编码 |
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
