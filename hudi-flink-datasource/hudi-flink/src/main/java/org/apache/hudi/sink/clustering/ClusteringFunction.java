/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.sink.clustering;

import org.apache.hudi.avro.HoodieAvroUtils;
import org.apache.hudi.client.FlinkTaskContextSupplier;
import org.apache.hudi.client.HoodieFlinkWriteClient;
import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.client.utils.ConcatenatingIterator;
import org.apache.hudi.common.model.ClusteringGroupInfo;
import org.apache.hudi.common.model.ClusteringOperation;
import org.apache.hudi.common.table.HoodieTableConfig;
import org.apache.hudi.common.table.log.HoodieFileSliceReader;
import org.apache.hudi.common.table.log.HoodieMergedLogRecordScanner;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.StringUtils;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.configuration.FlinkOptions;
import org.apache.hudi.configuration.OptionsResolver;
import org.apache.hudi.exception.HoodieClusteringException;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.io.IOUtils;
import org.apache.hudi.io.storage.HoodieFileReader;
import org.apache.hudi.io.storage.HoodieFileReaderFactory;
import org.apache.hudi.sink.bulk.BulkInsertWriterHelper;
import org.apache.hudi.sink.bulk.sort.SortOperatorGen;
import org.apache.hudi.sink.utils.NonThrownExecutor;
import org.apache.hudi.table.HoodieFlinkTable;
import org.apache.hudi.util.AvroSchemaConverter;
import org.apache.hudi.util.AvroToRowDataConverters;
import org.apache.hudi.util.StreamerUtil;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.generic.IndexedRecord;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.memory.ManagedMemoryUseCase;
import org.apache.flink.metrics.Gauge;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.memory.MemoryManager;
import org.apache.flink.streaming.api.datastream.AsyncDataStream;
import org.apache.flink.streaming.api.functions.async.ResultFuture;
import org.apache.flink.streaming.api.functions.async.RichAsyncFunction;
import org.apache.flink.streaming.api.operators.AbstractUdfStreamOperator;
import org.apache.flink.streaming.api.operators.OneInputStreamOperatorFactory;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorParameters;
import org.apache.flink.streaming.api.operators.YieldingOperatorFactory;
import org.apache.flink.streaming.api.operators.async.AsyncWaitOperatorFactory;
import org.apache.flink.streaming.runtime.tasks.StreamTask;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.binary.BinaryRowData;
import org.apache.flink.table.planner.codegen.sort.SortCodeGenerator;
import org.apache.flink.table.runtime.generated.NormalizedKeyComputer;
import org.apache.flink.table.runtime.generated.RecordComparator;
import org.apache.flink.table.runtime.operators.sort.BinaryExternalSorter;
import org.apache.flink.table.runtime.typeutils.AbstractRowDataSerializer;
import org.apache.flink.table.runtime.typeutils.BinaryRowDataSerializer;
import org.apache.flink.table.runtime.typeutils.RowDataSerializer;
import org.apache.flink.table.types.logical.RowType;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.apache.hudi.table.format.FormatUtils.buildAvroRecordBySchema;

/**
 * Function to execute the actual clustering task assigned by the clustering plan task.
 * In order to execute scalable, the input should shuffle by the clustering event {@link ClusteringPlanEvent}.
 */
public class ClusteringFunction extends RichAsyncFunction<ClusteringPlanEvent, ClusteringCommitEvent> {
  private static final Logger LOG = LoggerFactory.getLogger(ClusteringFunction.class);

  private final Configuration conf;
  private final RowType rowType;
  private int taskID;
  private transient HoodieWriteConfig writeConfig;
  private transient HoodieFlinkTable<?> table;
  private transient Schema schema;
  private transient Schema readerSchema;
  private transient int[] requiredPos;
  private transient AvroToRowDataConverters.AvroToRowDataConverter avroToRowDataConverter;
  private transient HoodieFlinkWriteClient writeClient;
  private transient BulkInsertWriterHelper writerHelper;

  private transient BinaryExternalSorter sorter;
  private transient BinaryRowDataSerializer binarySerializer;

  /**
   * Whether to execute clustering asynchronously.
   */
  private final boolean asyncClustering;

  /**
   * Whether the clustering sort is enabled.
   */
  private final boolean sortClusteringEnabled;

  /**
   * Executor service to execute the clustering task.
   */
  private transient NonThrownExecutor executor;

