package mil.nga.giat.geowave.datastore.hbase.query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import mil.nga.giat.geowave.core.index.ByteArrayId;
import mil.nga.giat.geowave.core.index.ByteArrayRange;
import mil.nga.giat.geowave.core.index.IndexMetaData;
import mil.nga.giat.geowave.core.index.StringUtils;
import mil.nga.giat.geowave.core.index.sfc.data.MultiDimensionalNumericData;
import mil.nga.giat.geowave.core.store.CloseableIterator;
import mil.nga.giat.geowave.core.store.CloseableIterator.Wrapper;
import mil.nga.giat.geowave.core.store.adapter.AdapterStore;
import mil.nga.giat.geowave.core.store.adapter.DataAdapter;
import mil.nga.giat.geowave.core.store.adapter.statistics.DuplicateEntryCount;
import mil.nga.giat.geowave.core.store.callback.ScanCallback;
import mil.nga.giat.geowave.core.store.filter.DedupeFilter;
import mil.nga.giat.geowave.core.store.filter.QueryFilter;
import mil.nga.giat.geowave.core.store.index.PrimaryIndex;
import mil.nga.giat.geowave.core.store.query.ConstraintsQuery;
import mil.nga.giat.geowave.core.store.query.Query;
import mil.nga.giat.geowave.core.store.query.aggregate.Aggregation;
import mil.nga.giat.geowave.datastore.hbase.operations.BasicHBaseOperations;
import mil.nga.giat.geowave.datastore.hbase.query.generated.RowCountProtos;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.coprocessor.AggregationClient;
import org.apache.hadoop.hbase.client.coprocessor.Batch;
import org.apache.hadoop.hbase.client.coprocessor.LongColumnInterpreter;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.ipc.BlockingRpcCallback;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.google.common.collect.Iterators;

