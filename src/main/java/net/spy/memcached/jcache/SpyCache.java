/**
 *  Copyright 2011 Terracotta, Inc.
 *  Copyright 2011 Oracle America Incorporated
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.spy.memcached.jcache;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.cache.Cache;
import javax.cache.CacheBuilder;
import javax.cache.CacheConfiguration;
import javax.cache.CacheException;
import javax.cache.CacheLoader;
import javax.cache.CacheManager;
import javax.cache.CacheStatistics;
import javax.cache.CacheWriter;
import javax.cache.Caching;
import javax.cache.InvalidConfigurationException;
import javax.cache.Status;
import javax.cache.Cache.Entry;
import javax.cache.Cache.EntryProcessor;
import javax.cache.Cache.MutableEntry;
import javax.cache.event.CacheEntryListener;
import javax.cache.implementation.AbstractCache;
import javax.cache.implementation.DelegatingCacheMXBean;

import javax.cache.mbeans.CacheMXBean;
import javax.cache.transaction.IsolationLevel;
import javax.cache.transaction.Mode;

import net.spy.memcached.AddrUtil;
import net.spy.memcached.ConnectionFactoryBuilder.Locator;
import net.spy.memcached.MemcachedClient;

/**
 */
public final class SpyCache<K, V> extends AbstractCache<K, V> {
	private static final int CACHE_LOADER_THREADS = 2;
    private SpySimpleCache<K, V> store;
    private final Set<CacheEntryListener<? super K, ? super V>> cacheEntryListeners =
            new CopyOnWriteArraySet<CacheEntryListener<? super K, ? super V>>();
    private volatile Status status;
    private final SpyCacheStatistics statistics;
    private final CacheMXBean mBean;
    private final LockManager<K> lockManager = new LockManager<K>();
    private String servers;
    private MemcachedClient client;
    private final ExecutorService executorService = Executors
			.newFixedThreadPool(CACHE_LOADER_THREADS);
    /**
     * Constructs a cache.
     * 
     * @param cacheName
     * @param cacheManagerName
     * @param classLoader
     * @param configuration
     * @param cacheLoader
     * @param cacheWriter
     * @param listeners
     * @param servers
     */
    private SpyCache(String cacheName, String cacheManagerName,
                    ClassLoader classLoader,
                    CacheConfiguration<K, V> configuration,
                    CacheLoader<K, ? extends V> cacheLoader, CacheWriter<? super K, ? super V> cacheWriter,
                    Set<CacheEntryListener<K, V>> listeners,String servers) {
        super(cacheName, cacheManagerName, classLoader, configuration, cacheLoader, cacheWriter);
        status = Status.UNINITIALISED;
        assert servers != null;
		assert servers.trim().length() > 0;
        this.servers = servers;
        statistics = new SpyCacheStatistics(this);
        mBean = new DelegatingCacheMXBean<K, V>(this);
        for (CacheEntryListener<K, V> listener : listeners) {
            registerCacheEntryListener(listener);
        }
    }

    /**
     * Gets the registered {@link javax.cache.CacheLoader}, if any.
     *
     * @return the {@link javax.cache.CacheLoader} or null if none has been set.
     */
    @Override
    public CacheLoader<K, ? extends V> getCacheLoader() {
        return super.getCacheLoader();
    }

