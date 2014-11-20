/*
 * Copyright 2013 NGDATA nv
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
package com.ngdata.hbaseindexer.mr;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.UUID;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapred.JobClient;
import org.apache.solr.hadoop.ForkedMapReduceIndexerTool.OptionsBridge;
import org.apache.solr.hadoop.ForkedZooKeeperInspector;
import org.apache.solr.hadoop.MapReduceIndexerTool;
import org.apache.solr.hadoop.MorphlineClasspathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.ngdata.hbaseindexer.ConfKeys;
import com.ngdata.hbaseindexer.conf.IndexerConf;
import com.ngdata.hbaseindexer.conf.IndexerConf.MappingType;
import com.ngdata.hbaseindexer.conf.XmlIndexerConfReader;
import com.ngdata.hbaseindexer.indexer.ResultToSolrMapperFactory;
import com.ngdata.hbaseindexer.model.api.IndexerDefinition;
import com.ngdata.hbaseindexer.model.api.IndexerNotFoundException;
import com.ngdata.hbaseindexer.model.impl.IndexerModelImpl;
import com.ngdata.hbaseindexer.morphline.MorphlineResultToSolrMapper;
import com.ngdata.hbaseindexer.parse.ResultToSolrMapper;
import com.ngdata.hbaseindexer.util.zookeeper.StateWatchingZooKeeper;
import com.ngdata.sep.impl.HBaseShims;
import com.ngdata.sep.util.io.Closer;

/**
 * Container for commandline options passed in for HBase Indexer, as well as a bridge to existing MapReduce index
 * building functionality.
 */
class HBaseIndexingOptions extends OptionsBridge {

    private static final Logger LOG = LoggerFactory.getLogger(MapReduceIndexerTool.class);

    static final String DEFAULT_INDEXER_NAME = "_default_";

    private Configuration conf;
    private Scan hbaseScan;
    private IndexingSpecification hbaseIndexingSpecification;
    // Flag that we have created our own output directory
    private boolean generatedOutputDir = false;

    public String hbaseIndexerZkHost;
    public String hbaseIndexerName = DEFAULT_INDEXER_NAME;
    public File hbaseIndexerConfigFile;
    public String hbaseTableName;
    public Integer documentType;
    public String hbaseStartRow;
    public String hbaseEndRow;
    public String hbaseStartTimeString;
    public String hbaseEndTimeString;
    public String hbaseTimestampFormat;
    public boolean overwriteOutputDir;

    public HBaseIndexingOptions(Configuration conf) {
        Preconditions.checkNotNull(conf);
        this.conf = conf;
    }

    /**
     * Determine if this is intended to be a map-only job that writes directly to a live Solr server.
     *
     * @return true if writes are to be done directly into Solr
     */
    public boolean isDirectWrite() {
        return reducers == 0;
    }

    public Scan getScan() {
        if (hbaseScan == null) {
            throw new IllegalStateException("Scan has not yet been evaluated");
        }
        return hbaseScan;
    }

    public IndexingSpecification getIndexingSpecification() {
        if (hbaseIndexingSpecification == null) {
            throw new IllegalStateException("Indexing specification has not yet been evaluated");
        }
        return hbaseIndexingSpecification;
    }

    /**
     * Evaluate the current state to calculate derived options settings. Validation of the state is also done here, so
     * IllegalStateException will be thrown if incompatible options have been set.
     *
     * @throws IllegalStateException if an illegal combination of options has been set, or needed options are missing
     */
    public void evaluate() {
        evaluateIndexingSpecification();
        evaluateOutputDir();
        evaluateGoLiveArgs();
        evaluateShards();
        evaluateNumReducers();
        evaluateScan();
    }

    /**
     * Check the existence of an output directory setting if needed, or setting one if applicable.
     */
    @VisibleForTesting
    void evaluateOutputDir() {

        if (isDirectWrite() || isDryRun) {
            if (outputDir != null) {
                throw new IllegalStateException(
                    "--output-dir must not be specified if --reducers is 0 or --dry-run is enabled");
            }
            if (zkHost == null && (hbaseIndexerName == null || hbaseIndexerZkHost == null)) {
                throw new IllegalStateException(
                    "--zk-host must be specified if --reducers is 0 or --dry-run is enabled");
            }
            return;
        }

        if (goLive) {
            if (outputDir == null) {
                outputDir = new Path(conf.get("hbase.search.mr.tmpdir", "/tmp"), "search-"
                        + UUID.randomUUID().toString());
                generatedOutputDir = true;
            }
        } else {
            if (outputDir == null) {
                throw new IllegalStateException("Must supply --output-dir unless --go-live is enabled");
            }
        }
    }