public class HBaseConstraintsQuery extends
		HBaseFilteredIndexQuery
{
	protected final ConstraintsQuery base;

	private final static Logger LOGGER = Logger.getLogger(HBaseConstraintsQuery.class);

	public HBaseConstraintsQuery(
			final List<ByteArrayId> adapterIds,
			final PrimaryIndex index,
			final Query query,
			final DedupeFilter clientDedupeFilter,
			final ScanCallback<?> scanCallback,
			final Pair<DataAdapter<?>, Aggregation<?, ?, ?>> aggregation,
			final IndexMetaData[] indexMetaData,
			final DuplicateEntryCount duplicateCounts,
			final String[] authorizations ) {
		this(
				adapterIds,
				index,
				query != null ? query.getIndexConstraints(index.getIndexStrategy()) : null,
				query != null ? query.createFilters(index.getIndexModel()) : null,
				clientDedupeFilter,
				scanCallback,
				aggregation,
				indexMetaData,
				duplicateCounts,
				authorizations);
	}

	public HBaseConstraintsQuery(
			final List<ByteArrayId> adapterIds,
			final PrimaryIndex index,
			final List<MultiDimensionalNumericData> constraints,
			final List<QueryFilter> queryFilters,
			final DedupeFilter clientDedupeFilter,
			final ScanCallback<?> scanCallback,
			final Pair<DataAdapter<?>, Aggregation<?, ?, ?>> aggregation,
			final IndexMetaData[] indexMetaData,
			final DuplicateEntryCount duplicateCounts,
			final String[] authorizations ) {

		super(
				adapterIds,
				index,
				scanCallback,
				authorizations);

		base = new ConstraintsQuery(
				constraints,
				aggregation,
				indexMetaData,
				index,
				queryFilters,
				clientDedupeFilter,
				duplicateCounts,
				this);

		if (isAggregation()) {
			// Because aggregations are done client-side make sure to set
			// the adapter ID here
			this.adapterIds = Collections.singletonList(aggregation.getLeft().getAdapterId());
			LOGGER.setLevel(Level.DEBUG);
		}
	}

	protected boolean isAggregation() {
		return base.isAggregation();
	}

	@Override
	protected List<ByteArrayRange> getRanges() {
		return base.getRanges();
	}

	@Override
	protected List<QueryFilter> getAllFiltersList() {
		final List<QueryFilter> filters = super.getAllFiltersList();
		for (final QueryFilter distributable : base.distributableFilters) {
			if (!filters.contains(distributable)) {
				filters.add(distributable);
			}
		}
		return filters;
	}

	@Override
	protected List<Filter> getDistributableFilter() {
		return new ArrayList<Filter>();
	}

	@Override
	public CloseableIterator<Object> query(
			final BasicHBaseOperations operations,
			final AdapterStore adapterStore,
			final Integer limit ) {
		if (!isAggregation()) {
			return super.query(
					operations,
					adapterStore,
					limit);
		}

		return aggQuery(
				operations,
				adapterStore,
				limit);
	}

	public CloseableIterator<Object> aggQuery(
			final BasicHBaseOperations operations,
			final AdapterStore adapterStore,
			final Integer limit ) {
		try {
			if (!operations.tableExists(StringUtils.stringFromBinary(index.getId().getBytes()))) {
				LOGGER.warn("Table does not exist " + StringUtils.stringFromBinary(index.getId().getBytes()));
				return new CloseableIterator.Empty();
			}
		}
		catch (final IOException ex) {
			LOGGER.warn("Unable to check if " + StringUtils.stringFromBinary(index.getId().getBytes()) + " table exists");
			return new CloseableIterator.Empty();
		}

		final String tableName = StringUtils.stringFromBinary(index.getId().getBytes());

		final List<Filter> distributableFilters = getDistributableFilter();
		CloseableIterator<DataAdapter<?>> adapters = null;

		Scan multiScanner = getMultiScanner(
				limit,
				distributableFilters,
				adapters);

		AggregationClient aggregationClient = new AggregationClient(
				operations.getConfig());

		try {
			LOGGER.debug("Calling aggregation client...");
			
			long total = aggregationClient.rowCount(
					BasicHBaseOperations.getTableName(tableName),
					new LongColumnInterpreter(),
					multiScanner);
			
			LOGGER.debug("Aggregation client returned " + total + " items.");
			
			aggregationClient.close();

			return new Wrapper(
					Iterators.singletonIterator(total));

		}
		catch (Throwable e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		LOGGER.error("Results were empty");
		return new CloseableIterator.Empty();
	}

	private CloseableIterator<Object> oldAggQuery(
			final BasicHBaseOperations operations,
			final AdapterStore adapterStore,
			final Integer limit ) {
		// Use the row count coprocessor
		final String tableName = StringUtils.stringFromBinary(index.getId().getBytes());
		long total = 0;

		try {
			Table table = operations.getTable(tableName);

			final RowCountProtos.CountRequest request = RowCountProtos.CountRequest.getDefaultInstance();

			// Determine total row range
			List<ByteArrayRange> rowRanges = getRanges();

			// Set start and end row key to "null" to count all rows.
			byte[] startRow = null;
			byte[] stopRow = null;

			if ((rowRanges != null) && (rowRanges.size() == 2)) {
				startRow = rowRanges.get(
						0).getStart().getBytes();
				stopRow = rowRanges.get(
						0).getEnd().getBytes();
			}

			Map<byte[], Long> results = table.coprocessorService(
					RowCountProtos.RowCountService.class,
					startRow,
					stopRow,
					new Batch.Call<RowCountProtos.RowCountService, Long>() {
						public Long call(
								RowCountProtos.RowCountService counter )
								throws IOException {
							BlockingRpcCallback<RowCountProtos.CountResponse> rpcCallback = new BlockingRpcCallback<RowCountProtos.CountResponse>();
							counter.getRowCount(
									null,
									request,
									rpcCallback);
							RowCountProtos.CountResponse response = rpcCallback.get();
							return response.hasCount() ? response.getCount() : 0;
						}
					});

			for (Map.Entry<byte[], Long> entry : results.entrySet()) {
				total += entry.getValue().longValue();
			}

		}
		catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		catch (Throwable e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return new Wrapper(
				Iterators.singletonIterator(total));
	}
}
