import React from 'react';
import { X, Download, ShieldCheck, Check, ExternalLink } from 'lucide-react';

interface ApkModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const ApkModal: React.FC<ApkModalProps> = ({ isOpen, onClose }) => {
  if (!isOpen) return null;

  const downloadList = [
    {
      name: 'Angela-Clash-1.2.1-meta-arm64-v8a-release.apk',
      version: 'v1.2.1 (10201)',
      arch: 'ARM64-v8a',
      size: '53.1 MB',
      recommended: true,
      desc: '适合 99% 的现代 Android 智能手机（高通、联发科、三星、Google Tensor 芯片）',
      url: 'https://github.com/dukangalex/AngelaClash/releases/download/v1.2.1/Angela-Clash-1.2.1-meta-arm64-v8a-release.apk',
    },
    {
      name: 'Angela-Clash-1.2.1-meta-universal-release.apk',
      version: 'v1.2.1 (10201)',
      arch: 'Universal (通用版)',
      size: '145.6 MB',
      recommended: false,
      desc: '包含所有芯片架构的完整独立安装包，不清楚机型架构时可直接选择',
      url: 'https://github.com/dukangalex/AngelaClash/releases/download/v1.2.1/Angela-Clash-1.2.1-meta-universal-release.apk',
    },
    {
      name: 'Angela-Clash-1.2.1-meta-armeabi-v7a-release.apk',
      version: 'v1.2.1 (10201)',
      arch: 'ARM 32位',
      size: '53.2 MB',
      recommended: false,
      desc: '老旧手机、低端电视盒子或 32 位 Android 系统设备',
      url: 'https://github.com/dukangalex/AngelaClash/releases/download/v1.2.1/Angela-Clash-1.2.1-meta-armeabi-v7a-release.apk',
    },
    {
      name: 'Angela-Clash-1.2.1-meta-x86_64-release.apk',
      version: 'v1.2.1 (10201)',
      arch: 'x86_64',
      size: '55.4 MB',
      recommended: false,
      desc: 'PC 安卓模拟器（雷电、MuMu、逍遥）或 x86 Chromebook',
      url: 'https://github.com/dukangalex/AngelaClash/releases/download/v1.2.1/Angela-Clash-1.2.1-meta-x86_64-release.apk',
    },
  ];

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-fade-in">
      <div className="bg-[#161F30] border border-slate-700 rounded-3xl max-w-xl w-full max-h-[90vh] flex flex-col shadow-2xl overflow-hidden">
        {/* Header */}
        <div className="p-6 border-b border-slate-800 flex items-center justify-between bg-[#0B0F17]/70">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-2xl bg-blue-500/20 border border-blue-500/40 flex items-center justify-center text-blue-400">
              <Download className="w-5 h-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h2 className="text-base font-bold text-slate-100">官方 Android 安装包下载</h2>
                <span className="px-2 py-0.5 rounded-full text-[10px] font-mono font-bold bg-blue-950 text-blue-300 border border-blue-800">
                  RELEASE v1.2.1
                </span>
              </div>
              <p className="text-xs text-slate-400 mt-0.5">
                内置 mihomo v1.19.33 @ 3f27ef39 · 支持 Android 7.0+ 设备
              </p>
            </div>
          </div>

          <button
            onClick={onClose}
            className="p-2 rounded-xl text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition-colors cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Content list */}
        <div className="p-6 overflow-y-auto space-y-3.5 flex-1 text-xs">
          {/* Security & Verification Notice */}
          <div className="bg-[#0B0F17] p-3.5 rounded-2xl border border-slate-800 flex items-start gap-3">
            <ShieldCheck className="w-5 h-5 text-emerald-400 shrink-0 mt-0.5" />
            <div className="text-[11px] text-slate-400 leading-relaxed">
              <div className="font-bold text-slate-200">官方正式签名与内置自检保护</div>
              <div>
                所有安装包均采用官方 Keystore 签名并在应用内通过{' '}
                <code className="text-slate-300">PackageManager.getPackageArchiveInfo</code>{' '}
                校验证书指纹一致性，SHA-256 证书指纹：
                <code className="text-blue-400 font-mono block mt-1 break-all bg-[#161F30] px-2 py-1 rounded">
                  82:D0:DB:00:7C:5E:AF:C3:7E:12:6B:4F:DC:16:66:0D:7C:F2:43:4C:7A:15:E6:10:F4:B4:3F:D6:42:69:43:DC
                </code>
              </div>
            </div>
          </div>

          {/* Download cards */}
          <div className="space-y-2.5">
            {downloadList.map((item, index) => (
              <div
                key={index}
                className={`p-4 rounded-2xl border transition-all ${
                  item.recommended
                    ? 'bg-[#13233F] border-blue-500/60 shadow-md shadow-blue-950/40'
                    : 'bg-[#0B0F17]/80 border-slate-800 hover:border-slate-700'
                }`}
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <span className="font-bold text-slate-100 text-xs">{item.arch}</span>
                      {item.recommended && (
                        <span className="px-1.5 py-0.2 rounded-full text-[10px] font-semibold bg-emerald-950 text-emerald-400 border border-emerald-800 flex items-center gap-1">
                          <Check className="w-2.5 h-2.5" /> 推荐机型
                        </span>
                      )}
                      <span className="text-[10px] text-slate-500 font-mono">{item.size}</span>
                    </div>
                    <div className="font-mono text-[11px] text-blue-400 truncate max-w-[280px] sm:max-w-md">
                      {item.name}
                    </div>
                    <p className="text-[11px] text-slate-400">{item.desc}</p>
                  </div>

                  <a
                    href={item.url}
                    target="_blank"
                    rel="noreferrer"
                    className={`px-3 py-1.5 rounded-xl font-bold text-xs flex items-center gap-1.5 shrink-0 transition-all cursor-pointer ${
                      item.recommended
                        ? 'bg-blue-600 hover:bg-blue-500 text-white shadow-sm'
                        : 'bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700'
                    }`}
                  >
                    <Download className="w-3.5 h-3.5" />
                    <span>下载</span>
                  </a>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-slate-800 bg-[#0B0F17] flex items-center justify-between">
          <a
            href="https://github.com/dukangalex/AngelaClash/releases"
            target="_blank"
            rel="noreferrer"
            className="text-xs text-blue-400 hover:text-blue-300 flex items-center gap-1 font-medium cursor-pointer"
          >
            <span>前往 GitHub Releases 查看全部发版与校验信息</span>
            <ExternalLink className="w-3.5 h-3.5" />
          </a>

          <button
            onClick={onClose}
            className="px-4 py-1.5 rounded-xl text-xs font-semibold bg-slate-800 hover:bg-slate-700 text-slate-300 transition-colors cursor-pointer"
          >
            关闭
          </button>
        </div>
      </div>
    </div>
  );
};
