package io.github.linkedfactory.core.rdf4j.common;

import org.eclipse.rdf4j.model.impl.SimpleBNode;

import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class BNodeWithValue extends SimpleBNode implements HasValue {

	private static WeakHashMap<Object, BNodeWithValue> cache = new WeakHashMap<>();

	private static final String uniqueIdPrefix = UUID.randomUUID().toString().replace("-", "");
	private static final AtomicLong uniqueIdSuffix = new AtomicLong();
	protected final Object value;

	private BNodeWithValue(Object value) {
		super(generateId());
		this.value = value;
	}

	static String generateId() {
		return uniqueIdPrefix + uniqueIdSuffix.incrementAndGet();
	}

	public static BNodeWithValue create(Object value, boolean useCache) {
		if (useCache) {
			synchronized (cache) {
				return cache.computeIfAbsent(value, v -> new BNodeWithValue(v));
			}
		} else {
			return new BNodeWithValue(value);
		}
	}

	@Override
	public Object getValue() {
		return value;
	}
}
