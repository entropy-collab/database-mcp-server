/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.entropy.database.mcp.routing;

import com.entropy.database.mcp.backup.DatabaseBackupService;
import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.cache.DatabaseCache;
import com.entropy.database.mcp.dialect.DatabaseDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 连接被驱逐之后，per-connection facade 缓存必须跟着放手。
 *
 * <p>这条路径以前完全不存在：{@code facades} 只增不删，而 BYOK 的连接名是调用方随便起的，于是历史上用过的每个
 * 名字都永久钉住一份 {@code DatabaseCache} 和一个已经 close 的 Hikari 池。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoutingDatabaseFacadeEvictionTest {

    @Mock
    private DynamicDataSourceManager dynamicDataSourceManager;

    @Mock
    private DatabaseBackupService backupService;

    private DynamicDataSourceManager.EvictionListener listener;

    private RoutingDatabaseFacade createFacade() {
        RoutingDatabaseFacade facade = new RoutingDatabaseFacade(dynamicDataSourceManager, backupService);
        ArgumentCaptor<DynamicDataSourceManager.EvictionListener> captor =
                ArgumentCaptor.forClass(DynamicDataSourceManager.EvictionListener.class);
        // 订阅发生在构造器里而不是 @PostConstruct，直接 new 出来的实例也必须注册上
        verify(dynamicDataSourceManager).addEvictionListener(captor.capture());
        listener = captor.getValue();
        return facade;
    }

    private ByokDataSourceContext contextNamed(String key, DatabaseCache cache) {
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);
        when(context.getKey()).thenReturn(key);
        when(context.getCache()).thenReturn(cache);
        when(context.getDialect()).thenReturn(mock(DatabaseDialect.class));
        return context;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> facadesOf(RoutingDatabaseFacade facade) {
        try {
            java.lang.reflect.Field field = RoutingDatabaseFacade.class.getDeclaredField("facades");
            field.setAccessible(true);
            return (Map<String, ?>) field.get(facade);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void evictedConnectionIsDroppedFromTheFacadeCache() {
        RoutingDatabaseFacade facade = createFacade();
        DatabaseCache cache = mock(DatabaseCache.class);
        ByokDataSourceContext context = contextNamed("c1", cache);
        when(dynamicDataSourceManager.acquire("c1")).thenReturn(context);

        facade.getDialect("c1");
        assertThat(facadesOf(facade)).containsKey("c1");

        listener.onConnectionEvicted("c1");

        // 这个 entry 是 DatabaseCache（queryCache + metadataCache + BloomFilter）和已关闭 Hikari 池的唯一强引用
        assertThat(facadesOf(facade)).doesNotContainKey("c1");
        verify(cache).invalidateAll();
    }

    /** 同一个名字被通知多次是允许的（先显式驱逐、再收到异步 removal 通知），不能重复清缓存或抛异常。 */
    @Test
    void evictionIsIdempotentForAnUnknownName() {
        RoutingDatabaseFacade facade = createFacade();
        DatabaseCache cache = mock(DatabaseCache.class);
        ByokDataSourceContext context = contextNamed("c1", cache);
        when(dynamicDataSourceManager.acquire("c1")).thenReturn(context);

        facade.getDialect("c1");
        listener.onConnectionEvicted("c1");
        listener.onConnectionEvicted("c1");
        listener.onConnectionEvicted("never-used");

        assertThat(facadesOf(facade)).isEmpty();
        verify(cache, times(1)).invalidateAll();
    }

    /**
     * 别名与规范名共享同一个上下文，也就是同一份 {@code DatabaseCache}。别名失效时不能把规范名正在用的缓存清掉，
     * 只有最后一个名字消失时才清——判据与管理器侧 {@code closeIfUnreferenced} 一致：按对象身份数引用。
     */
    @Test
    void aliasEvictionKeepsTheSharedCacheOfTheCanonicalName() {
        RoutingDatabaseFacade facade = createFacade();
        DatabaseCache sharedCache = mock(DatabaseCache.class);
        ByokDataSourceContext shared = contextNamed("canonical", sharedCache);
        // 别名与规范名拿到的是同一个 context 对象，只是 acquire 的入参不同
        when(dynamicDataSourceManager.acquire("canonical")).thenReturn(shared);
        when(dynamicDataSourceManager.acquire("alias")).thenReturn(shared);

        facade.getDialect("canonical");
        // facades 的 key 取自 context.getKey()，共享上下文无论用哪个名字 acquire 都只写出一条记录，
        // 所以手工复制一条别名 entry，才能表达「两个名字、一个池」这个形状
        duplicateEntryUnder(facade, "alias");
        assertThat(facadesOf(facade)).containsKeys("canonical", "alias");

        listener.onConnectionEvicted("alias");

        assertThat(facadesOf(facade)).containsOnlyKeys("canonical");
        verify(sharedCache, never()).invalidateAll();

        listener.onConnectionEvicted("canonical");

        assertThat(facadesOf(facade)).isEmpty();
        verify(sharedCache, times(1)).invalidateAll();
    }

    /** 把已有的那条 entry 再挂到 {@code alias} 名下，两个名字于是共享同一个上下文对象。 */
    @SuppressWarnings("unchecked")
    private static void duplicateEntryUnder(RoutingDatabaseFacade facade, String alias) {
        Map<String, Object> facades = (Map<String, Object>) facadesOf(facade);
        facades.put(alias, facades.values().iterator().next());
    }
}
