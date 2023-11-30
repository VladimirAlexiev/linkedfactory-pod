package io.github.linkedfactory.kvin.partitioned;

import io.github.linkedfactory.kvin.Kvin;
import io.github.linkedfactory.kvin.KvinListener;
import io.github.linkedfactory.kvin.KvinTuple;
import io.github.linkedfactory.kvin.leveldb.KvinLevelDbArchiver;
import io.github.linkedfactory.kvin.leveldb.KvinLevelDb;
import io.github.linkedfactory.kvin.parquet.KvinParquet;
import io.github.linkedfactory.kvin.util.AggregatingIterator;
import net.enilink.commons.iterator.IExtendedIterator;
import net.enilink.commons.iterator.NiceIterator;
import net.enilink.commons.iterator.WrappedIterator;
import net.enilink.commons.util.Pair;
import net.enilink.komma.core.URI;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class KvinPartitioned implements Kvin {
	final ReadWriteLock storeLock = new ReentrantReadWriteLock();
	protected List<KvinListener> listeners = new ArrayList<>();
	protected ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(0);
	protected Future<?> archivalTaskfuture;
	protected File path;
	protected File currentStorePath, currentStoreArchivePath, archiveStorePath;
	protected volatile KvinLevelDb hotStore, hotStoreArchive;
	protected KvinParquet archiveStore;

	public KvinPartitioned(File path, long archivalSize, TimeUnit timeUnit) throws IOException {
		this.path = path;
		this.currentStorePath = new File(path, "current");
		this.currentStoreArchivePath = new File(path, "current-archive");
		this.archiveStorePath = new File(path, "archive");
		Files.createDirectories(this.currentStorePath.toPath());
		hotStore = new KvinLevelDb(this.currentStorePath);
		hotStoreArchive = hotStore;
		archiveStore = new KvinParquet(archiveStorePath.toString());
		// archivalTaskfuture = scheduledExecutorService.scheduleAtFixedRate(this::runArchival, archivalSize, archivalSize, timeUnit);
	}

	void runArchival() {
		try {
			storeLock.writeLock().lock();
			createNewHotDataStore();
			new KvinLevelDbArchiver(hotStoreArchive, archiveStore).archive();
			this.hotStoreArchive.close();
			this.hotStoreArchive = null;
			FileUtils.deleteDirectory(this.currentStoreArchivePath);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			storeLock.writeLock().unlock();
		}
	}

	@Override
	public boolean addListener(KvinListener listener) {
		try {
			listeners.add(listener);
			return true;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public boolean removeListener(KvinListener listener) {
		try {
			listeners.remove(listener);
			return true;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void put(KvinTuple... tuples) {
		this.put(Arrays.asList(tuples));
	}

	@Override
	public void put(Iterable<KvinTuple> tuples) {
		hotStore.put(tuples);
	}

	public void createNewHotDataStore() throws IOException {
		hotStore.close();
		FileUtils.deleteDirectory(this.currentStoreArchivePath);
		Files.move(this.currentStorePath.toPath(), this.currentStoreArchivePath.toPath());
		hotStore = new KvinLevelDb(currentStorePath);
		hotStoreArchive = new KvinLevelDb(this.currentStoreArchivePath);
	}

	@Override
	public IExtendedIterator<KvinTuple> fetch(URI item, URI property, URI context, long limit) {
		return fetch(item, property, context, KvinTuple.TIME_MAX_VALUE, 0, limit, 0, null);
	}

	@Override
	public IExtendedIterator<KvinTuple> fetch(URI item, URI property, URI context, long end, long begin, long limit, long interval, String op) {
		IExtendedIterator<KvinTuple> internalResult = fetchInternal(item, property, context, end, begin, limit);
		if (op != null) {
			internalResult = new AggregatingIterator<>(internalResult, interval, op.trim().toLowerCase(), limit) {
				@Override
				protected KvinTuple createElement(URI item, URI property, URI context, long time, int seqNr, Object value) {
					return new KvinTuple(item, property, context, time, seqNr, value);
				}
			};
		}
		return internalResult;
	}

	protected IExtendedIterator<KvinTuple> fetchInternal(URI item, URI property, URI context, long end, long begin, long limit) {
		return new NiceIterator<>() {
			final PriorityQueue<Pair<KvinTuple, IExtendedIterator<KvinTuple>>> nextTuples = new PriorityQueue<>(
					Comparator.comparing(Pair::getFirst, (a, b) -> {
						int diff = a.property.equals(b.property) ? 0 : a.toString().compareTo(b.toString());
						if (diff != 0) {
							return diff;
						}
						diff = Long.compare(a.time, b.time);
						if (diff != 0) {
							// time is reverse
							return -diff;
						}
						diff = Integer.compare(a.seqNr, b.seqNr);
						if (diff != 0) {
							// seqNr is reverse
							return -diff;
						}
						return 0;
					}));
			long propertyValueCount;
			URI currentProperty;
			KvinTuple nextTuple;

			@Override
			public boolean hasNext() {
				if (nextTuple != null) {
					return true;
				}
				if (currentProperty == null) {
					// this is the case if the iterator is not yet initialized
					storeLock.readLock().lock();
					List<Kvin> stores = new ArrayList<>();
					stores.add(hotStore);
					if (hotStoreArchive != null) {
						stores.add(hotStoreArchive);
					}
					stores.add(archiveStore);
					for (Kvin store : stores) {
						IExtendedIterator<KvinTuple> storeTuples = store.fetch(item, property, context, end, begin,
								limit, 0L, null);
						if (storeTuples.hasNext()) {
							nextTuples.add(new Pair<>(storeTuples.next(), storeTuples));
						} else {
							storeTuples.close();
						}
					}
				}
				// skip properties if limit is reached
				if (limit != 0 && propertyValueCount >= limit) {
					while (!nextTuples.isEmpty()) {
						var next = nextTuples.poll();
						nextTuple = next.getFirst();
						if (next.getSecond().hasNext()) {
							nextTuples.add(new Pair<>(next.getSecond().next(), next.getSecond()));
						} else {
							next.getSecond().close();
						}
						if (!nextTuple.property.equals(currentProperty)) {
							propertyValueCount = 1;
							currentProperty = nextTuple.property;
							break;
						}
					}
				}
				if (nextTuple == null && !nextTuples.isEmpty()) {
					var next = nextTuples.poll();
					nextTuple = next.getFirst();
					if (currentProperty == null || !nextTuple.property.equals(currentProperty)) {
						propertyValueCount = 1;
						currentProperty = nextTuple.property;
					}
					if (next.getSecond().hasNext()) {
						nextTuples.add(new Pair<>(next.getSecond().next(), next.getSecond()));
					} else {
						next.getSecond().close();
					}
					propertyValueCount++;
				}
				return nextTuple != null;
			}

			@Override
			public KvinTuple next() {
				if (hasNext()) {
					KvinTuple result = nextTuple;
					nextTuple = null;
					return result;
				}
				throw new NoSuchElementException();
			}

			@Override
			public void close() {
				try {
					while (!nextTuples.isEmpty()) {
						try {
							nextTuples.poll().getSecond().close();
						} catch (Exception e) {
							// TODO log exception
						}
					}
				} finally {
					storeLock.readLock().unlock();
				}
			}
		};
	}

	@Override
	public long delete(URI item, URI property, URI context, long end, long begin) {
		try {
			storeLock.readLock().lock();
			return hotStore.delete(item, property, context, end, begin);
		} finally {
			storeLock.readLock().unlock();
		}
	}

	@Override
	public boolean delete(URI item) {
		try {
			storeLock.readLock().lock();
			return hotStore.delete(item);
		} finally {
			storeLock.readLock().unlock();
		}
	}

	@Override
	public IExtendedIterator<URI> descendants(URI item) {
		storeLock.readLock().lock();
		IExtendedIterator<URI> results = hotStore.descendants(item);
		return new NiceIterator<>() {
			@Override
			public boolean hasNext() {
				return results.hasNext();
			}

			@Override
			public URI next() {
				return results.next();
			}

			@Override
			public void close() {
				try {
					results.close();
				} finally {
					storeLock.readLock().unlock();
				}
			}
		};
	}

	@Override
	public IExtendedIterator<URI> descendants(URI item, long limit) {
		storeLock.readLock().lock();
		IExtendedIterator<URI> results = hotStore.descendants(item, limit);
		return new NiceIterator<>() {
			@Override
			public boolean hasNext() {
				return results.hasNext();
			}

			@Override
			public URI next() {
				return results.next();
			}

			@Override
			public void close() {
				try {
					results.close();
				} finally {
					storeLock.readLock().unlock();
				}
			}
		};
	}

	@Override
	public IExtendedIterator<URI> properties(URI item) {
		Set<URI> properties = new HashSet<>();
		try {
			storeLock.readLock().lock();
			properties.addAll(hotStore.properties(item).toList());
			if (hotStoreArchive != null) {
				properties.addAll(hotStoreArchive.properties(item).toList());
			}
			properties.addAll(archiveStore.properties(item).toList());
		} finally {
			storeLock.readLock().unlock();
		}
		return WrappedIterator.create(properties.iterator());
	}

	@Override
	public long approximateSize(URI item, URI property, URI context, long end, long begin) {
		return 0;
	}

	@Override
	public void close() {
		try {
			storeLock.readLock().lock();
			hotStore.close();
			if (hotStoreArchive != null) {
				hotStoreArchive.close();
			}
		} finally {
			storeLock.readLock().unlock();
		}
	}
}
