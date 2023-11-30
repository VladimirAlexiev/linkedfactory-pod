package io.github.linkedfactory.kvin.partitioned;

import io.github.linkedfactory.kvin.Kvin;
import io.github.linkedfactory.kvin.KvinTuple;
import io.github.linkedfactory.kvin.leveldb.KvinLevelDbArchiver;
import io.github.linkedfactory.kvin.util.KvinTupleGenerator;
import net.enilink.commons.iterator.IExtendedIterator;
import net.enilink.commons.iterator.NiceIterator;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@Ignore
public class KvinPartitionedTest {
	static String itemTemplate = "http://localhost:8080/linkedfactory/demofactory/{}";
	static String propertyTemplate = "http://example.org/{}";
	KvinTupleGenerator tupleGenerator;
	KvinPartitioned kvinPartitioned;
	File tempDir;

	@Before
	public void setup() throws IOException {
		tempDir = Files.createTempDirectory("kvinPartitioned").toFile();
		tupleGenerator = new KvinTupleGenerator();
		kvinPartitioned = new KvinPartitioned(tempDir, 2, TimeUnit.SECONDS); // archive at every 2 seconds
	}

	@After
	public void cleanup() throws IOException {
		kvinPartitioned.close();
		FileUtils.deleteDirectory(tempDir);
	}

	@Test
	public void shouldDoPut() {
		// continuing incremental put on kvinPartitioned
		kvinPartitioned.put(tupleGenerator.generate(1672614000L, 10, 10, 10,
				itemTemplate, propertyTemplate));
		kvinPartitioned.runArchival();
		kvinPartitioned.put(tupleGenerator.generate(1673218800L, 10, 10, 10,
				itemTemplate, propertyTemplate));
		kvinPartitioned.runArchival();
		kvinPartitioned.put(tupleGenerator.generate(1673823600L, 10, 10, 10,
				itemTemplate, propertyTemplate));

		NiceIterator<KvinTuple> storeIterator = new KvinLevelDbArchiver(kvinPartitioned.hotStore, null)
				.getDatabaseIterator();
		assertTrue(kvinPartitioned.archiveStorePath.listFiles().length > 0); // main folder
		assertEquals(2, new File(kvinPartitioned.archiveStorePath, "2023").listFiles(f -> f.isDirectory()).length); // folder for year 2023
		int recordCount = 0;
		while (storeIterator.hasNext()) {
			storeIterator.next();
			recordCount++;
		}
		assertEquals(recordCount, 10);
	}

	@Test
	public void shouldDoFetch() {
		kvinPartitioned.put(tupleGenerator.generate(1672614000L, 10, 10, 10,
				itemTemplate, propertyTemplate));
		kvinPartitioned.runArchival();
		kvinPartitioned.put(tupleGenerator.generate(1673218800L, 10, 10, 10,
				itemTemplate, propertyTemplate));

		URI item = URIs.createURI("http://localhost:8080/linkedfactory/demofactory/" + 1);
		URI property = URIs.createURI("http://example.org/" + 1 + "/measured-point-1");
		long limit = 0;

		IExtendedIterator<KvinTuple> tuples = kvinPartitioned.fetch(item, property, Kvin.DEFAULT_CONTEXT, limit);

		assertNotNull(tuples);
		assertTrue(tuples.toList().size() > 0);
	}

	@Test
	public void shouldFetchProperties() {
		kvinPartitioned.put(tupleGenerator.generate(1672614000L, 10, 10, 10,
				itemTemplate, propertyTemplate));
		kvinPartitioned.runArchival();
		kvinPartitioned.put(tupleGenerator.generate(1673218800L, 10, 10, 10,
				itemTemplate, propertyTemplate));

		URI item = URIs.createURI("http://localhost:8080/linkedfactory/demofactory/" + 5);
		IExtendedIterator<URI> properties = kvinPartitioned.properties(item);

		assertNotNull(properties);
		assertTrue(properties.toList().size() > 0);
	}
}
