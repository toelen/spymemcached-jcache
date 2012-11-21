package net.spy.memcached.jcache;

import javax.cache.CacheManager;
import javax.cache.implementation.AbstractCacheManagerFactory;

public class SpyCacheManagerFactory extends AbstractCacheManagerFactory {
	private static final SpyCacheManagerFactory INSTANCE = new SpyCacheManagerFactory();

	private SpyCacheManagerFactory() {
	}

	@Override
	protected CacheManager createCacheManager(ClassLoader classLoader,
			String name) {
		String servers = System.getProperty("spymemcachedservers");
		if (servers == null || servers.trim().length() == 0) {
			throw new NullPointerException(
					"spymemcachedservers system property not specified");
		}
		SpyCacheManager mgr = new SpyCacheManager(name,classLoader);
		mgr.setServers(servers);
		mgr.start();
		return mgr;
	}

	@Override
	protected ClassLoader getDefaultClassLoader() {
		return Thread.currentThread().getContextClassLoader();
	}

	/**
	 * Get the singleton instance
	 * 
	 * @return the singleton instance
	 */
	public static SpyCacheManagerFactory getInstance() {
		return INSTANCE;
	}
}
