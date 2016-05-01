package mil.nga.giat.geowave.format.nyctlc.ingest;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import mil.nga.giat.geowave.core.geotime.index.dimension.LatitudeDefinition;
import mil.nga.giat.geowave.core.geotime.index.dimension.LongitudeDefinition;
import mil.nga.giat.geowave.core.geotime.store.dimension.*;
import mil.nga.giat.geowave.core.index.ByteArrayId;
import mil.nga.giat.geowave.core.index.ByteArrayUtils;
import mil.nga.giat.geowave.core.index.dimension.BasicDimensionDefinition;
import mil.nga.giat.geowave.core.index.dimension.NumericDimensionDefinition;
import mil.nga.giat.geowave.core.index.dimension.bin.BinRange;
import mil.nga.giat.geowave.core.index.sfc.SFCFactory;
import mil.nga.giat.geowave.core.index.sfc.data.NumericData;
import mil.nga.giat.geowave.core.index.sfc.tiered.TieredSFCIndexFactory;
import mil.nga.giat.geowave.core.store.dimension.NumericDimensionField;
import mil.nga.giat.geowave.core.store.index.BasicIndexModel;
import mil.nga.giat.geowave.core.store.index.CommonIndexValue;
import mil.nga.giat.geowave.core.store.index.CustomIdIndex;
import mil.nga.giat.geowave.core.store.index.PrimaryIndex;
import mil.nga.giat.geowave.core.store.spi.DimensionalityTypeOptions;
import mil.nga.giat.geowave.core.store.spi.DimensionalityTypeProviderSpi;
import mil.nga.giat.geowave.format.nyctlc.NYCTLCUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.TimeUnit;

