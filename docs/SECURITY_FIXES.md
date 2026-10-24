# 审计修复清单（2026-10-24）

本文件记录 [审计报告](AUDIT_REPORT.md)（`docs/AUDIT_REPORT.md`）中各条目的处理方式。未修复项写明了原因与后续方向。

## 已修复

### F-01 脚本执行没有超时 / 栈限制（中）

`core/src/main/golang/native/config/script_override.go`

- 新增 `scriptExecutionTimeout`（默认 5s）。运行期用 `time.AfterFunc` + `vm.Interrupt` 打断，返回可读错误：
  `script main: 执行超过 5s，已被中止（请检查脚本是否有死循环）`。
- 新增 `vm.SetMaxCallStackSize(1024)`，递归爆栈不再拖垮进程。
- `scriptError()` 把 "interrupted" 翻译成用户能看懂的中文提示。
- 顺带修掉一个隐性问题：`scriptApplied` 标志改为每次 `process()` 重置，脚本被跳过/失败都不会被误判为"已生效"。

`service/.../clash/module/ConfigurationModule.kt`

- `Clash.load(...).await()` 外层加 `withTimeoutOrNull(180_000)`，超时抛出
  `加载超时（180s）：请检查订阅、脚本或节点提供者`，服务不再无限"加载中"。
  180s 是刻意的宽松值：订阅/proxy-provider 拉取可能确实很慢，这里只兜住"永不返回"。

### F-02 `async function main` 必然失败（中）

`script_override.go`

- `main()` 返回 `*goja.Promise` 时按状态处理：`Fulfilled` 取值继续；`Rejected` 返回真实原因；
  `Pending`（依赖计时器/事件循环）给出明确提示，而不是含糊的 `must return an object`。

### F-03 系统选项强改订阅 DNS / 注释与顺序不符（中）

`script_display.go`

- `DNS 走代理` 改为**叠加**：仅当订阅没有配置 `nameserver` / `proxy-server-nameserver` 时注入默认 DoH；
  不再清空 `fallback`，不再覆盖订阅自带的解锁/分流 DNS。
- 关闭该开关时也不再强行改写为阿里/腾讯 DoH，只保留订阅原值。
- 顶部注释改为描述真实顺序（script override → overrides → **script display** → chain）。

`docs/USER_GUIDE.md`、`docs/SCRIPT_OPTIONS.md` 同步说明"系统选项不再替换订阅 DNS"。

### F-04 repair 自动修复完全静默（中）

`repair.go`

- 所有自动改写现在都会写日志（`[repair] ...`）：丢弃 `dialer-proxy`、空分组回退、规则目标重写、
  `RULE-SET` provider 缺失导致整条规则被删除。
- 行为本身仍保持 fail-open（与上游 CMFA 兼容），但不再"无声"。严格模式（fail-closed）留作后续开关。

### F-05 CI 只编译两个 .go 文件（中）

`.github/workflows/build-debug.yaml`、`build-release.yaml`

- `go test native/config/chain.go native/config/chain_test.go`
  → `go test -tags "foss with_gvisor cmfa" ./native/config/`
- 现在 `script_override_test.go`、`audit_fixes_test.go`（新增 9 个用例）以及 repair/display 相关代码都会被执行。

### F-06 构建供应链（中）

- 4 个 workflow 内的第三方 Action 全部固定到 commit SHA（保留 `# vN` 注释便于升级）：
  checkout、setup-java、setup-gradle、cache、upload/download-artifact、gh-release、
  delete-tag-and-release、update-tag、release-changelog-builder、create-github-app-token、
  create-pull-request。
- `go install github.com/metacubex/update-go-mod-replace@latest` → 固定 commit `f782cd79…`。
- `app/build.gradle.kts`：`downloadGeoFiles` 现在校验每个文件的 SHA-256（`gradle/geo-files.sha256`），
  不匹配直接构建失败；`latest` 上游 tag 被替换内容时不会再静默进入 APK。
  有意刷新：`./gradlew app:downloadGeoFiles -PgeoRefresh=true` 然后审阅 diff 并提交。

