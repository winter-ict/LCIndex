/**
 * Copyright 2010 The Apache Software Foundation Licensed to the Apache Software Foundation (ASF)
 * under one or more contributor license agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership. The ASF licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or agreed to in
 * writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableSet;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseFileSystem;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValue.KVComparator;
import org.apache.hadoop.hbase.RemoteExceptionHandler;
import org.apache.hadoop.hbase.backup.HFileArchiver;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.fs.HFileSystem;
import org.apache.hadoop.hbase.index.client.DataType;
import org.apache.hadoop.hbase.index.client.IndexColumnDescriptor;
import org.apache.hadoop.hbase.index.client.IndexConstants;
import org.apache.hadoop.hbase.index.client.IndexDescriptor;
import org.apache.hadoop.hbase.index.client.Range;
import org.apache.hadoop.hbase.index.regionserver.ByteArray;
import org.apache.hadoop.hbase.index.regionserver.IndexKeyValue;
import org.apache.hadoop.hbase.index.regionserver.IndexKeyValue.IndexKVComparator;
import org.apache.hadoop.hbase.index.regionserver.IndexReader;
import org.apache.hadoop.hbase.index.regionserver.IndexWriter;
import org.apache.hadoop.hbase.index.regionserver.StoreIndexScanner;
import org.apache.hadoop.hbase.io.HFileLink;
import org.apache.hadoop.hbase.io.HeapSize;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.Compression;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.io.hfile.HFileDataBlockEncoder;
import org.apache.hadoop.hbase.io.hfile.HFileDataBlockEncoderImpl;
import org.apache.hadoop.hbase.io.hfile.HFileScanner;
import org.apache.hadoop.hbase.io.hfile.InvalidHFileException;
import org.apache.hadoop.hbase.io.hfile.NoOpDataBlockEncoder;
import org.apache.hadoop.hbase.monitoring.MonitoredTask;
import org.apache.hadoop.hbase.regionserver.compactions.CompactSelection;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionProgress;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionRequest;
import org.apache.hadoop.hbase.regionserver.metrics.SchemaConfigured;
import org.apache.hadoop.hbase.regionserver.metrics.SchemaMetrics;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.ChecksumType;
import org.apache.hadoop.hbase.util.ClassSize;
import org.apache.hadoop.hbase.util.CollectionBackedScanner;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.util.StringUtils;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import doTestAid.WinterOptimizer;
import doWork.LCCIndexConstant;
import doWork.LCCIndexGenerator;
import doWork.LCStatInfo;

/**
 * A Store holds a column family in a Region. Its a memstore and a set of zero or more StoreFiles,
 * which stretch backwards over time.
 * <p>
 * There's no reason to consider append-logging at this level; all logging and locking is handled at
 * the HRegion level. Store just provides services to manage sets of StoreFiles. One of the most
 * important of those services is compaction services where files are aggregated once they pass a
 * configurable threshold.
 * <p>
 * The only thing having to do with logs that Store needs to deal with is the reconstructionLog.
 * This is a segment of an HRegion's log that might NOT be present upon startup. If the param is
 * NULL, there's nothing to do. If the param is non-NULL, we need to process the log to reconstruct
 * a TreeMap that might not have been written to disk before the process died.
 * <p>
 * It's assumed that after this constructor returns, the reconstructionLog file will be deleted (by
 * whoever has instantiated the Store).
 * <p>
 * Locking and transactions are handled at a higher level. This API should not be called directly
 * but by an HRegion manager.
 */
public class Store extends SchemaConfigured implements HeapSize {
  static final Log LOG = LogFactory.getLog(Store.class);

  public static final String BLOCKING_STOREFILES_KEY = "hbase.hstore.blockingStoreFiles";
  public static final int DEFAULT_BLOCKING_STOREFILE_COUNT = 7;

  protected final MemStore memstore;
  // This stores directory in the filesystem.
  private final Path homedir;
  private final HRegion region;
  private final HColumnDescriptor family;
  final FileSystem fs;
  final Configuration conf;
  final CacheConfig cacheConf;
  // ttl in milliseconds.
  private long ttl;
  private final int minFilesToCompact;
  private final int maxFilesToCompact;
  private final long minCompactSize;
  private final long maxCompactSize;
  private long lastCompactSize = 0;
  volatile boolean forceMajor = false;
  /* how many bytes to write between status checks */
  static int closeCheckInterval = 0;
  private final int blockingStoreFileCount;
  private volatile long storeSize = 0L;
  private volatile long totalUncompressedBytes = 0L;
  private final Object flushLock = new Object();
  final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final boolean verifyBulkLoads;

  private long blockingFileCount;

  /*
   * The default priority for user-specified compaction requests. The user gets top priority unless
   * we have blocking compactions. (Pri <= 0)
   */
  public static final int PRIORITY_USER = 1;
  public static final int NO_PRIORITY = Integer.MIN_VALUE;

  // not private for testing
  /* package */ScanInfo scanInfo;
  /*
   * List of store files inside this store. This is an immutable list that is atomically replaced
   * when its contents change.
   */
  private volatile ImmutableList<StoreFile> storefiles = null;

  List<StoreFile> filesCompacting = Lists.newArrayList();

  // All access must be synchronized.
  private final Set<ChangedReadersObserver> changedReaderObservers = Collections
      .newSetFromMap(new ConcurrentHashMap<ChangedReadersObserver, Boolean>());

  private final int blocksize;
  private HFileDataBlockEncoder dataBlockEncoder;

  /** Checksum configuration */
  private ChecksumType checksumType;
  private int bytesPerChecksum;

  // Comparing KeyValues
  final KeyValue.KVComparator comparator;

  private final Compactor compactor;

  private static final int DEFAULT_FLUSH_RETRIES_NUMBER = 10;
  private static int flush_retries_number;
  private static int pauseTime;

  // index
  private boolean hasIndex = false;
  Map<byte[], IndexDescriptor> indexMap = null;
  private Path indexDir;
  private volatile long indexStoreSize = 0L;
  IndexKVComparator indexComparator = null;
  long indexCompactCacheMaxSize;
  private short indexReplication = 1;
  LocalFileSystem localfs = null;
  InetAddress localIP = null;
  // lccindex
  // public boolean useLCCIndexScanner = false;
  protected boolean hasLCCIndex = false;
  Map<byte[], IndexDescriptor> lccIndexMap = null;
  private LCCIndexGenerator lccindexGenerator = null;
  protected Path lccIndexDir; // /hbase/lcc/A/f/.lccindex
  protected Path lccLocalHome; // Local_Home/lcc/AAA/f/.lccindex
  private Path lcIndexLocalBaseDir;
  protected ArrayList<String> lccRegionServerHostnames = null;
  protected TreeMap<byte[], DataType> lccIndexQualifierType = null;
  protected String lccPreviousHoldServer = null;
  protected Path lccHolderName;

  /**
   * Constructor
   * @param basedir qualified path under which the region directory lives; generally the table
   *          subdirectory
   * @param region
   * @param family HColumnDescriptor for this column
   * @param fs file system object
   * @param confParam configuration object failed. Can be null.
   * @throws IOException
   */
  protected Store(Path basedir, HRegion region, HColumnDescriptor family, FileSystem fs,
      Configuration confParam) throws IOException {
    super(new CompoundConfiguration().add(confParam).add(family.getValues()), region.getTableDesc()
        .getNameAsString(), Bytes.toString(family.getName()));
    System.out.println(String.format(
      "winter init Store, path: %s, family name %s, family length: %d", basedir.toString(),
      Bytes.toString(family.getName()), family.getValues().size()));
    HRegionInfo info = region.getRegionInfo();
    this.fs = fs;
    Path p = getStoreHomedir(basedir, info.getEncodedName(), family.getName());
    this.homedir = createStoreHomeDir(this.fs, p);
    this.region = region;
    this.family = family;
    // 'conf' renamed to 'confParam' b/c we use this.conf in the constructor
    this.conf = new CompoundConfiguration().add(confParam).add(family.getValues());
    this.blocksize = family.getBlocksize();

    this.dataBlockEncoder =
        new HFileDataBlockEncoderImpl(family.getDataBlockEncodingOnDisk(),
            family.getDataBlockEncoding());

    this.comparator = info.getComparator();
    // getTimeToLive returns ttl in seconds. Convert to milliseconds.
    this.ttl = family.getTimeToLive();
    if (ttl == HConstants.FOREVER) {
      // default is unlimited ttl.
      ttl = Long.MAX_VALUE;
    } else if (ttl == -1) {
      ttl = Long.MAX_VALUE;
    } else {
      // second -> ms adjust for user data
      this.ttl *= 1000;
    }
    // used by ScanQueryMatcher
    long timeToPurgeDeletes = Math.max(conf.getLong("hbase.hstore.time.to.purge.deletes", 0), 0);
    LOG.info("time to purge deletes set to " + timeToPurgeDeletes + "ms in store " + this);
    scanInfo = new ScanInfo(family, ttl, timeToPurgeDeletes, this.comparator);
    this.memstore = new MemStore(conf, this.comparator);

    // By default, compact if storefile.count >= minFilesToCompact
    this.minFilesToCompact = Math.max(2, conf.getInt("hbase.hstore.compaction.min",
    /* old name */conf.getInt("hbase.hstore.compactionThreshold", 3)));

    LOG.info("hbase.hstore.compaction.min = " + this.minFilesToCompact);

    // Setting up cache configuration for this family
    this.cacheConf = new CacheConfig(conf, family);
    this.blockingStoreFileCount = conf.getInt("hbase.hstore.blockingStoreFiles", 7);

    this.maxFilesToCompact = conf.getInt("hbase.hstore.compaction.max", 10);
    this.minCompactSize =
        conf.getLong("hbase.hstore.compaction.min.size", this.region.memstoreFlushSize);
    this.maxCompactSize = conf.getLong("hbase.hstore.compaction.max.size", Long.MAX_VALUE);

    this.verifyBulkLoads = conf.getBoolean("hbase.hstore.bulkload.verify", false);

    this.blockingFileCount = conf.getInt(BLOCKING_STOREFILES_KEY, DEFAULT_BLOCKING_STOREFILE_COUNT);

    if (Store.closeCheckInterval == 0) {
      Store.closeCheckInterval =
          conf.getInt("hbase.hstore.close.check.interval", 10 * 1000 * 1000 /* 10 MB */);
    }
    // winter here load StoreFiles!
    this.storefiles = sortAndClone(loadStoreFiles());

    // Initialize checksum type from name. The names are CRC32, CRC32C, etc.
    this.checksumType = getChecksumType(conf);
    // initilize bytes per checksum
    this.bytesPerChecksum = getBytesPerChecksum(conf);
    // Create a compaction tool instance
    this.compactor = new Compactor(this.conf);
    if (Store.flush_retries_number == 0) {
      Store.flush_retries_number =
          conf.getInt("hbase.hstore.flush.retries.number", DEFAULT_FLUSH_RETRIES_NUMBER);
      Store.pauseTime =
          conf.getInt(HConstants.HBASE_SERVER_PAUSE, HConstants.DEFAULT_HBASE_SERVER_PAUSE);
      if (Store.flush_retries_number <= 0) {
        throw new IllegalArgumentException("hbase.hstore.flush.retries.number must be > 0, not "
            + Store.flush_retries_number);
      }
    }

    // load index
    IndexColumnDescriptor indexFamily = new IndexColumnDescriptor(family);
    // winter

    // winter default hasIndex = hasLCCIndex = false;
    if (indexFamily.hasIndex()) {
      if (indexFamily.getIndexType() == IndexColumnDescriptor.IndexType.IRIndex) {
        indexMap = indexFamily.getAllIndexMap();
        hasIndex = true;
      } else if (indexFamily.getIndexType() == IndexColumnDescriptor.IndexType.LCCIndex) {
        // winter data type indicated here, but how to use it is not decided yet.
        lccIndexQualifierType = indexFamily.mWinterGetQualifierType();
        if (lccIndexQualifierType.size() > 0) {
          hasLCCIndex = true;
        }
      } else {
        // winter unknown type, maybe CCIndex?
      }
    }
    assert ((hasIndex && hasLCCIndex) == false) : "irindex and lccindex be used together";

    System.out.println("winter index status of table " + this.getTableName() + ", family "
        + this.family.getNameAsString() + ", irindex is: " + hasIndex + ", lccindex is: "
        + hasLCCIndex);

    this.indexDir = new Path(this.homedir, IndexConstants.REGION_INDEX_DIR_NAME);
    this.lccIndexDir = new Path(this.homedir, LCCIndexConstant.INDEX_DIR_NAME);
    // homeDir = hdfs://localhost:9000/hbase/lcc/AAA/f
    // localhome = lcc/AAA/f/.lccindex
    this.lccLocalHome =
        new Path(this.homedir.getParent().getParent().getName(), this.homedir.getParent().getName());
    this.lccLocalHome =
        new Path(this.lccLocalHome, new Path(this.homedir.getName(),
            LCCIndexConstant.INDEX_DIR_NAME));

    // winter to be done!
    if (hasIndex) {
      this.indexCompactCacheMaxSize =
          this.conf.getLong(IndexConstants.HREGION_INDEX_COMPACT_CACHE_MAXSIZE,
            IndexConstants.DEFAULT_HREGION_INDEX_COMPACT_CACHE_MAXSIZE);
      this.indexReplication =
          (short) this.conf.getInt(IndexConstants.INDEXFILE_REPLICATION,
            IndexConstants.DEFAULT_INDEXFILE_REPLICATION);
      this.localfs = LocalFileSystem.getLocal(conf);
      localIP = InetAddress.getLocalHost();

      if (!this.fs.exists(this.indexDir)) {
        if (!this.fs.mkdirs(this.indexDir)) throw new IOException("Failed create of: "
            + this.indexDir.toString());
      } else {
        FileStatus[] indexfiles = fs.listStatus(this.indexDir);
        for (FileStatus tmp : indexfiles) {
          if (!validateIndex(tmp.getPath().getName())) {
            fs.delete(tmp.getPath(), true);
          } else {
            if (tmp.getReplication() != this.indexReplication) {
              fs.setReplication(tmp.getPath(), this.indexReplication);
            }
          }
        }
      }
      this.indexComparator = IndexKeyValue.COMPARATOR;
    } else if (hasLCCIndex) {
      boolean useLocalIndex =
          conf.getBoolean(LCCIndexConstant.WRITE_LOCAL_INDEX,
            LCCIndexConstant.DEFAULT_WRITE_LOCAL_INDEX);
      if (useLocalIndex) {
        localIP = InetAddress.getLocalHost();
        localfs = LocalFileSystem.getLocal(conf);
        lcIndexLocalBaseDir = new Path(conf.get(LCCIndexConstant.LCINDEX_LOCAL_DIR));
        lccLocalHome = new Path(lcIndexLocalBaseDir, this.lccLocalHome);
        String hostnames = conf.get(LCCIndexConstant.LCINDEX_REGIONSERVER_HOSTNAMES);
        if (localfs == null) {
          throw new IOException("has lcindex but localfs is null ");
        }
        if (lcIndexLocalBaseDir == null) {
          throw new IOException("has lcindex but miss the local dir, assign "
              + LCCIndexConstant.INDEX_DIR_NAME + " at first");
        }
        if (hostnames == null) {
          throw new IOException("has lcindex but miss lcindex regionserver hosts, assign "
              + LCCIndexConstant.LCINDEX_REGIONSERVER_HOSTNAMES + " at first");
        }
        String parts[] = hostnames.split(LCCIndexConstant.LCINDEX_REGIONSERVER_HOSTNAMES_DELIMITER);
        lccRegionServerHostnames = new ArrayList<String>();
        for (String hostname : parts) {
          lccRegionServerHostnames.add(hostname);
        }
        // System.out.println("winter use lccindex on LocalFS");
      } else {
        // set local fs to null if written to HDFS
        localfs = null;
        // System.out.println("winter use lccindex on HDFS");
      }
    } else {
      if (this.fs.exists(this.indexDir)) {
        this.fs.delete(this.indexDir, true);
      }
    }
  }

  boolean validateIndex(String indexFileName) {
    for (StoreFile sf : storefiles) {
      if (sf.getPath().getName().equals(indexFileName)) {
        return true;
      }
    }
    return false;
  }

  public long getIndexSize() {
    return this.indexStoreSize;
  }

  public List<byte[]> getAvaliableIndexes() {
    List<byte[]> list = new ArrayList<byte[]>();
    if (hasIndex) {
      list.addAll(indexMap.keySet());
    }
    return list;
  }

