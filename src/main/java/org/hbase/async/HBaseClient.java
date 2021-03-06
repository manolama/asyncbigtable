/*
 * Copyright (C) 2015  The Async BigTable Authors.  All rights reserved.
 * This file is part of Async BigTable.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the StumbleUpon nor the names of its contributors
 *     may be used to endorse or promote products derived from this software
 *     without specific prior written permission.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.hbase.async;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.CompareOperator;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Append;
import org.apache.hadoop.hbase.client.AsyncAdmin;
import org.apache.hadoop.hbase.client.AsyncBufferedMutator;
import org.apache.hadoop.hbase.client.AsyncConnection;
import org.apache.hadoop.hbase.client.AsyncTable;
import org.apache.hadoop.hbase.client.AsyncTable.CheckAndMutateBuilder;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.MultiAction;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.ScanResultConsumer;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hbase.thirdparty.io.netty.util.HashedWheelTimer;
import org.apache.hbase.thirdparty.io.netty.util.Timeout;
import org.apache.hbase.thirdparty.io.netty.util.Timer;
import org.apache.hbase.thirdparty.io.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.cloud.bigtable.hbase.BigtableConfiguration;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;

/**
 * A shim between projects using AsyncBigTable (such as OpenTSDB) and Google's
 * BigTable API. The client is meant to be a drop in replacement with minor 
 * changes required regarding configuration and dependencies.
 */
public final class HBaseClient {
  private static final Logger LOG = LoggerFactory.getLogger(HBaseClient.class);
  
  /** Used for some byte encoding operations */
  public static final Charset ASCII = Charset.forName("ISO_8859_1");
  
  /**
   * An empty byte array you can use.  This can be useful for instance with
   * {@link Scanner#setStartKey} and {@link Scanner#setStopKey}.
   */
  public static final byte[] EMPTY_ARRAY = new byte[0];

  /**
   * Timer we use to handle all our timeouts.
   * TODO(tsuna): Get it through the ctor to share it with others.
   * TODO(tsuna): Make the tick duration configurable?
   */
  private final HashedWheelTimer
      timer = new HashedWheelTimer(Threads.newDaemonThreadFactory("Flush-Timer"), 20, MILLISECONDS);

  /** Up to how many milliseconds can we buffer an edit on the client side.  */
  private volatile short flush_interval = 1000;  // ms

  /**
   * How many different counters do we want to keep in memory for buffering.
   * Each entry requires storing the table name, row key, family name and
   * column qualifier, plus 4 small objects.
   *
   * Assuming an average table name of 10 bytes, average key of 20 bytes,
   * average family name of 10 bytes and average qualifier of 8 bytes, this
   * would require 65535 * (10 + 20 + 10 + 8 + 4 * 32) / 1024 / 1024 = 11MB
   * of RAM, which isn't too excessive for a default value.  Of course this
   * might bite people with large keys or qualifiers, but then it's normal
   * to expect they'd tune this value to cater to their unusual requirements.
   */
  private volatile int increment_buffer_size = 65535;

  /**
   * Buffer for atomic increment coalescing.
   * This buffer starts out null, and remains so until the first time we need
   * to buffer an increment.  Once lazily initialized, this buffer will never
   * become null again.
   * <p>
   * We do this so that we can lazily schedule the flush timer only if we ever
   * have buffered increments.  Applications without buffered increments don't
   * need to pay any memory for the buffer or any CPU time for a useless timer.
   * @see #setupIncrementCoalescing
   */
  private volatile LoadingCache<BufferedIncrement, BufferedIncrement.Amount> increment_buffer;

  // ------------------------ //
  // Client usage statistics. //
  // ------------------------ //

  /** Number of connections created by a constructor.  */
  private final Counter num_connections_created = new Counter();

  /** How many {@code -ROOT-} lookups were made.  */
  private final Counter root_lookups = new Counter();

  /** How many {@code .META.} lookups were made (with a permit).  */
  private final Counter meta_lookups_with_permit = new Counter();

  /** How many {@code .META.} lookups were made (without a permit).  */
  private final Counter meta_lookups_wo_permit = new Counter();

  /** Number of calls to {@link #flush}.  */
  private final Counter num_flushes = new Counter();

  /** Number of NSREs handled by {@link #handleNSRE}.  */
  private final Counter num_nsres = new Counter();

  /** Number of RPCs delayed by {@link #handleNSRE}.  */
  private final Counter num_nsre_rpcs = new Counter();

  /** Number of {@link MultiAction} sent to the network.  */
  final Counter num_multi_rpcs = new Counter();

  /** Number of calls to {@link #get}.  */
  private final Counter num_gets = new Counter();

  /** Number of calls to {@link #openScanner}.  */
  private final Counter num_scanners_opened = new Counter();

  /** Number of calls to {@link #scanNextRows}.  */
  private final Counter num_scans = new Counter();

  /** Number calls to {@link #put}.  */
  private final Counter num_puts = new Counter();
  
  /** Number calls to {@link #append}.  */
  private final Counter num_appends = new Counter();

  /** Number calls to {@link #delete}.  */
  private final Counter num_deletes = new Counter();

  /** Number of {@link AtomicIncrementRequest} sent.  */
  private final Counter num_atomic_increments = new Counter();

  /** BigTable client configuration used by the standard BigTable drive */
  private final Configuration hbase_config;

  /** BigTable client async connection using the standard BigTable drive */
  private final AsyncConnection hbase_asyncConnection;

  private final ExecutorService executor;

  private final ConcurrentHashMap<TableName, AsyncBufferedMutator> mutators = 
      new ConcurrentHashMap<TableName, AsyncBufferedMutator>();

  /**
   * Legacy constructor for projects using the AsyncBigTable client.
   * @deprecated
   * @param quorum_spec UNUSED. BigTable does not require a Zookeeper cluster
   * so this value may be null or empty.
   */
  public HBaseClient(final String quorum_spec) {
    this(Executors.newCachedThreadPool());
  }

  /**
   * Legacy constructor for projects using the AsyncBigTable client.
   * @deprecated
   * @param quorum_spec UNUSED. BigTable does not require a Zookeeper cluster
   * so this value may be null or empty.
   * @param base_path UNUSED. BigTable does not require a Zookeeper cluster
   * so this value may be null or empty.
   */
  public HBaseClient(final String quorum_spec, final String base_path) {
    this(Executors.newCachedThreadPool());
  }