  /**
   * Parameters for this function to access the context of containing StreamOperator
   */
  private transient StreamOperatorParameters<ClusteringCommitEvent> parameters;

  private ClusteringFunction(Configuration conf, RowType rowType) {
    this.conf = conf;
    this.rowType = rowType;
    this.asyncClustering = OptionsResolver.needsAsyncClustering(conf);
    this.sortClusteringEnabled = OptionsResolver.sortClusteringEnabled(conf);
  }

  public void setStreamOperatorParameters(StreamOperatorParameters<ClusteringCommitEvent> parameters) {
    this.parameters = parameters;
  }

  @Override
  public void open(Configuration parameters) throws Exception {
    super.open(parameters);

    this.taskID = getRuntimeContext().getIndexOfThisSubtask();
    this.writeConfig = StreamerUtil.getHoodieClientConfig(this.conf);
    this.writeClient = StreamerUtil.createWriteClient(conf, getRuntimeContext());
    this.table = writeClient.getHoodieTable();

    this.schema = AvroSchemaConverter.convertToSchema(rowType);
    this.readerSchema = HoodieAvroUtils.addMetadataFields(this.schema);
    this.requiredPos = getRequiredPositions();

    this.avroToRowDataConverter = AvroToRowDataConverters.createRowConverter(rowType);
    this.binarySerializer = new BinaryRowDataSerializer(rowType.getFieldCount());

    if (this.sortClusteringEnabled) {
      initSorter();
    }

    if (this.asyncClustering) {
      this.executor = NonThrownExecutor.builder(LOG).build();
    }
  }

  @Override
  public void asyncInvoke(final ClusteringPlanEvent event, final ResultFuture<ClusteringCommitEvent> resultFuture) throws Exception {
    final String instantTime = event.getClusteringInstantTime();
    if (this.asyncClustering) {
      // executes the compaction task asynchronously to not block the checkpoint barrier propagate.
      executor.execute(
          () -> doClustering(instantTime, event, resultFuture),
          (errMsg, t) -> resultFuture.complete(Collections.singletonList(new ClusteringCommitEvent(instantTime, taskID))),
          "Execute clustering for instant %s from task %d", instantTime, taskID);
    } else {
      // executes the clustering task synchronously for batch mode.
      LOG.info("Execute clustering for instant {} from task {}", instantTime, taskID);
      doClustering(instantTime, event, resultFuture);
    }
  }

  @Override
  public void close() {
    if (this.writeClient != null) {
      this.writeClient.cleanHandlesGracefully();
      this.writeClient.close();
    }
    if (this.sorter != null) {
      this.sorter.close();
    }
  }

  // -------------------------------------------------------------------------
  //  Utilities
  // -------------------------------------------------------------------------

  private void doClustering(String instantTime, ClusteringPlanEvent event,
                            ResultFuture<ClusteringCommitEvent> resultFuture) throws Exception {
    final ClusteringGroupInfo clusteringGroupInfo = event.getClusteringGroupInfo();

    initWriterHelper(instantTime);

    List<ClusteringOperation> clusteringOps = clusteringGroupInfo.getOperations();
    boolean hasLogFiles = clusteringOps.stream().anyMatch(op -> op.getDeltaFilePaths().size() > 0);

    Iterator<RowData> iterator;
    if (hasLogFiles) {
      // if there are log files, we read all records into memory for a file group and apply updates.
      iterator = readRecordsForGroupWithLogs(clusteringOps, instantTime);
    } else {
      // We want to optimize reading records for case there are no log files.
      iterator = readRecordsForGroupBaseFiles(clusteringOps);
    }

    RowDataSerializer rowDataSerializer = new RowDataSerializer(rowType);

    if (this.sortClusteringEnabled) {
      while (iterator.hasNext()) {
        RowData rowData = iterator.next();
        BinaryRowData binaryRowData = rowDataSerializer.toBinaryRow(rowData).copy();
        this.sorter.write(binaryRowData);
      }

      BinaryRowData row = binarySerializer.createInstance();
      while ((row = sorter.getIterator().next(row)) != null) {
        this.writerHelper.write(row);
      }
    } else {
      while (iterator.hasNext()) {
        this.writerHelper.write(iterator.next());
      }
    }

    List<WriteStatus> writeStatuses = this.writerHelper.getWriteStatuses(this.taskID);
    resultFuture.complete(Collections.singletonList(new ClusteringCommitEvent(instantTime, writeStatuses, this.taskID)));
    this.writerHelper = null;
  }