  public List<byte[]> mWinterGetAvaliableLCCIndexes() {
    List<byte[]> list = new ArrayList<byte[]>();
    if (hasIndex) {
      list.addAll(indexMap.keySet());
    }
    return list;
  }

  public float getIndexScanScale(Range r) {
    long rangeSize = 0, totalSize = 0;
    lock.readLock().lock();
    try {
      for (StoreFile sf : this.getStorefiles()) {
        long[] tmp = sf.getIndexReader().getIndexFileReader().getRangeScale(r);
        rangeSize += tmp[0];
        totalSize += tmp[1];
      }
    } finally {
      lock.readLock().unlock();
    }
    if (totalSize == 0) {
      return 0.0f;
    } else {
      return (float) (((double) rangeSize) / ((double) totalSize));
    }
  }

  /**
   * @param family
   * @return
   */
  long getTTL(final HColumnDescriptor family) {
    // HCD.getTimeToLive returns ttl in seconds. Convert to milliseconds.
    long ttl = family.getTimeToLive();
    if (ttl == HConstants.FOREVER) {
      // Default is unlimited ttl.
      ttl = Long.MAX_VALUE;
    } else if (ttl == -1) {
      ttl = Long.MAX_VALUE;
    } else {
      // Second -> ms adjust for user data
      ttl *= 1000;
    }
    return ttl;
  }

  /**
   * Create this store's homedir
   * @param fs
   * @param homedir
   * @return Return <code>homedir</code>
   * @throws IOException
   */
  Path createStoreHomeDir(final FileSystem fs, final Path homedir) throws IOException {
    if (!fs.exists(homedir) && !HBaseFileSystem.makeDirOnFileSystem(fs, homedir)) {
      throw new IOException("Failed create of: " + homedir.toString());
    }
    return homedir;
  }

  FileSystem getFileSystem() {
    return this.fs;
  }

  /**
   * Returns the configured bytesPerChecksum value.
   * @param conf The configuration
   * @return The bytesPerChecksum that is set in the configuration
   */
  public static int getBytesPerChecksum(Configuration conf) {
    return conf.getInt(HConstants.BYTES_PER_CHECKSUM, HFile.DEFAULT_BYTES_PER_CHECKSUM);
  }

  /**
   * Returns the configured checksum algorithm.
   * @param conf The configuration
   * @return The checksum algorithm that is set in the configuration
   */
  public static ChecksumType getChecksumType(Configuration conf) {
    String checksumName = conf.get(HConstants.CHECKSUM_TYPE_NAME);
    if (checksumName == null) {
      return HFile.DEFAULT_CHECKSUM_TYPE;
    } else {
      return ChecksumType.nameToType(checksumName);
    }
  }

  public HColumnDescriptor getFamily() {
    return this.family;
  }

  /**
   * @return The maximum sequence id in all store files.
   */
  long getMaxSequenceId(boolean includeBulkFiles) {
    return StoreFile.getMaxSequenceIdInList(this.getStorefiles(), includeBulkFiles);
  }

  /**
   * @return The maximum memstoreTS in all store files.
   */
  public long getMaxMemstoreTS() {
    return StoreFile.getMaxMemstoreTSInList(this.getStorefiles());
  }

  /**
   * @param tabledir
   * @param encodedName Encoded region name.
   * @param family
   * @return Path to family/Store home directory.
   */
  public static Path getStoreHomedir(final Path tabledir, final String encodedName,
      final byte[] family) {
    return getStoreHomedir(tabledir, encodedName, Bytes.toString(family));
  }

  /**
   * @param tabledir
   * @param encodedName Encoded region name.
   * @param family
   * @return Path to family/Store home directory.
   */
  public static Path getStoreHomedir(final Path tabledir, final String encodedName,
      final String family) {
    return new Path(tabledir, new Path(encodedName, new Path(family)));
  }

  /**
   * @param parentRegionDirectory directory for the parent region
   * @param family family name of this store
   * @return Path to the family/Store home directory
   */
  public static Path getStoreHomedir(final Path parentRegionDirectory, final byte[] family) {
    return new Path(parentRegionDirectory, new Path(Bytes.toString(family)));
  }

  /**
   * Return the directory in which this store stores its StoreFiles
   */
  Path getHomedir() {
    return homedir;
  }

  /**
   * @return the data block encoder
   */
  public HFileDataBlockEncoder getDataBlockEncoder() {
    return dataBlockEncoder;
  }

  /**
   * Should be used only in tests.
   * @param blockEncoder the block delta encoder to use
   */
  void setDataBlockEncoderInTest(HFileDataBlockEncoder blockEncoder) {
    this.dataBlockEncoder = blockEncoder;
  }

  FileStatus[] getStoreFiles() throws IOException {
    return FSUtils.listStatus(this.fs, this.homedir, null);
  }

  FileStatus[] mWinterGetLCCIndexStoreFiles() throws IOException {
    return FSUtils.listStatus(this.fs, this.lccIndexDir, null);
  }

  /**
   * Creates an unsorted list of StoreFile loaded in parallel from the given directory.
   * @throws IOException
   */
  private List<StoreFile> loadStoreFiles() throws IOException {
    ArrayList<StoreFile> results = new ArrayList<StoreFile>();
    FileStatus files[] = getStoreFiles();

    if (files == null || files.length == 0) {
      return results;
    }
    // initialize the thread pool for opening store files in parallel..
    ThreadPoolExecutor storeFileOpenerThreadPool =
        this.region.getStoreFileOpenAndCloseThreadPool("StoreFileOpenerThread-"
            + this.family.getNameAsString());
    CompletionService<StoreFile> completionService =
        new ExecutorCompletionService<StoreFile>(storeFileOpenerThreadPool);

    int totalValidStoreFile = 0;
    for (int i = 0; i < files.length; i++) {
      // Skip directories.
      if (files[i].isDir()) {
        continue;
      }
      final Path p = files[i].getPath();
      // Check for empty hfile. Should never be the case but can happen
      // after data loss in hdfs for whatever reason (upgrade, etc.): HBASE-646
      // NOTE: that the HFileLink is just a name, so it's an empty file.
      if (!HFileLink.isHFileLink(p) && this.fs.getFileStatus(p).getLen() <= 0) {
        LOG.warn("Skipping " + p + " because its empty. HBASE-646 DATA LOSS?");
        continue;
      }

      // open each store file in parallel
      completionService.submit(new Callable<StoreFile>() {
        public StoreFile call() throws IOException {
          StoreFile storeFile =
              new StoreFile(fs, p, conf, cacheConf, family.getBloomFilterType(), dataBlockEncoder);
          passSchemaMetricsTo(storeFile);
          storeFile.createReader();
          return storeFile;
        }
      });
      totalValidStoreFile++;
    }

    IOException ioe = null;
    try {
      for (int i = 0; i < totalValidStoreFile; i++) {
        try {
          Future<StoreFile> future = completionService.take();
          StoreFile storeFile = future.get();
          long length = storeFile.getReader().length();
          this.storeSize += length;
          this.totalUncompressedBytes += storeFile.getReader().getTotalUncompressedBytes();
          if (LOG.isDebugEnabled()) {
            LOG.debug("loaded " + storeFile.toStringDetailed());
          }
          results.add(storeFile);
        } catch (InterruptedException e) {
          if (ioe == null) ioe = new InterruptedIOException(e.getMessage());
        } catch (ExecutionException e) {
          if (ioe == null) ioe = new IOException(e.getCause());
        }
      }
    } finally {
      storeFileOpenerThreadPool.shutdownNow();
    }
    if (ioe != null) {
      // close StoreFile readers
      for (StoreFile file : results) {
        try {
          if (file != null) file.closeReader(true);
        } catch (IOException e) {
          LOG.warn(e.getMessage());
        }
      }
      throw ioe;
    }

    return results;
  }

