## Privacy Policy / 隐私政策

Angela Clash 是一个本地运行的代理客户端（基于 mihomo 内核的 Android 客户端）。本页描述它实际会接触哪些数据，而不是一份通用模板。

_Last updated: 2026-10-24 (applies to 1.3.0 and later)._

### English summary

- **No analytics, no crash reporting, no advertising SDK.** The shipped APK contains no Firebase Analytics, Crashlytics, Sentry, Umeng, Bugly or similar SDK.
- **No account, no server operated by the project.** Everything runs on the device.
- **The only outbound call the app makes on its own** is `GET https://api.github.com/repos/dukangalex/AngelaClash/releases/latest`, and only when you open the app with “自动检查更新 / Auto check for updates” enabled (off by default) or tap “检查更新”. GitHub will see your IP address and that the app queried the endpoint.
- **Your subscriptions, node credentials and proxy rules never leave the device** except to the subscription URL you configured yourself.
- **Logs stay local.** Exporting a log is an explicit action; subscription URLs are redacted before they are written to the log.
- **Android cloud backup is disabled** (`allowBackup=false`), so profile files and settings are not uploaded to Google Drive or copied to another device by the OS.

### 中文说明

- **没有统计、没有崩溃上报、没有广告 SDK**。发布包内不含 Firebase Analytics、Crashlytics、Sentry、友盟、Bugly 等组件。
- **没有账号体系，项目也不运营任何服务器**，所有处理都在本机完成。
- **应用唯一主动发起的网络请求**是 `GET https://api.github.com/repos/dukangalex/AngelaClash/releases/latest`：只在你打开“自动检查更新”（默认关闭）或手动点“检查更新”时发生。GitHub 会看到你的 IP 以及"该应用询问过版本"这一事实。
- **订阅链接、节点凭据和分流规则不会离开设备**，只会发送到你自行配置的订阅地址。
- **日志只存在本机**；导出日志是显式操作，且写入日志前会去掉订阅 URL 的查询参数与用户信息。
- **系统云备份已关闭**（`allowBackup=false`），配置与凭据不会被上传到云端，也不会在换机时被系统复制。
- **换机迁移由你手动完成**：增强工具里的「导出备份」会用你设置的口令在本地加密，再通过系统
  文件选择器写到你指定的位置（应用不申请存储权限、不联网上传）；「从备份恢复」只在本地解密，
  口令不会被保存，也不会有任何副本离开设备。
- **导入的覆写脚本会在内核内执行**并能改写运行配置；请只导入你信任来源的脚本。远端脚本内容与上次同步不一致时会要求确认。

### 第三方组件

| 组件 | 作用 | 是否联网 |
|---|---|---|
| mihomo 内核（GPL-3.0） | 代理协议栈 | 通过你配置的节点和订阅联网 |
| Google ML Kit 条码模型（随包内置） | 二维码导入/分享，**完全离线** | 否 |
| GeoIP/GeoSite 数据（随包内置） | 分流匹配 | 否 |

### 权限

| 权限 | 为什么需要 |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | 代理与订阅 |
| `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` | 常驻代理服务与状态通知 |
| `QUERY_ALL_PACKAGES` | 分应用代理 / 进程匹配 |
| `REQUEST_INSTALL_PACKAGES` | 应用内更新（下载的 APK 会先做完整签名校验） |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 引导后台保活，可在“增强工具”里跳转，不强制 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启（可关闭） |

### 联系

通过 [GitHub Issues](https://github.com/dukangalex/AngelaClash/issues) 反馈。