    /**
     * Check if the output directory being used is an auto-generated temporary directory.
     */
    public boolean isGeneratedOutputDir() {
        return generatedOutputDir;
    }

    @VisibleForTesting
    void evaluateScan() {
        this.hbaseScan = new Scan();
        hbaseScan.setCacheBlocks(false);
        hbaseScan.setCaching(conf.getInt("hbase.client.scanner.caching", 200));

        if (documentType != null) {
            byte[] family = Bytes.toBytes("C");
            byte[] qualifier = Bytes.toBytes("C:B");
            byte[] value = null;
            if (documentType == 5) {
                value = new byte[] {0x02, 0x0A};
            } else if (documentType == 143) {
                value = new byte[] {0x02, (byte) 0x9E, 0x02};
            } else {
                value = Bytes.toBytes(documentType);
            }
            Filter filter = new SingleColumnValueFilter(family, qualifier, CompareOp.EQUAL, value);
            hbaseScan.setFilter(filter);
        }
        
        if (hbaseStartRow != null) {
            hbaseScan.setStartRow(Bytes.toBytesBinary(hbaseStartRow));
            LOG.debug("Starting row scan at " + hbaseStartRow);
        }

        if (hbaseEndRow != null) {
            hbaseScan.setStopRow(Bytes.toBytesBinary(hbaseEndRow));
            LOG.debug("Stopping row scan at " + hbaseEndRow);
        }
        
        Long startTime = evaluateTimestamp(hbaseStartTimeString, hbaseTimestampFormat);
        Long endTime = evaluateTimestamp(hbaseEndTimeString, hbaseTimestampFormat);

        if (startTime != null || endTime != null) {
            long scanStartTime = 0L;
            long scanEndTime = Long.MAX_VALUE;
            if (startTime != null) {
                scanStartTime = startTime;
                LOG.debug("Setting scan start of time range to " + startTime);
            }
            if (endTime != null) {
                scanEndTime = endTime;
                LOG.debug("Setting scan end of time range to " + endTime);
            }
            try {
                hbaseScan.setTimeRange(scanStartTime, scanEndTime);
            } catch (IOException e) {
                // In reality an IOE will never be thrown here
                throw new RuntimeException(e);
            }
        }

        // Only scan the column families and/or cells that the indexer requires
        // if we're running in row-indexing mode
        IndexerConf indexerConf = loadIndexerConf(new ByteArrayInputStream(hbaseIndexingSpecification.getIndexConfigXml().getBytes()));
        if (indexerConf.getMappingType() == MappingType.ROW) {
            MorphlineClasspathUtil.setupJavaCompilerClasspath();
            
            ResultToSolrMapper resultToSolrMapper = ResultToSolrMapperFactory.createResultToSolrMapper(
                        hbaseIndexingSpecification.getIndexerName(),
                        indexerConf,
                        hbaseIndexingSpecification.getIndexConnectionParams());
            Get get = resultToSolrMapper.getGet(HBaseShims.newGet().getRow());
            hbaseScan.setFamilyMap(get.getFamilyMap());
        }

    }

    // Taken from org.apache.solr.hadoop.MapReduceIndexerTool
    @VisibleForTesting
    void evaluateGoLiveArgs() {
        if (zkHost == null && solrHomeDir == null) {
            throw new IllegalStateException("At least one of --zk-host or --solr-home-dir is required");
        }
        if (goLive && zkHost == null && shardUrls == null) {
            throw new IllegalStateException("--go-live requires that you also pass --shard-url or --zk-host");
        }

        if (zkHost != null && collection == null) {
            throw new IllegalStateException("--zk-host requires that you also pass --collection");
        }

        if (zkHost != null) {
            return;
            // verify structure of ZK directory later, to avoid checking run-time errors during parsing.
        } else if (shardUrls != null) {
            if (shardUrls.size() == 0) {
                throw new IllegalStateException("--shard-url requires at least one URL");
            }
        } else if (shards != null) {
            if (shards <= 0) {
                throw new IllegalStateException("--shards must be a positive number: " + shards);
            }
        } else {
            throw new IllegalStateException("You must specify one of the following (mutually exclusive) arguments: "
                    + "--zk-host or --shard-url or --shards");
        }

        if (shardUrls != null) {
            shards = shardUrls.size();
        }

    }
    