public class NYCTLCDimensionalityTypeProvider implements
		DimensionalityTypeProviderSpi
{
	private final NYCTLCOptions options = new NYCTLCOptions();
	// BBox of NYC is more contrained, but we'll give it a little buffer
	// North Latitude: 40.915256 South Latitude: 40.496044 East Longitude:
	// -73.700272 West Longitude: -74.255735
	private static final double MIN_LAT = 40.3;
	private static final double MAX_LAT = 41.1;
	private static final double MIN_LON = -74.375;
	private static final double MAX_LON = -73.575;
	private static final String DEFAULT_NYCTLC_ID_STR = "NYCTLC_IDX";

	public final static ByteArrayId PICKUP_GEOMETRY_FIELD_ID = new ByteArrayId(
			ByteArrayUtils.combineArrays(
					mil.nga.giat.geowave.core.index.StringUtils.stringToBinary(
							NYCTLCUtils.Field.PICKUP_LOCATION.getIndexedName()),
					new byte[] {
						0,
						0
	}));

	public final static ByteArrayId DROPOFF_GEOMETRY_FIELD_ID = new ByteArrayId(
			ByteArrayUtils.combineArrays(
					mil.nga.giat.geowave.core.index.StringUtils.stringToBinary(
							NYCTLCUtils.Field.DROPOFF_LOCATION.getIndexedName()),
					new byte[] {
						0,
						0
	}));

	public final static ByteArrayId TIME_OF_DAY_SEC_FIELD_ID = new ByteArrayId(
			ByteArrayUtils.combineArrays(
					mil.nga.giat.geowave.core.index.StringUtils.stringToBinary(
							NYCTLCUtils.Field.TIME_OF_DAY_SEC.getIndexedName()),
					new byte[] {
						0,
						0
	}));

	public NYCTLCDimensionalityTypeProvider() {}

	@Override
	public String getDimensionalityTypeName() {
		return "nyctlc_sst";
	}

	@Override
	public String getDimensionalityTypeDescription() {
		return "This dimensionality type is sued to index NYCTLC formatted data with indices for pickup location, dropoff location, and time of day.";
	}

	@Override
	public int getPriority() {
		// arbitrary - just lower than spatial so that the default
		// will be spatial over spatial-temporal
		return 5;
	}

	@Override
	public DimensionalityTypeOptions getOptions() {
		return options;
	}

	@Override
	public PrimaryIndex createPrimaryIndex() {
		return internalCreatePrimaryIndex(
				options);
	}

	private static PrimaryIndex internalCreatePrimaryIndex(
			final NYCTLCOptions options ) {

		final NumericDimensionField[] fields = new NumericDimensionField[] {
			new LongitudeField(
					PICKUP_GEOMETRY_FIELD_ID),
			new LatitudeField(
					true,
					PICKUP_GEOMETRY_FIELD_ID),
			new LongitudeField(
					DROPOFF_GEOMETRY_FIELD_ID),
			new LatitudeField(
					true,
					DROPOFF_GEOMETRY_FIELD_ID),
			new TimeField(
					new BasicDimensionDefinition(
							0,
							new Long(
									TimeUnit.DAYS.toSeconds(
											1)).doubleValue()),
					TIME_OF_DAY_SEC_FIELD_ID)
		};

		final NumericDimensionDefinition[] dimensions = new NumericDimensionDefinition[] {
			new PickupLongitudeDefinition(),
			new PickupLatitudeDefinition(
					true),
			new DropoffLongitudeDefinition(),
			new DropoffLatitudeDefinition(
					true),
			new BasicDimensionDefinition(
					0,
					new Long(
							TimeUnit.DAYS.toSeconds(
									1)).doubleValue())
		};

		final String combinedId = DEFAULT_NYCTLC_ID_STR + "_" + options.bias;

		return new CustomIdIndex(
				TieredSFCIndexFactory.createSingleTierStrategy(
						dimensions,
						new int[] {
							options.bias.getSpatialPrecision(),
							options.bias.getSpatialPrecision(),
							options.bias.getSpatialPrecision(),
							options.bias.getSpatialPrecision(),
							options.bias.getTemporalPrecision()
		},
						SFCFactory.SFCType.HILBERT),
				new BasicIndexModel(
						fields),
				new ByteArrayId(
						combinedId + "_POINTONLY"));
	}

	@Override
	public Class<? extends CommonIndexValue>[] getRequiredIndexTypes() {
		return new Class[] {
			GeometryWrapper.class,
			Time.class
		};
	}

	private static class NYCTLCOptions implements
			DimensionalityTypeOptions
	{
		@Parameter(names = {
			"--bias"
		}, required = false, description = "The bias of the index. There can be more precision given to time or space if necessary.", converter = BiasConverter.class)
		protected Bias bias = Bias.BALANCED;
	}

	public static enum Bias {
		TEMPORAL,
		BALANCED,
		SPATIAL;
		// converter that will be used later
		public static Bias fromString(
				final String code ) {

			for (final Bias output : Bias.values()) {
				if (output.toString().equalsIgnoreCase(
						code)) {
					return output;
				}
			}

			return null;
		}

		protected int getSpatialPrecision() {
			switch (this) {
				case SPATIAL:
					return 14;
				case TEMPORAL:
					return 7;
				case BALANCED:
				default:
					return 12;
			}
		}

		protected int getTemporalPrecision() {
			switch (this) {
				case SPATIAL:
					return 6;
				case TEMPORAL:
					return 34;
				case BALANCED:
				default:
					return 12;
			}
		}
	}

	public static class BiasConverter implements
			IStringConverter<Bias>
	{
		@Override
		public Bias convert(
				final String value ) {
			final Bias convertedValue = Bias.fromString(
					value);

			if (convertedValue == null) {
				throw new ParameterException(
						"Value " + value + "can not be converted to an index bias. " + "Available values are: " + StringUtils.join(
								Bias.values(),
								", ").toLowerCase());
			}
			return convertedValue;
		}

	}

	public static class NYCTLCIndexBuilder
	{
		private final NYCTLCOptions options;

		public NYCTLCIndexBuilder() {
			options = new NYCTLCOptions();
		}

		private NYCTLCIndexBuilder(
				final NYCTLCOptions options ) {
			this.options = options;
		}

		public NYCTLCIndexBuilder setBias(
				final Bias bias ) {
			options.bias = bias;
			return new NYCTLCIndexBuilder(
					options);
		}

		public PrimaryIndex createIndex() {
			return internalCreatePrimaryIndex(
					options);
		}
	}

	public static class PickupLongitudeDefinition extends
			LongitudeDefinition
	{
		public PickupLongitudeDefinition() {
			super();
			min = MIN_LON;
			max = MAX_LON;
		}
		@Override
		public BinRange[] getNormalizedRanges(
				NumericData range ) {
			return new BinRange[] {
				new BinRange(
						// by default clamp to the min and max
						clamp(range.getMin()),
						clamp(range.getMax()))
			};
		}
	}

	public static class PickupLatitudeDefinition extends
			LatitudeDefinition
	{
		public PickupLatitudeDefinition() {
			super();
			min = MIN_LAT;
			max = MAX_LAT;
		}

		public PickupLatitudeDefinition(
				boolean useHalfRange ) {
			super(
					useHalfRange);
			min = MIN_LAT;
			max = MAX_LAT;
		}
	}

	public static class DropoffLongitudeDefinition extends
			LongitudeDefinition
	{
		public DropoffLongitudeDefinition() {
			super();
			min = MIN_LON;
			max = MAX_LON;
		}
		@Override
		public BinRange[] getNormalizedRanges(
				NumericData range ) {
			return new BinRange[] {
				new BinRange(
						// by default clamp to the min and max
						clamp(range.getMin()),
						clamp(range.getMax()))
			};
		}
	}

	public static class DropoffLatitudeDefinition extends
			LatitudeDefinition
	{
		public DropoffLatitudeDefinition() {
			min = MIN_LAT;
			max = MAX_LAT;
		}

		public DropoffLatitudeDefinition(
				boolean useHalfRange ) {
			super(
					useHalfRange);
		}
	}
}
