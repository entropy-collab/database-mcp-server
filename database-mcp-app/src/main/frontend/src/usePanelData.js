import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * 面板数据加载的公共 hook。
 *
 * 三件事值得说明：
 *
 * 1. deps 里带 refreshToken —— 顶部「刷新」按钮不改 limit 也要能重新拉数据，
 *    所以 App 维护一个自增的 token 传下来，而不是让每个面板自己暴露一个 reload 回调。
 *
 * 2. cancelled 标志 —— 面板切换比请求返回快是常态（切走再切回、连点刷新），
 *    没有这个标志时旧请求的响应会覆盖新请求的结果，表现为"数据偶尔跳回上一版"。
 *    React 18+ 的 StrictMode 在开发期会把 effect 跑两遍，这个 bug 在开发期尤其容易撞上。
 *
 * 3. 失败时不清空上一次的数据 —— 刷新失败（比如刚好在重启）时保留旧表格 + 顶部报错，
 *    比把表格清空更有用：运维至少还能看到几秒前的状态。
 */
export function usePanelData(loader, deps, { enabled = true } = {}) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [isLoading, setLoading] = useState(enabled);
  const loaderRef = useRef(loader);
  loaderRef.current = loader;

  useEffect(() => {
    if (!enabled) {
      setLoading(false);
      return undefined;
    }
    let cancelled = false;
    setLoading(true);
    loaderRef.current()
      .then((result) => {
        if (!cancelled) {
          setData(result);
          setError(null);
        }
      })
      .catch((e) => {
        if (!cancelled) {
          setError(e);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, ...deps]);

  const reset = useCallback(() => {
    setData(null);
    setError(null);
  }, []);

  return { data, error, isLoading, reset };
}