### F-07 发布包只有 v1/v2 签名（低）

`build.gradle.kts`

- release signingConfig 显式开启 `enableV1Signing` / `enableV2Signing` / `enableV3Signing`。
  v3 让后续可以在不重装的情况下轮换密钥，更新器已有的 rotation 校验逻辑也才真正可用。

### F-08 更新器优先下 138MB universal 包（低）

`UpdateReleaseAssets.kt`（+ 单测）

- 先按设备 ABI 选择，universal 仅作为兜底。arm64 设备更新体积从 138MB 降到 ~51MB。

### F-09 子串匹配误判（低）

- `isQuicReject` / `isStunReject` / `isDiscoveryReject` 改为 `ruleDstPort()` 精确解析端口与端口段
  （`4430` 不再命中 `443`）。
- `isBypassRule` 的 `GEOSITE` / `RULE-SET` 改为按 token 比较（`ncnc` 不再被当成 `cn`）。
- `hasScriptMain`（字符串搜索）整体删除，改为 goja parser 解析 AST 判断顶层 `main` 声明：
  注释、字符串字面量、`mainSomething` 都不会再骗过检测；语法错误会明确报 `script 语法错误`。

### F-10 云备份包含订阅与凭据（低）

`app/src/main/AndroidManifest.xml`、新增 `res/xml/data_extraction_rules.xml`

- `android:allowBackup="false"`，并用 `data_extraction_rules` 显式排除云备份与设备迁移。
  订阅 token 与节点凭据不再进入 Google Drive / 换机复制。
- 迁移能力由应用内「加密备份 / 恢复」补回（`service/.../backup/` + 增强工具入口）：
  - 容器 `AGB\x01 | salt(16) | iv(12) | AES-256-GCM(JSON)`，口令经 PBKDF2-HMAC-SHA1
    （120000 轮）派生 256 位密钥；口令不落盘，解错口令与篡改文件都只会得到
    `WrongPassphraseException`；
  - 内容覆盖订阅（数据库行 + `config.yaml` 明文节点凭据）、`filesDir/clash` 下的覆写脚本
    与脚本选项、链式代理绑定、分流组选择；
  - 恢复按 UUID 合并（同 ID 覆盖、其余保留），文件先写临时文件再原子替换，重复执行安全；
  - 备份文件只写用户通过 SAF 选择的路径，应用不申请任何存储权限；文件名白名单校验，
    单文件 8 MB、整包 64 MB 上限，且边读边计数，避免恶意文件撑爆内存。

### F-12 文档与隐私政策失真（低）

- `PRIVACY_POLICY.md` 重写为真实行为（无统计/无崩溃上报/唯一外联是 GitHub Releases API/
  备份已关闭/内嵌 ML Kit 完全离线）。
- `README.md` 不再硬编码客户端版本，`docs/IDENTITY.md` 的内核基线与 `version.properties` 对齐。

### F-16 脚本导入可被远端静默替换（信息）

`ScriptDisplayStore.kt`、`ScriptOptionsActivity.kt`、`design` strings

- 记录上次导入脚本的 SHA-256（`syncedSha256`）。
- 同一 URL 再次同步且内容哈希变化时弹窗确认：显示新旧哈希前缀，说明"脚本可以改写整份分流配置"，
  默认取消。清空来源时同步清除哈希。

## 新增功能：应用内加密备份 / 恢复

`service/.../service/backup/BackupArchive.kt`、`BackupManager.kt`、`EnhanceToolsDesign.kt`、
`EnhanceToolsActivity.kt`、`design` strings（中英）

关闭系统云备份（F-10）后，"换机丢订阅"必须由应用自己解决，所以这一版补上了显式备份：

