import React from 'react';
import { useClash } from '../context/ClashContext';
import { Globe, Download } from 'lucide-react';

interface HeaderProps {
  onOpenReleaseNotes: () => void;
  onOpenApkModal: () => void;
}

export const Header: React.FC<HeaderProps> = ({ onOpenReleaseNotes, onOpenApkModal }) => {
  const { status, mode, lang, setLang } = useClash();

  return (
    <header className="sticky top-0 z-40 bg-[#0B0F17]/85 backdrop-blur-xl border-b border-slate-800/80 px-4 py-3">
      <div className="max-w-4xl mx-auto flex items-center justify-between">
        {/* Brand */}
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-2xl bg-gradient-to-br from-blue-500 to-cyan-500 flex items-center justify-center shadow-lg shadow-blue-500/25 font-black text-white text-base">
            A
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="font-bold text-slate-100 text-base tracking-tight leading-none">
                Angela Clash
              </h1>
              <button
                onClick={onOpenReleaseNotes}
                className="px-2 py-0.5 rounded-full text-[10px] font-mono font-bold bg-blue-950/90 text-blue-400 border border-blue-800/60 hover:bg-blue-900 transition-colors cursor-pointer"
              >
                v1.2.1
              </button>
            </div>
            <div className="flex items-center gap-2 mt-1 text-[11px] text-slate-400 font-mono">
              <span>mihomo v1.19.33</span>
              <span>•</span>
              <span className="text-blue-400 font-semibold uppercase">{mode}</span>
            </div>
          </div>
        </div>

        {/* Action Controls */}
        <div className="flex items-center gap-2">
          {/* APK Download Button */}
          <button
            onClick={onOpenApkModal}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-blue-600/15 border border-blue-500/40 text-blue-300 hover:bg-blue-600/25 hover:text-blue-200 text-xs font-semibold transition-all cursor-pointer"
            title="下载 Android APK 安装包"
          >
            <Download className="w-3.5 h-3.5 text-blue-400" />
            <span>APK v1.2.1</span>
          </button>

          {/* Language Toggle */}
          <button
            onClick={() => setLang(lang === 'zh' ? 'en' : 'zh')}
            className="flex items-center gap-1 px-2.5 py-1.5 rounded-xl bg-[#161F30] border border-slate-800 text-slate-300 hover:text-slate-100 hover:border-slate-700 text-xs transition-colors cursor-pointer"
          >
            <Globe className="w-3.5 h-3.5 text-blue-400" />
            <span className="font-medium">{lang === 'zh' ? '中文' : 'EN'}</span>
          </button>

          {/* Status Indicator */}
          <div
            className={`flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl text-xs font-semibold border ${
              status === 'running'
                ? 'bg-emerald-950/70 text-emerald-300 border-emerald-800/70'
                : status === 'starting' || status === 'stopping'
                ? 'bg-amber-950/70 text-amber-300 border-amber-800/70'
                : 'bg-[#161F30] text-slate-400 border-slate-800'
            }`}
          >
            <span
              className={`w-2 h-2 rounded-full ${
                status === 'running'
                  ? 'bg-emerald-400 animate-pulse'
                  : status === 'starting' || status === 'stopping'
                  ? 'bg-amber-400 animate-pulse'
                  : 'bg-slate-600'
              }`}
            ></span>
            <span>
              {status === 'running'
                ? '已连接'
                : status === 'starting'
                ? '启动中'
                : status === 'stopping'
                ? '停止中'
                : '已停止'}
            </span>
          </div>
        </div>
      </div>
    </header>
  );
};
