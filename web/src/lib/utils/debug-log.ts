/**
 * 按 scope 控制的浏览器调试日志助手。
 * 默认全部静默；需要排查时在浏览器控制台执行：
 *   localStorage.setItem("debug:ws", "1")
 *   localStorage.setItem("debug:sse", "1")
 * 然后刷新页面即可。
 */

type LogScope = "ws" | "sse";

const enabled = (scope: LogScope): boolean => {
  if (typeof window === "undefined") return false;
  try {
    return window.localStorage?.getItem(`debug:${scope}`) === "1";
  } catch {
    return false;
  }
};

export const debugLog = (scope: LogScope) => ({
  log: (...args: unknown[]) => {
    if (enabled(scope)) console.log(...args);
  },
  warn: (...args: unknown[]) => {
    if (enabled(scope)) console.warn(...args);
  },
  error: (...args: unknown[]) => {
    if (enabled(scope)) console.error(...args);
  },
});
