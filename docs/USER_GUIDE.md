# Angela Clash 使用说明

Angela Clash 是面向社区用户的 Android 代理客户端，内核基于开源的 MetaCubeX/mihomo（Clash Meta）。界面与设置只描述功能本身，不绑定任何机场或订阅商。

本应用与 MetaCubeX、Clash Meta for Android 官方无从属或授权关系。

## 安装

1. 从 [Releases](https://github.com/dukangalex/ClashMetaForAndroid/releases) 下载 `Angela-Clash-android.apk`（或构建产物 `Angela-Clash-*.apk`）。
2. 允许安装未知来源应用后安装。
3. 同一签名且 versionCode 更大的新版可直接覆盖。

包名为 `io.chainbox.clash`。它与官方 `com.github.metacubex.clash.meta` 不是同一个应用，不能互相覆盖。

姐妹产品 [AngelaBox](https://github.com/dukangalex/AngelaBox) 使用 sing-box 内核，包名为 `io.chainbox.app`，可与本应用并存。

## 导入配置

1. 打开「配置」。
2. 通过 URL、文件或二维码导入 Clash / Mihomo YAML。
3. 选中配置后返回首页启动服务。首次启动需允许 VPN 请求。

## 链式代理

Clash / Mihomo 原生支持用 `dialer-proxy` 把一个出站接到另一个出站前面：

```
设备 → 入口节点 → 落地节点 → 目的站
```

当前版本先保留官方配置模型：可在配置里为落地节点设置 `dialer-proxy: 入口名称`。后续版本会按 AngelaBox 的组链逻辑提供独立的入口/落地绑定界面，并在启动时写入运行时配置，不修改订阅原文。

原则：

- 链路失败必须报错并停止启动，不得静默落到 DIRECT。
- 每份配置独立保存链路。
- 入口只作为第一跳，不会被当成出口显示。

## 检查更新

请使用本仓库 GitHub Releases。不要从 F-Droid 或官方 MetaCubeX 发布渠道获取本分支安装包。

## 许可与免责

本仓库继承上游 GPL-3.0。上游代码版权归属原作者。Angela Clash 为独立衍生工作，不代表 MetaCubeX 或官方 Clash Meta。
