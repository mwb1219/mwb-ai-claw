import React from 'react';
import ReactDOM from 'react-dom/client';

import App from './App';
import { applyTheme, useSettings } from './store/settings';
import './styles/theme.css';
import './styles/global.css';
import './styles/components.css';

// 首屏前应用持久化主题，避免闪烁
applyTheme(useSettings.getState().theme);

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
