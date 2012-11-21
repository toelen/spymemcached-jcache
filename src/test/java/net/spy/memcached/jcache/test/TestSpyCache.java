package net.spy.memcached.jcache.test;

import static org.junit.Assert.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.cache.Cache;
import javax.cache.CacheManager;

import net.spy.memcached.MemcachedClient;
import net.spy.memcached.jcache.SpyCachingProvider;

import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import com.thimbleware.jmemcached.CacheImpl;
import com.thimbleware.jmemcached.Key;
import com.thimbleware.jmemcached.LocalCacheElement;
import com.thimbleware.jmemcached.MemCacheDaemon;
import com.thimbleware.jmemcached.storage.CacheStorage;
import com.thimbleware.jmemcached.storage.hash.ConcurrentLinkedHashMap;

public class TestSpyCache {
	private static CacheManager cacheManager;
	private static Cache<String, String> cache;

	@BeforeClass
	public static void setup() throws IOException {
		int port = 11212;
		// create daemon and start it
		final MemCacheDaemon<LocalCacheElement> daemon = new MemCacheDaemon<LocalCacheElement>();

		CacheStorage<Key, LocalCacheElement> storage = ConcurrentLinkedHashMap
				.create(ConcurrentLinkedHashMap.EvictionPolicy.FIFO, 10000,
						10000);
		daemon.setCache(new CacheImpl(storage));
		// daemon.setBinary(true);
		daemon.setAddr(new InetSocketAddress("localhost", port));
		// daemon.setIdleTime(10000);
		// daemon.setVerbose(true);
		daemon.start();

		String servers = "localhost:" + port;
		System.setProperty("spymemcachedservers", servers);

		SpyCachingProvider provider = new SpyCachingProvider();
		assertNotNull(provider);

		// List<InetSocketAddress> addr = new ArrayList<InetSocketAddress>();
		// addr.add(new InetSocketAddress("localhost", port));
		// MemcachedClient client = new MemcachedClient(addr);
		// AddrUtil.getAddresses(servers));

		ClassLoader loader = TestSpyCache.class.getClassLoader();

		cacheManager = provider.getCacheManagerFactory().getCacheManager(
				loader, "default");
		assertNotNull(cacheManager);
		cache = cacheManager.<String, String> createCacheBuilder("cacheName")
				.build();
		assertNotNull(cache);
	}

	@Test
	public void testNonExisting() {
		String value = cache.get(UUID.randomUUID().toString());
		assertNull(value);
	}

	@Test
	public void testNonExistingAndPut() {
		String random = UUID.randomUUID().toString();
		String value = cache.get(random);
		assertNull(value);
		cache.put(random, random);
		String newValue = cache.get(random);
		assertEquals(random, newValue);
	}
	
	@Ignore
	public void testGetAndRemove() {
		String random = UUID.randomUUID().toString();
		String value = cache.get(random);
		assertNull(value);
		cache.put(random, random);
		String newValue = cache.get(random);
		assertEquals(random, newValue);
		
		// Not supported for now
		String oldValue = cache.getAndRemove(random);
		assertEquals(value, oldValue);
		
		String valueAfterRemove = cache.get(random);
		assertNull(valueAfterRemove);
	}

	@Test
	public void testPutAndGet() {
		String key = UUID.randomUUID().toString();
		cache.put(key, key);
		String value = cache.get(key);
		assertNotNull(value);
		assertEquals(key, value);
	}

	@Test
	public void testPutAndGetMultiple() {
		String key1 = UUID.randomUUID().toString();
		String key2 = UUID.randomUUID().toString();
		String key3 = UUID.randomUUID().toString();

		Set<String> keys = new LinkedHashSet<String>();
		keys.add(key1);
		keys.add(key2);
		keys.add(key3);

		Map<String, String> map = new LinkedHashMap<String, String>();
		map.put(key1, key1);
		map.put(key2, key2);
		map.put(key3, key3);

		Map<String, String> values = cache.getAll(keys);
		assertNotNull(values);
		assertEquals("Result for non-inserted random keys should be 0", 0,
				values.size());

		// Now insert all values
		cache.putAll(map);

		// And get them in bulk
		Map<String, String> newValues = cache.getAll(keys);
		assertNotNull(newValues);
		assertEquals(map.size(), newValues.size());

		assertEquals("value for key1 is wrong", key1, newValues.get(key1));
		assertEquals("value for key2 is wrong", key2, newValues.get(key2));
		assertEquals("value for key3 is wrong", key3, newValues.get(key3));

		cache.removeAll(keys);

		Map<String, String> valuesAfterRemove = cache.getAll(keys);
		assertNotNull(valuesAfterRemove);
		assertEquals("Keys have been removed. Size of getAll(k) should be 0",
				0, valuesAfterRemove.size());
	}

	@Test
	public void testPutExistingValue() {
		String key = UUID.randomUUID().toString();
		cache.put(key, key);
		String value = cache.get(key);
		assertNotNull(value);
		assertEquals(key, value);

		String nextvalue = UUID.randomUUID().toString();
		cache.put(key, nextvalue);
		String nextvaluefromcache = cache.get(key);
		assertEquals("Cache.put should overwrite an existing value", nextvalue,
				nextvaluefromcache);
	}

	@Ignore
	public void testGetAndReplace() {
		String key = UUID.randomUUID().toString();
		String value = UUID.randomUUID().toString();
		String nextvalue = UUID.randomUUID().toString();
		cache.put(key, value);

		// Not supported for now
		String oldValue = cache.getAndReplace(key, nextvalue);
		assertEquals(value, oldValue);
		oldValue = cache.get(key);
		assertEquals(value, nextvalue);
	}
}
