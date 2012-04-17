/*
 *    ImageI/O-Ext - OpenSource Java Image translation Library
 *    http://www.geo-solutions.it/
 *        http://java.net/projects/imageio-ext/
 *    (C) 2007 - 2009, GeoSolutions
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    either version 3 of the License, or (at your option) any later version.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package it.geosolutions.hdf.object.h4;

import ncsa.hdf.hdflib.HDFException;
import ncsa.hdf.hdflib.HDFLibrary;

/**
 * A package private class which allows to handle Attributes using the GR APIs.
 * 
 * @author Daniele Romagnoli, GeoSolutions
 */
class H4GRFamilyObjectsAttributesManager extends AbstractH4Object {

    public H4GRFamilyObjectsAttributesManager(final int identifier,
            final int numAttributes) {
        super(identifier, numAttributes);
    }

    /**
     * @see {@link AbstractH4Object#readAttribute(int, Object)}
     */
    protected boolean readAttribute(int index, Object values)
            throws HDFException {
    	H4Utilities.checkNonNull(values, "values");
        return HDFLibrary.GRgetattr(getIdentifier(), index, values);
    }

    /**
     * @see {@link AbstractH4Object#getAttributeInfo(int, String[])}
     */
    protected int[] getAttributeInfo(int index, String[] attrName)
            throws HDFException {
    	H4Utilities.checkNonNull(attrName, "attrName");
    	for(int i=0;i<attrName.length;i++)
    		H4Utilities.checkNonNull(attrName[i], "attrName["+i+"]");
        int[] attrInfo = new int[] { 0, 0 };
        boolean done = HDFLibrary.GRattrinfo(getIdentifier(), index, attrName,attrInfo);
        if (done)
            return attrInfo;
        else
            return null;
    }

    /**
     * @see {@link AbstractH4Object#getAttributeIndexByName(String)}
     */
    protected int getAttributeIndexByName(String attributeName)
            throws HDFException {
    	H4Utilities.checkNonNull(attributeName, "attributeName");
        return HDFLibrary.GRfindattr(getIdentifier(), attributeName);
    }
}
