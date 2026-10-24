# AngelaClash 项目审计报告

| 项目 | 内容 |
|---|---|
| 审计对象 | [dukangalex/AngelaClash](https://github.com/dukangalex/AngelaClash) `dev` 分支 |
| 审计版本 | HEAD `0c4d5a6f4e268a2f193e0b3d95ae35412e652567`（2026-10-24），`version.properties` = `1.2.4` / `10204` |
| 发布产物 | GitHub Release `v1.2.4`（2026-10-24T10:48:06Z，`Angela-Clash-1.2.4-meta-arm64-v8a-release.apk`） |
| 上游基线 | `MetaCubeX/ClashMetaForAndroid` 2.11.36 + 内核 `MetaCubeX/mihomo` `v1.19.33` |
| 审计日期 | 2026-10-24 |
| 审计方法 | 源码全量比对上游、Go 静态检查与单元测试、发布 APK 独立验签与二进制内容核查、CI/发布流程审阅 |

---

## 一、结论摘要

**未发现后门、隐蔽外联、恶意代码或凭据窃取行为。** 这是一个明确标注了上游来源、内核未做修改、发布链路有证书固定与逐包验签的 CMFA（Clash Meta for Android）衍生客户端。就工程规范而言，它**明显优于**同类个人维护的 Clash 客户端分支。

主要风险不在"作者是否作恶"，而在三个方向：

1. **用户脚本执行缺少资源限制** —— 内置 goja JS 引擎，脚本在配置加载时同步执行，无超时、无栈上限、无中断机制，可导致内核配置加载永久挂起。
2. **"系统显式选项"与"自动修复"会静默改写订阅配置** —— 默认强制替换 DNS、把无效规则改指向备用组、把空分组填成 `DIRECT`，与文档宣称的 "Fail Closed" 存在语义落差，可能造成用户不知情的分流/隐私降级。
3. **构建与发布链路的第三方信任** —— 使用 MetaCubeX 自定义 Go 工具链、多个未固定 commit 的第三方 Action、构建期无校验地下载 geo 数据、依赖更新使用 `@latest`。


### 修复状态（2026-10-24 更新）

所有 P1、P2 条目已在 `dev` 上修复并生成补丁 `AngelaClash-security-fixes.patch`，逐条对应关系见仓库内 `docs/SECURITY_FIXES.md`。

| ID | 状态 | 说明 |
|---|---|---|
| F-01 | ✅ 已修复 | 脚本 5s 看门狗 + `SetMaxCallStackSize(1024)`；内核加载侧 180s 超时 |
| F-02 | ✅ 已修复 | async `main()` 按 Promise 状态取值/报错 |
| F-03 | ✅ 已修复 | DNS 选项改为"仅在订阅未配置时补默认值"；注释与执行顺序对齐 |
| F-04 | ✅ 部分修复 | 全部自动改写写入 `[repair]` 日志；严格模式留作后续开关 |
| F-05 | ✅ 已修复 | CI 改为 `go test -tags "foss with_gvisor cmfa" ./native/config/` |
| F-06 | ✅ 大部分修复 | Action 全部固定 SHA；`go install` 固定 commit；geo 数据加入 SHA-256 校验；Go 工具链仍沿用上游 |
| F-07 | ✅ 已修复 | release 显式开启 v1/v2/v3 签名 |
| F-08 | ✅ 已修复 | 先按设备 ABI 选包，universal 仅兜底（138MB→51MB） |
| F-09 | ✅ 已修复 | 端口/规则改精确匹配；入口检测改用 goja AST 解析 |
| F-10 | ✅ 已修复 | `allowBackup=false` + `data_extraction_rules` 排除云备份/迁移；新增应用内「加密备份/恢复」补回迁移能力 |
| F-11 | ➖ 保留 | 权限均为功能必需 |
| F-12 | ✅ 已修复 | 隐私政策重写；README/IDENTITY 与 `version.properties` 对齐 |
| F-13 | ➖ 保留 | ML Kit 离线可用，替换会伤害无 GMS 用户 |
| F-14 | ➖ 保留 | 继承上游，建议后续加"仅信任系统 CA"开关 |
| F-15 | ✅ 部分修复 | 文档已说明；UI 提示待排期 |
| F-16 | ✅ 已修复 | 记录同步脚本 SHA-256，内容变化时弹窗确认 |

### 风险矩阵

| ID | 风险 | 等级 | 影响对象 |
|---|---|---|---|
| F-01 | 用户脚本无超时/资源限制，可挂死配置加载 | 中 | 可用性 |
| F-02 | `async function main` 被判定为有效入口但必然失败 | 中 | 功能/兼容性 |
| F-03 | 系统选项默认强制覆写订阅 DNS，且执行顺序与注释不符 | 中 | 隐私/功能 |
| F-04 | repair 系列自动"修复"是 fail-open，静默改写规则 | 中 | 安全语义 |
| F-05 | CI 只测 chain.go，新增的脚本/修复逻辑测试从未执行 | 中 | 质量保证 |
| F-06 | 构建供应链：自定义 Go 工具链、未固定 SHA 的 Action、无校验的 geo 数据 | 中 | 供应链 |
| F-07 | 发布 APK 仅 v1+v2 签名，无 v3（不支持密钥轮换） | 低 | 签名生命周期 |
| F-08 | 更新器优先下载 138 MB universal 包；一天内 3 个版本连发 | 低 | 体验/流量 |
| F-09 | 规则匹配子串误判（`4430` 命中 QUIC、`ncnc` 命中 `cn`、字符串里的 `function main`） | 低 | 正确性 |
| F-10 | `allowBackup=true` 且备份包含 sharedpref/数据库/订阅目录 | 低 | 隐私 |
| F-11 | 权限面较宽（`QUERY_ALL_PACKAGES`、`REQUEST_INSTALL_PACKAGES` 等） | 低 | 权限 |
| F-12 | 隐私政策是上游模板、README/IDENTITY 版本漂移 | 低 | 文档可信度 |
| F-13 | 内置 Google ML Kit 条码模型（闭源，继承上游） | 信息 | 依赖透明度 |
| F-14 | 信任用户 CA（可本地 MITM） | 信息 | 网络 |
| F-15 | Play/GMS 默认绕过 VPN、默认境外 DoH | 信息 | 隐私预期 |
| F-16 | 脚本导入仅 HTTPS 但无完整性校验 | 信息 | 输入信任 |

---

## 二、审计方法与验证证据

### 2.1 源码层

- 克隆 `dev` 分支与上游 `MetaCubeX/ClashMetaForAndroid`（`1a4f949`，v2.11.36）做全量文件比对：**6,511 行差异（含增删）、88 个新增文件**，差异集中在 `app/`、`design/`、`service/`、`core/src/main/golang/native/`。
- 子模块 `core/src/foss/golang/clash` 指向 `dukangalex/mihomo#chain-dev`，**实际固定 commit `3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8`**。
- 该 SHA 在官方 `MetaCubeX/mihomo` 中**同样存在**（git 对象内容寻址），即：**发布构建使用的内核与官方 `v1.19.33` 完全同源同内容，未被改写**。这是本次审计最重要的正面结论。
- 子模块内的 `config/fetch.go`、`override.go`、`provider.go`、`age.go`、`defaults.go` 与上游逐字节一致（diff 为空）。

### 2.2 Go 代码静态检查与测试（本次实际执行）

```bash
# 使用真实构建标签做 vet（等价于 Gradle 的 golang 插件参数）
CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
  go vet -tags "foss with_gvisor cmfa" ./native/config/
# → 通过，无告警

# 全包单元测试（含 chain_test.go / script_override_test.go）
go test -tags "foss with_gvisor cmfa" ./native/config/
# → ok  cfa/native/config
```

> 说明：`dns/patch_android.go` 带 `//go:build android && cmfa`，在桌面 GOOS 下做单元测试需要临时补一个非 android 的空实现桩（仅本地测试用，测试后已删除，仓库无改动）。

**CI 的命令只编译了两个文件：**

```yaml
# .github/workflows/build-release.yaml:76
run: go test native/config/chain.go native/config/chain_test.go
```

`script_override_test.go`、`repair` 相关逻辑**从未在 CI 中被编译或执行**（见 F-05）。我本地用完整包路径运行，全部通过——说明这不是"坏代码"，而是"没有被保护的代码"。

### 2.3 参考脚本兼容性实测

把项目文档推荐的 [HiClash `Script/mihomoScript.js`](https://github.com/dukangalex/HiClash)（112 KB）交给 goja 执行：

```
took=23.6ms  err=<nil>  proxies=7 groups=31 rules=78
groups sample: [默认代理 手动选择 自动选择 负载均衡 故障转移 FCM YouTube Google Claude AI Microsoft ...]
```

→ **推荐的第三方脚本可以正常工作**，不存在"脚本功能整体不可用"的问题。

### 2.4 发布 APK 独立验证

| 项 | 结果 |
|---|---|
| 文件 | `Angela-Clash-1.2.4-meta-arm64-v8a-release.apk`，53,173,686 字节 |
| SHA-256 | `8fcf9aa7c47c089c5d54365fd7002ec9803d95ec92e5e0b2bbca5e35cd888497` |
| v1 (JAR) 签名 | 验证通过（keytool） |
| v2 签名 | 存在并通过（签名块 ID `0x7109871a`） |
| v3 / v3.1 签名 | **不存在** |
| 其它签名块 | `0x504b4453`（AGP dependency source stamp）、`0x42726577`（padding） |
| 证书主体 | `CN=Angela Clash, O=Angela Clash, C=US`，自签，RSA 2048，`SHA384withRSA` |
| 证书 SHA-256 | `82:D0:DB:00:7C:5E:AF:C3:7E:12:6B:4F:DC:16:66:0D:7C:F2:43:4C:7A:15:E6:10:F4:B4:3F:D6:42:69:43:DC` |
| 有效期 | 2026-09-26 → 2054-02-11 |
| 与 CI 固定值一致性 | ✅ 与 `build-release.yaml` 中的 `EXPECTED_CERT` 完全一致 |
| 与文档一致性 | ✅ 与 `docs/SIGNING.md` 声明的证书 SHA-256 完全一致 |
| 包内条目 | 833 个（`res` 718、`META-INF` 71、`assets` 9、`lib` 5、2 个 dex） |
| 原生库 | `libclash.so`(81 MB)、`libbridge.so`、`libbarhopper_v3.so`、`libimage_processing_util_jni.so`、`libsurface_util_jni.so` |
| 数据资产 | `ASN.mmdb`、`BundleMRS.7z`、`geoip.metadb`、`geosite.dat`、3 个 ML Kit `.tflite` 模型 |
| 分析/崩溃 SDK | **无**（dex 中 `firebase/analytics`、`crashlytics`、`messaging`、`sentry`、`umeng`、`bugly` 均为 0 命中） |
| dex 中的运行时外联地址 | 仅 `https://api.github.com/repos/dukangalex/AngelaClash/releases/latest`（其余为文档/商店类静态链接） |

这是"自证 + 交叉验证"的双重确认：CI 上传前用 `apksigner` 固定证书校验，我又用 `keytool` 独立读取了证书指纹，二者与文档一致。

---

## 三、详细发现

### F-01【中】用户脚本在无资源限制的 goja VM 中执行

**位置**：`core/src/main/golang/native/config/script_override.go:210-235`

```go
vm := goja.New()
if _, err = vm.RunString(patchedSrc); err != nil { ... }
```

**问题**：

- 没有 `vm.Interrupt()` 超时中断、没有 `runtime.SetMaxCallStackSize`、没有内存/结果体积限制；
- 脚本在任何位置（顶层或 `main()` 内）出现 `while(true){}` 或超大字符串扩张，都会让 `config.Load()` 永不返回；
- `Clash.load()` 在 `ConfigurationModule` 中 `await()`，无超时兜底，VPN 服务会卡在"加载中"状态。

**影响面**：脚本是用户主动导入/编辑的内容（也可来自用户信任的第三方脚本 URL），因此不是远程可利用漏洞，而是**可用性缺陷 + 恶意/劣质脚本的拒绝服务面**。

**建议**：

```go
time.AfterFunc(3*time.Second, func() { vm.Interrupt("script timeout") })
vm.SetMaxCallStackSize(1024)
```

并在 Kotlin 侧给 `Clash.load().await()` 加超时，超时后明确提示并停止服务。

---

### F-02【中】`async function main` 被识别为合法入口，但必然加载失败

**位置**：`script_override.go:47-56`（`hasScriptMain` 匹配 `main=async`）、`:231-235`

**实测**：

```
src = "async function main(config) { return config }"
→ hasScriptMain = true
→ 执行结果 err = "script main() must return an object"
```

goja 对 async 函数返回的是 Promise 对象，代码没有 await，直接 `val.Export()` 得到非 map，于是报错。也就是说：**一个写法正确、在其他 mihomo 客户端可用的 async 脚本，在这里会直接把订阅打成加载失败**，而报错文案（"必须返回对象"）会误导用户以为是自己脚本写错了。

**建议**：要么支持 async（检测 Promise 并驱动 goja 事件循环），要么在 `hasScriptMain` 里排除 async 并给出明确提示（"暂不支持 async main"）。

---

### F-03【中】"系统显式选项"默认强制覆写订阅 DNS，且执行顺序与注释不符

**位置**：`script_display.go:112-123`、`:187-195`；顺序声明 `script_display.go:16` vs 实际 `process.go:29-30`

```go
if on(leak, true, "DNS 走代理", "dns-via-proxy") {
    cfg.DNS.NameServer = []string{"https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query"}
    cfg.DNS.Fallback   = nil
    cfg.DNS.ProxyServerNameserver = []string{"https://223.5.5.5/dns-query"}
}
```

**问题**：

1. `DNS 走代理` **默认开启**，效果是**无条件丢弃订阅自带的 DNS 配置**（包括机场用于流媒体解锁的 nameserver-policy、fake-ip 过滤等），并把所有境外解析固定送到 Cloudflare / Google；
2. `applyChina` 又会把 `geosite:cn` 改写为阿里 DoH，并把 `GEOSITE,cn,DIRECT` **前置到规则最前面**——对"人在海外、需要把中国站点走代理"的用户是反效果；
3. `script_display.go` 顶部注释写"在 profile、override 脚本和 chain **之后**应用"，但 `process.go` 中 `patchScriptDisplay` 在 `patchChain` **之前**。注释与实现不一致，会让后续维护者误判。

**建议**：把 DNS 覆写做成"叠加"而非"替换"（仅在订阅未配置 DNS 时注入默认值），或在 UI 明示"选择此项将忽略订阅 DNS"，并修正注释/顺序说明。

---

### F-04【中】repair 系列自动"修复"是 fail-open，与 "Fail Closed" 表述不一致

**位置**：`core/src/main/golang/native/config/repair.go:111、179、248-296`

| 行为 | 后果 |
|---|---|
| 删除指向不存在节点的 `dialer-proxy`（:111） | 原本"必须走落地"的节点静默变成直连/单跳，链式语义被削弱 |
| 分组为空时填入全部节点或 `DIRECT`（:166-181） | 本应报错的空组变成"能上网但走了 DIRECT" |
| 规则目标不存在时改指 `fallbackGroup`（:248+） | 例如 `DOMAIN-SUFFIX,bank.com,已删除组` → 改走其它组或 DIRECT |
| `RULE-SET` 的 provider 缺失时**整条规则被丢弃**（:259-264） | 订阅中常用 RULE-SET 做广告/恶意域名拦截；provider 拉取失败时该拦截规则消失，等于 **fail-open** |

**判定**：链路（`chain.json` 指向的入口/落地校验失败）确实是 fail-closed，`process()` 返回错误会阻止启动，这点与 README 一致；但"规则/分组修复"走的是 fail-open 路线，两者共用一套文档表述，容易被误读为"全链路严格"。

**建议**：修复行为写入日志（可查询），提供"严格模式"开关；`RULE-SET` provider 缺失应考虑保留规则并报错，而不是删除。

---

### F-05【中】CI 只覆盖 chain.go，新增逻辑的测试形同虚设

**位置**：`.github/workflows/build-release.yaml:76`、`build-debug.yaml`

```yaml
run: go test native/config/chain.go native/config/chain_test.go
```

`go test <files...>` 只编译列出的文件。后果：

- `script_override_test.go`（264 行）、`script_display`/`repair` 相关逻辑**不进 CI**；
- 这些文件只在 Gradle 的 Go 构建阶段参与编译，逻辑错误要等到 APK 出问题才会暴露。

我本地用完整包路径运行的结果是**全部通过**，所以建议直接改成：

```yaml
run: go test -tags "foss with_gvisor cmfa" ./native/config/
```

（附带说明：在 CI 的 Go 1.26 + 官方 tag 组合下应按仓库现有方式运行，这里的关键是"用包路径而非文件列表"。）

---

### F-06【中】构建与发布链路的第三方信任

| 项 | 位置 | 说明 |
|---|---|---|
| 自定义 Go 工具链 | `build-release.yaml` / `build-debug.yaml` / `update-dependencies.yaml` 的 `go-download-base-url: https://github.com/MetaCubeX/go/releases/download/build` | 编译器本身来自第三方发行版，理论上可影响所有二进制产物（继承自上游 CMFA） |
| 第三方 Action 未固定 commit | `softprops/action-gh-release@v3`、`dev-drprasad/delete-tag-and-release@v1.1`、`richardsimko/update-tag@v3`、`gradle/actions/setup-gradle@v5`、`actions/checkout@v6` 等 | 只有 `actions/setup-go` 固定了 SHA；持有 `contents: write` 的发布类 Action 一旦 tag 被移动，可直接替换 release 资产 |
| 依赖更新用 `@latest` | `update-dependencies.yaml` | `go install github.com/metacubex/update-go-mod-replace@latest` 无版本约束 |
| geo 数据无校验 | `app/build.gradle.kts:56-95` | 构建期从 `raw.githubusercontent.com`（MetaCubeX / Loyalsoldier）下载 `geosite.dat` / `geoip.dat` / `country.mmdb`，只检查 HTTP 状态与空文件，不校验哈希；这些文件会进 APK 的 `assets` |
| 预发布流程使用可变 tag | `build-pre-release.yaml` | `delete-tag-and-release` + `update-tag` 会移动 tag，预发布产物不具备不可篡改性 |

**建议**：所有 Action 固定到 commit SHA；geo 文件固定 SHA-256；`go install` 固定版本；若可能，使用官方 Go 工具链 + 上游 patch 文件。

---

### F-07【低】发布 APK 没有 v3 签名

`apksigner`/签名块核查显示只有 v1 + v2。影响：

- 无法使用 Android 的**签名密钥轮换（key rotation lineage）**；
- 更新器 `UpdateApkSignature` 已经实现了 rotation 校验逻辑，但当前证书体系用不上它（属于"能力冗余"而非漏洞）；
- 一旦签名密钥丢失，只能要求用户卸载重装（会丢配置）。

**建议**：在 `signingConfig` 中启用 v3（AGP 默认会给 API 28+ 加 v3，这里因 `enableV3Signing`/`enableV4Signing` 或旧配置未开启），并提前规划密钥轮换。

---

### F-08【低】更新下载选用 138 MB 的 universal 包；版本发布节奏极快

- `UpdateReleaseAssets.select()` **优先匹配 `universal`**（`UpdateReleaseAssets.kt:12`），而 v1.2.4 的 universal 包 **138.1 MB**，arm64 包只有 50.7 MB。默认路径下每次更新都会下 138 MB。
- 2026-10-24 当天 **1.2.2（09:44Z）、1.2.3（10:15Z）、1.2.4（10:48Z）** 连发三个正式版。`autoCheckUpdate` 默认关闭（这点是好的），但一旦开启，用户可能一天被提示 3 次。

**建议**：优先选设备 ABI 的包（universal 仅作为兜底），或至少把 universal 排在 ABI 之后；对补丁级连发做合并发布。

---

### F-09【低】规则与脚本识别的子串误判（已实测复现）

| 位置 | 表达式 | 实测结果 |
|---|---|---|
| `script_display.go:449` `isQuicReject` | `strings.Contains(upper,"DST-PORT,443")` | `AND,((NETWORK,UDP),(DST-PORT,4430)),REJECT` → **true**（误删 4430-4439 端口的规则） |
| `chain.go:621` `isBypassRule` | `strings.Contains(payload,"cn")` | `GEOSITE,ncnc,DIRECT` → **true**（任何含 "cn" 字母的 GEOSITE 名都会被当作"中国直连"保留） |
| `script_override.go:47` `hasScriptMain` | 字符串里出现 `function main` | `const s = "function main(x) {}"` → **true** |

影响有限（多为规则被误保留/误删除），但属于"字符串匹配代替语法分析"的系统性隐患。**建议**：对端口/规则目标做精确 token 匹配，`hasScriptMain` 改为 goja 解析后检查 AST。

---

### F-10【低】备份包含订阅与凭据

`AndroidManifest.xml:31` `allowBackup="true"`，`xml/full_backup_content.xml` 包含 `sharedpref`、`database`、`file/imported`、`file/pending`。

- `imported/` 内是完整 `config.yaml`（含节点密码、UUID、订阅 token）；
- `sharedpref` 内有 `chain_bindings`、`script-options.json` 等；
- 云备份（Google Drive / 厂商云）会把它们带出设备。

**建议**：`allowBackup=false`，或至少从备份中排除 `imported/`、`pending/` 与 `service.xml`。

---

### F-11【低】权限面

| 权限 | 用途 | 评价 |
|---|---|---|
| `QUERY_ALL_PACKAGES` | 分应用代理 / 进程匹配 / 应用列表 | 功能必需，但 Play 商店敏感权限（本项目自发布，影响小） |
| `REQUEST_INSTALL_PACKAGES` | 内置 APK 更新 | 自更新必需；已做完整签名校验（见正向实践） |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | `EnhanceToolsDesign.kt:47-56` 引导后台保活 | 合理 |
| `INTERACT_ACROSS_USERS` | 仅 ADB 可授权，上游为 Android 17 loopback 兼容保留 | 无法被静默获得，风险低 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启（`ProfileReceiver`） | 上游行为 |

未发现任何未使用的高危权限（如 `READ_SMS`、`CALL_PHONE`、`SYSTEM_ALERT_WINDOW` 等）。

---

### F-12【低】文档漂移与隐私政策失真

- `PRIVACY_POLICY.md` 仍是上游模板，明确声称使用 **AppCenter** 与 **Google Play Services** 收集数据、会上报 Log Data。而实际 APK 中**没有任何分析与崩溃 SDK**，这一描述对用户构成误导（虽然是"多承认了收集行为"）。
- `README.md` 写"当前 **1.2.2**"，实际 `version.properties` 已是 **1.2.4**。
- `docs/IDENTITY.md` 写内核 tag `v1.19.31-chain.1` / `4e6f2ee…`，与实际的 `v1.19.33` / `3f27ef3…` 不一致。

**建议**：用真实行为重写隐私政策（"不收集、唯一外联为可选 GitHub Release 检查"），并把版本号/内核基线收敛为单一来源。

---

### F-13【信息】内置 Google ML Kit 条码模型

APK 含 `assets/mlkit_barcode_models/*.tflite`、`libbarhopper_v3.so`、Firebase 组件类（仅 `firebase-components/encoders/annotations`，**无** analytics/crashlytics/messaging）。

- 来源：上游 CMFA 的二维码导入/分享功能（`libs.versions.toml` 中的 `quickie-bundled`，与上游一致，非本 fork 新增）；
- 属于闭源二进制，对"隐私优先"的定位是一个观感风险点；bundle 版本离线可用，不上传图片。

**建议**：如追求极简，可换成 ZXing-only 的 `quickie`，可显著减小 APK 并移除闭源模型。

---

### F-14【信息】信任用户 CA

`network_security_config.xml` 同时信任 `system` 与 `user` 证书。任何能安装用户 CA 的本地环境可以中间人订阅拉取、日志上传等流量；但：

- 更新包有完整的 apksig 校验，MITM 无法投递恶意 APK；
- 订阅内容本身来自网络，MITM 者若已控制用户网络，本就能篡改订阅（这是所有 Clash 客户端的共性）。

**建议**：至少在设置中提供"仅信任系统 CA"选项。

---

### F-15【信息】Play/GMS 默认绕过 VPN、默认境外 DoH

`TunService.kt` 的 `storeFix` 会在三种访问控制模式下把 `com.android.vending`、`com.google.android.gms`、`com.google.android.gsf` 排除出 VPN（用于避免应用商店更新失败）。行为本身合理，但**用户界面没有任何提示**。同理，默认 DNS 是境外 DoH（1.1.1.1 / 8.8.8.8），对"不希望任何境外解析"的用户需要显式告知。

---

### F-16【信息】脚本导入的防护与缺口

`ScriptOptionsActivity.kt:123-222`：

- ✅ 强制 HTTPS、禁止 URL 中带 userInfo、最多 5 次重定向且**每次重定向后重新校验协议**、512 KB 大小上限、拒绝空内容与 HTML；
- ⚠️ 没有完整性校验（无固定哈希/签名），脚本域名被攻陷或作者推送恶意版本时，脚本会在 goja 中执行并**改写整份运行配置**（可插入任意 `rules`、`proxies`、`dns`，也能把流量导向攻击者节点）。

**建议**：支持"固定脚本 SHA-256"，变更时要求用户确认；或对来源提供官方签名校验。

---

## 四、值得肯定的正向实践

1. **内核零修改**：子模块固定到官方 `v1.19.33` 的同一 commit SHA，`version.properties` 与 README 均声明该基线；发布流程即使用该固定 commit 构建内核。
2. **更新器是本项目最强的安全设计**（`UpdateApk*.kt`）：
   - 使用 Google `apksig` 做**完整 APK 签名验证**，而非只看 `getPackageArchiveInfo`；
   - 强制包名一致、`versionCode` 严格递增、`versionName` 与 release tag 一致、`minSdk` 兼容；
   - 签名者必须与已安装应用一致，或来自**带 `installed-data` 能力的轮换谱系**，且谱系末端必须是当前活跃签名者；
   - 下载文件先复制到应用私有目录再校验，避免"校验后替换"的 TOCTOU；
   - 5 个 JVM 测试类、48 个用例 + 一次性测试密钥夹具（`same-signer / rotated / tampered / unsigned / unrelated`）。
3. **发布门禁**（`build-release.yaml`）：
   - 只接受 40 位 commit SHA 且必须是 `origin/dev` 的祖先；拒绝已存在的 tag；
   - 校验提交里的 `version.properties` 与 tag 计算值一致；
   - 逐个 APK 用 `apksigner` 校验证书 SHA-256 = `82d0db…`（本次独立复核一致）；
   - 版本/包名/ABI 元数据脚本二次校验后才上传。
4. **拒绝用调试证书签正式包**：`build.gradle.kts` 在缺少 `signing.properties` / `release.keystore` 时直接抛异常，且 `*Release` 任务不会回落到 debug key。
5. **订阅 URL 日志脱敏**：`app/redact.go` 去掉 query/fragment/userinfo，避免 token 进日志（这是很多同类客户端都没做的）。
6. **链式代理实现是防御式的**：深拷贝后校验、名称冲突/重复检查、循环与深度（24）与规模（2048）限制、`include-all` 组自动 `exclude-filter`、`no-resolve` 自动补齐、失败即中止（fail-closed）。
7. **无遥测**：APK 中无任何分析/崩溃 SDK；唯一硬编码外网地址是 GitHub Releases API，且 `autoCheckUpdate` 默认关闭。

---

## 五、修复建议（按优先级）

### P1（建议尽快）

1. **给 goja 加超时与栈限制**（F-01），并在 `Clash.load()` 侧加超时。
2. **明确 async 支持策略**（F-02）：实现 Promise 驱动或提前报错"不支持 async main"。
3. **CI 改为包级测试**：`go test -tags "foss with_gvisor cmfa" ./native/config/`（F-05）。
4. **DNS 覆写改为可选/叠加**，并在 UI 明示对订阅 DNS 的影响（F-03）。
5. **repair 增加日志与严格模式**，`RULE-SET` provider 缺失不要静默丢弃规则（F-04）。

### P2（建议下一个迭代）

6. 所有 GitHub Actions 固定到 commit SHA；`go install ...@latest` 固定版本；geo 数据加 SHA-256 校验（F-06）。
7. 启用 v3 签名并制定密钥轮换方案（F-07）。
8. 更新器优先选 ABI 包，减少 138 MB 下载（F-08）。
9. `allowBackup=false` 或从备份排除订阅/凭据（F-10）。
10. 重写隐私政策与 README/IDENTITY 的版本信息（F-12）。

### P3（清理）

11. 修正 `isQuicReject` / `isBypassRule` / `hasScriptMain` 的子串匹配（F-09）。
12. 评估用 ZXing-only 替换 ML Kit bundle（F-13）。
13. 在设置中提供"仅信任系统 CA"（F-14）；在 UI 标注 Play/GMS 绕过与默认境外 DoH（F-15）。
14. 脚本导入支持固定哈希/签名（F-16）。

---

## 六、给普通用户的使用建议

1. **只从官方 Releases 下载**，安装前可自行核对签名指纹：`82:D0:DB:00:7C:…:69:43:DC`（与 `docs/SIGNING.md`、CI 固定值一致）。
2. 本客户端会**执行你导入的 JS 脚本并让它改写运行配置**：不要从不可信来源导入脚本；导入后建议在"脚本编辑"里大致浏览一遍。
3. **"系统显式选项"优先级高于你的订阅**：默认会替换 DNS 为 1.1.1.1 / 8.8.8.8，并把中国域名/IP 前置直连。若你的机场依赖自带 DNS 做解锁，请先关闭这些开关再判断问题归属。
4. 自动检查更新默认关闭（只会访问 GitHub API）。开启后每次启动会向 GitHub 暴露一次 IP 与"在用此应用"的信息。
5. 订阅链接通常含账号 token，**不要分享日志**（虽然本项目已对 URL 做了脱敏，但其它客户端未必）。
6. 一天内连发多个版本属于当前常态，遇到"更新后行为变化"可先回退到上一个 release。

---

## 七、附录：本次审计使用的主要命令

```bash
# 源码与上游比对
git clone --depth 50 https://github.com/dukangalex/AngelaClash.git angela
git clone --depth 1  https://github.com/MetaCubeX/ClashMetaForAndroid.git cmfa-up
git -C angela submodule update --init --depth 1 core/src/foss/golang/clash
diff -rq --exclude=.git cmfa-up angela

# 内核 commit 溯源（关键）
curl -s https://api.github.com/repos/MetaCubeX/mihomo/commits/3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8
curl -s https://api.github.com/repos/dukangalex/mihomo/commits/3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8

# Go 检查与测试
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go vet -tags "foss with_gvisor cmfa" ./native/config/
go test -tags "foss with_gvisor cmfa" ./native/config/

# 发布产物核查
curl -sL -o angela.apk https://github.com/dukangalex/AngelaClash/releases/download/v1.2.4/Angela-Clash-1.2.4-meta-arm64-v8a-release.apk
keytool -printcert -jarfile angela.apk          # → SHA256 82:D0:DB:…
python3 -c "..."                                # 读取 APK Signing Block：0x7109871a(v2) / 0x504b4453 / 0x42726577
python3 -c "..."                                # dex 字符串扫描：唯一外网地址 = api.github.com/dukangalex/AngelaClash
```

### 本次实测结论汇总

| 验证项 | 结果 |
|---|---|
| 内核是否被改写 | 否（与官方 `v1.19.33` 同一 commit SHA） |
| 发布 APK 签名 | v1 + v2 有效；证书与 CI 固定值、SIGNING.md 三方一致 |
| 是否存在遥测/后门 | 未发现；无分析/崩溃 SDK，唯一外联为 GitHub Releases API |
| Go 静态检查 | 通过（真实构建标签） |
| Go 单元测试 | 全包通过（CI 只跑了其中两个文件） |
| 推荐第三方脚本兼容性 | 正常（23.6 ms，31 组 / 78 规则） |
| async main 脚本 | 失败（`script main() must return an object`） |
| 修复/规则改写 | 存在 fail-open 行为，与文档表述不一致 |

---

*本报告基于 2026-10-24 的 `dev` 分支与 `v1.2.4` 发布产物。项目提交频率很高（当日 3 个正式版），结论可能随版本变化，建议在重要版本后复核 F-01/F-03/F-04 相关代码。*
