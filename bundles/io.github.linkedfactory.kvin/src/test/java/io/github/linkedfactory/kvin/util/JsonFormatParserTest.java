/*
 * Copyright (c) 2023 Fraunhofer IWU.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.linkedfactory.kvin.util;

import io.github.linkedfactory.kvin.KvinTuple;
import io.github.linkedfactory.kvin.Record;
import net.enilink.commons.iterator.IExtendedIterator;
import net.enilink.komma.core.URIs;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.*;
import static org.junit.Assert.fail;

public class JsonFormatParserTest {

    @Test
    public void shouldParseJson() throws IOException {
        JsonFormatParser jsonParser = new JsonFormatParser(
            getClass().getClassLoader().getResourceAsStream("JsonFormatParserTestContent.json"));
        IExtendedIterator<KvinTuple> tuples = jsonParser.parse();
        assertNotNull(tuples);
        int index = 0;
        while (tuples.hasNext()) {
            KvinTuple t = tuples.next();
            if (index == 2) {
                assertTrue(t.value instanceof Integer);
            } else if (index == 3) {
                assertTrue(t.value instanceof BigInteger);
            } else if (index == 4) {
                assertTrue(t.value instanceof BigDecimal);
            } else if (index == 5) {
                assertTrue(t.value instanceof Long);
            } else if (index == 6) {
                assertTrue(t.value instanceof Boolean);
            } else if (index == 7 || index == 10) {
                assertTrue(t.value instanceof Record);
            }
            index++;
        }
        assertEquals(index, 11);
    }
}
