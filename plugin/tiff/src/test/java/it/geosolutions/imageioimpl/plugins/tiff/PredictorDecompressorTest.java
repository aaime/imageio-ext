/*
 *    ImageI/O-Ext - OpenSource Java Image translation Library
 *    http://www.geo-solutions.it/
 *    https://github.com/geosolutions-it/imageio-ext
 *    (C) 2026, GeoSolutions
 *    All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of GeoSolutions nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY GeoSolutions ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED. IN NO EVENT SHALL GeoSolutions
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY ARISING
 * IN ANY WAY OUT OF THE USE OF THIS SOFTWARE.
 */
package it.geosolutions.imageioimpl.plugins.tiff;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import it.geosolutions.imageio.plugins.tiff.BaselineTIFFTagSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import javax.imageio.IIOException;
import org.junit.Test;

/**
 * Checks the predictor decode: the 8-bit horizontal reversal against the original straightforward loop, and every
 * sample size, byte order and predictor against data encoded here and decoded back.
 * {@link PredictorDecompressorBenchmark} and {@link PredictorDecompressorMultiByteBenchmark} measure the speed.
 */
public class PredictorDecompressorTest {

    private static final int[] WIDTHS = {1, 2, 7, 33};
    private static final int[] HEIGHTS = {1, 3};
    private static final ByteOrder[] ORDERS = {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN};

    /** Bytes before and after the image, which the decode must leave alone. */
    private static final int PADDING = 5;

    @Test
    public void testHorizontalRoundTrip() throws Exception {
        for (int bytes : new int[] {1, 2, 4, 8}) {
            for (int spp = 1; spp <= 5; spp++) {
                assertRoundTrip(BaselineTIFFTagSet.PREDICTOR_HORIZONTAL_DIFFERENCING, bytes, spp);
            }
        }
    }

    /** 24 bit floats have no fast path, they check the generic one. */
    @Test
    public void testFloatingPointRoundTrip() throws Exception {
        for (int bytes : new int[] {2, 3, 4, 8}) {
            for (int spp = 1; spp <= 4; spp++) {
                assertRoundTrip(BaselineTIFFTagSet.PREDICTOR_FLOATING_POINT, bytes, spp);
            }
        }
    }

    /**
     * Places sums that overflow a byte or set its top bit on each band in turn, with zero on the other bands: a carry
     * leaking into a neighbour band turns its zero into one.
     */
    @Test
    public void testEightBitCarry() throws Exception {
        int[][] sums = {{0xFF, 0x01, 0x00}, {0x80, 0x80, 0x00}, {0x7F, 0x01, 0x80}, {0xFF, 0xFF, 0xFE}};
        for (int spp = 2; spp <= 4; spp++) {
            for (int band = 0; band < spp; band++) {
                for (int[] sum : sums) {
                    byte[] row = new byte[2 * spp];
                    row[band] = (byte) sum[0];
                    row[spp + band] = (byte) sum[1];
                    byte[] expected = new byte[2 * spp];
                    expected[band] = (byte) sum[0];
                    expected[spp + band] = (byte) sum[2];

                    decoder(BaselineTIFFTagSet.PREDICTOR_HORIZONTAL_DIFFERENCING, 1, spp, ByteOrder.LITTLE_ENDIAN)
                            .decompress(row, 0, 1, 2, row.length);

                    String where = String.format("spp=%d band=%d 0x%X+0x%X", spp, band, sum[0], sum[1]);
                    assertArrayEquals(where, expected, row);
                }
            }
        }
    }

    @Test
    public void testHorizontalUnsupportedBits() {
        assertRejected(
                BaselineTIFFTagSet.PREDICTOR_HORIZONTAL_DIFFERENCING,
                new int[] {12},
                BaselineTIFFTagSet.SAMPLE_FORMAT_UNSIGNED_INTEGER,
                "12-bit samples are not supported for Horizontal differencing Predictor");
    }

