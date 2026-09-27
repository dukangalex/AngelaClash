// Angela Clash 覆写脚本
// function main(config) { ... return config } 会覆写除 proxies、proxy-providers 以外的配置。
// 保存时如果服务正在运行，会立刻重载。
// 下面这些 true/false 是显示开关，总开关打开后才会改运行配置。
const Compatible_With_AngelaClash = {
  ruleOptionsEnable: true,
  leakOptionsEnable: true,
  cnDirectOptionsEnable: true,
  strictRouteOptionsEnable: true,
};

// 分流。键名等于策略组名。关闭后移除该组，原来指向它的规则回到主组。
const ruleOptionsEnable = {
  "AI": true,
  "Google": true,
  "YouTube": true,
  "Telegram": true,
  "Netflix": true,
  "广告拦截": true,
};

// 防泄漏
const leakOptionsEnable = {
  "DNS 走代理": true,
  "禁止系统 DNS": true,
  "关闭 IPv6": true,
  "阻断 QUIC": false,
  "嗅探防泄漏": true,
};

// 中国直连
const cnDirectOptionsEnable = {
  "中国大陆 IP 直连": true,
  "中国大陆域名直连": true,
  "国内 DNS": true,
  "局域网直连": true,
};

// 严格路由
const strictRouteOptionsEnable = {
  "严格路由": false,
  "禁止绕过 VPN": false,
  "DNS 遵循规则": true,
  "进程严格匹配": false,
};
