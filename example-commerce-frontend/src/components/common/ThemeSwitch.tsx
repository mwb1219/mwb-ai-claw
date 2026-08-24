import { Moon, Sun } from 'lucide-react';

import { useSettings } from '../../store/settings';

/** 主题切换开关（亮色浅蓝 / 暗色深蓝黑） */
export function ThemeSwitch() {
  const theme = useSettings((s) => s.theme);
  const toggleTheme = useSettings((s) => s.toggleTheme);

  return (
    <button
      type="button"
      className="btn-icon theme-switch"
      title={theme === 'light' ? '切换到暗色主题' : '切换到亮色主题'}
      aria-label="切换主题"
      onClick={toggleTheme}
    >
      {theme === 'light' ? <Moon size={18} /> : <Sun size={18} />}
    </button>
  );
}