    /** The fast loops read the width of the first band only, so mixed widths must be rejected. */
    @Test
    public void testHorizontalVaryingBits() {
        assertRejected(
                BaselineTIFFTagSet.PREDICTOR_HORIZONTAL_DIFFERENCING,
                new int[] {8, 16},
                BaselineTIFFTagSet.SAMPLE_FORMAT_UNSIGNED_INTEGER,
                "Varying sample width is not supported for Horizontal differencing Predictor (first: 8, unexpected:16)");
    }

    @Test
    public void testFloatingPointUnsupportedBits() {
        assertRejected(
                BaselineTIFFTagSet.PREDICTOR_FLOATING_POINT,
                new int[] {8},
                BaselineTIFFTagSet.SAMPLE_FORMAT_FLOATING_POINT,
                "8-bit samples are not supported for Floating point Predictor");
    }

    @Test
    public void testFloatingPointVaryingBits() {
        assertRejected(
                BaselineTIFFTagSet.PREDICTOR_FLOATING_POINT,
                new int[] {32, 64},
                BaselineTIFFTagSet.SAMPLE_FORMAT_FLOATING_POINT,
                "Varying sample width is not supported for Floating point Predictor (first: 32, unexpected:64)");
    }

    @Test
    public void testFloatingPointIntegerFormat() {
        assertRejected(
                BaselineTIFFTagSet.PREDICTOR_FLOATING_POINT,
                new int[] {32},
                BaselineTIFFTagSet.SAMPLE_FORMAT_UNSIGNED_INTEGER,
                "Floating point Predictor not supported with 1 data format");
    }

    @Test
    public void testFloatingPointRowLength() throws Exception {
        PredictorDecompressor pd = decoder(BaselineTIFFTagSet.PREDICTOR_FLOATING_POINT, 4, 1, ByteOrder.LITTLE_ENDIAN);
        pd.validate();
        IIOException e = assertThrows(IIOException.class, () -> pd.decompress(new byte[6], 0, 1, 1, 6));
        assertEquals(
                "The number of bytes in a row (6) is not divisible by the number of bytes per pixel (4)",
                e.getMessage());
    }

    private static void assertRejected(int predictor, int[] bitsPerSample, int format, String message) {
        int[] sampleFormat = new int[bitsPerSample.length];
        Arrays.fill(sampleFormat, format);
        PredictorDecompressor pd = new PredictorDecompressor(
                predictor, bitsPerSample, sampleFormat, bitsPerSample.length, ByteOrder.LITTLE_ENDIAN);
        IIOException e = assertThrows(IIOException.class, pd::validate);
        assertEquals(message, e.getMessage());
    }

    private static void assertRoundTrip(int predictor, int bytes, int spp) throws Exception {
        Random rnd = new Random(20261004);
        for (ByteOrder order : ORDERS) {
            for (int w : WIDTHS) {
                for (int h : HEIGHTS) {
                    int rowBytes = w * spp * bytes;
                    byte[] image = new byte[PADDING + rowBytes * h + PADDING];
                    rnd.nextBytes(image);
                    byte[] encoded = image.clone();
                    for (int row = 0; row < h; row++) {
                        int offset = PADDING + row * rowBytes;
                        if (predictor == BaselineTIFFTagSet.PREDICTOR_FLOATING_POINT) {
                            encodeFloatingPoint(encoded, offset, w * spp, bytes, spp, order);
                        } else {
                            encodeHorizontal(encoded, offset, w * spp, bytes, spp, order);
                        }
                    }

                    decoder(predictor, bytes, spp, order).decompress(encoded, PADDING, h, w, rowBytes);

                    String where = "predictor=" + predictor + " bytes=" + bytes + " spp=" + spp + " order=" + order
                            + " w=" + w + " h=" + h;
                    assertArrayEquals(where, image, encoded);
                }
            }
        }
    }

    private static PredictorDecompressor decoder(int predictor, int bytes, int spp, ByteOrder order) {
        int[] bps = new int[spp];
        int[] sf = new int[spp];
        for (int b = 0; b < spp; b++) {
            bps[b] = bytes * 8;
            sf[b] = predictor == BaselineTIFFTagSet.PREDICTOR_FLOATING_POINT
                    ? BaselineTIFFTagSet.SAMPLE_FORMAT_FLOATING_POINT
                    : BaselineTIFFTagSet.SAMPLE_FORMAT_UNSIGNED_INTEGER;
        }
        return new PredictorDecompressor(predictor, bps, sf, spp, order);
    }