  /**
   * Adds a value to the memstore
   * @param kv
   * @return memstore size delta
   */
  protected long add(final KeyValue kv) {
    lock.readLock().lock();
    try {
      return this.memstore.add(kv);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * When was the oldest edit done in the memstore
   */
  public long timeOfOldestEdit() {
    return memstore.timeOfOldestEdit();
  }

  /**
   * Adds a value to the memstore
   * @param kv
   * @return memstore size delta
   */
  protected long delete(final KeyValue kv) {
    lock.readLock().lock();
    try {
      return this.memstore.delete(kv);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Removes a kv from the memstore. The KeyValue is removed only if its key & memstoreTS matches
   * the key & memstoreTS value of the kv parameter.
   * @param kv
   */
  protected void rollback(final KeyValue kv) {
    lock.readLock().lock();
    try {
      this.memstore.rollback(kv);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * @return All store files.
   */
  public List<StoreFile> getStorefiles() {
    return this.storefiles;
  }

  /**
   * This throws a WrongRegionException if the HFile does not fit in this region, or an
   * InvalidHFileException if the HFile is not valid.
   */
  void assertBulkLoadHFileOk(Path srcPath) throws IOException {
    HFile.Reader reader = null;
    try {
      LOG.info("Validating hfile at " + srcPath + " for inclusion in " + "store " + this
          + " region " + this.region);
      reader = HFile.createReader(srcPath.getFileSystem(conf), srcPath, cacheConf);
      reader.loadFileInfo();

      byte[] firstKey = reader.getFirstRowKey();
      byte[] lk = reader.getLastKey();
      byte[] lastKey = (lk == null) ? null : KeyValue.createKeyValueFromKey(lk).getRow();

      LOG.debug("HFile bounds: first=" + Bytes.toStringBinary(firstKey) + " last="
          + Bytes.toStringBinary(lastKey));
      LOG.debug("Region bounds: first=" + Bytes.toStringBinary(region.getStartKey()) + " last="
          + Bytes.toStringBinary(region.getEndKey()));

      HRegionInfo hri = region.getRegionInfo();
      if (!hri.containsRange(firstKey, lastKey)) {
        throw new WrongRegionException("Bulk load file " + srcPath.toString()
            + " does not fit inside region " + this.region);
      }

      if (verifyBulkLoads) {
        KeyValue prevKV = null;
        HFileScanner scanner = reader.getScanner(false, false, false);
        scanner.seekTo();
        do {
          KeyValue kv = scanner.getKeyValue();
          if (prevKV != null) {
            if (Bytes.compareTo(prevKV.getBuffer(), prevKV.getRowOffset(), prevKV.getRowLength(),
              kv.getBuffer(), kv.getRowOffset(), kv.getRowLength()) > 0) {
              throw new InvalidHFileException("Previous row is greater than"
                  + " current row: path=" + srcPath + " previous="
                  + Bytes.toStringBinary(prevKV.getKey()) + " current="
                  + Bytes.toStringBinary(kv.getKey()));
            }
            if (Bytes.compareTo(prevKV.getBuffer(), prevKV.getFamilyOffset(),
              prevKV.getFamilyLength(), kv.getBuffer(), kv.getFamilyOffset(), kv.getFamilyLength()) != 0) {
              throw new InvalidHFileException("Previous key had different"
                  + " family compared to current key: path=" + srcPath + " previous="
                  + Bytes.toStringBinary(prevKV.getFamily()) + " current="
                  + Bytes.toStringBinary(kv.getFamily()));
            }
          }
          prevKV = kv;
        } while (scanner.next());
      }
    } finally {
      if (reader != null) reader.close();
    }
  }

  /**
   * This method should only be called from HRegion. It is assumed that the ranges of values in the
   * HFile fit within the stores assigned region. (assertBulkLoadHFileOk checks this)
   */
  public void bulkLoadHFile(String srcPathStr, long seqNum) throws IOException {
    System.out.println("winter calling bulkLoadHFile");
    Path srcPath = new Path(srcPathStr);

    // Move the file if it's on another filesystem
    FileSystem srcFs = srcPath.getFileSystem(conf);
    FileSystem desFs = fs instanceof HFileSystem ? ((HFileSystem) fs).getBackingFs() : fs;
    // We can't compare FileSystem instances as
    // equals() includes UGI instance as part of the comparison
    // and won't work when doing SecureBulkLoad
    // TODO deal with viewFS
    if (!srcFs.getUri().equals(desFs.getUri())) {
      LOG.info("File " + srcPath + " on different filesystem than "
          + "destination store - moving to this filesystem.");
      Path tmpPath = getTmpPath();
      FileUtil.copy(srcFs, srcPath, fs, tmpPath, false, conf);

      Path srcIndexPath = getIndexFilePathFromHFilePath(srcPath);
      if (srcFs.exists(srcIndexPath)) {
        Path tmpIndexPath = getIndexFilePathFromHFilePath(tmpPath);
        FileUtil.copy(srcFs, srcIndexPath, fs, tmpIndexPath, false, conf);
      }

      LOG.info("Copied to temporary path on dst filesystem: " + tmpPath);
      srcPath = tmpPath;
    }

    Path dstPath =
        StoreFile.getRandomFilename(fs, homedir, (seqNum == -1) ? null : "_SeqId_" + seqNum + "_");

    Path srcIndexPath = getIndexFilePathFromHFilePath(srcPath);
    if (fs.exists(srcIndexPath)) {
      Path dstIndexPath = new Path(indexDir, dstPath.getName());
      LOG.debug("Renaming bulk load index file " + srcIndexPath + " to " + dstIndexPath);
      System.out.println("winter Renaming bulk load index file " + srcIndexPath + " to "
          + dstIndexPath);
      StoreFile.rename(fs, srcIndexPath, dstIndexPath);
    }
    System.out.println("winter in bulk load, some function not implemented yet");
    System.out.println("winter Renaming bulk load file " + srcPath + " to " + dstPath);
    LOG.debug("Renaming bulk load file " + srcPath + " to " + dstPath);
    StoreFile.rename(fs, srcPath, dstPath);

    StoreFile sf =
        new StoreFile(fs, dstPath, this.conf, this.cacheConf, this.family.getBloomFilterType(),
            this.dataBlockEncoder);
    passSchemaMetricsTo(sf);

    StoreFile.Reader r = sf.createReader();
    this.storeSize += r.length();
    this.totalUncompressedBytes += r.getTotalUncompressedBytes();

    LOG.info("Moved hfile " + srcPath + " into store directory " + homedir
        + " - updating store file list.");

    // Append the new storefile into the list
    System.out.println("winter in bulk load files?");
    this.lock.writeLock().lock();
    try {
      ArrayList<StoreFile> newFiles = new ArrayList<StoreFile>(storefiles);
      newFiles.add(sf);
      this.storefiles = sortAndClone(newFiles);
    } finally {
      // We need the lock, as long as we are updating the storefiles
      // or changing the memstore. Let us release it before calling
      // notifyChangeReadersObservers. See HBASE-4485 for a possible
      // deadlock scenario that could have happened if continue to hold
      // the lock.
      this.lock.writeLock().unlock();
    }
    notifyChangedReadersObservers();
    LOG.info("Successfully loaded store file " + srcPath + " into store " + this
        + " (new location: " + dstPath + ")");
  }

  /**
   * Get a temporary path in this region. These temporary files will get cleaned up when the region
   * is re-opened if they are still around.
   */
  private Path getTmpPath() throws IOException {
    return StoreFile.getRandomFilename(fs, region.getTmpDir());
  }

  /**
   * Close all the readers We don't need to worry about subsequent requests because the HRegion
   * holds a write lock that will prevent any more reads or writes.
   * @throws IOException
   */
  ImmutableList<StoreFile> close() throws IOException {
    this.lock.writeLock().lock();
    try {
      ImmutableList<StoreFile> result = storefiles;

      // Clear so metrics doesn't find them.
      storefiles = ImmutableList.of();

      if (!result.isEmpty()) {
        // initialize the thread pool for closing store files in parallel.
        ThreadPoolExecutor storeFileCloserThreadPool =
            this.region.getStoreFileOpenAndCloseThreadPool("StoreFileCloserThread-"
                + this.family.getNameAsString());

        // close each store file in parallel
        CompletionService<Void> completionService =
            new ExecutorCompletionService<Void>(storeFileCloserThreadPool);
        for (final StoreFile f : result) {
          completionService.submit(new Callable<Void>() {
            public Void call() throws IOException {
              f.closeReader(true);
              return null;
            }
          });
        }

        IOException ioe = null;
        try {
          for (int i = 0; i < result.size(); i++) {
            try {
              Future<Void> future = completionService.take();
              future.get();
            } catch (InterruptedException e) {
              if (ioe == null) {
                ioe = new InterruptedIOException();
                ioe.initCause(e);
              }
            } catch (ExecutionException e) {
              if (ioe == null) ioe = new IOException(e.getCause());
            }
          }
        } finally {
          storeFileCloserThreadPool.shutdownNow();
        }
        if (ioe != null) throw ioe;
      }
      LOG.info("Closed " + this);
      return result;
    } finally {
      this.lock.writeLock().unlock();
    }
  }

  /**
   * Snapshot this stores memstore. Call before running {@link #flushCache(long,
   * SortedSet<KeyValue>)} so it has some work to do.
   */
  void snapshot() {
    this.lock.writeLock().lock();
    try {
      this.memstore.snapshot();
    } finally {
      this.lock.writeLock().unlock();
    }
  }

  /**
   * Write out current snapshot. Presumes {@link #snapshot()} has been called previously.
   * @param logCacheFlushId flush sequence number
   * @param snapshot
   * @param snapshotTimeRangeTracker
   * @param flushedSize The number of bytes flushed
   * @param status
   * @return Path The path name of the tmp file to which the store was flushed
   * @throws IOException
   */
  protected Path flushCache(final long logCacheFlushId, SortedSet<KeyValue> snapshot,
      TimeRangeTracker snapshotTimeRangeTracker, AtomicLong flushedSize, MonitoredTask status)
      throws IOException {
    // If an exception happens flushing, we let it out without clearing
    // the memstore snapshot. The old snapshot will be returned when we say
    // 'snapshot', the next time flush comes around.
    // Retry after catching exception when flushing, otherwise server will abort
    // itself
    IOException lastException = null;
    for (int i = 0; i < Store.flush_retries_number; i++) {
      try {
        Path pathName =
            internalFlushCache(snapshot, logCacheFlushId, snapshotTimeRangeTracker, flushedSize,
              status);
        try {
          // Path name is null if there is no entry to flush
          if (pathName != null) {
            validateStoreFile(pathName);
          }
          return pathName;
        } catch (Exception e) {
          LOG.warn("Failed validating store file " + pathName + ", retring num=" + i, e);
          if (e instanceof IOException) {
            lastException = (IOException) e;
          } else {
            lastException = new IOException(e);
          }
        }
      } catch (IOException e) {
        LOG.warn("Failed flushing store file, retring num=" + i, e);
        lastException = e;
      }
      if (lastException != null && i < (flush_retries_number - 1)) {
        try {
          Thread.sleep(pauseTime);
        } catch (InterruptedException e) {
          IOException iie = new InterruptedIOException();
          iie.initCause(e);
          throw iie;
        }
      }
    }
    throw lastException;
  }

  /*
   * @param cache
   * @param logCacheFlushId
   * @param snapshotTimeRangeTracker
   * @param flushedSize The number of bytes flushed
   * @return Path The path name of the tmp file to which the store was flushed
   * @throws IOException
   */
  private Path internalFlushCache(final SortedSet<KeyValue> set, final long logCacheFlushId,
      TimeRangeTracker snapshotTimeRangeTracker, AtomicLong flushedSize, MonitoredTask status)
      throws IOException {
    long timeStart = System.currentTimeMillis();
    StoreFile.Writer writer;
    IndexWriter indexWriter = null;
    LCCIndexWriter lccIndexWriterV2 = null;
    // Find the smallest read point across all the Scanners.
    long smallestReadPoint = region.getSmallestReadPoint();
    long flushed = 0;
    Path pathName;
    // Don't flush if there are no entries.
    if (set.size() == 0) {
      return null;
    }
    // Use a store scanner to find which rows to flush.
    // Note that we need to retain deletes, hence
    // treat this as a minor compaction.
    InternalScanner scanner = null;
    KeyValueScanner memstoreScanner = new CollectionBackedScanner(set, this.comparator);
    if (getHRegion().getCoprocessorHost() != null) {
      scanner = getHRegion().getCoprocessorHost().preFlushScannerOpen(this, memstoreScanner);
    }
    if (scanner == null) {
      Scan scan = new Scan();
      scan.setMaxVersions(scanInfo.getMaxVersions());
      scanner =
          new StoreScanner(this, scanInfo, scan, Collections.singletonList(memstoreScanner),
              ScanType.MINOR_COMPACT, this.region.getSmallestReadPoint(),
              HConstants.OLDEST_TIMESTAMP);
    }
    if (getHRegion().getCoprocessorHost() != null) {
      InternalScanner cpScanner = getHRegion().getCoprocessorHost().preFlush(this, scanner);
      // NULL scanner returned from coprocessor hooks means skip normal processing
      if (cpScanner == null) {
        return null;
      }
      scanner = cpScanner;
    }
    try {
      int compactionKVMax = conf.getInt(HConstants.COMPACTION_KV_MAX, 10);
      // TODO: We can fail in the below block before we complete adding this
      // flush to list of store files. Add cleanup of anything put on filesystem
      // if we fail.
      synchronized (flushLock) {
        status.setStatus("Flushing " + this + ": creating writer");
        // A. Write the map out to the disk
        writer = createWriterInTmp(set.size());
        writer.setTimeRangeTracker(snapshotTimeRangeTracker);
        pathName = writer.getPath();
        TreeSet<IndexKeyValue> indexSet = null;
        if (hasIndex) {
          indexSet = new TreeSet<IndexKeyValue>(this.indexComparator);
        }

        if (hasLCCIndex) {
          String desStr =
              this.getHRegion().getTableDesc().getValue(LCCIndexConstant.LCC_TABLE_DESC_RANGE_STR);
          lccindexGenerator = new LCCIndexGenerator(lccIndexQualifierType, desStr);
        }
        KeyValue firstKV = null;
        int lccIndexRecordsCounter = 0;
        int rawKeyValueCounter = 0;
        long lccFlushStart = System.currentTimeMillis();
        try {
          List<KeyValue> kvs = new ArrayList<KeyValue>();
          boolean hasMore;
          int counter = 0;
          do {
            hasMore = scanner.next(kvs, compactionKVMax);
            if (!kvs.isEmpty()) {
              for (KeyValue kv : kvs) {
                ++counter;
                // If we know that this KV is going to be included always, then let us
                // set its memstoreTS to 0. This will help us save space when writing to disk.
                if (kv.getMemstoreTS() <= smallestReadPoint) {
                  // let us not change the original KV. It could be in the memstore
                  // changing its memstoreTS could affect other threads/scanners.
                  kv = kv.shallowCopy();
                  kv.setMemstoreTS(0);
                }
                ++rawKeyValueCounter;
                writer.append(kv);
                // generate Index KeyValue
                if (hasIndex && kv.getType() == KeyValue.Type.Put.getCode()) {
                  if (indexMap.containsKey(kv.getQualifier())) {
                    indexSet.add(new IndexKeyValue(kv));
                  }
                }
                if (hasLCCIndex) {
                  lccindexGenerator.processKeyValue(kv);
                }
                flushed += this.memstore.heapSizeChange(kv, true);
              }
              kvs.clear();
            }
          } while (hasMore);
          System.out.println("winter flush raw data has " + counter + " records");
          if (hasIndex && !indexSet.isEmpty()) {
            indexWriter = createIndexWriterInTmp(pathName);
            for (IndexKeyValue tmpkv : indexSet) {
              indexWriter.append(tmpkv);
            }
          }
          if (hasLCCIndex) {
            KeyValue[] lccIndexResultArray = lccindexGenerator.generatedSortedKeyValueArray();
            if (lccIndexResultArray != null && lccIndexResultArray.length > 0) {
              lccIndexRecordsCounter = lccIndexResultArray.length;
              lccIndexWriterV2 =
                  new LCCIndexWriter(pathName, lccindexGenerator.getResultStatistic(),
                      lccindexGenerator.getLCRangeStatistic(), snapshotTimeRangeTracker);
              for (KeyValue kv : lccIndexResultArray) {
                if (firstKV == null) {
                  firstKV = kv;
                }
                lccIndexWriterV2.append(kv);
              }
            } else {
              System.out.println("winter the lccResults is zero, how come?!");
            }
          }
        } finally {
          // Write out the log sequence number that corresponds to this output
          // hfile. The hfile is current up to and including logCacheFlushId.
          status.setStatus("Flushing " + this + ": appending metadata");
          writer.appendMetadata(logCacheFlushId, false);
          status.setStatus("Flushing " + this + ": closing flushed file");
          writer.close();
          if (indexWriter != null) {
            indexWriter.close();
          }
          if (lccIndexWriterV2 != null) {
            // winter flush happens in the same host in one execution
            // however, the HFile are not located in the same host
            // the problem is: how to move the lccindex files to the proper IP? or just flush the
            // file to that host?
            InetAddress thisip = InetAddress.getLocalHost();
            lccIndexWriterV2.close();
            System.out.println("winter flushing lccIndexWriter with size: "
                + lccIndexRecordsCounter + " at ip: " + thisip.getHostAddress() + ", host: "
                + thisip.getHostName());
            System.out.println("winter memstore flush lccindex cost: " + 1.0
                * (System.currentTimeMillis() - lccFlushStart) / 1000.0 + " seconds for raw size:"
                + rawKeyValueCounter + " and lcc size: " + lccIndexRecordsCounter);
          }
          if (lccindexGenerator != null) {
            lccindexGenerator.clear();
          } else { // lccindexGenerator = null, say no lccindex used
            System.out.println("winter memstore flush raw cost: " + 1.0
                * (System.currentTimeMillis() - lccFlushStart) / 1000.0 + " seconds for raw size:"
                + rawKeyValueCounter);
          }
          indexSet = null;
        }
      }
    } finally {
      flushedSize.set(flushed);
      scanner.close();
    }
    if (LOG.isInfoEnabled()) {
      LOG.info("Flushed " + ", sequenceid=" + logCacheFlushId + ", memsize="
          + StringUtils.humanReadableInt(flushed) + ", into tmp file " + pathName);
    }
    return pathName;
  }

  public class LCCIndexWriter {
    final Path basePath;
    TreeMap<byte[], StoreFile.Writer> indexWriters = new TreeMap<byte[], StoreFile.Writer>(
        Bytes.BYTES_COMPARATOR);
    List<LCStatInfo> rangeInfoList;

    // raw pathName is: /hbase/AAA/xxx/.tmp/BBB,
    // writerBaseDir = /hbase/lcc/AAA/.tmp/BBB.lccindex
    // file /hbase/lcc/AAA/.tmp/BBB.lccindex/Q1-Q4 indicates a index file for qualifier Q1/Q4
    public LCCIndexWriter(Path rawHFile, Map<byte[], Integer> statistic,
        List<LCStatInfo> rangeInfoList, TimeRangeTracker tracker) throws IOException {
      this.rangeInfoList = rangeInfoList;
      if (localfs != null) {
        basePath = getLocalBaseDirByFileName(rawHFile);
        if (!localfs.exists(basePath)) { // create dir
          localfs.mkdirs(basePath);
        }
      } else {
        basePath = mWinterGetLCCIndexFilePathFromHFilePathInTmp(rawHFile);
      }
      for (Entry<byte[], Integer> entry : statistic.entrySet()) {
        if (entry.getValue() > 0) {
          // targetPath = /hbase/lcc/AAA/.tmp/BBB.lccindex/Q1-Q4
          Path targetPath = new Path(basePath, Bytes.toString(entry.getKey()));
          StoreFile.Writer writer = null;
          if (localfs != null) {
            writer =
                mWinterCreateWriterInLocalTmp(entry.getValue(), family.getCompression(), false,
                  true, targetPath);
            writeStatInfo(localfs, basePath, Bytes.toString(entry.getKey()));
          } else {
            writer =
                mWinterCreateWriterInTmp(entry.getValue(), family.getCompression(), false, true,
                  targetPath);
            writeStatInfo(fs, basePath, Bytes.toString(entry.getKey()));
          }
          if (tracker != null) {
            writer.setTimeRangeTracker(tracker);
          }
          indexWriters.put(entry.getKey(), writer);
        } else {
          System.out.println("winter ignore cf: " + Bytes.toString(entry.getKey())
              + " bacause it contains " + entry.getValue() + " rows");
        }
      }
    }

    private void writeStatInfo(FileSystem fs, Path basePath, String qualifierName)
        throws IOException {
      for (LCStatInfo stat : rangeInfoList) {
        if (stat.getName().equals(qualifierName)) {
          Path pt = new Path(basePath, qualifierName + LCCIndexConstant.LC_STAT_FILE_SUFFIX);
          BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fs.create(pt, true)));
          if (stat.isSet()) {
            for (long l : stat.getSetValues()) {
              bw.write(l + "\n");
            }
          } else {
            for (long l : stat.getCounts()) {
              bw.write(l + "\n");
            }
          }
          bw.close();
          return;
        }
      }
    }

    public Path getBaseDirPath() {
      return this.basePath;
    }

    public void append(KeyValue kv) throws IOException {
      byte[] rawQualifier =
          Bytes.toBytes(LCCIndexGenerator.mWinterRecoverLCCQualifier_Str(Bytes.toString(kv
              .getFamily())));
      if (!indexWriters.containsKey(rawQualifier)) {
        System.out.println("winter error, try to write indexed value but writer not exists");
      } else {
        indexWriters.get(rawQualifier).append(kv);
      }
    }

    public void close() throws IOException {
      for (Entry<byte[], StoreFile.Writer> entry : indexWriters.entrySet()) {
        entry.getValue().close();
        System.out.println("winter flushing memstore to file: " + entry.getValue().getPath());
      }
    }
  }

  public Path getLocalBaseDirByFileName(Path rawHFile) {
    return getLocalBaseDirByFileName(this.lccLocalHome, rawHFile);
  }

  /*
   * @param rawHFile: hdfs://localhost:9000/hbase/lcc/AAA/.tmp/BBB
   * @param want return: lcIndexLocalBaseDir/lcc/AAA/.tmp/BBB.lccindex
   * @param localhome = lcIndexLocalBaseDir/lcc/AAA/f/.lccindex
   */
  public static Path getLocalBaseDirByFileName(Path localHome, Path rawHFile) {
    Path p =
        new Path(rawHFile.getParent().getName(), rawHFile.getName()
            + LCCIndexConstant.INDEX_DIR_NAME); // now = .tmp/BBB.lccindex
    // return lcIndexLocalBaseDir/lcc/AAA/.tmp/BBB.lccindex
    return new Path(localHome.getParent().getParent(), p);
  }

  /*
   * @param path The pathname of the tmp file into which the store was flushed
   * @param logCacheFlushId
   * @return StoreFile created.
   * @throws IOException
   */
  private StoreFile commitFile(final Path path, final long logCacheFlushId,
      TimeRangeTracker snapshotTimeRangeTracker, AtomicLong flushedSize, MonitoredTask status)
      throws IOException {
    // mWinterDebugXceiverByBaseTable();

    // Write-out finished successfully, move into the right spot
    String fileName = path.getName();
    Path dstPath = new Path(homedir, fileName);
    // System.out.println("winter path: " + wint);

    Path indexPath = new Path(path.toString() + IndexConstants.REGION_INDEX_DIR_NAME);
    Path indexdstPath = new Path(indexDir, fileName);
    // make sure to move index file first, otherwise data may be inconsistent
    // System.out.println("winter checking irindex file " + indexPath + " to " + indexdstPath);
    if (fs.exists(indexPath)) {
      String msg = "Renaming flushed index file at " + indexPath + " to " + indexdstPath;
      LOG.debug(msg);
      status.setStatus("Flushing " + this + ": " + msg);
      if (!HBaseFileSystem.renameDirForFileSystem(fs, indexPath, indexdstPath)) {
        LOG.warn("Unable to rename index file " + indexPath + " to " + indexdstPath);
      }
      // System.out.println("winter renaming irindex file " + indexPath + " to " + indexdstPath);
    }

    if (this.hasLCCIndex) {
      if (localfs != null) {
        mWinterCommitLCCLocal(path, status);
      } else {
        mWinterCommitLCCHDFS(path, status);
      }
    }

    String msg = "Renaming flushed file at " + path + " to " + dstPath;
    LOG.debug(msg);
    status.setStatus("Flushing " + this + ": " + msg);
    try {
      if (!HBaseFileSystem.renameDirForFileSystem(fs, path, dstPath)) {
        LOG.warn("Unable to rename " + path + " to " + dstPath);
      }
    } catch (IOException e) {
      HBaseFileSystem.deleteFileFromFileSystem(fs, indexdstPath);
      throw e;
    }
    // System.out.println("winter renaming original file " + path + " to " + dstPath);

    status.setStatus("Flushing " + this + ": reopening flushed file");
    StoreFile sf =
        new StoreFile(this.fs, dstPath, this.conf, this.cacheConf,
            this.family.getBloomFilterType(), this.dataBlockEncoder);
    passSchemaMetricsTo(sf);

    StoreFile.Reader r = sf.createReader();
    this.storeSize += r.length();
    this.totalUncompressedBytes += r.getTotalUncompressedBytes();

    // This increments the metrics associated with total flushed bytes for this
    // family. The overall flush count is stored in the static metrics and
    // retrieved from HRegion.recentFlushes, which is set within
    // HRegion.internalFlushcache, which indirectly calls this to actually do
    // the flushing through the StoreFlusherImpl class
    getSchemaMetrics().updatePersistentStoreMetric(SchemaMetrics.StoreMetricType.FLUSH_SIZE,
      flushedSize.longValue());
    if (LOG.isInfoEnabled()) {
      LOG.info("Added "
          + sf
          + ", entries="
          + r.getEntries()
          + ", sequenceid="
          + logCacheFlushId
          + ", filesize="
          + StringUtils.humanReadableInt(r.length())
          + (sf.getIndexReader() == null ? "" : ", indexsize="
              + StringUtils.humanReadableInt(sf.getIndexReader().length())));
    }
    return sf;
  }

  private void mWinterCommitLCCHDFS(final Path path, MonitoredTask status) throws IOException {
    // target must be f/.lccindex/Q1-Q4/BBB // path = /hbase/lcc/AAA/.tmp/BBB
    Path lccIndexPath = new Path(path.toString() + LCCIndexConstant.INDEX_DIR_NAME);
    FileStatus[] lccIndexTempFileStatus = fs.listStatus(lccIndexPath);
    if (lccIndexTempFileStatus != null && lccIndexTempFileStatus.length > 0) {
      for (FileStatus fileStatus : lccIndexTempFileStatus) {
        // fileStatus = tempFileLocation = /hbase/lcc/AAA/.tmp/BBB.lccindex/Q1-Q4
        // lccIndexDir = /hbase/lcc/AAA/f/.lccindex
        // indexQualifierPath = /hbase/lcc/AAA/f/.lccindex/Q1-Q4
        Path indexQualifierPath = new Path(lccIndexDir, fileStatus.getPath().getName());
        if (!fs.exists(indexQualifierPath)) {
          HBaseFileSystem.makeDirOnFileSystem(fs, indexQualifierPath);
          LOG.debug("winter lccindex qualifier dir path does not exist, create first: "
              + indexQualifierPath);
        } else {
          LOG.debug("winter lccindex qualifier dir exists: " + indexQualifierPath);
        } // lccIndexdstPath = /hbase/lcc/AAA/f/.lccindex/Q1-Q4/BBB Path
        Path lccIndexDestPath = new Path(indexQualifierPath, path.getName());
        String msg =
            "Renaming flushed lccindex file at " + fileStatus.getPath() + " to " + lccIndexDestPath;
        LOG.debug(msg);
        status.setStatus("Flushing " + this + ": " + msg);
        if (!HBaseFileSystem.renameDirForFileSystem(fs, new Path(fileStatus.getPath().getParent(),
            fileStatus.getPath().getName() + LCCIndexConstant.LC_STAT_FILE_SUFFIX), new Path(
            indexQualifierPath, path.getName() + LCCIndexConstant.LC_STAT_FILE_SUFFIX))) {
          LOG.warn("Unable to rename lccindex stat file " + fileStatus.getPath() + " to "
              + lccIndexDestPath);
        }

        if (!HBaseFileSystem.renameDirForFileSystem(fs, fileStatus.getPath(), lccIndexDestPath)) {
          LOG.warn("Unable to rename lccindex file " + fileStatus.getPath() + " to "
              + lccIndexDestPath);
        }
        LOG.info("winter finish renaming lccindex file " + fileStatus.getPath() + " to "
            + lccIndexDestPath);
      }
      lccIndexTempFileStatus = fs.listStatus(lccIndexPath);
      if (lccIndexTempFileStatus != null && lccIndexTempFileStatus.length == 0) {
        HFileArchiver.mWinterArchiveFile(conf, fs, this.region, family.getName(), lccIndexPath);
      } else {
        WinterOptimizer.ThrowWhenCalled("winter commit lcc dir should be empty but not: "
            + lccIndexPath);
      }
    } else { // no lccindex exists
      System.out.println("winter lccIndexPath dir not exist: " + lccIndexPath);
    }
  }

  private void mWinterCommitLCCLocal(final Path path, MonitoredTask status) throws IOException {
    // target must be f/.lccindex/Q1-Q4/BBB
    // path = /hbase/lcc/AAA/.tmp/BBB
    Path lccLocalTmpPath = getLocalBaseDirByFileName(path);
    // before commit, dir lccLocalPath have already got files from flush or commit
    FileStatus[] lccLocalTempFileStatus = localfs.listStatus(lccLocalTmpPath);
    if (lccLocalTempFileStatus != null && lccLocalTempFileStatus.length > 0) {
      for (FileStatus fileStatus : lccLocalTempFileStatus) {
        if (fileStatus.getPath().getName().endsWith(LCCIndexConstant.LC_STAT_FILE_SUFFIX)) continue;
        // fileStatus = Local_Home/lcc/AAA/.tmp/BBB.lccindex/Q1-Q4
        // indexQualifierPath = Local_Home/hbase/lcc/AAA/f/.lccindex/Q1-Q4
        // lccLocalHome = Local_Home/lcc/AAA/f/.lccindex
        Path indexQualifierPath = new Path(lccLocalHome, fileStatus.getPath().getName());
        // create dir if not existed
        if (!localfs.exists(indexQualifierPath)) {
          HBaseFileSystem.makeDirOnFileSystem(localfs, indexQualifierPath);
        }

        // move stat file
        Path rawStatFile =
            new Path(fileStatus.getPath().getParent(), fileStatus.getPath().getName()
                + LCCIndexConstant.LC_STAT_FILE_SUFFIX);
        Path destStatFile =
            new Path(indexQualifierPath, path.getName() + LCCIndexConstant.LC_STAT_FILE_SUFFIX);
        // System.out.println("winter move raw stat file: " + rawStatFile + " to " +
        // targetStatFile);
        if (!HBaseFileSystem.renameDirForFileSystem(localfs, rawStatFile, destStatFile)) {
          LOG.warn("Unable to rename lccindex local stat file " + rawStatFile + " to "
              + destStatFile);
        }

        // move index file
        // lccIndexDestPath = HOME/lcc/AAA/f/.lccindex/Q1-Q4/BBB
        Path lccIndexDestPath = new Path(indexQualifierPath, path.getName());
        LOG.debug("Renaming flushed lccindex local file from " + fileStatus.getPath() + " to "
            + lccIndexDestPath);
        System.out.println("winter renaming flushed lccindex local file from "
            + fileStatus.getPath() + " to " + lccIndexDestPath);
        if (!HBaseFileSystem
            .renameDirForFileSystem(localfs, fileStatus.getPath(), lccIndexDestPath)) {
          LOG.warn("Unable to rename lccindex local file " + fileStatus.getPath() + " to "
              + lccIndexDestPath);
        }
      }
      // delete dir if necessary
      lccLocalTempFileStatus = localfs.listStatus(lccLocalTmpPath);
      if (lccLocalTempFileStatus != null && lccLocalTempFileStatus.length == 0) {
        localfs.delete(lccLocalTmpPath, true);
      } else if (lccLocalTempFileStatus != null) {
        WinterOptimizer.ThrowWhenCalled("winter commit local lcc dir should be empty but not: "
            + lccLocalTmpPath);
      }
    } else {
      // no lccindex exists
      System.out.println("winter lccIndexPath local dir not exist: " + lccLocalTmpPath);
    }
  }

  /*
   * @return Writer for a new IndexFile in the tmp dir.
   */
  IndexWriter createIndexWriterInTmp(Path hfilePath) throws IOException {
    return createIndexWriterInTmp(hfilePath, this.family.getCompression());
  }

  IndexWriter createIndexWriterInTmp(Compression.Algorithm compression) throws IOException {
    return createIndexWriterInTmp(null, compression);
  }

  /*
   * @param compression Compression algorithm to use
   * @return Writer for a new IndexFile in the tmp dir.
   */
  IndexWriter createIndexWriterInTmp(Path hfilePath, Compression.Algorithm compression)
      throws IOException {
    return StoreFile.createIndexWriter(this.fs, getIndexFilePathFromHFilePathInTmp(hfilePath),
      this.indexReplication, this.blocksize, compression, this.indexComparator, this.conf);
  }

  public static Path getIndexFilePathFromHFilePath(Path hfilePath) throws IOException {
    return new Path(hfilePath.toString() + IndexConstants.REGION_INDEX_DIR_NAME);
  }

  Path getIndexFilePathFromHFilePathInTmp(Path hfilePath) throws IOException {
    return new Path((hfilePath != null ? hfilePath.toString() : StoreFile.getUniqueFile(fs,
      region.getTmpDir()).toString())
        + IndexConstants.REGION_INDEX_DIR_NAME);
  }

  // return hfilePath.lccindex
  Path mWinterGetLCCIndexFilePathFromHFilePathInTmp(Path hfilePath) throws IOException {
    return new Path((hfilePath != null ? hfilePath.toString() : StoreFile.getUniqueFile(fs,
      region.getTmpDir()).toString())
        + LCCIndexConstant.INDEX_DIR_NAME);
  }

  IndexWriter createIndexWriterInLocalTmp(Compression.Algorithm compression) throws IOException {
    return StoreFile.createIndexWriter(this.localfs, getIndexFilePathInLocalTmp(),
      this.indexReplication, this.blocksize, compression, this.indexComparator, this.conf);
  }

  Path getIndexFilePathInLocalTmp() throws IOException {
    Path indexCompactionPath = new Path(conf.get(IndexConstants.INDEX_COMPACTION_LOCAL_DIR));
    if (!this.localfs.exists(indexCompactionPath)) {
      this.localfs.mkdirs(indexCompactionPath);
    }
    return new Path(StoreFile.getUniqueFile(localfs, indexCompactionPath)
        + IndexConstants.REGION_INDEX_DIR_NAME);
  }

  /*
   * @param maxKeyCount
   * @return Writer for a new StoreFile in the tmp dir.
   */
  private StoreFile.Writer createWriterInTmp(int maxKeyCount) throws IOException {
    return createWriterInTmp(maxKeyCount, this.family.getCompression(), false, true);
  }

  /*
   * @param maxKeyCount
   * @param compression Compression algorithm to use
   * @param isCompaction whether we are creating a new file in a compaction
   * @return Writer for a new StoreFile in the tmp dir.
   */
  public StoreFile.Writer createWriterInTmp(int maxKeyCount, Compression.Algorithm compression,
      boolean isCompaction, boolean includeMVCCReadpoint) throws IOException {
    final CacheConfig writerCacheConf;
    if (isCompaction) {
      // Don't cache data on write on compactions.
      writerCacheConf = new CacheConfig(cacheConf);
      writerCacheConf.setCacheDataOnWrite(false);
    } else {
      writerCacheConf = cacheConf;
    }
    StoreFile.Writer w =
        new StoreFile.WriterBuilder(conf, writerCacheConf, fs, blocksize)
            .withOutputDir(region.getTmpDir()).withDataBlockEncoder(dataBlockEncoder)
            .withComparator(comparator).withBloomType(family.getBloomFilterType())
            .withMaxKeyCount(maxKeyCount).withChecksumType(checksumType)
            .withBytesPerChecksum(bytesPerChecksum).withCompression(compression)
            .includeMVCCReadpoint(includeMVCCReadpoint).build();
    // The store file writer's path does not include the CF name, so we need
    // to configure the HFile writer directly.
    SchemaConfigured sc = (SchemaConfigured) w.writer;
    SchemaConfigured.resetSchemaMetricsConf(sc);
    passSchemaMetricsTo(sc);
    return w;
  }

  // winte careful, this is temp, not local temp!
  protected StoreFile.Writer mWinterCreateWriterInTmp(int maxKeyCount, Path rawPath)
      throws IOException {
    System.out.println("winter raw pathName is: " + rawPath + ", lccindex to: "
        + mWinterGetLCCIndexFilePathFromHFilePathInTmp(rawPath).toString());
    return mWinterCreateWriterInTmp(maxKeyCount, this.family.getCompression(), false, true,
      mWinterGetLCCIndexFilePathFromHFilePathInTmp(rawPath));
  }

  /*
   * @param maxKeyCount
   * @param compression Compression algorithm to use
   * @param isCompaction whether we are creating a new file in a compaction
   * @return Writer for a new StoreFile in the tmp dir.
   */
  public StoreFile.Writer mWinterCreateWriterInTmp(int maxKeyCount,
      Compression.Algorithm compression, boolean isCompaction, boolean includeMVCCReadpoint,
      Path lccIndexFilePath) throws IOException {
    final CacheConfig writerCacheConf;
    if (isCompaction) {
      // Don't cache data on write on compactions.
      writerCacheConf = new CacheConfig(cacheConf);
      writerCacheConf.setCacheDataOnWrite(false);
    } else {
      writerCacheConf = cacheConf;
    }
    StoreFile.Writer w =
        new StoreFile.WriterBuilder(conf, writerCacheConf, fs, blocksize)
            .withFilePath(lccIndexFilePath).withDataBlockEncoder(dataBlockEncoder)
            .withComparator(comparator).withBloomType(family.getBloomFilterType())
            .withMaxKeyCount(maxKeyCount).withChecksumType(checksumType)
            .withBytesPerChecksum(bytesPerChecksum).withCompression(compression)
            .includeMVCCReadpoint(includeMVCCReadpoint).build();
    // The store file writer's path does not include the CF name, so we need
    // to configure the HFile writer directly.
    SchemaConfigured sc = (SchemaConfigured) w.writer;
    SchemaConfigured.resetSchemaMetricsConf(sc);
    passSchemaMetricsTo(sc);
    return w;
  }

  public StoreFile.Writer mWinterCreateWriterInLocalTmp(int maxKeyCount,
      Compression.Algorithm compression, boolean isCompaction, boolean includeMVCCReadpoint,
      Path lccIndexFilePath) throws IOException {
    final CacheConfig writerCacheConf;
    if (isCompaction) {
      // Don't cache data on write on compactions.
      writerCacheConf = new CacheConfig(cacheConf);
      writerCacheConf.setCacheDataOnWrite(false);
    } else {
      writerCacheConf = cacheConf;
    }
    StoreFile.Writer w =
        new StoreFile.WriterBuilder(conf, writerCacheConf, localfs, blocksize)
            .withFilePath(lccIndexFilePath).withDataBlockEncoder(dataBlockEncoder)
            .withComparator(comparator).withBloomType(family.getBloomFilterType())
            .withMaxKeyCount(maxKeyCount).withChecksumType(checksumType)
            .withBytesPerChecksum(bytesPerChecksum).withCompression(compression)
            .includeMVCCReadpoint(includeMVCCReadpoint).build();
    // The store file writer's path does not include the CF name, so we need
    // to configure the HFile writer directly.
    SchemaConfigured sc = (SchemaConfigured) w.writer;
    SchemaConfigured.resetSchemaMetricsConf(sc);
    passSchemaMetricsTo(sc);
    return w;
  }

  /*
   * Change storefiles adding into place the Reader produced by this new flush.
   * @param sf
   * @param set That was used to make the passed file <code>p</code>.
   * @throws IOException
   * @return Whether compaction is required.
   */
  private boolean updateStorefiles(final StoreFile sf, final SortedSet<KeyValue> set)
      throws IOException {
    this.lock.writeLock().lock();
    try {
      ArrayList<StoreFile> newList = new ArrayList<StoreFile>(storefiles);
      newList.add(sf);
      storefiles = sortAndClone(newList);
      this.memstore.clearSnapshot(set);
    } finally {
      // We need the lock, as long as we are updating the storefiles
      // or changing the memstore. Let us release it before calling
      // notifyChangeReadersObservers. See HBASE-4485 for a possible
      // deadlock scenario that could have happened if continue to hold
      // the lock.
      this.lock.writeLock().unlock();
    }

    // Tell listeners of the change in readers.
    notifyChangedReadersObservers();

    return needsCompaction();
  }

  /*
   * Notify all observers that set of Readers has changed.
   * @throws IOException
   */
  private void notifyChangedReadersObservers() throws IOException {
    for (ChangedReadersObserver o : this.changedReaderObservers) {
      System.out.println("winter store.notifyChangedReadersObservers is called");
      o.updateReaders();
    }
  }

  /**
   * Get all scanners with no filtering based on TTL (that happens further down the line).
   * @return all scanners for this store
   */
  protected List<KeyValueScanner> getScanners(boolean cacheBlocks, boolean usePread,
      boolean isCompaction, ScanQueryMatcher matcher) throws IOException {
    List<StoreFile> storeFiles;
    List<KeyValueScanner> memStoreScanners;
    this.lock.readLock().lock();
    try {
      storeFiles = this.getStorefiles();
      memStoreScanners = this.memstore.getScanners();
    } finally {
      this.lock.readLock().unlock();
    }

    // First the store file scanners

    // TODO this used to get the store files in descending order,
    // but now we get them in ascending order, which I think is
    // actually more correct, since memstore get put at the end.
    // winter this is the storefile is loaded!
    List<StoreFileScanner> sfScanners =
        StoreFileScanner.getScannersForStoreFiles(storeFiles, cacheBlocks, usePread, isCompaction,
          matcher);
    List<KeyValueScanner> scanners = new ArrayList<KeyValueScanner>(sfScanners.size() + 1);
    scanners.addAll(sfScanners);
    // Then the memstore scanners
    scanners.addAll(memStoreScanners);
    return scanners;
  }

  protected List<KeyValueScanner> mWinterGetScanners(boolean cacheBlocks, boolean usePread,
      boolean isCompaction, ScanQueryMatcher matcher, byte[] targetLCCIndexCF) throws IOException {
    List<StoreFile> storeFiles;
    List<KeyValueScanner> memStoreScanners;
    this.lock.readLock().lock();
    try {
      storeFiles = this.getStorefiles();
      memStoreScanners = this.memstore.getScanners();
    } finally {
      this.lock.readLock().unlock();
    }

    List<StoreFileScanner> sfScanners =
        StoreFileScanner.mWinterGetScannersFromFiles(storeFiles, cacheBlocks, usePread,
          isCompaction, matcher, targetLCCIndexCF, this);

    List<KeyValueScanner> scanners = new ArrayList<KeyValueScanner>(sfScanners.size() + 1);
    scanners.addAll(sfScanners);
    scanners.addAll(memStoreScanners);
    return scanners;
  }

  /*
   * @param o Observer who wants to know about changes in set of Readers
   */
  void addChangedReaderObserver(ChangedReadersObserver o) {
    this.changedReaderObservers.add(o);
  }

  /*
   * @param o Observer no longer interested in changes in set of Readers.
   */
  public void deleteChangedReaderObserver(ChangedReadersObserver o) {
    // We don't check if observer present; it may not be (legitimately)
    this.changedReaderObservers.remove(o);
  }

  // ////////////////////////////////////////////////////////////////////////////
  // Compaction
  // ////////////////////////////////////////////////////////////////////////////

  /**
   * Compact the StoreFiles. This method may take some time, so the calling thread must be able to
   * block for long periods.
   * <p>
   * During this time, the Store can work as usual, getting values from StoreFiles and writing new
   * StoreFiles from the memstore. Existing StoreFiles are not destroyed until the new compacted
   * StoreFile is completely written-out to disk.
   * <p>
   * The compactLock prevents multiple simultaneous compactions. The structureLock prevents us from
   * interfering with other write operations.
   * <p>
   * We don't want to hold the structureLock for the whole time, as a compact() can be lengthy and
   * we want to allow cache-flushes during this period.
   * @param cr compaction details obtained from requestCompaction()
   * @throws IOException
   * @return Storefile we compacted into or null if we failed or opted out early.
   */
  StoreFile compact(CompactionRequest cr) throws IOException {
    // System.out.println("winter calling compact now!");
    if (cr == null || cr.getFiles().isEmpty()) return null;
    Preconditions.checkArgument(cr.getStore().toString().equals(this.toString()));
    List<StoreFile> filesToCompact = cr.getFiles();
    synchronized (filesCompacting) {
      // sanity check: we're compacting files that this store knows about
      // TODO: change this to LOG.error() after more debugging
      Preconditions.checkArgument(filesCompacting.containsAll(filesToCompact));
    }

    // Max-sequenceID is the last key in the files we're compacting
    long maxId = StoreFile.getMaxSequenceIdInList(filesToCompact, true);

    // Ready to go. Have list of files to compact.
    LOG.info("Starting compaction of " + filesToCompact.size() + " file(s) in " + this + " of "
        + this.region.getRegionInfo().getRegionNameAsString() + " into tmpdir="
        + region.getTmpDir() + ", seqid=" + maxId + ", totalSize="
        + StringUtils.humanReadableInt(cr.getSize()));

    StoreFile sf = null;
    long compactStart = System.currentTimeMillis();
    try {
      StoreFile.Writer writer = this.compactor.compact(cr, maxId);
      System.out.println("winter compact raw " + (cr.isMajor() ? "major" : "minor") + ", cost "
          + (System.currentTimeMillis() - compactStart) * 1.0 / 1000.0 + " seconds");

      // compact index files
      if (hasIndex && writer != null) {
        this.compactor.compactIndex(cr, writer.getPath());
      }

      // compact lccindex files
      if (hasLCCIndex && writer != null) {
        if (localfs != null) this.compactor.mWinterCompactLCCIndexLocal(cr, writer.getPath());
        else this.compactor.mWinterCompactLCCIndex(cr, writer.getPath());
        System.out.println("winter compact lcc " + (cr.isMajor() ? "major" : "minor") + ", cost "
            + (System.currentTimeMillis() - compactStart) * 1.0 / 1000.0 + " seconds");
      }

      // Move the compaction into place.
      if (this.conf.getBoolean("hbase.hstore.compaction.complete", true)) {
        long completeCompactionStart = System.currentTimeMillis();
        sf = completeCompaction(filesToCompact, writer, cr.isMajor());
        if (this.hasLCCIndex) {
          System.out.println("winter completeCompaction lcc " + (cr.isMajor() ? "major" : "minor")
              + ", cost " + (System.currentTimeMillis() - completeCompactionStart) * 1.0 / 1000.0
              + " seconds");
        } else {
          System.out.println("winter completeCompaction raw " + (cr.isMajor() ? "major" : "minor")
              + ", cost " + (System.currentTimeMillis() - completeCompactionStart) * 1.0 / 1000.0
              + " seconds");
        }
        if (region.getCoprocessorHost() != null) {
          region.getCoprocessorHost().postCompact(this, sf, cr);
        }
      } else {
        // Create storefile around what we wrote with a reader on it.
        sf =
            new StoreFile(this.fs, writer.getPath(), this.conf, this.cacheConf,
                this.family.getBloomFilterType(), this.dataBlockEncoder);
        sf.createReader();
        System.out.println("winter creater reader after compact for file: " + writer.getPath());
      }
    } finally {
      synchronized (filesCompacting) {
        filesCompacting.removeAll(filesToCompact);
      }
    }

    LOG.info("Completed" + (cr.isMajor() ? " major " : " ") + "compaction of "
        + filesToCompact.size() + " file(s) in " + this + " of "
        + this.region.getRegionInfo().getRegionNameAsString() + " into "
        + (sf == null ? "none" : sf.getPath().getName()) + ", size="
        + (sf == null ? "none" : StringUtils.humanReadableInt(sf.getReader().length()))
        + "; total size for store is " + StringUtils.humanReadableInt(storeSize));
    return sf;
  }

  /**
   * Compact the most recent N files. Used in testing.
   */
  public void compactRecentForTesting(int N) throws IOException {
    List<StoreFile> filesToCompact;
    long maxId;
    boolean isMajor;

    this.lock.readLock().lock();
    try {
      synchronized (filesCompacting) {
        filesToCompact = Lists.newArrayList(storefiles);
        if (!filesCompacting.isEmpty()) {
          // exclude all files older than the newest file we're currently
          // compacting. this allows us to preserve contiguity (HBASE-2856)
          StoreFile last = filesCompacting.get(filesCompacting.size() - 1);
          int idx = filesToCompact.indexOf(last);
          Preconditions.checkArgument(idx != -1);
          filesToCompact.subList(0, idx + 1).clear();
        }
        int count = filesToCompact.size();
        if (N > count) {
          throw new RuntimeException("Not enough files");
        }

        filesToCompact = filesToCompact.subList(count - N, count);
        maxId = StoreFile.getMaxSequenceIdInList(filesToCompact, true);
        isMajor = (filesToCompact.size() == storefiles.size());
        filesCompacting.addAll(filesToCompact);
        Collections.sort(filesCompacting, StoreFile.Comparators.SEQ_ID);
      }
    } finally {
      this.lock.readLock().unlock();
    }

    try {
      // Ready to go. Have list of files to compact.
      StoreFile.Writer writer =
          this.compactor.compactForTesting(this, conf, filesToCompact, isMajor, maxId);
      // Move the compaction into place.
      StoreFile sf = completeCompaction(filesToCompact, writer, isMajor);
      if (region.getCoprocessorHost() != null) {
        region.getCoprocessorHost().postCompact(this, sf, null);
      }
    } finally {
      synchronized (filesCompacting) {
        filesCompacting.removeAll(filesToCompact);
      }
    }
  }

  boolean hasReferences() {
    return hasReferences(this.storefiles);
  }

  /*
   * @param files
   * @return True if any of the files in <code>files</code> are References.
   */
  private boolean hasReferences(Collection<StoreFile> files) {
    if (files != null && files.size() > 0) {
      for (StoreFile hsf : files) {
        if (hsf.isReference()) {
          return true;
        }
      }
    }
    return false;
  }

  /*
   * Gets lowest timestamp from candidate StoreFiles
   * @param fs
   * @param dir
   * @throws IOException
   */
  public static long getLowestTimestamp(final List<StoreFile> candidates) throws IOException {
    long minTs = Long.MAX_VALUE;
    for (StoreFile storeFile : candidates) {
      minTs = Math.min(minTs, storeFile.getModificationTimeStamp());
    }
    return minTs;
  }

  /**
   * getter for CompactionProgress object
   * @return CompactionProgress object; can be null
   */
  public CompactionProgress getCompactionProgress() {
    return this.compactor.getProgress();
  }

  /*
   * @return True if we should run a major compaction.
   */
  boolean isMajorCompaction() throws IOException {
    for (StoreFile sf : this.storefiles) {
      if (sf.getReader() == null) {
        LOG.debug("StoreFile " + sf + " has null Reader");
        return false;
      }
    }

    List<StoreFile> candidates = new ArrayList<StoreFile>(this.storefiles);

    // exclude files above the max compaction threshold
    // except: save all references. we MUST compact them
    int pos = 0;
    while (pos < candidates.size()
        && candidates.get(pos).getReader().length() > this.maxCompactSize
        && !candidates.get(pos).isReference())
      ++pos;
    candidates.subList(0, pos).clear();

    return isMajorCompaction(candidates);
  }

  /*
   * @param filesToCompact Files to compact. Can be null.
   * @return True if we should run a major compaction.
   */
  private boolean isMajorCompaction(final List<StoreFile> filesToCompact) throws IOException {
    boolean result = false;
    long mcTime = getNextMajorCompactTime();
    if (filesToCompact == null || filesToCompact.isEmpty() || mcTime == 0) {
      return result;
    }
    // TODO: Use better method for determining stamp of last major (HBASE-2990)
    long lowTimestamp = getLowestTimestamp(filesToCompact);
    long now = EnvironmentEdgeManager.currentTimeMillis();
    if (lowTimestamp > 0l && lowTimestamp < (now - mcTime)) {
      // Major compaction time has elapsed.
      if (filesToCompact.size() == 1) {
        // Single file
        StoreFile sf = filesToCompact.get(0);
        long oldest =
            (sf.getReader().timeRangeTracker == null) ? Long.MIN_VALUE : now
                - sf.getReader().timeRangeTracker.minimumTimestamp;
        if (sf.isMajorCompaction() && (this.ttl == HConstants.FOREVER || oldest < this.ttl)) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Skipping major compaction of " + this
                + " because one (major) compacted file only and oldestTime " + oldest
                + "ms is < ttl=" + this.ttl);
          }
        } else if (this.ttl != HConstants.FOREVER && oldest > this.ttl) {
          LOG.debug("Major compaction triggered on store " + this
              + ", because keyvalues outdated; time since last major compaction "
              + (now - lowTimestamp) + "ms");
          result = true;
        }
      } else {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Major compaction triggered on store " + this
              + "; time since last major compaction " + (now - lowTimestamp) + "ms");
        }
        result = true;
      }
    }
    return result;
  }

  long getNextMajorCompactTime() {
    // default = 24hrs
    long ret = conf.getLong(HConstants.MAJOR_COMPACTION_PERIOD, 1000 * 60 * 60 * 24);
    if (family.getValue(HConstants.MAJOR_COMPACTION_PERIOD) != null) {
      String strCompactionTime = family.getValue(HConstants.MAJOR_COMPACTION_PERIOD);
      ret = (new Long(strCompactionTime)).longValue();
    }

    if (ret > 0) {
      // default = 20% = +/- 4.8 hrs
      double jitterPct = conf.getFloat("hbase.hregion.majorcompaction.jitter", 0.20F);
      if (jitterPct > 0) {
        long jitter = Math.round(ret * jitterPct);
        // deterministic jitter avoids a major compaction storm on restart
        ImmutableList<StoreFile> snapshot = storefiles;
        if (snapshot != null && !snapshot.isEmpty()) {
          String seed = snapshot.get(0).getPath().getName();
          double curRand = new Random(seed.hashCode()).nextDouble();
          ret += jitter - Math.round(2L * jitter * curRand);
        } else {
          ret = 0; // no storefiles == no major compaction
        }
      }
    }
    return ret;
  }

  public CompactionRequest requestCompaction() throws IOException {
    return requestCompaction(NO_PRIORITY, null);
  }

  public CompactionRequest requestCompaction(int priority, CompactionRequest request)
      throws IOException {
    // don't even select for compaction if writes are disabled
    if (!this.region.areWritesEnabled()) {
      return null;
    }

    this.lock.readLock().lock();
    try {
      synchronized (filesCompacting) {
        // candidates = all storefiles not already in compaction queue
        List<StoreFile> candidates = Lists.newArrayList(storefiles);

        if (!filesCompacting.isEmpty()) {
          // exclude all files older than the newest file we're currently
          // compacting. this allows us to preserve contiguity (HBASE-2856)
          StoreFile last = filesCompacting.get(filesCompacting.size() - 1);
          int idx = candidates.indexOf(last);
          Preconditions.checkArgument(idx != -1);
          candidates.subList(0, idx + 1).clear();
        }

        boolean override = false;
        if (region.getCoprocessorHost() != null) {
          override = region.getCoprocessorHost().preCompactSelection(this, candidates, request);
        }
        CompactSelection filesToCompact;
        if (override) {
          // coprocessor is overriding normal file selection
          filesToCompact = new CompactSelection(conf, candidates);
        } else {
          filesToCompact = compactSelection(candidates, priority);
        }

        if (region.getCoprocessorHost() != null) {
          region.getCoprocessorHost().postCompactSelection(this,
            ImmutableList.copyOf(filesToCompact.getFilesToCompact()), request);
        }

        // no files to compact
        if (filesToCompact.getFilesToCompact().isEmpty()) {
          return null;
        }

        // basic sanity check: do not try to compact the same StoreFile twice.
        if (!Collections.disjoint(filesCompacting, filesToCompact.getFilesToCompact())) {
          // TODO: change this from an IAE to LOG.error after sufficient testing
          Preconditions
              .checkArgument(false, "%s overlaps with %s", filesToCompact, filesCompacting);
        }
        filesCompacting.addAll(filesToCompact.getFilesToCompact());
        Collections.sort(filesCompacting, StoreFile.Comparators.SEQ_ID);

        // major compaction iff all StoreFiles are included
        boolean isMajor = (filesToCompact.getFilesToCompact().size() == this.storefiles.size());
        if (isMajor) {
          // since we're enqueuing a major, update the compaction wait interval
          this.forceMajor = false;
        }

        // everything went better than expected. create a compaction request
        int pri = getCompactPriority(priority);
        // not a special compaction request, so we need to make one
        if (request == null) {
          request = new CompactionRequest(region, this, filesToCompact, isMajor, pri);
        } else {
          // update the request with what the system thinks the request should be
          // its up to the request if it wants to listen
          request.setSelection(filesToCompact);
          request.setIsMajor(isMajor);
          request.setPriority(pri);
        }
      }
    } finally {
      this.lock.readLock().unlock();
    }
    if (request != null) {
      CompactionRequest.preRequest(request);
    }
    return request;
  }

  public void finishRequest(CompactionRequest cr) {
    CompactionRequest.postRequest(cr);
    cr.finishRequest();
    synchronized (filesCompacting) {
      filesCompacting.removeAll(cr.getFiles());
    }
  }

  /**
   * Algorithm to choose which files to compact, see {@link #compactSelection(java.util.List, int)}
   * @param candidates
   * @return
   * @throws IOException
   */
  CompactSelection compactSelection(List<StoreFile> candidates) throws IOException {
    return compactSelection(candidates, NO_PRIORITY);
  }

  /**
   * Algorithm to choose which files to compact Configuration knobs: "hbase.hstore.compaction.ratio"
   * normal case: minor compact when file <= sum(smaller_files) * ratio
   * "hbase.hstore.compaction.min.size" unconditionally compact individual files below this size
   * "hbase.hstore.compaction.max.size" never compact individual files above this size (unless
   * splitting) "hbase.hstore.compaction.min" min files needed to minor compact
   * "hbase.hstore.compaction.max" max files to compact at once (avoids OOM)
   * @param candidates candidate files, ordered from oldest to newest
   * @return subset copy of candidate list that meets compaction criteria
   * @throws IOException
   */
  CompactSelection compactSelection(List<StoreFile> candidates, int priority) throws IOException {
    // ASSUMPTION!!! filesCompacting is locked when calling this function

    /*
     * normal skew: older ----> newer _ | | _ | | | | _ --|-|- |-|- |-|---_-------_-------
     * minCompactSize | | | | | | | | _ | | | | | | | | | | | | | | | | | | | | | | | | | |
     */
    CompactSelection compactSelection = new CompactSelection(conf, candidates);

    boolean forcemajor = this.forceMajor && filesCompacting.isEmpty();
    if (!forcemajor) {
      // Delete the expired store files before the compaction selection.
      if (conf.getBoolean("hbase.store.delete.expired.storefile", true) && (ttl != Long.MAX_VALUE)
          && (this.scanInfo.minVersions == 0)) {
        CompactSelection expiredSelection =
            compactSelection.selectExpiredStoreFilesToCompact(EnvironmentEdgeManager
                .currentTimeMillis() - this.ttl);

        // If there is any expired store files, delete them by compaction.
        if (expiredSelection != null) {
          return expiredSelection;
        }
      }
      // do not compact old files above a configurable threshold
      // save all references. we MUST compact them
      int pos = 0;
      while (pos < compactSelection.getFilesToCompact().size()
          && compactSelection.getFilesToCompact().get(pos).getReader().length() > maxCompactSize
          && !compactSelection.getFilesToCompact().get(pos).isReference())
        ++pos;
      if (pos != 0) compactSelection.clearSubList(0, pos);
    }

    if (compactSelection.getFilesToCompact().isEmpty()) {
      LOG.debug(this.getHRegionInfo().getEncodedName() + " - " + this
          + ": no store files to compact");
      compactSelection.emptyFileList();
      return compactSelection;
    }

    // Force a major compaction if this is a user-requested major compaction,
    // or if we do not have too many files to compact and this was requested
    // as a major compaction
    boolean majorcompaction =
        (forcemajor && priority == PRIORITY_USER)
            || (forcemajor || isMajorCompaction(compactSelection.getFilesToCompact()))
            && (compactSelection.getFilesToCompact().size() < this.maxFilesToCompact);
    LOG.debug(this.getHRegionInfo().getEncodedName() + " - " + this.getColumnFamilyName()
        + ": Initiating " + (majorcompaction ? "major" : "minor") + "compaction");

    if (!majorcompaction && !hasReferences(compactSelection.getFilesToCompact())) {

      // remove bulk import files that request to be excluded from minors
      compactSelection.getFilesToCompact().removeAll(
        Collections2.filter(compactSelection.getFilesToCompact(), new Predicate<StoreFile>() {
          public boolean apply(StoreFile input) {
            return input.excludeFromMinorCompaction();
          }
        }));

      // skip selection algorithm if we don't have enough files
      if (compactSelection.getFilesToCompact().size() < this.minFilesToCompact) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Not compacting files because we only have "
              + compactSelection.getFilesToCompact().size() + " files ready for compaction.  Need "
              + this.minFilesToCompact + " to initiate.");
        }
        compactSelection.emptyFileList();
        return compactSelection;
      }
      if (conf.getBoolean("hbase.hstore.useExploringCompation", false)) {
        compactSelection = exploringCompactionSelection(compactSelection);
      } else {
        compactSelection = defaultCompactionSelection(compactSelection);
      }
    } else {
      if (majorcompaction) {
        if (compactSelection.getFilesToCompact().size() > this.maxFilesToCompact) {
          LOG.debug("Warning, compacting more than " + this.maxFilesToCompact
              + " files, probably because of a user-requested major compaction");
          if (priority != PRIORITY_USER) {
            LOG.error("Compacting more than max files on a non user-requested compaction");
          }
        }
      } else if (compactSelection.getFilesToCompact().size() > this.maxFilesToCompact) {
        // all files included in this compaction, up to max
        int pastMax = compactSelection.getFilesToCompact().size() - this.maxFilesToCompact;
        compactSelection.getFilesToCompact().subList(0, pastMax).clear();
      }
    }
    return compactSelection;
  }

  private CompactSelection defaultCompactionSelection(CompactSelection compactSelection) {
    // we're doing a minor compaction, let's see what files are applicable
    int start = 0;

    double r = compactSelection.getCompactSelectionRatio();

    // get store file sizes for incremental compacting selection.
    int countOfFiles = compactSelection.getFilesToCompact().size();
    long[] fileSizes = new long[countOfFiles];
    long[] sumSize = new long[countOfFiles];
    for (int i = countOfFiles - 1; i >= 0; --i) {
      StoreFile file = compactSelection.getFilesToCompact().get(i);
      fileSizes[i] = file.getReader().length();
      // calculate the sum of fileSizes[i,i+maxFilesToCompact-1) for algo
      int tooFar = i + this.maxFilesToCompact - 1;
      sumSize[i] =
          fileSizes[i] + ((i + 1 < countOfFiles) ? sumSize[i + 1] : 0)
              - ((tooFar < countOfFiles) ? fileSizes[tooFar] : 0);
    }

    /*
     * Start at the oldest file and stop when you find the first file that meets compaction
     * criteria: (1) a recently-flushed, small file (i.e. <= minCompactSize) OR (2) within the
     * compactRatio of sum(newer_files) Given normal skew, any newer files will also meet this
     * criteria Additional Note: If fileSizes.size() >> maxFilesToCompact, we will recurse on
     * compact(). Consider the oldest files first to avoid a situation where we always compact
     * [end-threshold,end). Then, the last file becomes an aggregate of the previous compactions.
     */
    while (countOfFiles - start >= this.minFilesToCompact
        && fileSizes[start] > Math.max(minCompactSize, (long) (sumSize[start + 1] * r))) {
      ++start;
    }
    int end = Math.min(countOfFiles, start + this.maxFilesToCompact);
    long totalSize = fileSizes[start] + ((start + 1 < countOfFiles) ? sumSize[start + 1] : 0);
    compactSelection = compactSelection.getSubList(start, end);

    // if we don't have enough files to compact, just wait
    if (compactSelection.getFilesToCompact().size() < this.minFilesToCompact) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Skipped compaction of " + this + ".  Only " + (end - start)
            + " file(s) of size " + StringUtils.humanReadableInt(totalSize)
            + " have met compaction criteria.");
      }
      compactSelection.emptyFileList();
      return compactSelection;
    }
    return compactSelection;
  }

  private CompactSelection exploringCompactionSelection(CompactSelection compactSelection) {

    List<StoreFile> candidates = compactSelection.getFilesToCompact();
    int futureFiles = filesCompacting.isEmpty() ? 0 : 1;
    boolean mayBeStuck =
        (candidates.size() - filesCompacting.size() + futureFiles) >= blockingStoreFileCount;
    // Start off choosing nothing.
    List<StoreFile> bestSelection = new ArrayList<StoreFile>(0);
    List<StoreFile> smallest = new ArrayList<StoreFile>(0);
    long bestSize = 0;
    long smallestSize = Long.MAX_VALUE;
    double r = compactSelection.getCompactSelectionRatio();

    // Consider every starting place.
    for (int startIndex = 0; startIndex < candidates.size(); startIndex++) {
      // Consider every different sub list permutation in between start and end with min files.
      for (int currentEnd = startIndex + minFilesToCompact - 1; currentEnd < candidates.size(); currentEnd++) {
        List<StoreFile> potentialMatchFiles = candidates.subList(startIndex, currentEnd + 1);

        // Sanity checks
        if (potentialMatchFiles.size() < minFilesToCompact) {
          continue;
        }
        if (potentialMatchFiles.size() > maxFilesToCompact) {
          continue;
        }

        // Compute the total size of files that will
        // have to be read if this set of files is compacted.
        long size = getCompactionSize(potentialMatchFiles);

        // Store the smallest set of files. This stored set of files will be used
        // if it looks like the algorithm is stuck.
        if (size < smallestSize) {
          smallest = potentialMatchFiles;
          smallestSize = size;
        }

        if (size >= minCompactSize && !filesInRatio(potentialMatchFiles, r)) {
          continue;
        }

        if (size > maxCompactSize) {
          continue;
        }

        // Keep if this gets rid of more files. Or the same number of files for less io.
        if (potentialMatchFiles.size() > bestSelection.size()
            || (potentialMatchFiles.size() == bestSelection.size() && size < bestSize)) {
          bestSelection = potentialMatchFiles;
          bestSize = size;
        }
      }
    }

    if (bestSelection.size() == 0 && mayBeStuck) {
      smallest = new ArrayList<StoreFile>(smallest);
      compactSelection.getFilesToCompact().clear();
      compactSelection.getFilesToCompact().addAll(smallest);
    } else {
      bestSelection = new ArrayList<StoreFile>(bestSelection);
      compactSelection.getFilesToCompact().clear();
      compactSelection.getFilesToCompact().addAll(bestSelection);
    }

    return compactSelection;

  }

  /**
   * Check that all files satisfy the ratio
   * @param files set of files to examine.
   * @param currentRatio The raio
   * @return if all files are in ratio.
   */
  private boolean filesInRatio(final List<StoreFile> files, final double currentRatio) {
    if (files.size() < 2) {
      return true;
    }
    long totalFileSize = 0;
    for (int i = 0; i < files.size(); i++) {
      totalFileSize += files.get(i).getReader().length();
    }
    for (int i = 0; i < files.size(); i++) {
      long singleFileSize = files.get(i).getReader().length();
      long sumAllOtherFilesize = totalFileSize - singleFileSize;

      if ((singleFileSize > sumAllOtherFilesize * currentRatio)
          && (sumAllOtherFilesize >= this.minCompactSize)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Get the number of bytes a proposed compaction would have to read.
   * @param files Set of files in a proposed compaction.
   * @return size in bytes.
   */
  private long getCompactionSize(final List<StoreFile> files) {
    long size = 0;
    if (files == null) {
      return size;
    }
    for (StoreFile f : files) {
      size += f.getReader().length();
    }
    return size;
  }

  /**
   * Validates a store file by opening and closing it. In HFileV2 this should not be an expensive
   * operation.
   * @param path the path to the store file
   */
  private void validateStoreFile(Path path) throws IOException {
    StoreFile storeFile = null;
    try {
      storeFile =
          new StoreFile(this.fs, path, this.conf, this.cacheConf, this.family.getBloomFilterType(),
              NoOpDataBlockEncoder.INSTANCE);
      passSchemaMetricsTo(storeFile);
      storeFile.createReader();
    } catch (IOException e) {
      LOG.error("Failed to open store file : " + path + ", keeping it in tmp location", e);
      throw e;
    } finally {
      if (storeFile != null) {
        storeFile.closeReader(false);
      }
    }
  }

  /*
   * <p>It works by processing a compaction that's been written to disk. <p>It is usually invoked at
   * the end of a compaction, but might also be invoked at HStore startup, if the prior execution
   * died midway through. <p>Moving the compacted TreeMap into place means: <pre> 1) Moving the new
   * compacted StoreFile into place 2) Unload all replaced StoreFile, close and collect list to
   * delete. 3) Loading the new TreeMap. 4) Compute new store size </pre>
   * @param compactedFiles list of files that were compacted
   * @param compactedFile StoreFile that is the result of the compaction
   * @return StoreFile created. May be null.
   * @throws IOException
   */
  StoreFile completeCompaction(final Collection<StoreFile> compactedFiles,
      final StoreFile.Writer compactedFile, boolean isMajor) throws IOException {
    // 0. Moving the new index file into place,
    if (compactedFile != null) {
    }

    // 1. Moving the new files into place -- if there is a new file (may not
    // be if all cells were expired or deleted).
    StoreFile result = null;
    if (compactedFile != null) {
      validateStoreFile(compactedFile.getPath());

      // make sure to move index file first
      Path indexorigPath = this.getIndexFilePathFromHFilePathInTmp(compactedFile.getPath());
      Path indexdstPath = new Path(indexDir, compactedFile.getPath().getName());
      if (fs.exists(indexorigPath)) {
        LOG.info("Renaming compacted index file at " + indexorigPath + " to " + indexdstPath);
        if (!HBaseFileSystem.renameDirForFileSystem(fs, indexorigPath, indexdstPath)) {
          LOG.error("Failed move of compacted index file " + indexorigPath + " to " + indexdstPath);
          throw new IOException("Failed move of compacted index file " + indexorigPath + " to "
              + indexdstPath);
        }
      }
      // be care ful to move the files...
      // move lccindex now
      if (this.hasLCCIndex) {
        if (localfs != null) {
          mWinterLCCCompleteCompactionLocal(compactedFiles, compactedFile);
        } else if (hasLCCIndex) {
          mWinterLCCCompleteCompactionHDFS(compactedFiles, compactedFile);
        }
      }

      // Move the file into the right spot
      Path origPath = compactedFile.getPath();
      Path destPath = new Path(homedir, origPath.getName());
      LOG.info("Renaming compacted file at " + origPath + " to " + destPath);
      if (!HBaseFileSystem.renameDirForFileSystem(fs, origPath, destPath)) {
        LOG.error("Failed move of compacted file " + origPath + " to " + destPath);
        // try to move index file to tmp, failure of this operation will not affect consistency
        if (fs.exists(indexdstPath)) {
          HBaseFileSystem.renameDirForFileSystem(fs, indexdstPath, indexorigPath);
          throw new IOException("Failed move of compacted file " + origPath + " to " + destPath);
        }
        if (fs.exists(indexdstPath)) {
          HBaseFileSystem.renameDirForFileSystem(fs, indexdstPath, indexorigPath);
          throw new IOException("Failed move of compacted file " + origPath + " to " + destPath);
        }
        WinterOptimizer.ThrowWhenCalled("winter what to do on failure of movement?");
      }
      result =
          new StoreFile(this.fs, destPath, this.conf, this.cacheConf,
              this.family.getBloomFilterType(), this.dataBlockEncoder);
      passSchemaMetricsTo(result);
      result.createReader();
    }
    try {
      this.lock.writeLock().lock();
      try {
        // Change this.storefiles so it reflects new state but do not
        // delete old store files until we have sent out notification of
        // change in case old files are still being accessed by outstanding
        // scanners.
        ArrayList<StoreFile> newStoreFiles = Lists.newArrayList(storefiles);
        newStoreFiles.removeAll(compactedFiles);
        filesCompacting.removeAll(compactedFiles); // safe bc: lock.writeLock()

        // If a StoreFile result, move it into place. May be null.
        if (result != null) {
          newStoreFiles.add(result);
        }

        this.storefiles = sortAndClone(newStoreFiles);
      } finally {
        // We need the lock, as long as we are updating the storefiles
        // or changing the memstore. Let us release it before calling
        // notifyChangeReadersObservers. See HBASE-4485 for a possible
        // deadlock scenario that could have happened if continue to hold
        // the lock.
        this.lock.writeLock().unlock();
      }

      // Tell observers that list of StoreFiles has changed.
      notifyChangedReadersObservers();

      // let the archive util decide if we should archive or delete the files
      LOG.debug("Removing store files after compaction...");
      HFileArchiver.archiveStoreFiles(this.conf, this.fs, this.region, this.family.getName(),
        compactedFiles);
      // now delete local fs
      if (this.hasLCCIndex && localfs != null) {
        mWinterArchiveStoreFileLocal(compactedFiles, isMajor);
      }
    } catch (IOException e) {
      e = RemoteExceptionHandler.checkIOException(e);
      LOG.error(
        "Failed replacing compacted files in " + this + ". Compacted file is "
            + (result == null ? "none" : result.toString()) + ".  Files replaced "
            + compactedFiles.toString() + " some of which may have been already removed", e);
    }

    // 4. Compute new store size
    this.storeSize = 0L;
    this.totalUncompressedBytes = 0L;
    for (StoreFile hsf : this.storefiles) {
      StoreFile.Reader r = hsf.getReader();
      if (r == null) {
        LOG.warn("StoreFile " + hsf + " has a null Reader");
        continue;
      }
      this.storeSize += r.length();
      this.totalUncompressedBytes += r.getTotalUncompressedBytes();
    }

    indexStoreSize = 0L;
    if (hasIndex) {
      for (StoreFile isf : this.storefiles) {
        IndexReader r = isf.getIndexReader();
        if (r == null) {
          continue;
        }
        indexStoreSize += r.length();
      }
    }
    return result;
  }

  public ImmutableList<StoreFile> sortAndClone(List<StoreFile> storeFiles) {
    Collections.sort(storeFiles, StoreFile.Comparators.SEQ_ID);
    ImmutableList<StoreFile> newList = ImmutableList.copyOf(storeFiles);
    return newList;
  }

  private void mWinterLCCCompleteCompactionHDFS(final Collection<StoreFile> compactedFiles,
      final StoreFile.Writer compactedFile) throws IOException {
    // lccIndexOrigPath = /hbase/lcc/xxx/.tmp/aaa.lccindex
    Path lccIndexDirPath = mWinterGetLCCIndexFilePathFromHFilePathInTmp(compactedFile.getPath());
    FileStatus[] fileStatusArray = fs.listStatus(lccIndexDirPath);
    if (fileStatusArray == null || fileStatusArray.length == 0) {
      return;
    }
    for (FileStatus fileStatus : fileStatusArray) {
      // fileStatus = /hbase/lcc/xxx/.tmp/aaa.lccindex/qualifier
      // System.out.println("winter checking lccIndexRawPath: " + fileStatus.getPath());
      // lccIndexDir = /hbase/lcc/AAA/.lccindex + Q1-Q4 + BBB
      Path lccIndexDstPath =
          new Path(new Path(lccIndexDir, fileStatus.getPath().getName()), compactedFile.getPath()
              .getName());
      // System.out.println("winter checking lccIndexDstPath: " + lccIndexDstPath);
      if (!fs.exists(lccIndexDstPath.getParent())) {
        // System.out.println("winter lccindex dir path not exists, create first: "
        // + lccIndexDstPath.getParent());
        HBaseFileSystem.makeDirOnFileSystem(fs, lccIndexDstPath.getParent());
      }
//      System.out.println("winter renaming compacted lcc index file at " + fileStatus.getPath()
//          + " to " + lccIndexDstPath);
      LOG.info("Renaming compacted index file at " + fileStatus.getPath() + " to "
          + lccIndexDstPath);
      if (!HBaseFileSystem.renameDirForFileSystem(fs, fileStatus.getPath(), lccIndexDstPath)) {
        LOG.error("Failed move of compacted index file " + fileStatus.getPath() + " to "
            + lccIndexDstPath);
        WinterOptimizer.NeedImplementation("Failed move of compacted index file "
            + fileStatus.getPath() + " to " + lccIndexDstPath);
      }
    }
    fileStatusArray = fs.listStatus(lccIndexDirPath);
    if (fileStatusArray != null && fileStatusArray.length == 0) {
      HFileArchiver.mWinterArchiveFile(conf, fs, this.region, family.getName(), lccIndexDirPath);
    } else {
      WinterOptimizer.ThrowWhenCalled("winter completeCompaction lcc dir should be empty but not: "
          + lccIndexDirPath);
    }
  }

  // move index file and stat file
  private void mWinterLCCCompleteCompactionLocal(final Collection<StoreFile> compactedFiles,
      final StoreFile.Writer compactedFile) throws IOException {
    Path lccLocalTmpPath = getLocalBaseDirByFileName(compactedFile.getPath());
    // dir lccLocalPath has already got files from compact
    FileStatus[] fileStatusArray = localfs.listStatus(lccLocalTmpPath);
    if (fileStatusArray != null) {
      for (FileStatus fileStatus : fileStatusArray) {
        // fileStatus = /hbase/lcc/AAA.tmp/BBB.lccindex/qualifier(-stat)
        // lccLocalHome = /lcc/AAA/f/.lccindex
        if (fileStatus.getPath().getName().endsWith(LCCIndexConstant.LC_STAT_FILE_SUFFIX)) {
          // fileStatus = /lcc/AAA/.tmp/.lccindex/qualifier-stat
          // lccStatDstPath = /hbase/lcc/AAA/.lccindex/qualifier/BBB-stat
          String name = fileStatus.getPath().getName();
          Path lccStatDstPath =
              new Path(new Path(lccLocalHome, name.substring(0, name.length()
                  - LCCIndexConstant.LC_STAT_FILE_SUFFIX.length())), compactedFile.getPath()
                  .getName() + LCCIndexConstant.LC_STAT_FILE_SUFFIX);
          if (!localfs.exists(lccStatDstPath.getParent())) {
            HBaseFileSystem.makeDirOnFileSystem(localfs, lccStatDstPath.getParent());
          }
          LOG.info("Renaming compacted local index stat file at " + fileStatus.getPath() + " to "
              + lccStatDstPath);
          if (!HBaseFileSystem
              .renameDirForFileSystem(localfs, fileStatus.getPath(), lccStatDstPath)) {
            LOG.error("Failed move of compacted local index stat file " + fileStatus.getPath()
                + " to " + lccStatDstPath);
            WinterOptimizer.NeedImplementation("Failed move of compacted index stat file "
                + fileStatus.getPath() + " to " + lccStatDstPath);
          }
        } else {
          // fileStatus = /lcc/AAA/.tmp/.lccindex/qualifier
          // lccIndexDir = /hbase/lcc/AAA/f/.lccindex/qualifier/BBB
          Path lccIndexDstPath =
              new Path(new Path(lccLocalHome, fileStatus.getPath().getName()), compactedFile
                  .getPath().getName());
          // System.out.println("winter checking lccIndexDstPath: " + lccIndexDstPath);
          if (!localfs.exists(lccIndexDstPath.getParent())) {
            HBaseFileSystem.makeDirOnFileSystem(localfs, lccIndexDstPath.getParent());
          }
          System.out.println("winter renaming compacted local index file at " + fileStatus.getPath()
              + " to " + lccIndexDstPath);
          LOG.info("Renaming compacted local index file at " + fileStatus.getPath() + " to "
              + lccIndexDstPath);
          if (!HBaseFileSystem.renameDirForFileSystem(localfs, fileStatus.getPath(),
            lccIndexDstPath)) {
            LOG.error("Failed move of compacted local index file " + fileStatus.getPath() + " to "
                + lccIndexDstPath);
            WinterOptimizer.NeedImplementation("Failed move of compacted index file "
                + fileStatus.getPath() + " to " + lccIndexDstPath);
          }
        }
      }
      // delete if necessary
      fileStatusArray = localfs.listStatus(lccLocalTmpPath);
      if (fileStatusArray != null && fileStatusArray.length == 0) {
        localfs.delete(lccLocalTmpPath, true);
      } else if (fileStatusArray != null) {
        WinterOptimizer
            .ThrowWhenCalled("winter completeCompaction lcc dir should be empty but not: "
                + lccLocalTmpPath + ", now size: " + fileStatusArray.length);
      }
    }
  }

  // delete local file?
  private void mWinterArchiveStoreFileLocal(final Collection<StoreFile> compactedFiles,
      boolean isMajor) throws IOException {
    // delete local lccindex file
    // hfilePath = /hbase/lcc/AAA/.tmp/BBB/
    // lccLocalHome = /lcc/AAA/f/.lccindex
    FileStatus[] fileStatusList = localfs.listStatus(lccLocalHome);
    if (fileStatusList == null || fileStatusList.length == 0) {
      WinterOptimizer.NeedImplementation("winter in completeCompaction has no index file under: "
          + lccLocalHome);
      throw new IOException("winter completeCompaction has no index file under: " + lccLocalHome);
    }
    for (FileStatus fileStatus : fileStatusList) {
      for (StoreFile lccToBeCompacted : compactedFiles) {
        // lccToBeCompacted = /hbase/lcc/AAA/f/B1-B3, AAA is always the same
        // existingLCCIndexPath = /hbase/lcc/AAA/f/.lccindex/Q1/Bi. In this loop, Bi is changing
        Path existingLCCIndexPath =
            new Path(fileStatus.getPath(), lccToBeCompacted.getPath().getName());
        Path existingLCCStatPath =
            new Path(fileStatus.getPath(), lccToBeCompacted.getPath().getName()
                + LCCIndexConstant.LC_STAT_FILE_SUFFIX);
        if (localfs.exists(existingLCCIndexPath)) {
          // System.out.println("winter now to delete " + existingLCCIndexPath);
          localfs.delete(existingLCCIndexPath, true);
          localfs.delete(existingLCCStatPath, true);
        } else {
          if (lccToBeCompacted.isReference() && isMajor) {
            // is is major and is reference, the file can be ignored then
          } else {
            Compactor.deleteRemoteLCCLocalFile(this, existingLCCIndexPath);
            Compactor.deleteRemoteLCCLocalFile(this, existingLCCStatPath);
          }
        }
        System.out.println("winter archive in complete compact " + existingLCCIndexPath);
      }
    }
  }

  // ////////////////////////////////////////////////////////////////////////////
  // Accessors.
  // (This is the only section that is directly useful!)
  // ////////////////////////////////////////////////////////////////////////////
  /**
   * @return the number of files in this store
   */
  public int getNumberOfStoreFiles() {
    return this.storefiles.size();
  }

  /*
   * @param wantedVersions How many versions were asked for.
   * @return wantedVersions or this families' {@link HConstants#VERSIONS}.
   */
  int versionsToReturn(final int wantedVersions) {
    if (wantedVersions <= 0) {
      throw new IllegalArgumentException("Number of versions must be > 0");
    }
    // Make sure we do not return more than maximum versions for this store.
    int maxVersions = this.family.getMaxVersions();
    return wantedVersions > maxVersions ? maxVersions : wantedVersions;
  }

  static boolean isExpired(final KeyValue key, final long oldestTimestamp) {
    return key.getTimestamp() < oldestTimestamp;
  }

  /**
   * Find the key that matches <i>row</i> exactly, or the one that immediately precedes it. WARNING:
   * Only use this method on a table where writes occur with strictly increasing timestamps. This
   * method assumes this pattern of writes in order to make it reasonably performant. Also our
   * search is dependent on the axiom that deletes are for cells that are in the container that
   * follows whether a memstore snapshot or a storefile, not for the current container: i.e. we'll
   * see deletes before we come across cells we are to delete. Presumption is that the
   * memstore#kvset is processed before memstore#snapshot and so on.
   * @param row The row key of the targeted row.
   * @return Found keyvalue or null if none found.
   * @throws IOException
   */
  KeyValue getRowKeyAtOrBefore(final byte[] row) throws IOException {
    // If minVersions is set, we will not ignore expired KVs.
    // As we're only looking for the latest matches, that should be OK.
    // With minVersions > 0 we guarantee that any KV that has any version
    // at all (expired or not) has at least one version that will not expire.
    // Note that this method used to take a KeyValue as arguments. KeyValue
    // can be back-dated, a row key cannot.
    long ttlToUse = scanInfo.getMinVersions() > 0 ? Long.MAX_VALUE : this.ttl;

    KeyValue kv = new KeyValue(row, HConstants.LATEST_TIMESTAMP);

    GetClosestRowBeforeTracker state =
        new GetClosestRowBeforeTracker(this.comparator, kv, ttlToUse, this.region.getRegionInfo()
            .isMetaRegion());
    this.lock.readLock().lock();
    try {
      // First go to the memstore. Pick up deletes and candidates.
      this.memstore.getRowKeyAtOrBefore(state);
      // Check if match, if we got a candidate on the asked for 'kv' row.
      // Process each store file. Run through from newest to oldest.
      for (StoreFile sf : Lists.reverse(storefiles)) {
        // Update the candidate keys from the current map file
        rowAtOrBeforeFromStoreFile(sf, state);
      }
      return state.getCandidate();
    } finally {
      this.lock.readLock().unlock();
    }
  }

  /*
   * Check an individual MapFile for the row at or before a given row.
   * @param f
   * @param state
   * @throws IOException
   */
  private void
      rowAtOrBeforeFromStoreFile(final StoreFile f, final GetClosestRowBeforeTracker state)
          throws IOException {
    StoreFile.Reader r = f.getReader();
    if (r == null) {
      LOG.warn("StoreFile " + f + " has a null Reader");
      return;
    }
    if (r.getEntries() == 0) {
      LOG.warn("StoreFile " + f + " is a empty store file");
      return;
    }
    // TODO: Cache these keys rather than make each time?
    byte[] fk = r.getFirstKey();
    if (fk == null) return;
    KeyValue firstKV = KeyValue.createKeyValueFromKey(fk, 0, fk.length);
    byte[] lk = r.getLastKey();
    KeyValue lastKV = KeyValue.createKeyValueFromKey(lk, 0, lk.length);
    KeyValue firstOnRow = state.getTargetKey();
    if (this.comparator.compareRows(lastKV, firstOnRow) < 0) {
      // If last key in file is not of the target table, no candidates in this
      // file. Return.
      if (!state.isTargetTable(lastKV)) return;
      // If the row we're looking for is past the end of file, set search key to
      // last key. TODO: Cache last and first key rather than make each time.
      firstOnRow = new KeyValue(lastKV.getRow(), HConstants.LATEST_TIMESTAMP);
    }
    // Get a scanner that caches blocks and that uses pread.
    HFileScanner scanner = r.getScanner(true, true, false);
    // Seek scanner. If can't seek it, return.
    if (!seekToScanner(scanner, firstOnRow, firstKV)) return;
    // If we found candidate on firstOnRow, just return. THIS WILL NEVER HAPPEN!
    // Unlikely that there'll be an instance of actual first row in table.
    if (walkForwardInSingleRow(scanner, firstOnRow, state)) return;
    // If here, need to start backing up.
    while (scanner.seekBefore(firstOnRow.getBuffer(), firstOnRow.getKeyOffset(),
      firstOnRow.getKeyLength())) {
      KeyValue kv = scanner.getKeyValue();
      if (!state.isTargetTable(kv)) break;
      if (!state.isBetterCandidate(kv)) break;
      // Make new first on row.
      firstOnRow = new KeyValue(kv.getRow(), HConstants.LATEST_TIMESTAMP);
      // Seek scanner. If can't seek it, break.
      if (!seekToScanner(scanner, firstOnRow, firstKV)) break;
      // If we find something, break;
      if (walkForwardInSingleRow(scanner, firstOnRow, state)) break;
    }
  }

  /*
   * Seek the file scanner to firstOnRow or first entry in file.
   * @param scanner
   * @param firstOnRow
   * @param firstKV
   * @return True if we successfully seeked scanner.
   * @throws IOException
   */
  private boolean seekToScanner(final HFileScanner scanner, final KeyValue firstOnRow,
      final KeyValue firstKV) throws IOException {
    KeyValue kv = firstOnRow;
    // If firstOnRow < firstKV, set to firstKV
    if (this.comparator.compareRows(firstKV, firstOnRow) == 0) kv = firstKV;
    int result = scanner.seekTo(kv.getBuffer(), kv.getKeyOffset(), kv.getKeyLength());
    return result >= 0;
  }

  /*
   * When we come in here, we are probably at the kv just before we break into the row that
   * firstOnRow is on. Usually need to increment one time to get on to the row we are interested in.
   * @param scanner
   * @param firstOnRow
   * @param state
   * @return True we found a candidate.
   * @throws IOException
   */
  private boolean walkForwardInSingleRow(final HFileScanner scanner, final KeyValue firstOnRow,
      final GetClosestRowBeforeTracker state) throws IOException {
    boolean foundCandidate = false;
    do {
      KeyValue kv = scanner.getKeyValue();
      // If we are not in the row, skip.
      if (this.comparator.compareRows(kv, firstOnRow) < 0) continue;
      // Did we go beyond the target row? If so break.
      if (state.isTooFar(kv, firstOnRow)) break;
      if (state.isExpired(kv)) {
        continue;
      }
      // If we added something, this row is a contender. break.
      if (state.handle(kv)) {
        foundCandidate = true;
        break;
      }
    } while (scanner.next());
    return foundCandidate;
  }

  public boolean canSplit() {
    this.lock.readLock().lock();
    try {
      // Not splitable if we find a reference store file present in the store.
      for (StoreFile sf : storefiles) {
        if (sf.isReference()) {
          if (LOG.isDebugEnabled()) {
            LOG.debug(sf + " is not splittable");
          }
          return false;
        }
      }

      return true;
    } finally {
      this.lock.readLock().unlock();
    }
  }

  /**
   * Determines if Store should be split
   * @return byte[] if store should be split, null otherwise.
   */
  public byte[] getSplitPoint() {
    this.lock.readLock().lock();
    try {
      // sanity checks
      if (this.storefiles.isEmpty()) {
        return null;
      }
      // Should already be enforced by the split policy!
      assert !this.region.getRegionInfo().isMetaRegion();

      // Not splitable if we find a reference store file present in the store.
      long maxSize = 0L;
      StoreFile largestSf = null;
      for (StoreFile sf : storefiles) {
        if (sf.isReference()) {
          // Should already be enforced since we return false in this case
          return null;
        }

        StoreFile.Reader r = sf.getReader();
        if (r == null) {
          LOG.warn("Storefile " + sf + " Reader is null");
          continue;
        }

        long size = r.length();
        if (size > maxSize) {
          // This is the largest one so far
          maxSize = size;
          largestSf = sf;
        }
      }

      StoreFile.Reader r = largestSf.getReader();
      if (r == null) {
        LOG.warn("Storefile " + largestSf + " Reader is null");
        return null;
      }
      // Get first, last, and mid keys. Midkey is the key that starts block
      // in middle of hfile. Has column and timestamp. Need to return just
      // the row we want to split on as midkey.
      byte[] midkey = r.midkey();
      if (midkey != null) {
        KeyValue mk = KeyValue.createKeyValueFromKey(midkey, 0, midkey.length);
        byte[] fk = r.getFirstKey();
        KeyValue firstKey = KeyValue.createKeyValueFromKey(fk, 0, fk.length);
        byte[] lk = r.getLastKey();
        KeyValue lastKey = KeyValue.createKeyValueFromKey(lk, 0, lk.length);
        // if the midkey is the same as the first or last keys, then we cannot
        // (ever) split this region.
        if (this.comparator.compareRows(mk, firstKey) == 0
            || this.comparator.compareRows(mk, lastKey) == 0) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("cannot split because midkey is the same as first or " + "last row");
          }
          return null;
        }
        return mk.getRow();
      }
    } catch (IOException e) {
      LOG.warn("Failed getting store size for " + this, e);
    } finally {
      this.lock.readLock().unlock();
    }
    return null;
  }

  /** @return aggregate size of all HStores used in the last compaction */
  public long getLastCompactSize() {
    return this.lastCompactSize;
  }

  /** @return aggregate size of HStore */
  public long getSize() {
    return storeSize;
  }

  public void triggerMajorCompaction() {
    this.forceMajor = true;
  }

  boolean getForceMajorCompaction() {
    return this.forceMajor;
  }

  // ////////////////////////////////////////////////////////////////////////////
  // File administration
  // ////////////////////////////////////////////////////////////////////////////

  /**
   * Return a scanner for both the memstore and the HStore files. Assumes we are not in a
   * compaction.
   * @throws IOException
   */
  public KeyValueScanner getScanner(Scan scan, final NavigableSet<byte[]> targetCols)
      throws IOException {
    lock.readLock().lock();
    try {
      KeyValueScanner scanner = null;
      if (getHRegion().getCoprocessorHost() != null) {
        scanner = getHRegion().getCoprocessorHost().preStoreScannerOpen(this, scan, targetCols);
      }
      if (scanner == null) {
        scanner = new StoreScanner(this, getScanInfo(), scan, targetCols);
      }
      return scanner;
    } finally {
      lock.readLock().unlock();
    }
  }

  public KeyValueScanner mWinterGetLCCIndexScanner(Scan scan,
      final NavigableSet<byte[]> targetCols, final byte[] targetLCCIndexCF) throws IOException {
    lock.readLock().lock();
    try {
      KeyValueScanner scanner = null;
      if (getHRegion().getCoprocessorHost() != null) {
        scanner = getHRegion().getCoprocessorHost().preStoreScannerOpen(this, scan, targetCols);
      }
      if (scanner == null) {
        scanner =
            new StoreScanner(this, getScanInfo(), scan, targetCols, lccIndexQualifierType,
                targetLCCIndexCF);
      }
      return scanner;
    } finally {
      lock.readLock().unlock();
    }
  }

  public StoreIndexScanner getStoreIndexScanner(Range r, Scan s, Set<ByteArray> joinSet,
      boolean isAND) throws IOException {
    lock.readLock().lock();
    try {
      StoreIndexScanner sis =
          new StoreIndexScanner(this, this.memstore.getScanners(), this.comparator,
              this.indexComparator, r, s, joinSet, isAND);
      this.addChangedReaderObserver(sis);
      return sis;
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public String toString() {
    return getColumnFamilyName();
  }

  /**
   * @return Count of store files
   */
  int getStorefilesCount() {
    return this.storefiles.size();
  }

  /**
   * @return The size of the store files, in bytes, uncompressed.
   */
  long getStoreSizeUncompressed() {
    return this.totalUncompressedBytes;
  }

  /**
   * @return The size of the store files, in bytes.
   */
  long getStorefilesSize() {
    long size = 0;
    for (StoreFile s : storefiles) {
      StoreFile.Reader r = s.getReader();
      if (r == null) {
        LOG.warn("StoreFile " + s + " has a null Reader");
        continue;
      }
      size += r.length();
    }
    return size;
  }

  /**
   * @return The size of the store file indexes, in bytes.
   */
  long getStorefilesIndexSize() {
    long size = 0;
    for (StoreFile s : storefiles) {
      StoreFile.Reader r = s.getReader();
      if (r == null) {
        LOG.warn("StoreFile " + s + " has a null Reader");
        continue;
      }
      size += r.indexSize();
    }
    return size;
  }

  /**
   * Returns the total size of all index blocks in the data block indexes, including the root level,
   * intermediate levels, and the leaf level for multi-level indexes, or just the root level for
   * single-level indexes.
   * @return the total size of block indexes in the store
   */
  long getTotalStaticIndexSize() {
    long size = 0;
    for (StoreFile s : storefiles) {
      size += s.getReader().getUncompressedDataIndexSize();
    }
    return size;
  }

  /**
   * Returns the total byte size of all Bloom filter bit arrays. For compound Bloom filters even the
   * Bloom blocks currently not loaded into the block cache are counted.
   * @return the total size of all Bloom filters in the store
   */
  long getTotalStaticBloomSize() {
    long size = 0;
    for (StoreFile s : storefiles) {
      StoreFile.Reader r = s.getReader();
      size += r.getTotalBloomSize();
    }
    return size;
  }

  /**
   * @return The size of this store's memstore, in bytes
   */
  long getMemStoreSize() {
    return this.memstore.heapSize();
  }

  public int getCompactPriority() {
    return getCompactPriority(NO_PRIORITY);
  }

  /**
   * @return The priority that this store should have in the compaction queue
   * @param priority
   */
  public int getCompactPriority(int priority) {
    // If this is a user-requested compaction, leave this at the highest priority
    if (priority == PRIORITY_USER) {
      return PRIORITY_USER;
    } else {
      return this.blockingStoreFileCount - this.storefiles.size();
    }
  }

  boolean throttleCompaction(long compactionSize) {
    long throttlePoint =
        conf.getLong("hbase.regionserver.thread.compaction.throttle", 2 * this.minFilesToCompact
            * this.region.memstoreFlushSize);
    return compactionSize > throttlePoint;
  }

  public HRegion getHRegion() {
    return this.region;
  }

  HRegionInfo getHRegionInfo() {
    return this.region.getRegionInfo();
  }

  /**
   * Increments the value for the given row/family/qualifier. This function will always be seen as
   * atomic by other readers because it only puts a single KV to memstore. Thus no read/write
   * control necessary.
   * @param row
   * @param f
   * @param qualifier
   * @param newValue the new value to set into memstore
   * @return memstore size delta
   * @throws IOException
   */
  public long updateColumnValue(byte[] row, byte[] f, byte[] qualifier, long newValue)
      throws IOException {

    this.lock.readLock().lock();
    try {
      long now = EnvironmentEdgeManager.currentTimeMillis();

      return this.memstore.updateColumnValue(row, f, qualifier, newValue, now);

    } finally {
      this.lock.readLock().unlock();
    }
  }

  /**
   * Adds or replaces the specified KeyValues.
   * <p>
   * For each KeyValue specified, if a cell with the same row, family, and qualifier exists in
   * MemStore, it will be replaced. Otherwise, it will just be inserted to MemStore.
   * <p>
   * This operation is atomic on each KeyValue (row/family/qualifier) but not necessarily atomic
   * across all of them.
   * @param kvs
   * @return memstore size delta
   * @throws IOException
   */
  public long upsert(List<KeyValue> kvs) throws IOException {
    this.lock.readLock().lock();
    try {
      // TODO: Make this operation atomic w/ MVCC
      return this.memstore.upsert(kvs);
    } finally {
      this.lock.readLock().unlock();
    }
  }

  public StoreFlusher getStoreFlusher(long cacheFlushId) {
    return new StoreFlusherImpl(cacheFlushId);
  }

  private class StoreFlusherImpl implements StoreFlusher {

    private long cacheFlushId;
    private SortedSet<KeyValue> snapshot;
    private StoreFile storeFile;
    private Path storeFilePath;
    private TimeRangeTracker snapshotTimeRangeTracker;
    private AtomicLong flushedSize;

    private StoreFlusherImpl(long cacheFlushId) {
      this.cacheFlushId = cacheFlushId;
      this.flushedSize = new AtomicLong();
    }

    @Override
    public void prepare() {
      memstore.snapshot();
      this.snapshot = memstore.getSnapshot();
      this.snapshotTimeRangeTracker = memstore.getSnapshotTimeRangeTracker();
    }

    @Override
    public void flushCache(MonitoredTask status) throws IOException {
      storeFilePath =
          Store.this.flushCache(cacheFlushId, snapshot, snapshotTimeRangeTracker, flushedSize,
            status);
    }

    @Override
    public boolean commit(MonitoredTask status) throws IOException {
      if (storeFilePath == null) {
        return false;
      }
      storeFile =
          Store.this.commitFile(storeFilePath, cacheFlushId, snapshotTimeRangeTracker, flushedSize,
            status);
      if (Store.this.getHRegion().getCoprocessorHost() != null) {
        Store.this.getHRegion().getCoprocessorHost().postFlush(Store.this, storeFile);
      }

      // Add new file to store files. Clear snapshot too while we have
      // the Store write lock.
      return Store.this.updateStorefiles(storeFile, snapshot);
    }
  }

  /**
   * See if there's too much store files in this store
   * @return true if number of store files is greater than the number defined in minFilesToCompact
   */
  public boolean needsCompaction() {
    return (storefiles.size() - filesCompacting.size()) > minFilesToCompact;
  }

  /**
   * Used for tests. Get the cache configuration for this Store.
   */
  public CacheConfig getCacheConfig() {
    return this.cacheConf;
  }

  public static final long FIXED_OVERHEAD = ClassSize
      .align(SchemaConfigured.SCHEMA_CONFIGURED_UNALIGNED_HEAP_SIZE + +(17 * ClassSize.REFERENCE)
          + (7 * Bytes.SIZEOF_LONG) + (5 * Bytes.SIZEOF_INT) + Bytes.SIZEOF_BOOLEAN);

  public static final long DEEP_OVERHEAD = ClassSize.align(FIXED_OVERHEAD + ClassSize.OBJECT
      + ClassSize.REENTRANT_LOCK + ClassSize.CONCURRENT_SKIPLISTMAP
      + ClassSize.CONCURRENT_SKIPLISTMAP_ENTRY + ClassSize.OBJECT + ScanInfo.FIXED_OVERHEAD);

  @Override
  public long heapSize() {
    return DEEP_OVERHEAD + this.memstore.heapSize();
  }

  public KeyValue.KVComparator getComparator() {
    return comparator;
  }

  public ScanInfo getScanInfo() {
    return scanInfo;
  }

  public boolean hasTooManyStoreFiles() {
    return getStorefilesCount() > this.blockingFileCount;
  }

  /**
   * Immutable information for scans over a store.
   */
  public static class ScanInfo {
    private byte[] family;
    private int minVersions;
    private int maxVersions;
    private long ttl;
    private boolean keepDeletedCells;
    private long timeToPurgeDeletes;
    private KVComparator comparator;

    public static final long FIXED_OVERHEAD = ClassSize.align(ClassSize.OBJECT
        + (2 * ClassSize.REFERENCE) + (2 * Bytes.SIZEOF_INT) + Bytes.SIZEOF_LONG
        + Bytes.SIZEOF_BOOLEAN);

    /**
     * @param family {@link HColumnDescriptor} describing the column family
     * @param ttl Store's TTL (in ms)
     * @param timeToPurgeDeletes duration in ms after which a delete marker can be purged during a
     *          major compaction.
     * @param comparator The store's comparator
     */
    public ScanInfo(HColumnDescriptor family, long ttl, long timeToPurgeDeletes,
        KVComparator comparator) {
      this(family.getName(), family.getMinVersions(), family.getMaxVersions(), ttl, family
          .getKeepDeletedCells(), timeToPurgeDeletes, comparator);
    }

    /**
     * @param family Name of this store's column family
     * @param minVersions Store's MIN_VERSIONS setting
     * @param maxVersions Store's VERSIONS setting
     * @param ttl Store's TTL (in ms)
     * @param timeToPurgeDeletes duration in ms after which a delete marker can be purged during a
     *          major compaction.
     * @param keepDeletedCells Store's keepDeletedCells setting
     * @param comparator The store's comparator
     */
    public ScanInfo(byte[] family, int minVersions, int maxVersions, long ttl,
        boolean keepDeletedCells, long timeToPurgeDeletes, KVComparator comparator) {
      this.family = family;
      this.minVersions = minVersions;
      this.maxVersions = maxVersions;
      this.ttl = ttl;
      this.keepDeletedCells = keepDeletedCells;
      this.timeToPurgeDeletes = timeToPurgeDeletes;
      this.comparator = comparator;
    }

    public byte[] getFamily() {
      return family;
    }

    public int getMinVersions() {
      return minVersions;
    }

    public int getMaxVersions() {
      return maxVersions;
    }

    public long getTtl() {
      return ttl;
    }

    public boolean getKeepDeletedCells() {
      return keepDeletedCells;
    }

    public long getTimeToPurgeDeletes() {
      return timeToPurgeDeletes;
    }

    public KVComparator getComparator() {
      return comparator;
    }
  }

  public Range mWinterSelectTheBestRange(List<Range> ranges) throws IOException {
    List<StoreFile> storeFiles;
    this.lock.readLock().lock();
    try {
      storeFiles = this.getStorefiles();
    } finally {
      this.lock.readLock().unlock();
    }
    if (storeFiles.size() == 0) {
      return ranges.get(0);
    }
    TreeMap<byte[], Long> qualifierCounts = new TreeMap<byte[], Long>(Bytes.BYTES_COMPARATOR);
    for (StoreFile sf : storeFiles) {
      // sf.path => lcc/AAA/f/BBB
      TreeMap<byte[], Long> newCounters = sf.mWinterCalculateAllRanges(this, ranges);
      for (Entry<byte[], Long> entry : newCounters.entrySet()) {
        if (qualifierCounts.containsKey(entry.getKey()) && entry.getValue() != Long.MAX_VALUE) {
          qualifierCounts.put(entry.getKey(),
            qualifierCounts.get(entry.getKey()) + entry.getValue());
        } else {
          qualifierCounts.put(entry.getKey(), entry.getValue());
        }
      }
    }
    byte[] minKey = null;
    long minCount = Long.MAX_VALUE;
    for (Entry<byte[], Long> entry : qualifierCounts.entrySet()) {
      // System.out.println("winter final result to " + Bytes.toString(entry.getKey())
      // + ",with counter: " + entry.getValue());
      if (minCount > entry.getValue()) {
        minKey = entry.getKey();
        minCount = entry.getValue();
      }
    }
    for (Range r : ranges) {
      if (Bytes.equals(r.getQualifier(), minKey)) {
        return r;
      }
    }
    System.out.println("winter final set min to " + Bytes.toString(minKey) + ", now min counter: "
        + minCount);
    return ranges.get(0);
  }
}