| 文件 | 作用 |
|---|---|
| `BackupArchive.kt` | 加密容器（`AGB\x01 | salt16 | iv12 | AES-256-GCM`）与 JSON 编解码 |
| `BackupManager.kt` | 采集（订阅行 + `config.yaml`、`filesDir/clash/*`、链式绑定、分流选择）与写回 |
| `EnhanceToolsDesign.kt` | 增强工具页新增两个入口 |
| `EnhanceToolsActivity.kt` | SAF 选路径 + 口令对话框 + 结果提示 |

- 密钥派生：PBKDF2-HMAC-SHA1 120000 轮 → 256 位；口令只存在于内存，用完 `CharArray.fill` 清零。
- 解密失败（错口令 / 被篡改 / 截断 / 非本应用文件）统一抛 `WrongPassphraseException`，
  UI 文案不区分"口令错"和"文件坏"，不给离线爆破提供额外信息。
- 明文 JSON 包含 `profiles[].config`（订阅原文，含节点密码），所以整个容器必须加密；这一点
  在代码注释里写明，避免后来者"顺手"改成明文先落盘。
- 恢复为合并语义：按 UUID 命中则覆盖，否则新增，不删除任何既有订阅；每个文件先写临时文件再
  `renameTo` 原子替换，中途被杀掉也不会留下半个 `config.yaml`。
- 安全边界：只走 SAF，不申请存储权限；文件名白名单 `[A-Za-z0-9._-]`（防 `../`）；单文件 8 MB、
  整包 64 MB，读取时边读边计数（不先 `readBytes()` 再检查，避免恶意文件撑爆内存）。

验证：用真实 `BackupArchive.kt` 在 JVM 上跑通 26 项断言（往返、错口令、翻转 1 bit、截断、
异构文件、路径穿越、未来版本号），全部通过；`BackupManager.kt` 与 `EnhanceToolsActivity.kt`
用与真实签名一致的桩做了 kotlinc 类型检查。

## 未修复（有意保留）

| 条目 | 原因 | 建议方向 |
|---|---|---|
| F-06 自定义 Go 工具链 | 继承上游 CMFA，换官方工具链需要重新验证所有 patch 与交叉编译矩阵 | 单独分支试验官方 Go + 等价 patch |
| F-11 权限较宽 | 均为功能必需（分应用代理、自更新、保活） | 若上架应用商店再按平台要求收敛 |
| F-13 内置 ML Kit 条码模型 | `quickie-bundled` 离线可用；换成 `quickie` 需要 GMS，对无 GMS 用户是倒退 | 提供两种 flavor，或在设置里说明 |
| F-14 信任用户 CA | 继承上游；部分用户依赖本地抓包/企业代理 | 增加"仅信任系统 CA"开关 |
| F-15 Play/GMS 绕过 VPN | 规避应用商店更新失败，是刻意行为 | 在 UI 中加一行说明 |
| F-04 严格模式 | 需要新增设置项与 UI，改动面大 | 用 `clash/repair.strict` 文件开关先做灰度 |

## 验证方式

```bash
# Go：静态检查 + 全包测试（含新增回归用例）
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go vet -tags "foss with_gvisor cmfa" ./native/config/
go test -tags "foss with_gvisor cmfa" ./native/config/

# 真实第三方脚本回归（HiClash mihomoScript.js）
# 25.8ms，7 proxies / 31 groups / 78 rules，scriptApplied=true

# 备份容器 / 编解码（JVM，真实 BackupArchive.kt + android.util.Base64 桩）
# 26 项断言：往返、错口令、篡改、截断、路径穿越、未来版本号 —— ALL CHECKS PASSED

# APK 侧
./gradlew app:testMetaDebugUnitTest app:assembleMetaRelease
apksigner verify --print-certs app/build/outputs/apk/meta/release/*.apk   # 应出现 v3
```

> `native/config` 的单元测试在桌面 GOOS 下需要一个非 android 的空 `dns` 桩
> （`dns/patch_android.go` 是 `android && cmfa`），CI 在 Android 构建中不受影响。