  private void initWriterHelper(String clusteringInstantTime) {
    if (this.writerHelper == null) {
      this.writerHelper = new BulkInsertWriterHelper(this.conf, this.table, this.writeConfig,
          clusteringInstantTime, this.taskID, getRuntimeContext().getNumberOfParallelSubtasks(), getRuntimeContext().getAttemptNumber(),
          this.rowType);
    }
  }

  /**
   * Read records from baseFiles, apply updates and convert to Iterator.
   */
  @SuppressWarnings("unchecked")
  private Iterator<RowData> readRecordsForGroupWithLogs(List<ClusteringOperation> clusteringOps, String instantTime) {
    List<Iterator<RowData>> recordIterators = new ArrayList<>();

    long maxMemoryPerCompaction = IOUtils.getMaxMemoryPerCompaction(new FlinkTaskContextSupplier(null), writeConfig);
    LOG.info("MaxMemoryPerCompaction run as part of clustering => " + maxMemoryPerCompaction);

    for (ClusteringOperation clusteringOp : clusteringOps) {
      try {
        Option<HoodieFileReader> baseFileReader = StringUtils.isNullOrEmpty(clusteringOp.getDataFilePath())
            ? Option.empty()
            : Option.of(HoodieFileReaderFactory.getFileReader(table.getHadoopConf(), new Path(clusteringOp.getDataFilePath())));
        HoodieMergedLogRecordScanner scanner = HoodieMergedLogRecordScanner.newBuilder()
            .withFileSystem(table.getMetaClient().getFs())
            .withBasePath(table.getMetaClient().getBasePath())
            .withLogFilePaths(clusteringOp.getDeltaFilePaths())
            .withReaderSchema(readerSchema)
            .withLatestInstantTime(instantTime)
            .withMaxMemorySizeInBytes(maxMemoryPerCompaction)
            .withReadBlocksLazily(writeConfig.getCompactionLazyBlockReadEnabled())
            .withReverseReader(writeConfig.getCompactionReverseLogReadEnabled())
            .withBufferSize(writeConfig.getMaxDFSStreamBufferSize())
            .withSpillableMapBasePath(writeConfig.getSpillableMapBasePath())
            .withDiskMapType(writeConfig.getCommonConfig().getSpillableDiskMapType())
            .withBitCaskDiskMapCompressionEnabled(writeConfig.getCommonConfig().isBitCaskDiskMapCompressionEnabled())
            .build();

        HoodieTableConfig tableConfig = table.getMetaClient().getTableConfig();
        HoodieFileSliceReader<? extends IndexedRecord> hoodieFileSliceReader = HoodieFileSliceReader.getFileSliceReader(baseFileReader, scanner, readerSchema,
            tableConfig.getPayloadClass(),
            tableConfig.getPreCombineField(),
            tableConfig.populateMetaFields() ? Option.empty() : Option.of(Pair.of(tableConfig.getRecordKeyFieldProp(),
                tableConfig.getPartitionFieldProp())));

        recordIterators.add(StreamSupport.stream(Spliterators.spliteratorUnknownSize(hoodieFileSliceReader, Spliterator.NONNULL), false).map(hoodieRecord -> {
          try {
            return this.transform((IndexedRecord) hoodieRecord.getData().getInsertValue(readerSchema).get());
          } catch (IOException e) {
            throw new HoodieIOException("Failed to read next record", e);
          }
        }).iterator());
      } catch (IOException e) {
        throw new HoodieClusteringException("Error reading input data for " + clusteringOp.getDataFilePath()
            + " and " + clusteringOp.getDeltaFilePaths(), e);
      }
    }

    return new ConcatenatingIterator<>(recordIterators);
  }

  /**
   * Read records from baseFiles and get iterator.
   */
  private Iterator<RowData> readRecordsForGroupBaseFiles(List<ClusteringOperation> clusteringOps) {
    List<Iterator<RowData>> iteratorsForPartition = clusteringOps.stream().map(clusteringOp -> {
      Iterable<IndexedRecord> indexedRecords = () -> {
        try {
          return HoodieFileReaderFactory.getFileReader(table.getHadoopConf(), new Path(clusteringOp.getDataFilePath())).getRecordIterator(readerSchema);
        } catch (IOException e) {
          throw new HoodieClusteringException("Error reading input data for " + clusteringOp.getDataFilePath()
              + " and " + clusteringOp.getDeltaFilePaths(), e);
        }
      };

      return StreamSupport.stream(indexedRecords.spliterator(), false).map(this::transform).iterator();
    }).collect(Collectors.toList());

    return new ConcatenatingIterator<>(iteratorsForPartition);
  }

