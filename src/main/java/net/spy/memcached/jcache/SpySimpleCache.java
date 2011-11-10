package net.spy.memcached.jcache;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;

import net.spy.memcached.MemcachedClient;
import net.spy.memcached.internal.OperationFuture;

public class SpySimpleCache<K, V> implements SimpleCache<K, V> {
	private final MemcachedClient client;
	private int expiry = 3600;

	public SpySimpleCache(MemcachedClient client) {
		this.client = client;
	}

	@Override
	public Iterator<Entry<K, V>> iterator() {
		throw new UnsupportedOperationException();

	}

	@Override
	public V get(Object key) {
		V value = (V) client.get(key.toString());
		return value;
	}

	@Override
	public boolean containsKey(Object key) {
		throw new UnsupportedOperationException();

	}

	@Override
	public void put(K key, V value) {
		OperationFuture<Boolean> of = client.add(key.toString(), expiry, value);
		try {
			of.get();
		} catch (InterruptedException e) {
			of.cancel(false);
		} catch (ExecutionException e) {
			of.cancel(false);
		}
	}

	@Override
	public V getAndPut(K key, V value) {
		throw new UnsupportedOperationException();

	}

	@Override
	public void putAll(Map<? extends K, ? extends V> map) {
		throw new UnsupportedOperationException();

	}

	@Override
	public boolean putIfAbsent(K key, V value) {
		throw new UnsupportedOperationException();

	}

	@Override
	public boolean remove(Object key) {
		OperationFuture<Boolean> of = client.delete(key.toString());
		try {
			return of.get();
		} catch (InterruptedException e) {
			of.cancel(false);
			return false;
		} catch (ExecutionException e) {
			of.cancel(false);
			return false;
		}
	}

	@Override
	public boolean remove(Object key, V oldValue) {
		OperationFuture<Boolean> of = client.delete(key.toString());
		try {
			return of.get();
		} catch (InterruptedException e) {
			of.cancel(false);
			return false;
		} catch (ExecutionException e) {
			of.cancel(false);
			return false;
		}
	}

	@Override
	public V getAndRemove(Object key) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean replace(K key, V oldValue, V newValue) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean replace(K key, V value) {
		throw new UnsupportedOperationException();
	}

	@Override
	public V getAndReplace(K key, V value) {
		throw new UnsupportedOperationException();

	}

	@Override
	public int size() {
		return 0;
	}

	@Override
	public void removeAll() {
		throw new UnsupportedOperationException();
	}

}
