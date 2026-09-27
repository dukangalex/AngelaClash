# Angela Clash 使用说明

Angela Clash 是面向社区用户的 Android 代理客户端，内核基于开源的 MetaCubeX/mihomo（Clash Meta）。界面与设置只描述功能本身，不绑定任何机场或订阅商。

本应用与 MetaCubeX、Clash Meta for Android 官方无从属或授权关系。

## 安装

1. 从 [Releases](https://github.com/dukangalex/AngelaClash/releases) 下载 `Angela-Clash-android.apk`（或构建产物 `Angela-Clash-*.apk`）。
2. 允许安装未知来源应用后安装。
3. 同一签名且 versionCode 更大的新版可直接覆盖。

包名为 `io.chainbox.clash`。它与官方 `com.github.metacubex.clash.meta` 不是同一个应用，不能互相覆盖。

姐妹产品 [AngelaBox](https://github.com/dukangalex/AngelaBox) 使用 sing-box 内核，包名为 `io.chainbox.app`，可与本应用并存。

## 导入配置

1. 打开「配置」。
2. 通过 URL、文件或二维码导入 Clash / Mihomo YAML。
3. 选中配置后返回首页启动服务。首次启动需允许 VPN 请求。

## 链式代理

首页有独立的 **链式代理**。不要在原生节点页里手工填 `dialer-proxy`。

1. 先导入至少一份配置。落地可以来自另一份配置，不必把落地设成当前配置。
2. 打开首页 **链式代理**。右上角「说明」是完整规则。
3. 顶部先选 **要组链的那份配置**。每份配置单独保存，互不影响。
4. 点左边 **入口**：只列出这份配置里的分组、节点、订阅。不要选「漏网之鱼」。入口只是第一跳。
5. 点右边 **落地**：先是当前配置，下面按配置分组列出其他配置的分组、节点、订阅。可以搜索。出口 IP 是落地。
6. 点 **确定并保存**。若这份配置正在运行，会立刻重载。路径是：手机 → 入口 → 落地 → 目标。
7. 请用规则模式。国内、局域网和拦截保留；其余流量改到落地。DNS 不改到链路上。
8. 订阅原文不会被改。更新订阅也不会清掉这里的选择。

失败会报错并停止启动，不会偷偷改走 DIRECT。点「已有 N 个链式代理配置」可查看其他配置各自落到哪里。

内核用的是官方 `dialer-proxy`，没有第二套协议。

## 脚本与系统显式选项

仪表盘中间的卡片是系统显式选项。和脚本或订阅冲突时，以这些开关为准。

覆写脚本入口只在启用脚本时出现。内置脚本会执行 `main(config)`，覆写除节点和节点订阅以外的配置。正在运行时保存会立刻重载。

细节见 [SCRIPT_OPTIONS.md](SCRIPT_OPTIONS.md)。

## 检查更新

工具页里可以检查更新，有新版本会下载安装包。也可以从 [Releases](https://github.com/dukangalex/AngelaClash/releases) 手动下载 `Angela-Clash-*.apk`。不要从 F-Droid 或官方 MetaCubeX 发布渠道获取本分支安装包。

## 许可与免责

本仓库继承上游 GPL-3.0。上游代码版权归属原作者。Angela Clash 为独立衍生工作，不代表 MetaCubeX 或官方 Clash Meta。
