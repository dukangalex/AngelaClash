# 脚本

工具 → **脚本显示选项**，或仪表盘上的脚本入口。

如果脚本里有 `function main(config)`，保存后会用它的返回值覆写当前配置，但 **proxies 和 proxy-providers（节点和节点订阅）保持原配置**。服务正在运行时会立刻重载。`main` 必须 `return config`。

```javascript
function main(config) {
  config["proxy-groups"] = [
    { name: "PROXY", type: "select", proxies: ["节点名"] },
  ];
  config.rules = ["MATCH,PROXY"];
  return config;
}
```

显示开关默认关闭。打开后，下次启动（或这次重载）还会按开关改分流、防泄漏、中国直连、严格路由。

覆写脚本里可以声明：

```javascript
const Compatible_With_AngelaClash = { ruleOptionsEnable: true };
const ruleOptionsEnable = {
  "Google": true,
  "AI": false,
};
```

`ruleOptionsEnable` 的键是策略组名。关掉之后，这个组会从本次运行配置里拿掉，原来指向它的规则改到主组（`主代理` / `PROXY` / `节点选择`，否则用剩下的第一个组）。

在这之上，Angela Clash 固定多三组。内置脚本里的键会真正改配置：

| 组 | 打开后 |
|---|---|
| 防泄漏 | DNS 走 1.1.1.1 / 8.8.8.8 并按规则出站；去掉 `system://`；可关 IPv6、阻断 UDP 443、开启嗅探 |
| 中国直连 | `GEOSITE,cn,DIRECT`、`GEOIP,CN,DIRECT`、国内 DoH `223.5.5.5`、私有地址直连 |
| 严格路由 | VPN 接管全部地址并不允许绕过；DNS 遵循规则；`find-process-mode: strict` |

脚本只用来声明开关。客户端不执行 `main()`。自定义键如果不是上面这些名字，分流组仍按组名隐藏或补一个 `include-all` 策略组；防泄漏 / 中国直连 / 严格路由只认内置名字。

内置脚本：`service/src/main/assets/angela/default-script.js`。界面里可以编辑，也可以恢复内置脚本。
