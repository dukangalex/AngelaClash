import React from 'react';
import { X, Globe, Shield, Terminal, Zap, LayoutGrid } from 'lucide-react';

interface ReleaseModalProps {
  isOpen: boolean;
  onClose: () => void;
  onOpenApkModal: () => void;
}

export const ReleaseModal: React.FC<ReleaseModalProps> = ({
  isOpen,
  onClose,
  onOpenApkModal,
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-fade-in">
      <div className="bg-[#161F30] border border-slate-700 rounded-3xl max-w-xl w-full max-h-[90vh] flex flex-col shadow-2xl overflow-hidden">
        {/* Header */}
        <div className="p-6 border-b border-slate-800 flex items-center justify-between bg-[#0B0F17]/70">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-2xl bg-blue-500/20 border border-blue-500/40 flex items-center justify-center text-blue-400 font-bold">
              A
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h2 className="text-lg font-bold text-slate-100">Angela Clash v1.2.1</h2>
                <span className="px-2 py-0.5 rounded-full text-[10px] font-mono font-bold bg-blue-950 text-blue-300 border border-blue-800/80">
                  RELEASE (10201)
                </span>
              </div>
              <p className="text-xs text-slate-400 mt-0.5">
                官方稳定基线 (Kernel: mihomo v1.19.33 @ 3f27ef39)
              </p>
            </div>
          </div>

          <button
            onClick={onClose}
            className="p-1.5 rounded-xl text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition-colors cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Content list */}
        <div className="p-6 overflow-y-auto space-y-5 flex-1 text-xs text-slate-300">
          {/* Release Baseline Specs */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5 bg-[#0B0F17] p-3.5 rounded-2xl border border-slate-800 font-mono text-[11px]">
            <div>
              <div className="text-[10px] text-slate-500 uppercase">Version Code</div>
              <div className="font-bold text-blue-400 mt-0.5">10201</div>
            </div>
            <div>
              <div className="text-[10px] text-slate-500 uppercase">CMFA Upstream</div>
              <div className="font-bold text-slate-200 mt-0.5">v2.11.36</div>
            </div>
            <div>
              <div className="text-[10px] text-slate-500 uppercase">Stable Kernel</div>
              <div className="font-bold text-slate-200 mt-0.5">v1.19.33</div>
            </div>
            <div>
              <div className="text-[10px] text-slate-500 uppercase">Commit Hash</div>
              <div className="font-bold text-emerald-400 mt-0.5">3f27ef39</div>
            </div>
          </div>

          {/* Highlights */}
          <div className="space-y-3">
            <h3 className="font-bold text-slate-100 text-sm flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-blue-400"></span>
              <span>本版主要更新亮点 (v1.2.1 Highlights)</span>
            </h3>

            <div className="space-y-2.5">
              <div className="p-3.5 rounded-2xl bg-[#0B0F17] border border-slate-800 flex items-start gap-3">
                <LayoutGrid className="w-4 h-4 text-blue-400 shrink-0 mt-0.5" />
                <div>
                  <div className="font-semibold text-slate-100">
                    链式代理（dialer-proxy）全链路加速 &amp; 跨配置最小闭包裁剪
                  </div>
                  <div className="text-slate-400 mt-1 leading-relaxed">
                    将链式重写移至分流显示选项之后执行，保留全部非 MATCH 显式直连规则，为重写 IP/GEOIP 规则自动追加 no-resolve 并后置 GEOIP,CN，跨配置合并仅导入可达节点闭包且自动排除 include-all 自环，连接响应对齐原生配置。
                  </div>
                </div>
              </div>

              <div className="p-3.5 rounded-2xl bg-[#0B0F17] border border-slate-800 flex items-start gap-3">
                <Zap className="w-4 h-4 text-emerald-400 shrink-0 mt-0.5" />
                <div>
                  <div className="font-semibold text-slate-100">
                    代理节点卡片与语义化分色延迟胶囊重构 (ProxyView)
                  </div>
                  <div className="text-slate-400 mt-1 leading-relaxed">
                    重构自定义 Canvas 节点卡片渲染引擎，采用选中态 1.6dp 高亮主色描边与未选中态精细轮廓描边，右侧配备语义化延迟胶囊（&lt;200ms 翡翠绿、200–499ms 琥珀黄、≥500ms 珊瑚红）。
                  </div>
                </div>
              </div>

              <div className="p-3.5 rounded-2xl bg-[#0B0F17] border border-slate-800 flex items-start gap-3">
                <Terminal className="w-4 h-4 text-cyan-400 shrink-0 mt-0.5" />
                <div>
                  <div className="font-semibold text-slate-100">
                    同步上游 mihomo v1.19.33 稳定内核与 CMake 构建修复
                  </div>
                  <div className="text-slate-400 mt-1 leading-relaxed">
                    锁定 MetaCubeX/mihomo v1.19.33 (3f27ef39) 稳定基线，修复子模块处于 detached tag 时的 CMake 正则参数构建问题，保障五架构正式包签名发布。
                  </div>
                </div>
              </div>

              <div className="p-3.5 rounded-2xl bg-[#0B0F17] border border-slate-800 flex items-start gap-3">
                <Shield className="w-4 h-4 text-purple-400 shrink-0 mt-0.5" />
                <div>
                  <div className="font-semibold text-slate-100">
                    跨配置链式代理事务保护与 HTTPS 远程脚本校验
                  </div>
                  <div className="text-slate-400 mt-1 leading-relaxed">
                    严格遵循 dialer-proxy Fail-Closed 闭环策略，链路失败直接阻断启动而不回落到 DIRECT；远程覆写脚本同步全程强制校验 HTTPS。
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-slate-800 bg-[#0B0F17] flex items-center justify-between">
          <button
            onClick={() => {
              onClose();
              onOpenApkModal();
            }}
            className="px-4 py-2 rounded-xl text-xs font-bold bg-blue-600 hover:bg-blue-500 text-white transition-colors cursor-pointer"
          >
            下载此版本 Android 安装包
          </button>

          <button
            onClick={onClose}
            className="px-4 py-2 rounded-xl text-xs font-semibold bg-slate-800 hover:bg-slate-700 text-slate-300 transition-colors cursor-pointer"
          >
            关闭
          </button>
        </div>
      </div>
    </div>
  );
};
