/*
 *    JImageIO-extension - OpenSource Java Image translation Library
 *    http://www.geo-solutions.it/
 *	  https://imageio-ext.dev.java.net/
 *    (C) 2007, GeoSolutions
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package it.geosolutions.imageio.plugins.ecw;

import it.geosolutions.imageio.gdalframework.GDALCommonIIOImageMetadata;
import it.geosolutions.imageio.gdalframework.GDALImageReader;

import java.awt.image.RenderedImage;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.metadata.IIOMetadata;

import org.gdal.gdal.Dataset;

/**
 * {@link ECWImageReader} is a {@link GDALImageReader} able to create
 * {@link RenderedImage} from ECW files.
 * 
 * @author Simone Giannecchini, GeoSolutions.
 * @author Daniele Romagnoli, GeoSolutions.
 */
public class ECWImageReader extends GDALImageReader {

	public class ECWDataSetWrapper extends GDALDatasetWrapper {

		public ECWDataSetWrapper(String sDatasetName) {
			super(sDatasetName);
		}

		public ECWDataSetWrapper(Dataset ds, String name) {
			super(ds, name);
		}
	}
	
	private static final Logger LOGGER = Logger
			.getLogger("it.geosolutions.imageio.plugins.ecw");

	public ECWImageReader(ECWImageReaderSpi originatingProvider) {
		super(originatingProvider);
		if (LOGGER.isLoggable(Level.FINE))
			LOGGER.fine("ECWImageReader Constructor");
		nSubdatasets = 0;
	}

	public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
		return getDataSetWrapper(imageIndex).getImageIOMetadata();
	}

	protected GDALDatasetWrapper createDataSetWrapper(Dataset mainDataset,
			String mainDatasetFileName) {
		return new ECWDataSetWrapper(mainDataset, mainDatasetFileName);
	}

	protected GDALDatasetWrapper createDataSetWrapper(String string) {
		return new ECWDataSetWrapper(string);
	}
	
	protected IIOMetadata getIIOImageMetadata(GDALDatasetWrapper wrapper) {
		return new GDALCommonIIOImageMetadata(wrapper);
	}
}