  /**
   * Transform IndexedRecord into HoodieRecord.
   */
  private RowData transform(IndexedRecord indexedRecord) {
    GenericRecord record = buildAvroRecordBySchema(indexedRecord, schema, requiredPos, new GenericRecordBuilder(schema));
    return (RowData) avroToRowDataConverter.convert(record);
  }

  private int[] getRequiredPositions() {
    final List<String> fieldNames = readerSchema.getFields().stream().map(Schema.Field::name).collect(Collectors.toList());
    return schema.getFields().stream()
        .map(field -> fieldNames.indexOf(field.name()))
        .mapToInt(i -> i)
        .toArray();
  }

  private void initSorter() {
    StreamTask<?, ?> containingTask = parameters.getContainingTask();
    Environment environment = containingTask.getEnvironment();
    ClassLoader cl = containingTask.getUserCodeClassLoader();
    NormalizedKeyComputer computer = createSortCodeGenerator().generateNormalizedKeyComputer("SortComputer").newInstance(cl);
    RecordComparator comparator = createSortCodeGenerator().generateRecordComparator("SortComparator").newInstance(cl);

    MemoryManager memManager = environment.getMemoryManager();
    this.sorter =
        new BinaryExternalSorter(
            containingTask,
            memManager,
            computeMemorySize(),
            environment.getIOManager(),
            (AbstractRowDataSerializer) binarySerializer,
            binarySerializer,
            computer,
            comparator,
            containingTask.getJobConfiguration());
    this.sorter.startThreads();

    // register the metrics.
    environment.getMetricGroup().gauge("memoryUsedSizeInBytes", (Gauge<Long>) sorter::getUsedMemoryInBytes);
    environment.getMetricGroup().gauge("numSpillFiles", (Gauge<Long>) sorter::getNumSpillFiles);
    environment.getMetricGroup().gauge("spillInBytes", (Gauge<Long>) sorter::getSpillInBytes);
  }

  private long computeMemorySize() {
    Environment environment = parameters.getContainingTask().getEnvironment();
    return environment.getMemoryManager().computeMemorySize(
        parameters.getStreamConfig().getManagedMemoryFractionOperatorUseCaseOfSlot(
            ManagedMemoryUseCase.OPERATOR, environment.getTaskManagerInfo().getConfiguration(), environment.getUserCodeClassLoader().asClassLoader()
        )
    );
  }

  private SortCodeGenerator createSortCodeGenerator() {
    SortOperatorGen sortOperatorGen = new SortOperatorGen(rowType,
        conf.getString(FlinkOptions.CLUSTERING_SORT_COLUMNS).split(","));
    return sortOperatorGen.createSortCodeGenerator();
  }

  @VisibleForTesting
  public void setExecutor(NonThrownExecutor executor) {
    this.executor = executor;
  }

  public static class ClusteringOperatorFactory extends AsyncWaitOperatorFactory<ClusteringPlanEvent, ClusteringCommitEvent>
      implements OneInputStreamOperatorFactory<ClusteringPlanEvent, ClusteringCommitEvent>, YieldingOperatorFactory<ClusteringCommitEvent> {

    public ClusteringOperatorFactory(Configuration conf, RowType rowType) {
      super(new ClusteringFunction(conf, rowType), 0, 1, AsyncDataStream.OutputMode.ORDERED);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends StreamOperator<ClusteringCommitEvent>> T createStreamOperator(
        StreamOperatorParameters<ClusteringCommitEvent> parameters) {
      AbstractUdfStreamOperator<ClusteringCommitEvent, ClusteringFunction> streamOperator = super.createStreamOperator(parameters);
      ClusteringFunction clusteringFunction = streamOperator.getUserFunction();
      clusteringFunction.setStreamOperatorParameters(parameters);
      return (T) streamOperator;
    }

    @Override
    public Class<? extends StreamOperator> getStreamOperatorClass(ClassLoader classLoader) {
      return super.getStreamOperatorClass(classLoader);
    }
  }
}