  /**
   * Legacy constructor for projects using the AsyncBigTable client.
   * @deprecated
   * @param quorum_spec UNUSED. BigTable does not require a Zookeeper cluster
   * so this value may be null or empty.
   * @param base_path UNUSED. BigTable does not require a Zookeeper cluster
   * so this value may be null or empty.
   * @param executor The executor from which to obtain threads for NIO
   * operations.  It is <strong>strongly</strong> encouraged to use a
   * {@link Executors#newCachedThreadPool} or something equivalent unless
   * you're sure to understand how Netty creates and uses threads.
   * Using a fixed-size thread pool will not work the way you expect.
   * <p>
   * Note that calling {@link #shutdown} on this client <b>will</b> shut down 
   * the executor.
   */
  public HBaseClient(final String quorum_spec, final String base_path,
                     final ExecutorService executor) {
    this(executor);
  }
  
  /**
   * Default ctor initializes settings from hbase-site.xml in HBASE_HOME
   */
  public HBaseClient() {
    this(Executors.newCachedThreadPool());
  }
  
  /**
   * Ctor that reads settings from the hbase-site.xml in HBASE_HOME and/or from
   * a different properties configuration file. Note that values in the config
   * file will override those in the hbase-site.xml or other hadoop config files).
   * @param config The config to pull settings from.
   */
  public HBaseClient(final Config config) {
    this(config, Executors.newCachedThreadPool());
  }
  
  /**
   * Ctor that reads settings from the hbase-site.xml in HBASE_HOME
   * @param executor The executor from which to obtain threads for NIO
   * operations.  It is <strong>strongly</strong> encouraged to use a
   * {@link Executors#newCachedThreadPool} or something equivalent unless
   * you're sure to understand how Netty creates and uses threads.
   * Using a fixed-size thread pool will not work the way you expect.
   * <p>
   * Note that calling {@link #shutdown} on this client <b>will</b> shut down 
   * the executor.
   */
  public HBaseClient(final ExecutorService executor) {
    this(new Config(), executor);
  }

  /**
   * Ctor that reads settings from the hbase-site.xml in HBASE_HOME and/or from
   * a different properties configuration file. Note that values in the config
   * file will override those in the hbase-site.xml or other hadoop config files).
   * @param config The config to pull settings from.
   * @param executor The executor from which to obtain threads for NIO
   * operations.  It is <strong>strongly</strong> encouraged to use a
   * {@link Executors#newCachedThreadPool} or something equivalent unless
   * you're sure to understand how Netty creates and uses threads.
   * Using a fixed-size thread pool will not work the way you expect.
   * <p>
   * Note that calling {@link #shutdown} on this client <b>will</b> shut down 
   * the executor.
   */
  public HBaseClient(final Config config, final ExecutorService executor) {
    this(toHBaseConfig(config), executor);
  }

  public HBaseClient(final Configuration configuration, final ExecutorService executor) {
    this.executor = executor;
    num_connections_created.increment();
    hbase_config = BigtableConfiguration.asyncConfigure(configuration);
    LOG.info("BigTable API: Connecting with config: {}", hbase_config);
    
    try {
      hbase_asyncConnection = ConnectionFactory.createAsyncConnection(hbase_config).get();
    } catch (InterruptedException | ExecutionException e) {
      LOG.error("Failed to create BigtableAsyncConnection", e);
      throw new RuntimeException("Failed to create BigtableAsyncConnection", e);
    }
  }

  private static Configuration toHBaseConfig(final Config config) {
    Configuration conf = HBaseConfiguration.create();
    for (final Entry<String, String> entry : config.getMap().entrySet()) {
      conf.set(entry.getKey(), entry.getValue());
    }
    return conf;
  }

  /**
   * Returns a snapshot of usage statistics for this client.
   */
  public ClientStats stats() {
    final LoadingCache<BufferedIncrement, BufferedIncrement.Amount> cache =
      increment_buffer;
    return new ClientStats(
      num_connections_created.get(),
      root_lookups.get(),
      meta_lookups_with_permit.get(),
      meta_lookups_wo_permit.get(),
      num_flushes.get(),
      num_nsres.get(),
      num_nsre_rpcs.get(),
      num_multi_rpcs.get(),
      num_gets.get(),
      num_scanners_opened.get(),
      num_scans.get(),
      num_puts.get(),
      num_appends.get(),
      0,
      num_deletes.get(),
      num_atomic_increments.get(),
      cache != null ? cache.stats() : BufferedIncrement.ZERO_STATS
    );
  }
  
  /**
   * UNUSED at this time. Eventually we may store BigTable stats in these
   * objects. It is here for backwards compatability with AsyncHBase.
   * @return An empty list.
   */
  public List<RegionClientStats> regionStats() {
    return Collections.emptyList();
  }
  
  /**
   * Flushes to BigTable any buffered client-side write operation.
   * <p>
   * @return A {@link Deferred} - Returns a Deffed set to Null for compatibility.
   * The underlying  AsyncBufferedMutator does not return a future on flush
   * 
   * <p>
   * Note that this doesn't guarantee that <b>ALL</b> outstanding RPCs have
   * completed.  This doesn't introduce any sort of global sync point.  All
   * it does really is it sends any buffered RPCs to BigTable.
   */
  public Deferred<Object> flush() {
    LOG.info("Flushing buffered mutations");

    for (final AsyncBufferedMutator mutator : mutators.values()) {
      mutator.flush();
    }
    
    num_flushes.increment();
    return Deferred.fromResult(null);
  }

  /**
   * Sets the maximum time (in milliseconds) for which edits can be buffered.
   * <p>
   * This interval will be honored on a "best-effort" basis.  Edits can be
   * buffered for longer than that due to GC pauses, the resolution of the
   * underlying timer, thread scheduling at the OS level (particularly if the
   * OS is overloaded with concurrent requests for CPU time), any low-level
   * buffering in the TCP/IP stack of the OS, etc.
   * <p>
   * Setting a longer interval allows the code to batch requests more
   * efficiently but puts you at risk of greater data loss if the JVM
   * or machine was to fail.  It also entails that some edits will not
   * reach BigTable until a longer period of time, which can be troublesome
   * if you have other applications that need to read the "latest" changes.
   * <p>
   * Setting this interval to 0 disables this feature.
   * <p>
   * The change is guaranteed to take effect at most after a full interval
   * has elapsed, <i>using the previous interval</i> (which is returned).
   * @param flush_interval A positive time interval in milliseconds.
   * @return The previous flush interval.
   * @throws IllegalArgumentException if {@code flush_interval < 0}.
   */
  public short setFlushInterval(final short flush_interval) {
    // Note: if we have buffered increments, they'll pick up the new flush
    // interval next time the current timer fires.
    if (flush_interval < 0) {
      throw new IllegalArgumentException("Negative: " + flush_interval);
    }
    final short prev = this.flush_interval;
    this.flush_interval = flush_interval;
    return prev;
  }