    // Taken from org.apache.solr.hadoop.MapReduceIndexerTool
    private void evaluateShards()  {
        if (zkHost != null) {
            assert collection != null;
            ForkedZooKeeperInspector zki = new ForkedZooKeeperInspector();
            try {
                shardUrls = zki.extractShardUrls(zkHost, collection);
            } catch (Exception e) {
                LOG.debug("Cannot extract SolrCloud shard URLs from ZooKeeper", e);
                throw new RuntimeException("Cannot extract SolrCloud shard URLs from ZooKeeper", e);
            }
            assert shardUrls != null;
            if (shardUrls.size() == 0) {
                throw new IllegalStateException("--zk-host requires ZooKeeper " + zkHost
                        + " to contain at least one SolrCore for collection: " + collection);
            }
            shards = shardUrls.size();
            LOG.debug("Using SolrCloud shard URLs: {}", shardUrls);
        }
    }

    // Taken from org.apache.solr.hadoop.MapReduceIndexerTool
    private void evaluateNumReducers() {

        if (isDirectWrite()) {
            return;
        }

        if (shards <= 0) {
            throw new IllegalStateException("Illegal number of shards: " + shards);
        }
        if (fanout <= 1) {
            throw new IllegalStateException("Illegal fanout: " + fanout);
        }

        int reduceTaskCount;
        try {
            // MR1
            reduceTaskCount = new JobClient(conf).getClusterStatus().getMaxReduceTasks();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        LOG.info("Cluster reports {} reduce slots", reduceTaskCount);

        if (reducers == -2) {
            reduceTaskCount = shards;
        } else {
            reduceTaskCount = reducers;
        }
        reduceTaskCount = Math.max(reduceTaskCount, shards);

        if (reduceTaskCount != shards) {
            // Ensure fanout isn't misconfigured. fanout can't meaningfully be larger than what would be
            // required to merge all leaf shards in one single tree merge iteration into root shards
            fanout = Math.min(fanout, (int)ceilDivide(reduceTaskCount, shards));

            // Ensure invariant reducers == options.shards * (fanout ^ N) where N is an integer >= 1.
            // N is the number of mtree merge iterations.
            // This helps to evenly spread docs among root shards and simplifies the impl of the mtree merge algorithm.
            int s = shards;
            while (s < reducers) {
                s = s * fanout;
            }
            reduceTaskCount = s;
            assert reduceTaskCount % fanout == 0;
        }
        this.reducers = reduceTaskCount;
    }

    // same as IntMath.divide(p, q, RoundingMode.CEILING)
    private long ceilDivide(long p, long q) {
        long result = p / q;
        if (p % q != 0) {
            result++;
        }
        return result;
    }

    @VisibleForTesting
    void evaluateIndexingSpecification() {
        String tableName = null;
        String indexerConfigXml = null;
        Map<String,String> indexConnectionParams = Maps.newHashMap();

        if (hbaseIndexerZkHost != null) {

            if (hbaseIndexerName == null) {
                throw new IllegalStateException("--hbase-indexer-name must be supplied if --hbase-indexer-zk is specified");
            }

            StateWatchingZooKeeper zk = null;
            try {
                zk = new StateWatchingZooKeeper(hbaseIndexerZkHost, 30000);
                IndexerModelImpl indexerModel = new IndexerModelImpl(zk, conf.get(ConfKeys.ZK_ROOT_NODE, "/ngdata/hbaseindexer"));
                IndexerDefinition indexerDefinition = indexerModel.getIndexer(hbaseIndexerName);
                byte[] indexConfigBytes = indexerDefinition.getConfiguration();
                tableName = getTableNameFromConf(new ByteArrayInputStream(indexConfigBytes));
                indexerConfigXml = Bytes.toString(indexConfigBytes);
                if (indexerDefinition.getConnectionParams() != null) {
                    indexConnectionParams.putAll(indexerDefinition.getConnectionParams());
                }
                if (zkHost == null) {
                    zkHost = indexConnectionParams.get("solr.zk");
                }
                if (collection == null) {
                    collection = indexConnectionParams.get("solr.collection");
                }
                indexerModel.stop();
            } catch (IndexerNotFoundException infe) {
                throw new IllegalStateException("Indexer " + hbaseIndexerName + " doesn't exist");
            } catch (Exception e) {
                // We won't bother trying to do any recovery here if things don't work out,
                // so we just throw the wrapped exception up the stack
                throw new RuntimeException(e);
            } finally {
                Closer.close(zk);
            }
        } else {
            if (hbaseIndexerConfigFile == null) {
                throw new IllegalStateException(
                        "--hbase-indexer-file must be specified if --hbase-indexer-zk is not specified");
            }
            if (solrHomeDir == null) {
                if (zkHost == null) {
                    throw new IllegalStateException(
                            "--zk-host must be specified if --hbase-indexer-zk is not specified");
                }
                if (collection == null) {
                    throw new IllegalStateException(
                            "--collection must be specified if --hbase-indexer-zk is not specified");
                }
            }
        }


        if (this.hbaseIndexerConfigFile != null) {
            try {
                indexerConfigXml = Files.toString(hbaseIndexerConfigFile, Charsets.UTF_8);
                tableName = getTableNameFromConf(new FileInputStream(hbaseIndexerConfigFile));
            } catch (IOException e) {
                throw new RuntimeException("Error loading " + hbaseIndexerConfigFile, e);
            }
        }

        if (hbaseTableName != null) {
            tableName = hbaseTableName;
        }


        if (solrHomeDir != null) {
            indexConnectionParams.put("solr.mode", "classic");
            indexConnectionParams.put("solr.home", solrHomeDir.getAbsolutePath());
        } else {
            if (zkHost != null) {
                indexConnectionParams.put("solr.zk", zkHost);
            }

            if (collection != null) {
                indexConnectionParams.put("solr.collection", collection);
            }
        }

        if (hbaseIndexerName == null) {
            hbaseIndexerName = DEFAULT_INDEXER_NAME;
        }

        this.hbaseIndexingSpecification = new IndexingSpecification(
                                            tableName,
                                            hbaseIndexerName,
                                            indexerConfigXml,
                                            indexConnectionParams);
    }

    private IndexerConf loadIndexerConf(InputStream indexerConfigInputStream) {
        IndexerConf indexerConf;
        try {
            indexerConf = new XmlIndexerConfReader().read(indexerConfigInputStream);       
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            Closer.close(indexerConfigInputStream);
        }
        
        if (morphlineFile != null) {
            indexerConf.getGlobalParams().put(
                MorphlineResultToSolrMapper.MORPHLINE_FILE_PARAM, morphlineFile.getPath());
        }
        if (morphlineId != null) {
            indexerConf.getGlobalParams().put(
                MorphlineResultToSolrMapper.MORPHLINE_ID_PARAM, morphlineId);
        }
        
        for (Map.Entry<String, String> entry : conf) {
            if (entry.getKey().startsWith(MorphlineResultToSolrMapper.MORPHLINE_VARIABLE_PARAM + ".")) {
                indexerConf.getGlobalParams().put(entry.getKey(), entry.getValue());
            }
            if (entry.getKey().startsWith(MorphlineResultToSolrMapper.MORPHLINE_FIELD_PARAM + ".")) {
                indexerConf.getGlobalParams().put(entry.getKey(), entry.getValue());
            }
        }

        return indexerConf;
    }

    private String getTableNameFromConf(InputStream indexerConfigInputStream) {

        return loadIndexerConf(indexerConfigInputStream).getTable();
    }

    /**
     * Evaluate a timestamp string with an optional format. If the format is not present,
     * the timestamp string is assumed to be a Long.
     * 
     * @param timestampString
     * @param timestampFormat
     * @return evaluated timestamp, or null if there is no timestamp information supplied
     */
    static Long evaluateTimestamp(String timestampString, String timestampFormat) {
        if (timestampString == null) {
            return null;
        }
        if (timestampFormat == null) {
            try {
                return Long.parseLong(timestampString);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Invalid timestamp value: " + timestampString);
            }
        } else {
            SimpleDateFormat dateTimeFormat = null;
            try {
                dateTimeFormat = new SimpleDateFormat(timestampFormat);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Invalid timestamp format: " + e.getMessage());
            }
            try {
                return dateTimeFormat.parse(timestampString).getTime();
            } catch (ParseException e) {
                throw new IllegalStateException("Can't parse timestamp string '"
                        + timestampString + "': " + e.getMessage());
            }
        }
    }
}