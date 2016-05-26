package mil.nga.giat.geowave.cli.geoserver;

import mil.nga.giat.geowave.core.cli.spi.CLIOperationProviderSpi;

public class GeoServerOperationProvider implements
		CLIOperationProviderSpi
{
	private static final Class<?>[] OPERATIONS = new Class<?>[] {
		GeoServerSection.class,
		AddGeoServerCommand.class,
		GeoServerWorkspaceCommand.class,
	};

	@Override
	public Class<?>[] getOperations() {
		return OPERATIONS;
	}
}