  /**
   * Changes the size of the increment buffer.
   * <p>
   * <b>NOTE:</b> because there is no way to resize the existing buffer,
   * this method will flush the existing buffer and create a new one.
   * This side effect might be unexpected but is unfortunately required.
   * <p>
   * This determines the maximum number of counters this client will keep
   * in-memory to allow increment coalescing through
   * {@link #bufferAtomicIncrement}.
   * <p>
   * The greater this number, the more memory will be used to buffer
   * increments, and the more efficient increment coalescing can be
   * if you have a high-throughput application with a large working
   * set of counters.
   * <p>
   * If your application has excessively large keys or qualifiers, you might
   * consider using a lower number in order to reduce memory usage.
   * @param increment_buffer_size The new size of the buffer.
   * @return The previous size of the buffer.
   * @throws IllegalArgumentException if {@code increment_buffer_size < 0}.
   */
  public int setIncrementBufferSize(final int increment_buffer_size) {
    if (increment_buffer_size < 0) {
      throw new IllegalArgumentException("Negative: " + increment_buffer_size);
    }
    final int current = this.increment_buffer_size;
    if (current == increment_buffer_size) {
      return current;
    }
    this.increment_buffer_size = increment_buffer_size;
    final LoadingCache<BufferedIncrement, BufferedIncrement.Amount> prev =
      increment_buffer;  // Volatile-read.
    if (prev != null) {  // Need to resize.
      makeIncrementBuffer();  // Volatile-write.
      flushBufferedIncrements(prev);
    }
    return current;
  }

  /**
   * Returns the timer used by this client.
   * <p>
   * All timeouts, retries and other things that need to "sleep
   * asynchronously" use this timer.  This method is provided so
   * that you can also schedule your own timeouts using this timer,
   * if you wish to share this client's timer instead of creating
   * your own.
   * <p>
   * The precision of this timer is implementation-defined but is
   * guaranteed to be no greater than 20ms.
   */
  public Timer getTimer() {
    return timer;
  }

  /**
   * Returns a configuration object that contains all of the properties from the
   * Hadoop configs on the class path as well as the potential config passed
   * in the ctor.
   * @return A configuration object
   */
  public Config getConfig() {
    final Config config = new Config();
    final Iterator<Entry<String, String>> iterator = hbase_config.iterator();
    while (iterator.hasNext()) {
      final Entry<String, String> entry = iterator.next();
      config.overrideConfig(entry.getKey(), entry.getValue());
    }
    return config;
  }
  
  /**
   * Schedules a new timeout.
   * @param task The task to execute when the timer times out.
   * @param timeout_ms The timeout, in milliseconds (strictly positive).
   */
  void newTimeout(final TimerTask task, final long timeout_ms) {
    try {
      timer.newTimeout(task, timeout_ms, MILLISECONDS);
    } catch (IllegalStateException e) {
      // This can happen if the timer fires just before shutdown()
      // is called from another thread, and due to how threads get
      // scheduled we tried to call newTimeout() after timer.stop().
      LOG.warn("Failed to schedule timer."
               + "  Ignore this if we're shutting down.", e);
    }
  }

  /**
   * Returns the maximum time (in milliseconds) for which edits can be buffered.
   * <p>
   * The default value is an unspecified and implementation dependent, but is
   * guaranteed to be non-zero.
   * <p>
   * A return value of 0 indicates that edits are sent directly to BigTable
   * without being buffered.
   * @see #setFlushInterval
   */
  public short getFlushInterval() {
    return flush_interval;
  }

  /** @returns the default RPC timeout period in milliseconds */
  public int getDefaultRpcTimeout() {
    return hbase_config.getInt("hbase.rpc.timeout", 60000);
  }
  
  /**
   * Returns the capacity of the increment buffer.
   * <p>
   * Note this returns the <em>capacity</em> of the buffer, not the number of
   * items currently in it.  There is currently no API to get the current
   * number of items in it.
   */
  public int getIncrementBufferSize() {
    return increment_buffer_size;
  }

  /**
   * Performs a graceful shutdown of this instance.
   * <p>
   * <ul>
   *   <li>{@link #flush Flushes} all buffered edits.</li>
   *   <li>Completes all outstanding requests.</li>
   *   <li>Terminates all connections.</li>
   *   <li>Releases all other resources.</li>
   * </ul>
   * <strong>Not calling this method before losing the last reference to this
   * instance may result in data loss and other unwanted side effects</strong>
   * @return A {@link Deferred}, whose callback chain will be invoked once all
   * of the above have been done.  If this callback chain doesn't fail, then
   * the clean shutdown will be successful, and all the data will be safe on
   * the BigTable side (provided that you use <a href="#durability">durable</a>
   * edits).  In case of a failure (the "errback" is invoked) you may want to
   * retry the shutdown to avoid losing data, depending on the nature of the
   * failure.  TODO(tsuna): Document possible / common failure scenarios.
   */
  public Deferred<Object> shutdown() {
    // 1. Flush everything.
    // TODO - async callback
    flush();

    // Close all open BufferedMutator instances
    ArrayList<Deferred<Object>> d = 
        new ArrayList<Deferred<Object>>(mutators.size() + 1);
    for (AsyncBufferedMutator bm : mutators.values()) {
      bm.close();
      d.add(Deferred.fromResult(null));
    }

    if (executor != null) {
        executor.shutdown();
    }

    // Close BigTable asyncConnection
    if (hbase_asyncConnection != null) {
      try {
        hbase_asyncConnection.close();
        d.add(Deferred.fromResult(null));
      } catch (IOException e) {
        LOG.error("Error occurred while disconnecting asyncConnection from BigTable", e);
        d.add(Deferred.fromError(e));
      }
    }

    @SuppressWarnings("unchecked")
    final Deferred<Object> shutdown = (Deferred) Deferred.group(d);
    return shutdown;
  }

