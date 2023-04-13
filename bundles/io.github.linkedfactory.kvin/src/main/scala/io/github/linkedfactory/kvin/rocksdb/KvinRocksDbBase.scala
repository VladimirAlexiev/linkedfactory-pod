/*
 * Copyright (c) 2022 Fraunhofer IWU.
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
package io.github.linkedfactory.kvin.rocksdb

import io.github.linkedfactory.kvin.KvinTuple
import net.enilink.commons.iterator.NiceIterator
import net.enilink.komma.core.URI

import java.nio.{ByteBuffer, ByteOrder}
import java.util
import java.util.Collections
import java.util.concurrent.{ConcurrentHashMap, Executors, ScheduledExecutorService}
import scala.util.matching.Regex
import org.rocksdb.{Options, RocksDB, RocksIterator, WriteBatch, WriteOptions}

/**
 * Common base functions for LevelDB based value stores.
 */
trait KvinRocksDbBase {
  val TIME_BYTES = 6
  val SEQ_BYTES = 2

  val LONG_BYTES: Int = java.lang.Long.SIZE / 8
  val BYTE_ORDER: ByteOrder = ByteOrder.BIG_ENDIAN
  val ID_POOL_SIZE = 1000L

  val waitingForTTL: util.Set[ByteBuffer] = Collections.newSetFromMap[ByteBuffer](new ConcurrentHashMap[ByteBuffer, java.lang.Boolean])
  val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor

  abstract class StoreIterator[T](base: RocksIterator) extends NiceIterator[T] {
    var open = true
    var current: Option[T] = None
    var initialized: Boolean = false

    def init(): Unit = {}

    override def hasNext: Boolean = {
      if (!initialized) {
        // prepare this iterator
        try {
          init()
        } catch {
          case t: Throwable => close(); throw t
        }
        initialized = true
      }

      if (current.isDefined) true
      else if (open && base.isValid) {
        current = computeNext
        base.next
        if (current.isDefined) true else {
          close()
          false
        }
      } else {
        if (open) close()
        false
      }
    }

    override def next: T = if (hasNext) {
      val result = current.get
      current = None
      result
    } else throw new NoSuchElementException

    def computeNext: Option[T]

    override def close(): Unit = {
      if (open) {
        try {
          base.close()
        } finally {
          open = false
        }
      }
    }
  }

  protected def putLong(db: RocksDB, key: Array[Byte], value: Long): Unit = {
    val bb = ByteBuffer.wrap(new Array[Byte](LONG_BYTES)).order(BYTE_ORDER)
    bb.putLong(value)
    db.put(key, bb.array)
  }

  protected def readLong(db: RocksDB, key: Array[Byte]): Long = {
    val longBytes = db.get(key)
    if (longBytes == null) -1L else {
      ByteBuffer.wrap(longBytes).order(BYTE_ORDER).getLong
    }
  }

  // TTL support

  val TTL: Regex = "(?:^|&)ttl=([0-9]+)(ns|us|ms|s|m|d)".r
  protected def ttl(uri: URI): Option[Long] = {
    val query = uri.query
    if (query == null) None else query match {
      case TTL(durationStr, unit) =>
        val d = durationStr.toLong
        Some(unit match {
          case "ns" => d / 1000000L
          case "us" => d / 1000L
          case "ms" => d
          case "s" => d * 1000L
          case "m" => d * 60000L
          case "d" => d * 1440000L
        })
      case _ => None
    }
  }

  protected def asyncRemoveByTtl(db: RocksDB, prefix: Array[Byte], ttl: Long): Unit = {
    val key = ByteBuffer.wrap(prefix)
    if (waitingForTTL.add(key)) {
      executor.submit(new Runnable {
        def run(): Unit = {
          removeByTtl(db, prefix, ttl)
          waitingForTTL.remove(key)
        }
      })
    }
  }

  protected def removeByTtl(db: RocksDB, prefix: Array[Byte], ttl: Long): Unit = {
    val now = System.currentTimeMillis
    val eldest = now - ttl
    val idTimePrefix = new Array[Byte](prefix.length + TIME_BYTES)
    val it = db.newIterator()
    try {
      var done = false
      it.seek(idTimePrefix)
      while (it.isValid && !done) {
        it.next
        val key = it.key()
        if (key.startsWith(prefix)) {
          db.delete(key)
        } else done = true
      }
    } finally {
      it.close()
    }
  }

  def mapTime(time: Long): Long = KvinTuple.TIME_MAX_VALUE - time

  def mapSeq(seq: Int): Int = KvinTuple.SEQ_MAX_VALUE - seq
}