    /** Replaces each sample of a row with its difference from the same band in the previous pixel. */
    private static void encodeHorizontal(byte[] buf, int offset, int samples, int bytes, int spp, ByteOrder order) {
        ByteBuffer bb = ByteBuffer.wrap(buf).order(order);
        for (int i = samples - 1; i >= spp; i--) {
            int at = offset + i * bytes;
            putSample(bb, at, bytes, getSample(bb, at, bytes) - getSample(bb, at - spp * bytes, bytes));
        }
    }

    /**
     * Splits a row into byte planes, most significant byte first, then differences the bytes with a stride of one
     * pixel, as the floating point predictor of the TIFF Technical Note 3 does.
     */
    private static void encodeFloatingPoint(byte[] buf, int offset, int samples, int bytes, int spp, ByteOrder order) {
        byte[] planes = new byte[samples * bytes];
        for (int i = 0; i < samples; i++) {
            for (int k = 0; k < bytes; k++) {
                int fileByte = order == ByteOrder.BIG_ENDIAN ? k : bytes - 1 - k;
                planes[k * samples + i] = buf[offset + i * bytes + fileByte];
            }
        }
        for (int p = planes.length - 1; p >= spp; p--) {
            planes[p] -= planes[p - spp];
        }
        System.arraycopy(planes, 0, buf, offset, planes.length);
    }

    private static long getSample(ByteBuffer bb, int at, int bytes) {
        switch (bytes) {
            case 1:
                return bb.get(at);
            case 2:
                return bb.getShort(at);
            case 4:
                return bb.getInt(at);
            default:
                return bb.getLong(at);
        }
    }

    private static void putSample(ByteBuffer bb, int at, int bytes, long value) {
        switch (bytes) {
            case 1:
                bb.put(at, (byte) value);
                break;
            case 2:
                bb.putShort(at, (short) value);
                break;
            case 4:
                bb.putInt(at, (int) value);
                break;
            default:
                bb.putLong(at, value);
        }
    }

    @Test
    public void testEightBitMatchesReference() {
        int[] sppValues = {1, 2, 3, 4, 5};
        int[] widths = {1, 2, 7, 512};
        int[] heights = {1, 3, 33};
        Random rnd = new Random(20260724);
        for (int spp : sppValues) {
            for (int w : widths) {
                for (int h : heights) {
                    byte[] input = new byte[w * spp * h];
                    rnd.nextBytes(input);

                    byte[] expected = input.clone();
                    reference(expected, w, h, spp);

                    byte[] actual = input.clone();
                    int[] bps = new int[spp];
                    int[] sf = new int[spp];
                    for (int b = 0; b < spp; b++) {
                        bps[b] = 8;
                        sf[b] = BaselineTIFFTagSet.SAMPLE_FORMAT_UNSIGNED_INTEGER;
                    }
                    PredictorDecompressor pd = new PredictorDecompressor(
                            BaselineTIFFTagSet.PREDICTOR_HORIZONTAL_DIFFERENCING,
                            bps,
                            sf,
                            spp,
                            ByteOrder.LITTLE_ENDIAN);
                    try {
                        pd.decompress(actual, 0, h, w, w * spp);
                    } catch (Exception e) {
                        throw new AssertionError("decompress threw for spp=" + spp + " w=" + w + " h=" + h, e);
                    }

                    assertArrayEquals("spp=" + spp + " w=" + w + " h=" + h, expected, actual);
                }
            }
        }
    }

    /** The original straightforward 8-bit reversal, kept here as the reference. */
    private static void reference(byte[] buf, int srcWidth, int srcHeight, int samplesPerPixel) {
        for (int j = 0; j < srcHeight; j++) {
            int count = samplesPerPixel * (j * srcWidth + 1);
            for (int i = samplesPerPixel; i < srcWidth * samplesPerPixel; i++) {
                buf[count] += buf[count - samplesPerPixel];
                count++;
            }
        }
    }
}