    /**
     * Gets the registered {@link javax.cache.CacheWriter}, if any.
     *
     * @return
     */
    @Override
    public CacheWriter<? super K, ? super V> getCacheWriter() {
        return super.getCacheWriter();
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public V get(K key) {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException();
        }
        return getInternal(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<K, V> getAll(Set<? extends K> keys) {
        checkStatusStarted();
        if (keys.contains(null)) {
            throw new NullPointerException("key");
        }
        // will throw NPE if keys=null
        HashMap<K, V> map = new HashMap<K, V>(keys.size());
        for (K key : keys) {
            V value = getInternal(key);
            if (value != null) {
                map.put(key, value);
            }
        }
        return map;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsKey(K key) {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException();
        }
        lockManager.lock(key);
        try {
            return store.containsKey(key);
        } finally {
            lockManager.unLock(key);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<V> load(K key) {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (getCacheLoader() == null) {
            return null;
        }
        if (containsKey(key)) {
            return null;
        }
        FutureTask<V> task = new FutureTask<V>(new SpyCacheLoaderLoadCallable<K, V>(this, getCacheLoader(), key));
        submit(task);
        return task;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public Future<Map<K, ? extends V>> loadAll(Set<? extends K> keys) {
        checkStatusStarted();
        if (keys == null) {
            throw new NullPointerException("keys");
        }
        if (getCacheLoader() == null) {
            return null;
        }
        if (keys.contains(null)) {
            throw new NullPointerException("key");
        }
        Callable<Map<K, ? extends V>> callable = new SpyCacheLoaderLoadAllCallable<K, V>(this, getCacheLoader(), keys);
        FutureTask<Map<K, ? extends V>> task = new FutureTask<Map<K, ? extends V>>(callable);
        submit(task);
        return task;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CacheStatistics getStatistics() {
        checkStatusStarted();
        if (statisticsEnabled()) {
            return statistics;
        } else {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void put(K key, V value) {
        checkStatusStarted();
        long start = statisticsEnabled() ? System.nanoTime() : 0;
        lockManager.lock(key);
        try {
            store.put(key, value);
        } finally {
            lockManager.unLock(key);
        }
        if (statisticsEnabled()) {
            statistics.increaseCachePuts(1);
            statistics.addPutTimeNano(System.nanoTime() - start);
        }
    }

    @Override
    public V getAndPut(K key, V value) {
        checkStatusStarted();
        long start = statisticsEnabled() ? System.nanoTime() : 0;
        V result;
        lockManager.lock(key);
        try {
            result = store.getAndPut(key, value);
        } finally {
            lockManager.unLock(key);
        }
        if (statisticsEnabled()) {
            statistics.increaseCachePuts(1);
            statistics.addPutTimeNano(System.nanoTime() - start);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        checkStatusStarted();
        long start = statisticsEnabled() ? System.nanoTime() : 0;
        if (map.containsKey(null)) {
            throw new NullPointerException("key");
        }
        //store.putAll(map);
        for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
            K key = entry.getKey();
            lockManager.lock(key);
            try {
                store.put(key, entry.getValue());
            } finally {
                lockManager.unLock(key);
            }
        }
        if (statisticsEnabled()) {
            statistics.increaseCachePuts(map.size());
            statistics.addPutTimeNano(System.nanoTime() - start);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean putIfAbsent(K key, V value) {
        checkStatusStarted();
        long start = statisticsEnabled() ? System.nanoTime() : 0;
        boolean result;
        lockManager.lock(key);
        try {
            result = store.putIfAbsent(key, value);
        } finally {
            lockManager.unLock(key);
        }
        if (result && statisticsEnabled()) {
            statistics.increaseCachePuts(1);
            statistics.addPutTimeNano(System.nanoTime() - start);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean remove(K key) {
        checkStatusStarted();
        long start = statisticsEnabled() ? System.nanoTime() : 0;
        boolean result;
        lockManager.lock(key);
        try {
            result = store.remove(key);
        } finally {
            lockManager.unLock(key);
        }
        if (result && statisticsEnabled()) {
            statistics.increaseCacheRemovals(1);
            statistics.addRemoveTimeNano(System.nanoTime() - start);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean remove(K key, V oldValue) {
        checkStatusStarted();
        long start = statisticsEnabled() ? System.nanoTime() : 0;
        boolean result;
        lockManager.lock(key);
        try {
            result = store.remove(key, oldValue);
        } finally {
            lockManager.unLock(key);
        }
        if (result && statisticsEnabled()) {
            statistics.increaseCacheRemovals(1);
            statistics.addRemoveTimeNano(System.nanoTime() - start);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V getAndRemove(K key) {
        checkStatusStarted();
        V result;
        lockManager.lock(key);
        try {
            result = store.getAndRemove(key);
        } finally {
            lockManager.unLock(key);
        }
        if (statisticsEnabled()) {
            if (result != null) {
                statistics.increaseCacheHits(1);
                statistics.increaseCacheRemovals(1);
            } else {
                statistics.increaseCacheMisses(1);
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        checkStatusStarted();
        boolean result;
        lockManager.lock(key);
        try {
            result = store.replace(key, oldValue, newValue);
        } finally {
            lockManager.unLock(key);
        }
        if (result && statisticsEnabled()) {
            statistics.increaseCachePuts(1);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean replace(K key, V value) {
        checkStatusStarted();
        boolean result;
        lockManager.lock(key);
        try {
            result = store.replace(key, value);
        } finally {
            lockManager.unLock(key);
        }
        if (result && statisticsEnabled()) {
            statistics.increaseCachePuts(1);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V getAndReplace(K key, V value) {
        checkStatusStarted();
        V result;
        lockManager.lock(key);
        try {
            result = store.getAndReplace(key, value);
        } finally {
            lockManager.unLock(key);
        }
        if (statisticsEnabled()) {
            if (result != null) {
                statistics.increaseCacheHits(1);
                statistics.increaseCachePuts(1);
            } else {
                statistics.increaseCacheMisses(1);
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeAll(Set<? extends K> keys) {
        checkStatusStarted();
        for (K key : keys) {
            lockManager.lock(key);
            try {
                store.remove(key);
            } finally {
                lockManager.unLock(key);
            }
        }
        if (statisticsEnabled()) {
            statistics.increaseCacheRemovals(keys.size());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeAll() {
        checkStatusStarted();
        int size = (statisticsEnabled()) ? store.size() : 0;
        //store.removeAll();
        Iterator<Map.Entry<K, V>> iterator = store.iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, V> entry = iterator.next();
            K key = entry.getKey();
            lockManager.lock(key);
            try {
                iterator.remove();
            } finally {
                lockManager.unLock(key);
            }
        }
        //possible race here but it is only stats
        if (statisticsEnabled()) {
            statistics.increaseCacheRemovals(size);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean registerCacheEntryListener(CacheEntryListener<? super K, ? super V> cacheEntryListener) {
        return cacheEntryListeners.add(cacheEntryListener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean unregisterCacheEntryListener(CacheEntryListener<?, ?> cacheEntryListener) {
        return cacheEntryListeners.remove(cacheEntryListener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object invokeEntryProcessor(K key, EntryProcessor<K, V> entryProcessor) {
        checkStatusStarted();
        if (key == null) {
            throw new NullPointerException();
        }
        if (key == entryProcessor) {
            throw new NullPointerException();
        }
        Object result = null;
        lockManager.lock(key);
        try {
        	SpyMutableEntry<K, V> entry = new SpyMutableEntry<K, V>(key, store);
            result = entryProcessor.process(entry);
            entry.commit();
        } finally {
            lockManager.unLock(key);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterator<Entry<K, V>> iterator() {
        checkStatusStarted();
        return new RIEntryIterator<K, V>(store.iterator(), lockManager);
    }

    @Override
    public CacheMXBean getMBean() {
        return mBean;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
    	if(servers == null){
    		throw new NullPointerException("servers is null");
    	}
    	List<InetSocketAddress> addresses = AddrUtil.getAddresses(servers);
		assert addresses != null;
		assert addresses.size() > 0;

		try {
			client = new MemcachedClient(addresses);

			store = new SpySimpleCache<K, V>(client);
			status = Status.STARTED;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        super.stop();
        store.removeAll();
        
        try {

			executorService.shutdown();
			executorService.awaitTermination(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			throw new CacheException(e);
		}

		try {
			if (client != null) {
				client.shutdown();
			}
		} catch (Exception e) {
			throw new CacheException(e);
		} finally {
			client = null;
		}
		store.removeAll();
		status = Status.STOPPED;
    }

    private void checkStatusStarted() {
        if (!status.equals(Status.STARTED)) {
            throw new IllegalStateException("The cache status is not STARTED");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Status getStatus() {
        return status;
    }

    @Override
    public <T> T unwrap(java.lang.Class<T> cls) {
        if (cls.isAssignableFrom(this.getClass())) {
            return cls.cast(this);
        }
        
        throw new IllegalArgumentException("Unwrapping to " + cls + " is not a supported by this implementation");
    }

    private boolean statisticsEnabled() {
        return getConfiguration().isStatisticsEnabled();
    }

    private V getInternal(K key) {
        long start = statisticsEnabled() ? System.nanoTime() : 0;

        V value = null;
        lockManager.lock(key);
        try {
            value = store.get(key);
        } finally {
            lockManager.unLock(key);
        }
        if (statisticsEnabled()) {
            statistics.addGetTimeNano(System.nanoTime() - start);
        }
        if (value == null) {
            if (statisticsEnabled()) {
                statistics.increaseCacheMisses(1);
            }
            if (getCacheLoader() != null) {
                return getFromLoader(key);
            } else {
                return null;
            }
        } else {
            if (statisticsEnabled()) {
                statistics.increaseCacheHits(1);
            }
            return value;
        }
    }

    private V getFromLoader(K key) {
        Entry<K, ? extends V> entry = getCacheLoader().load(key);
        if (entry != null) {
            store.put(entry.getKey(), entry.getValue());
            return entry.getValue();
        } else {
            return null;
        }
    }

    /**
     * Returns the size of the cache.
     *
     * @return the size in entries of the cache
     */
    long getSize() {
        return store.size();
    }

    /**
     * {@inheritDoc}
     *
     * @author Yannis Cosmadopoulos
     */
    private static class RIEntry<K, V> implements Entry<K, V> {
        private final K key;
        private final V value;

        public RIEntry(K key, V value) {
            if (key == null) {
                throw new NullPointerException("key");
            }
            this.key = key;
            this.value = value;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RIEntry<?, ?> e2 = (RIEntry<?, ?>) o;

            return this.getKey().equals(e2.getKey()) &&
                    this.getValue().equals(e2.getValue());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            return getKey().hashCode() ^ getValue().hashCode();
        }
    }

    /**
     * {@inheritDoc}
     * TODO: not obvious how iterator should behave with locking.
     * TODO: in the impl below, by the time we get the lock, value may be stale
     *
     * @author Yannis Cosmadopoulos
     */
    private static final class RIEntryIterator<K, V> implements Iterator<Entry<K, V>> {
        private final Iterator<Map.Entry<K, V>> mapIterator;
        private final LockManager<K> lockManager;

        private RIEntryIterator(Iterator<Map.Entry<K, V>> mapIterator, LockManager<K> lockManager) {
            this.mapIterator = mapIterator;
            this.lockManager = lockManager;
        }
        /**
         * {@inheritDoc}
         */
        @Override
        public boolean hasNext() {
            return mapIterator.hasNext();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Entry<K, V> next() {
            Map.Entry<K, V> mapEntry = mapIterator.next();
            K key = mapEntry.getKey();
            lockManager.lock(key);
            try {
                return new RIEntry<K, V>(key, mapEntry.getValue());
            } finally {
                lockManager.unLock(key);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void remove() {
            mapIterator.remove();
        }
    }

    /**
     * Callable used for cache loader.
     *
     * @param <K> the type of the key
     * @param <V> the type of the value
     * @author Yannis Cosmadopoulos
     */
    private static class SpyCacheLoaderLoadCallable<K, V> implements Callable<V> {
        private final SpyCache<K, V> cache;
        private final CacheLoader<K, ? extends V> cacheLoader;
        private final K key;

        SpyCacheLoaderLoadCallable(SpyCache<K, V> cache, CacheLoader<K, ? extends V> cacheLoader, K key) {
            this.cache = cache;
            this.cacheLoader = cacheLoader;
            this.key = key;
        }

        @Override
        public V call() throws Exception {
            Entry<K, ? extends V> entry = cacheLoader.load(key);
            cache.put(entry.getKey(), entry.getValue());
            return entry.getValue();
        }
    }

    /**
     * Callable used for cache loader.
     *
     * @param <K> the type of the key
     * @param <V> the type of the value
     * @author Yannis Cosmadopoulos
     */
    private static class SpyCacheLoaderLoadAllCallable<K, V> implements Callable<Map<K, ? extends V>> {
        private final SpyCache<K, V> cache;
        private final CacheLoader<K, ? extends V> cacheLoader;
        private final Collection<? extends K> keys;

        SpyCacheLoaderLoadAllCallable(SpyCache<K, V> cache, CacheLoader<K, ? extends V> cacheLoader, Collection<? extends K> keys) {
            this.cache = cache;
            this.cacheLoader = cacheLoader;
            this.keys = keys;
        }

        @Override
        public Map<K, ? extends V> call() throws Exception {
            ArrayList<K> keysNotInStore = new ArrayList<K>();
            for (K key : keys) {
                if (!cache.containsKey(key)) {
                    keysNotInStore.add(key);
                }
            }
            Map<K, ? extends V> value = cacheLoader.loadAll(keysNotInStore);
            cache.putAll(value);
            return value;
        }
    }

    /**
     * A Builder for RICache.
     *
     * @param <K>
     * @param <V>
     * @author Yannis Cosmadopoulos
     */
    public static class Builder<K, V> extends AbstractCache.Builder<K, V> {
        private final Set<CacheEntryListener<K, V>> listeners = new CopyOnWriteArraySet<CacheEntryListener<K, V>>();
        private final String servers;
        
        /**
         * Construct a builder.
         *
         * @param cacheName        the name of the cache to be built
         * @param cacheManagerName the name of the cache manager
         * @param classLoader the class loader
         */
        public Builder(String cacheName, String cacheManagerName, ClassLoader classLoader,String servers) {
            this(cacheName, cacheManagerName, classLoader, new SpyCacheConfiguration.Builder(),servers);
        }

        private Builder(String cacheName, String cacheManagerName, ClassLoader classLoader,
        		SpyCacheConfiguration.Builder configurationBuilder,String servers) {
            super(cacheName, cacheManagerName, classLoader, configurationBuilder);
            this.servers = servers;
        }

        /**
         * Builds the cache
         *
         * @return a constructed cache.
         */
        @Override
        public SpyCache<K, V> build() {
            CacheConfiguration<K, V> configuration = createCacheConfiguration();
            SpyCache<K, V> riCache = new SpyCache<K, V>(cacheName, cacheManagerName,
                classLoader, configuration,
                cacheLoader, cacheWriter, listeners,servers);
            ((SpyCacheConfiguration) configuration).setRiCache(riCache);
            return riCache;
        }

        @Override
        public Builder<K, V> registerCacheEntryListener(CacheEntryListener<K, V> listener) {
            listeners.add(listener);
            return this;
        }
    }

    /**
     * Simple lock management
     * @param <K> the type of the object to be locked
     * @author Yannis Cosmadopoulos
     * @since 1.0
     */
    private static final class LockManager<K> {
        private final ConcurrentHashMap<K, ReentrantLock> locks = new ConcurrentHashMap<K, ReentrantLock>();
        private final LockFactory lockFactory = new LockFactory();

        private LockManager() {
        }

        /**
         * Lock the object
         * @param key the key
         */
        private void lock(K key) {
            ReentrantLock lock = lockFactory.getLock();

            while (true) {
                ReentrantLock oldLock = locks.putIfAbsent(key, lock);
                if (oldLock == null) {
                    return;
                }
                // there was a lock
                oldLock.lock();
                // now we have it. Because of possibility that someone had it for remove,
                // we don't re-use directly
                lockFactory.release(oldLock);
            }
        }

        /**
         * Unlock the object
         * @param key the object
         */
        private void unLock(K key) {
            ReentrantLock lock = locks.remove(key);
            lockFactory.release(lock);
        }

        /**
         * Factory/pool
         * @author Yannis Cosmadopoulos
         * @since 1.0
         */
        private static final class LockFactory {
            private static final int CAPACITY = 100;
            private static final ArrayList<ReentrantLock> LOCKS = new ArrayList<ReentrantLock>(CAPACITY);

            private LockFactory() {
            }

            private ReentrantLock getLock() {
                ReentrantLock qLock = null;
                synchronized (LOCKS) {
                    if (!LOCKS.isEmpty()) {
                        qLock = LOCKS.remove(0);
                    }
                }

                ReentrantLock lock = qLock != null ? qLock : new ReentrantLock();
                lock.lock();
                return lock;
            }

            private void release(ReentrantLock lock) {
                lock.unlock();
                synchronized (LOCKS) {
                    if (LOCKS.size() <= CAPACITY) {
                        LOCKS.add(lock);
                    }
                }
            }
        }
    }

    /**
     * A mutable entry
     * @param <K>
     * @param <V>
     * @author Yannis Cosmadopoulos
     * @since 1.0
     */
    private static class SpyMutableEntry<K, V> implements MutableEntry<K, V> {
        private final K key;
        private V value;
        private final SpySimpleCache<K, V> store;
        private boolean exists;
        private boolean remove;

        SpyMutableEntry(K key, SpySimpleCache<K, V> store) {
            this.key = key;
            this.store = store;
            exists = store.containsKey(key);
        }

        private void commit() {
            if (remove) {
                store.remove(key);
            } else if (value != null) {
                store.put(key, value);
            }
        }

        @Override
        public boolean exists() {
            return exists;
        }

        @Override
        public void remove() {
            remove = true;
            exists = false;
            value = null;
        }

        @Override
        public void setValue(V value) {
            if (value == null) {
                throw new NullPointerException();
            }
            exists = true;
            remove = false;
            this.value = value;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value != null ? value : store.get(key);
        }
    }
}