  /**
   * Ensures that a given table/family pair really exists.
   * <p>
   * It's recommended to call this method in the startup code of your
   * application if you know ahead of time which tables / families you're
   * going to need, because it'll allow you to "fail fast" if they're missing.
   * <p>
   * Both strings are assumed to use the platform's default charset.
   * @param table The name of the table you intend to use.
   * @param family The column family you intend to use in that table.
   * @return A deferred object that indicates the completion of the request.
   * The {@link Object} has not special meaning and can be {@code null}
   * (think of it as {@code Deferred<Void>}).  But you probably want to attach
   * at least an errback to this {@code Deferred} to handle failures.
   * @throws TableNotFoundException (deferred) if the table doesn't exist.
   * @throws NoSuchColumnFamilyException (deferred) if the family doesn't exist.
   */
  public Deferred<Object> ensureTableFamilyExists(final String table,
                                                  final String family) {
    return ensureTableFamilyExists(table.getBytes(), family.getBytes());
  }

  /**
   * Ensures that a given table/family pair really exists.
   * <p>
   * It's recommended to call this method in the startup code of your
   * application if you know ahead of time which tables / families you're
   * going to need, because it'll allow you to "fail fast" if they're missing.
   * <p>
   * @param table The name of the table you intend to use.
   * @param family The column family you intend to use in that table.
   * @return A deferred object that indicates the completion of the request.
   * The {@link Object} has not special meaning and can be {@code null}
   * (think of it as {@code Deferred<Void>}).  But you probably want to attach
   * at least an errback to this {@code Deferred} to handle failures.
   * @throws TableNotFoundException (deferred) if the table doesn't exist.
   * @throws NoSuchColumnFamilyException (deferred) if the family doesn't exist.
   */
  public Deferred<Object> ensureTableFamilyExists(final byte[] table, final byte[] family) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("BigTable API: Checking if table [{}] and family [{}] exist", Bytes.pretty(table),
          Bytes.pretty(family));
    }

    AsyncAdmin admin = hbase_asyncConnection.getAdmin();
    CompletableFuture<Boolean> cf = admin.getDescriptor(TableName.valueOf(table)).thenApply(res -> {
      if (family == EMPTY_ARRAY || (res != null && res.getColumnFamily(family) != null)) {
        return true;
      }
      throw new NoSuchColumnFamilyException(Bytes.pretty(family), null);
    });

    return (Deferred) convertToDeferred(cf);
  }

  /**
   * Ensures that a given table really exists.
   * <p>
   * It's recommended to call this method in the startup code of your
   * application if you know ahead of time which tables / families you're
   * going to need, because it'll allow you to "fail fast" if they're missing.
   * <p>
   * @param table The name of the table you intend to use.
   * The string is assumed to use the platform's default charset.
   * @return A deferred object that indicates the completion of the request.
   * The {@link Object} has not special meaning and can be {@code null}
   * (think of it as {@code Deferred<Void>}).  But you probably want to attach
   * at least an errback to this {@code Deferred} to handle failures.
   * @throws TableNotFoundException (deferred) if the table doesn't exist.
   */
  public Deferred<Object> ensureTableExists(final String table) {
    return ensureTableFamilyExists(table.getBytes(), EMPTY_ARRAY);
  }

  /**
   * Ensures that a given table really exists.
   * <p>
   * It's recommended to call this method in the startup code of your
   * application if you know ahead of time which tables / families you're
   * going to need, because it'll allow you to "fail fast" if they're missing.
   * <p>
   * @param table The name of the table you intend to use.
   * @return A deferred object that indicates the completion of the request.
   * The {@link Object} has not special meaning and can be {@code null}
   * (think of it as {@code Deferred<Void>}).  But you probably want to attach
   * at least an errback to this {@code Deferred} to handle failures.
   * @throws TableNotFoundException (deferred) if the table doesn't exist.
   */
  public Deferred<Object> ensureTableExists(final byte[] table) {
    return ensureTableFamilyExists(table, EMPTY_ARRAY);
  }

  /**
   * Retrieves data from BigTable.
   * @param request The {@code get} request.
   * @return A deferred list of key-values that matched the get request.
   */
  public Deferred<ArrayList<KeyValue>> get(final GetRequest request) {
    num_gets.increment();

    AsyncTable<ScanResultConsumer> table = hbase_asyncConnection.getTable(TableName.valueOf(request.table()), executor);
    Get get = new Get(request.key());
    
    if (request.qualifiers() != null) {
      for (byte[] qualifier : request.qualifiers()) {
        get.addColumn(request.family(), qualifier);
      }
    }

    return convertToDeferred(table.get(get).thenApply(result -> {
      ArrayList<KeyValue> kvs = new ArrayList<KeyValue>();
      if (!result.isEmpty()) {
        for (NavigableMap.Entry<byte[], NavigableMap<byte[], NavigableMap<Long, byte[]>>> family_entry : result
            .getMap().entrySet()) {
          byte[] family = family_entry.getKey();
          for (NavigableMap.Entry<byte[], NavigableMap<Long, byte[]>> qualifier_entry : family_entry
              .getValue().entrySet()) {
            final byte[] qualifier = qualifier_entry.getKey();
            final long ts = qualifier_entry.getValue().firstKey();
            final byte[] value = qualifier_entry.getValue().get(ts);

            final KeyValue kv = new KeyValue(result.getRow(), family, qualifier, ts, value);
            kvs.add(kv);
          }
        }
      }
      return kvs;
    }));
  }
  
  /** Singleton callback to handle responses of "get" RPCs.  */
  private static final Callback<ArrayList<KeyValue>, Object> got =
    new Callback<ArrayList<KeyValue>, Object>() {
      public ArrayList<KeyValue> call(final Object response) {
        if (response instanceof ArrayList) {
          @SuppressWarnings("unchecked")
          final ArrayList<KeyValue> row = (ArrayList<KeyValue>) response;
          return row;
        } else {
          throw new InvalidResponseException(ArrayList.class, response);
        }
      }
      public String toString() {
        return "type get response";
      }
    };
    
  private static final Callback<GetResultOrException, Object> MUL_GOT_ONE = 
      new Callback<GetResultOrException, Object>() {
        public GetResultOrException call(final Object response) {
          if (response instanceof ArrayList) {
            @SuppressWarnings("unchecked")
            final ArrayList<KeyValue> row = (ArrayList<KeyValue>) response;
            return new GetResultOrException(row);
          } else if (response instanceof Exception) {
            Exception e = (Exception) (response);
            return new GetResultOrException(e);
          } else {
            return new GetResultOrException(new InvalidResponseException(ArrayList.class, response));
          }
        }

        public String toString() {
          return "type mul get one response";
        }
    };

  /**
   * Execute multiple "get"s against Bigtable in a batch.
   * @param requests A non-null list of 1 or more RPCs.
   * @return A deferred to wait on that will resolve to the results or an exception.
   */
  public Deferred<List<GetResultOrException>> get(final List<GetRequest> requests) {
    return Deferred.groupInOrder(multiGet(requests))
        .addCallback(
            new Callback<List<GetResultOrException>, ArrayList<GetResultOrException>>() {
              public List<GetResultOrException> call(ArrayList<GetResultOrException> results) {
                return results;
              }
            }
        );
  }

  private List<Deferred<GetResultOrException>> multiGet(final List<GetRequest> requests) {
    
//    final class MultiActionCallback implements Callback<Object, Object> {
//      
//      final MultiAction request;
//      public MultiActionCallback(final MultiAction request) {
//        this.request = request;
//      }
//      
//      // TODO - double check individual RPC timer timeouts.
//      public Object call(final Object resp) {
//        if (!(resp instanceof MultiAction.Response)) {
//          if (resp instanceof BatchableRpc) {  // Single-RPC multi-action?
//            return null;  // Yes, nothing to do.  See multiActionToSingleAction.
//          } else if (resp instanceof Exception) {
//            return handleException((Exception) resp);
//          }
//          throw new InvalidResponseException(MultiAction.Response.class, resp);
//        }
//        final MultiAction.Response response = (MultiAction.Response) resp;
//        final ArrayList<BatchableRpc> batch = request.batch();
//        final int n = batch.size();
//        for (int i = 0; i < n; i++) {
//          final BatchableRpc rpc = batch.get(i);
//          final Object r = response.result(i);
//          if (r instanceof RecoverableException) {
//            if (r instanceof NotServingRegionException ||
//                r instanceof RegionMovedException || 
//                r instanceof RegionServerStoppedException) {
//              // We need to do NSRE handling here too, as the response might
//              // have come back successful, but only some parts of the batch
//              // could have encountered an NSRE.
//              try {
//              handleNSRE(rpc, rpc.getRegion().name(),
//                                      (NotServingRegionException) r);
//              } catch (RuntimeException e) {
//                LOG.error("Unexpected exception processing NSRE for RPC " + rpc, e);
//                rpc.callback(e);
//              }
//            } else {
//              // TODO - potentially retry?
//              //retryEdit(rpc, (RecoverableException) r);
//            }
//          } else {
//            rpc.callback(r);
//          }
//        }
//        // We're successful.  If there was a problem, the exception was
//        // delivered to the specific RPCs that failed, and they will be
//        // responsible for retrying.
//        return null;
//      }
//
//      private Object handleException(final Exception e) {
//        if (!(e instanceof RecoverableException)) {
//          for (final BatchableRpc rpc : request.batch()) {
//            rpc.callback(e);
//          }
//          return e;  // Can't recover from this error, let it propagate.
//        }
//        if (LOG.isDebugEnabled()) {
//          LOG.debug(this + " Multi-action request failed, retrying each of the "
//                    + request.size() + " RPCs individually.", e);
//        }
//        for (final BatchableRpc rpc : request.batch()) {
//          if (e instanceof NotServingRegionException ||
//              e instanceof RegionMovedException || 
//              e instanceof RegionServerStoppedException) {
//            try {
//            handleNSRE(rpc, rpc.getRegion().name(),
//                                    (NotServingRegionException) e);
//            } catch (RuntimeException ex) {
//              LOG.error("Unexpected exception trying to NSRE the RPC " + rpc, ex);
//              rpc.callback(ex);
//            }
//          } else {
//            // TODO - potentially retry?
//            //retryEdit(rpc, (RecoverableException) e);
//          }
//        }
//        return null;  // We're retrying, so let's call it a success for now.
//      }
//
//      public String toString() {
//        return "multi-action response";
//      }
//    };
//    
//    final List<Deferred<GetResultOrException>> result_deferreds =
//        new ArrayList<Deferred<GetResultOrException>>(requests.size());
//
//    final Map<RegionClient, MultiAction> batch_by_region = 
//        new HashMap<RegionClient, MultiAction>();
//    
//    // Split gets according to regions.
//    for (int i = 0; i < requests.size(); i++) {
//      final GetRequest request = requests.get(i);
//      final byte[] table = request.table;
//      final byte[] key = request.key;
//      
//      // maybe be able to just use discoverRegion() here.
//      final RegionInfo region = getRegion(table, key);
//      RegionClient client = null;
//      if (region != null) {
//        client = (Bytes.equals(region.table(), ROOT)
//                  ? rootregion : region2client.get(region));
//      }
//
//      if (client == null || !client.isAlive()) {
//        // no region or client found so we need to perform the entire lookup. 
//        // Therefore these won't get the batch treatment.
//        result_deferreds.add(sendRpcToRegion(request).addBoth(MUL_GOT_ONE));
//        continue;
//      }
//
//      request.setRegion(region);
//      MultiAction batch = batch_by_region.get(client);
//      if (batch == null) {
//        batch = new MultiAction();
//        batch_by_region.put(client, batch);
//      }
//      batch.add(request);
//      
//      result_deferreds.add(request.getDeferred().addBoth(MUL_GOT_ONE));
//    }
//
//    for (Map.Entry<RegionClient, MultiAction> entry : batch_by_region.entrySet()) {
//      final MultiAction request = entry.getValue();
//      final Deferred<Object> d = request.getDeferred();
//      d.addBoth(new MultiActionCallback(request));
//      entry.getKey().sendRpc(request);
//    }
//
//    return result_deferreds;
    // TODO - fail it all!
    final List<Deferred<GetResultOrException>> temp_results = 
        Lists.newArrayListWithCapacity(requests.size());
    for (int i = 0; i < requests.size(); i++) {
      temp_results.add(Deferred.<GetResultOrException>fromError(
          new UnsupportedOperationException("Not implemented yet!")));
    }
    return temp_results;
  }
  
  /**
   * Creates a new {@link Scanner} for a particular table.
   * @param table The name of the table you intend to scan.
   * @return A new scanner for this table.
   */
  public Scanner newScanner(final byte[] table) {
    return new Scanner(this, table);
  }

  /**
   * Creates a new {@link Scanner} for a particular table.
   * @param table The name of the table you intend to scan.
   * The string is assumed to use the platform's default charset.
   * @return A new scanner for this table.
   */
  public Scanner newScanner(final String table) {
    return new Scanner(this, table.getBytes());
  }

  /**
   * Package-private access point for {@link Scanner}s to open themselves.
   * @param scanner The scanner to open.
   * deferred {@link Object}
   */
  Deferred<Object> openScanner(final Scanner scanner) {
    num_scanners_opened.increment();

    if (LOG.isDebugEnabled()) {
      LOG.debug("BigTable API: Scanning table with {}", scanner.toString());
    }

    AsyncTable<ScanResultConsumer> table = hbase_asyncConnection.getTable(TableName.valueOf(scanner.table()), executor);
    ResultScanner result = table.getScanner(scanner.getHbaseScan());
    scanner.setResultScanner(result);
    return Deferred.fromResult(new Object());
  }

  /**
   * Package-private access point for {@link Scanner}s to close themselves.
   * @param scanner The scanner to close.
   * @return A deferred object that indicates the completion of the request.
   * The {@link Object} has not special meaning and can be {@code null}.
   */
  Deferred<Object> closeScanner(final Scanner scanner) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("BigTable API: Closing scanner {}", scanner);
    }
    try {
      if (scanner.getResultScanner() != null) {
        scanner.getResultScanner().close();
      } else {
        LOG.warn("Cannot close " + scanner + " properly, no result scanner open");
      }
      return Deferred.fromResult(null);
    } catch (Exception e) {
      return Deferred.fromError(e);
    } finally {
      scanner.setResultScanner(null);
    }
  }

  /**
   * Atomically and durably increments a value in BigTable.
   * <p>
   * This is equivalent to
   * {@link #atomicIncrement(AtomicIncrementRequest, boolean) atomicIncrement}
   * {@code (request, true)}
   * @param request The increment request.
   * @return The deferred {@code long} value that results from the increment.
   */
  public Deferred<Long> atomicIncrement(final AtomicIncrementRequest request) {
    num_atomic_increments.increment();

    AsyncTable<ScanResultConsumer> table = hbase_asyncConnection.getTable(TableName.valueOf(request.table()), executor);
    return convertToDeferred(table.incrementColumnValue(request.key(), request.family(),
        request.qualifier(), request.getAmount(),
        request.isDurable() ? Durability.USE_DEFAULT : Durability.SKIP_WAL));
  }

  /**
   * Buffers a durable atomic increment for coalescing.
   * <p>
   * This increment will be held in memory up to the amount of time allowed
   * by {@link #getFlushInterval} in order to allow the client to coalesce
   * increments.
   * <p>
   * Increment coalescing can dramatically reduce the number of RPCs and write
   * load on BigTable if you tend to increment multiple times the same working
   * set of counters.  This is very common in user-facing serving systems that
   * use BigTable counters to keep track of user actions.
   * <p>
   * If client-side buffering is disabled ({@link #getFlushInterval} returns
   * 0) then this function has the same effect as calling
   * {@link #atomicIncrement(AtomicIncrementRequest)} directly.
   * @param request The increment request.
   * @return The deferred {@code long} value that results from the increment.
   */
  public Deferred<Long> bufferAtomicIncrement(final AtomicIncrementRequest request) {
    final long value = request.getAmount();
    if (!BufferedIncrement.Amount.checkOverflow(value)  // Value too large.
        || flush_interval == 0) {           // Client-side buffer disabled.
      return atomicIncrement(request);
    }

    final BufferedIncrement incr =
      new BufferedIncrement(request.table(), request.key(), request.family(),
                            request.qualifier());

    do {
      BufferedIncrement.Amount amount;
      // Semi-evil: the very first time we get here, `increment_buffer' will
      // still be null (we don't initialize it in our constructor) so we catch
      // the NPE that ensues to allocate the buffer and kick off a timer to
      // regularly flush it.
      try {
        amount = increment_buffer.getUnchecked(incr);
      } catch (NullPointerException e) {
        setupIncrementCoalescing();
        amount = increment_buffer.getUnchecked(incr);
      }
      if (amount.update(value)) {
        final Deferred<Long> deferred = new Deferred<Long>();
        amount.deferred.chain(deferred);
        return deferred;
      }
      // else: Loop again to retry.
      increment_buffer.refresh(incr);
    } while (true);
  }

  /**
   * Called the first time we get a buffered increment.
   * Lazily creates the increment buffer and sets up a timer to regularly
   * flush buffered increments.
   */
  private synchronized void setupIncrementCoalescing() {
    // If multiple threads attempt to setup coalescing at the same time, the
    // first one to get here will make `increment_buffer' non-null, and thus
    // subsequent ones will return immediately.  This is important to avoid
    // creating more than one FlushBufferedIncrementsTimer below.
    if (increment_buffer != null) {
      return;
    }
    makeIncrementBuffer();  // Volatile-write.

    // Start periodic buffered increment flushes.
    final class FlushBufferedIncrementsTimer implements TimerTask {
      public void run(final Timeout timeout) {
        try {
          flushBufferedIncrements(increment_buffer);
        } finally {
          final short interval = flush_interval; // Volatile-read.
          // Even if we paused or disabled the client side buffer by calling
          // setFlushInterval(0), we will continue to schedule this timer
          // forever instead of pausing it.  Pausing it is troublesome because
          // we don't keep a reference to this timer, so we can't cancel it or
          // tell if it's running or not.  So let's just KISS and assume that
          // if we need the timer once, we'll need it forever.  If it's truly
          // not needed anymore, we'll just cause a bit of extra work to the
          // timer thread every 100ms, no big deal.
          newTimeout(this, interval > 0 ? interval : 100);
        }
      }
    }
    final short interval = flush_interval; // Volatile-read.
    // Handle the extremely unlikely yet possible racy case where:
    //   flush_interval was > 0
    //   A buffered increment came in
    //   It was the first one ever so we landed here
    //   Meanwhile setFlushInterval(0) to disable buffering
    // In which case we just flush whatever we have in 1ms.
    timer.newTimeout(new FlushBufferedIncrementsTimer(),
                     interval > 0 ? interval : 1, MILLISECONDS);
  }

  /**
   * Flushes all buffered increments.
   * @param increment_buffer The buffer to flush.
   */
  private static void flushBufferedIncrements(// JAVA Y U NO HAVE TYPEDEF? F U!
    final LoadingCache<BufferedIncrement, BufferedIncrement.Amount> increment_buffer) {
    // Calling this method to clean up before shutting down works solely
    // because `invalidateAll()' will *synchronously* remove everything.
    // The Guava documentation says "Discards all entries in the cache,
    // possibly asynchronously" but in practice the code in `LocalCache'
    // works as follows:
    //
    //   for each segment:
    //     segment.clear
    //
    // Where clearing a segment consists in:
    //
    //   lock the segment
    //   for each active entry:
    //     add entry to removal queue
    //   null out the hash table
    //   unlock the segment
    //   for each entry in removal queue:
    //     call the removal listener on that entry
    //
    // So by the time the call to `invalidateAll()' returns, every single
    // buffered increment will have been dealt with, and it is thus safe
    // to shutdown the rest of the client to let it complete all outstanding
    // operations.
    if (LOG.isDebugEnabled()) {
      LOG.debug("Flushing " + increment_buffer.size() + " buffered increments");
    }
    synchronized (increment_buffer) {
      increment_buffer.invalidateAll();
    }
  }

  /**
   * Creates the increment buffer according to current configuration.
   */
  private void makeIncrementBuffer() {
    final int size = increment_buffer_size;
    increment_buffer = BufferedIncrement.newCache(this, size);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Created increment buffer of " + size + " entries");
    }
  }

  /**
   * Atomically increments a value in BigTable.
   * @param request The increment request.
   * @param durable If {@code true}, the success of this RPC guarantees that
   * BigTable has stored the edit in a <a href="#durability">durable</a> fashion.
   * When in doubt, use {@link #atomicIncrement(AtomicIncrementRequest)}.
   * @return The deferred {@code long} value that results from the increment.
   */
  public Deferred<Long> atomicIncrement(final AtomicIncrementRequest request,
                                        final boolean durable) {
    request.setDurable(durable);
    return atomicIncrement(request);
  }

  /**
   * Stores data in BigTable.
   * <p>
   * Note that this provides no guarantee as to the order in which subsequent
   * {@code put} requests are going to be applied to the backend.  If you need
   * ordering, you must enforce it manually yourself by starting the next
   * {@code put} once the {@link Deferred} of this one completes successfully.
   * @param request The {@code put} request.
   * @return A deferred object that indicates the completion of the request.
   * The {@link Object} has not special meaning and can be {@code null}
   * (think of it as {@code Deferred<Void>}).  But you probably want to attach
   * at least an errback to this {@code Deferred} to handle failures.
   * TODO(tsuna): Document failures clients are expected to handle themselves.
   */
  public Deferred<Object> put(final PutRequest request) {
    num_puts.increment();
    
    Put put = new Put(request.key());
    long ts = request.timestamp();
    for (int i = 0; i < request.qualifiers().length; i++) {
      put.addColumn(request.family, request.qualifiers()[i], ts, request.values()[i]);
    }

    @SuppressWarnings("unchecked")
    Deferred<Object> deferedPut = (Deferred) sendMutation(put, TableName.valueOf(request.table()));
    return deferedPut;
  }

  /**
   * Appends data to (or creates) one or more columns in BigTable.
   * <p>
   * Note that this provides no guarantee as to the order in which subsequent
   * {@code append} requests are going to be applied to the column(s).  If you 
   * need ordering, you must enforce it manually yourself by starting the next
   * {@code append} once the {@link Deferred} of this one completes successfully.
   * @param request The {@code append} request.
   * @return A deferred object that indicates the completion of the request and
   * may contain data from the column(s).
   * The {@link Object} has not special meaning and can be {@code null}
   * (think of it as {@code Deferred<Void>}).  But you probably want to attach
   * at least an errback to this {@code Deferred} to handle failures.
   */
  public Deferred<Object> append(final AppendRequest request) {
    num_appends.increment();

    final Append append = new Append(request.key);
    for (int i = 0; i < request.qualifiers().length; i++) {
      append.add(request.family, request.qualifiers()[i], request.values()[i]);
    }

    @SuppressWarnings("unchecked")
    Deferred<Object> deferedAppend = (Deferred) sendMutation(append,
        TableName.valueOf(request.table()));
    return deferedAppend;
  }
    
  /**
   * Atomic Compare-And-Set (CAS) on a single cell.
   * <p>
   * Note that edits sent through this method <b>cannot be batched</b>, and
   * won't be subject to the {@link #setFlushInterval flush interval}.  This
   * entails that write throughput will be lower with this method as edits
   * have to be sent out to the wire one by one.
   * <p>
   * This request enables you to atomically update the value of an existing
   * cell in BigTable using a CAS operation.  It's like a {@link PutRequest}
   * except that you also pass an expected value.  If the last version of the
   * cell identified by your {@code PutRequest} matches the expected value,
   * BigTable will atomically update it to the new value.
   * <p>
   * If the expected value is the empty byte array, BigTable will atomically
   * create the cell provided that it doesn't exist already. This can be used
   * to ensure that your RPC doesn't overwrite an existing value.  Note
   * however that this trick cannot be used the other way around to delete
   * an expected value atomically.
   * @param edit The new value to write.
   * @param expected The expected value of the cell to compare against.
   * <strong>This byte array will NOT be copied.</strong>
   * @return A deferred boolean, if {@code true} the CAS succeeded, otherwise
   * the CAS failed because the value in BigTable didn't match the expected value
   * of the CAS request.
   */
  public Deferred<Boolean> compareAndSet(final PutRequest edit,
                                         final byte[] expected) {
    
    AsyncTable<ScanResultConsumer> table = hbase_asyncConnection.getTable(TableName.valueOf(edit.table()), executor);
    Put put = new Put(edit.key());
    long ts = edit.timestamp();
    for (int i = 0; i < edit.qualifiers().length; i++) {
      put.addColumn(edit.family, edit.qualifiers()[i], ts, edit.values()[i]);
    }

    CheckAndMutateBuilder builder = table.checkAndMutate(edit.key(), edit.family())
        .qualifier(edit.qualifier());

    byte[] expect = expected;
    if (Bytes.memcmp(EMPTY_ARRAY, expected) == 0) {
      builder.ifNotExists();
    } else {
      builder.ifMatches(CompareOperator.EQUAL, expect);
    }

    return convertToDeferred(builder.thenPut(put));
  }

  /**
   * Atomic Compare-And-Set (CAS) on a single cell.
   * <p>
   * Note that edits sent through this method <b>cannot be batched</b>.
   * @see #compareAndSet(PutRequest, byte[])
   * @param edit The new value to write.
   * @param expected The expected value of the cell to compare against.
   * This string is assumed to use the platform's default charset.
   * @return A deferred boolean, if {@code true} the CAS succeeded, otherwise
   * the CAS failed because the value in BigTable didn't match the expected value
   * of the CAS request.
   */
  public Deferred<Boolean> compareAndSet(final PutRequest edit,
                                         final String expected) {
    return compareAndSet(edit, expected.getBytes());
  }

  /**
   * Atomically insert a new cell in BigTable.
   * <p>
   * Note that edits sent through this method <b>cannot be batched</b>.
   * <p>
   * This is equivalent to calling
   * {@link #compareAndSet(PutRequest, byte[]) compareAndSet}{@code (edit,
   * EMPTY_ARRAY)}
   * @see #compareAndSet(PutRequest, byte[])
   * @param edit The new value to insert.
   * @return A deferred boolean, {@code true} if the edit got atomically
   * inserted in BigTable, {@code false} if there was already a value in the
   * given cell.
   */
  public Deferred<Boolean> atomicCreate(final PutRequest edit) {
    return compareAndSet(edit, EMPTY_ARRAY);
  }

  /**
   * Deletes data from BigTable.
   * @param request The {@code delete} request.
   * @return A deferred object that indicates the completion of the request.
   * The {@link Object} has not special meaning and can be {@code null}
   * (think of it as {@code Deferred<Void>}).  But you probably want to attach
   * at least an errback to this {@code Deferred} to handle failures.
   */
  public Deferred<Object> delete(final DeleteRequest request) {
    num_deletes.increment();

    Delete delete = new Delete(request.key());
    long ts = request.timestamp();
    if (request.family() != null) {
      if (request.qualifiers() != null && request.qualifiers().length > 0) {
        for (int i = 0; i < request.qualifiers().length; i++) {
          if (request.deleteAtTimestampOnly()) {
            delete.addColumn(request.family, request.qualifiers()[i], ts);
          } else {
            delete.addColumns(request.family, request.qualifiers()[i], ts);
          }
        }
      } else {
        delete.addFamily(request.family, ts);
      }
    }

    @SuppressWarnings("unchecked")
    Deferred<Object> deferedDelete = (Deferred) sendMutation(delete,
        TableName.valueOf(request.table()));
    return deferedDelete;
  }

  /**
   * A no-op for BigTable clients
   * @param table Ignored
   * @return A deferred with a null result immediately.
   * @deprecated
   */
  public Deferred<Object> prefetchMeta(final String table) {
    return Deferred.fromResult(null);
  }
  
  /**
   * A no-op for BigTable clients
   * @param table Ignored
   * @param start Ignored
   * @param stop Ignored
   * @return A deferred with a null result immediately.
   * @deprecated
   */
  public Deferred<Object> prefetchMeta(final String table,
      final String start,
      final String stop) {
    return Deferred.fromResult(null);
  }
  
  /**
   * A no-op for BigTable clients
   * @param table Ignored
   * @return A deferred with a null result immediately.
   * @deprecated
   */
  public Deferred<Object> prefetchMeta(final byte[] table) {
    return Deferred.fromResult(null);
  }
  
  /**
   * A no-op for BigTable clients
   * @param table Ignored
   * @param start Ignored
   * @param stop Ignored
   * @return A deferred with a null result immediately.
   * @deprecated
   */
  public Deferred<Object> prefetchMeta(final byte[] table,
      final byte[] start,
      final byte[] stop) {
    return Deferred.fromResult(null);
  }
  
  /**
   * A no-op for BigTable clients
   * @deprecated
   * @param table Ignored
   * @return An empty list
   */
  public Deferred<List<RegionLocation>> locateRegions(final String table) {
    return locateRegions(table.getBytes());
  }
  
  /**
   * A no-op for BigTable clients
   * @deprecated
   * @param table Ignored
   * @return An empty list
   */
  public Deferred<List<RegionLocation>> locateRegions(final byte[] table) {
    return Deferred.fromResult(Collections.<RegionLocation>emptyList());
  }
  
  public AsyncConnection getBigtableAsyncConnection() {
    return hbase_asyncConnection;
  }
  
  // --------------- //
  // Little helpers. //
  // --------------- //
  
  private AsyncBufferedMutator getBufferedMutator(TableName table) {
    AsyncBufferedMutator mutator = mutators.get(table);

    if (mutator == null) {
      AsyncBufferedMutator newMutator = hbase_asyncConnection.getBufferedMutator(table);
      // in the case of a race condition, use the first stored mutator and close the rest
      if ((mutator = mutators.putIfAbsent(table, newMutator)) != null) {
        newMutator.close();
      } else {
        mutator = newMutator;
      }
    }

    return mutator; 
  }

  private Deferred<Void> sendMutation(final Mutation mutation, final TableName tableName) {
    return convertToDeferred(getBufferedMutator(tableName).mutate(mutation));
  }
  
  /**
   * helper to convert CompletableFuture to Deferred
   */
  protected static <T> Deferred<T> convertToDeferred(CompletableFuture<T> cf) {
    Deferred<T> d = new Deferred<>();

    cf.whenComplete((r, ex) -> {
      if (ex != null) {
        LOG.error("BigTable API: CompletableFuture failed with exception", ex);
        if (ex instanceof CompletionException && ex.getCause() != null) {
          d.callback(ex.getCause());
        } else {
          d.callback(ex);
        }
      } else {
        LOG.debug("BigTable API: CompletableFuture returned {}", r);
        d.callback(r);
      }
    });

    return d;
  }
}
