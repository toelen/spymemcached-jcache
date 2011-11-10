package net.spy.memcached.jcache.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.UUID;

import javax.cache.Cache;
import javax.cache.CacheManager;

import net.spy.memcached.jcache.SpyCachingProvider;

import org.junit.BeforeClass;
import org.junit.Test;

public class TestSpyCache {
	private static CacheManager cacheManager;
	private static Cache<String, String> cache;

	@BeforeClass
	public static void setup() {
		System.setProperty("spymemcachedservers", "localhost:11211");
		SpyCachingProvider provider = new SpyCachingProvider();
		assertNotNull(provider);
		cacheManager = provider.createCacheManager(
				provider.getDefaultClassLoader(), "default");
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
	public void testPutAndGet() {
		String key = UUID.randomUUID().toString();
		cache.put(key, key);
		String value = cache.get(key);
		assertNotNull(value);
		assertEquals(key, value);
	}
}
