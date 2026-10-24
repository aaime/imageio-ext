/*
 *    ImageI/O-Ext - OpenSource Java Image translation Library
 *    http://www.geo-solutions.it/
 *    https://github.com/geosolutions-it/imageio-ext
 *    (C) 2021, GeoSolutions
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
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL GeoSolutions BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package it.geosolutions.imageioimpl.plugins.tiff;

import it.geosolutions.imageio.plugins.tiff.BaselineTIFFTagSet;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import javax.imageio.IIOException;

/** Class applying the Predictor algorithm to restore the data from its compressed form. */
public class PredictorDecompressor {

    /**
     * Read and write a whole sample at any offset of a byte[]. The JIT turns each access into one load or store of the
     * sample size, where reading byte by byte takes one per byte. They use the native byte order: {@link #swap} tells
     * when the file uses the other one.
     */
    private static final VarHandle SHORT_VIEW =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.nativeOrder());

    private static final VarHandle INT_VIEW =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());

    private static final VarHandle LONG_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

    /** predictor's type */
    private final int predictor;

    private int[] bitsPerSample;
    private int[] sampleFormat;
    private int samplesPerPixel;
    private ByteOrder byteOrder;

    /** True when the file byte order is not the native one, so samples read through the views need reversing. */
    private final boolean swap;

    public PredictorDecompressor(
            int predictor, int[] bitsPerSample, int[] sampleFormat, int samplesPerPixel, ByteOrder byteOrder) {
        this.predictor = predictor;
        this.bitsPerSample = bitsPerSample;
        this.sampleFormat = sampleFormat;
        this.samplesPerPixel = samplesPerPixel;
        this.byteOrder = byteOrder;
        this.swap = byteOrder != ByteOrder.nativeOrder();
    }

    /** Reverses the predictor in place on srcHeight rows of bytesPerRow bytes, starting at bufOffset in buf. */
    public void decompress(byte[] buf, int bufOffset, int srcHeight, int srcWidth, int bytesPerRow)
            throws IIOException {
        if (predictor == BaselineTIFFTagSet.PREDICTOR_HORIZONTAL_DIFFERENCING) {
            if (bitsPerSample[0] == 8) {
                reverseByteDifferencing(buf, bufOffset, srcHeight, srcWidth * samplesPerPixel);
            } else if (bitsPerSample[0] == 16) {
                reverseDifferencingShorts(
                        buf, bufOffset, srcHeight, srcWidth * samplesPerPixel * 2, samplesPerPixel * 2);
            } else if (bitsPerSample[0] == 32) {
                reverseDifferencingInts(buf, bufOffset, srcHeight, srcWidth * samplesPerPixel * 4, samplesPerPixel * 4);
            } else if (bitsPerSample[0] == 64) {
                reverseDifferencingLongs(
                        buf, bufOffset, srcHeight, srcWidth * samplesPerPixel * 8, samplesPerPixel * 8);
            } else
                throw new IIOException(
                        "Unexpected branch of Horizontal differencing Predictor, bps=" + bitsPerSample[0]);
        } else if (predictor == BaselineTIFFTagSet.PREDICTOR_FLOATING_POINT) {
            int bytesPerSample = bitsPerSample[0] / 8;
            if (bytesPerRow % (bytesPerSample * samplesPerPixel) != 0) {
                throw new IIOException("The number of bytes in a row (" + bytesPerRow + ") is not divisible"
                        + " by the number of bytes per pixel (" + bytesPerSample * samplesPerPixel + ")");
            }

            byte[] planes = new byte[bytesPerRow];
            int samplesPerRow = srcWidth * samplesPerPixel;
            for (int j = 0; j < srcHeight; j++) {
                int offset = bufOffset + j * bytesPerRow;
                reverseByteDifferencing(buf, offset, 1, bytesPerRow);
                System.arraycopy(buf, offset, planes, 0, bytesPerRow);
                interleavePlanes(planes, buf, offset, samplesPerRow, bytesPerSample);
            }
        }
    }

    /** Reverses 8-bit differencing between the same band of consecutive pixels, used by both predictors. */
    private void reverseByteDifferencing(byte[] buf, int offset, int rows, int rowBytes) {
        if (samplesPerPixel == 1) {
            reverseDifferencing(buf, offset, rows, rowBytes, samplesPerPixel);
        } else if (samplesPerPixel == 2) {
            reverseDifferencingTwoBands(buf, offset, rows, rowBytes, samplesPerPixel);
        } else if (samplesPerPixel == 3) {
            reverseDifferencingThreeBands(buf, offset, rows, rowBytes, samplesPerPixel);
        } else if (samplesPerPixel == 4) {
            reverseDifferencingFourBands(buf, offset, rows, rowBytes);
        } else {
            reverseDifferencingInterleaved(buf, offset, rows, rowBytes, samplesPerPixel);
        }
    }

    /**
     * Rebuilds the samples of one floating point predictor row from its byte planes. The planes hold the most
     * significant byte of every sample first, then the next one, and so on (TIFF Technical Note 3). Each 2, 4 and 8
     * byte sample is assembled as a value and stored with one write, other sizes byte by byte.
     */
    private void interleavePlanes(byte[] planes, byte[] buf, int offset, int n, int bytesPerSample) {
        switch (bytesPerSample) {
            case 2:
                for (int i = 0; i < n; i++) {
                    short v = (short) ((planes[i] << 8) | (planes[n + i] & 0xFF));
                    SHORT_VIEW.set(buf, offset + i * 2, swap ? Short.reverseBytes(v) : v);
                }
                break;
            case 4:
                for (int i = 0; i < n; i++) {
                    int v = (planes[i] << 24)
                            | (planes[n + i] & 0xFF) << 16
                            | (planes[2 * n + i] & 0xFF) << 8
                            | (planes[3 * n + i] & 0xFF);
                    INT_VIEW.set(buf, offset + i * 4, swap ? Integer.reverseBytes(v) : v);
                }
                break;
            case 8:
                for (int i = 0; i < n; i++) {
                    long v = 0;
                    for (int k = 0; k < 8; k++) {
                        v = (v << 8) | (planes[k * n + i] & 0xFF);
                    }
                    LONG_VIEW.set(buf, offset + i * 8, swap ? Long.reverseBytes(v) : v);
                }
                break;
            default:
                boolean bigEndian = byteOrder == ByteOrder.BIG_ENDIAN;
                for (int i = 0; i < n; i++) {
                    for (int k = 0; k < bytesPerSample; k++) {
                        int plane = bigEndian ? k : bytesPerSample - k - 1;
                        buf[offset + i * bytesPerSample + k] = planes[plane * n + i];
                    }
                }
        }
    }

    /**
     * Reverses 8-bit horizontal differencing on single band rows, in place.
     *
     * <p>The running value stays in acc rather than being read back from the array. stride is always 1 here, but it is
     * taken as an argument on purpose: written as a literal 1 the same loop measured four times slower, the JIT
     * evidently choosing a worse shape for it. byte addition wraps mod 256, which is the predictor's semantics.
     */
    private static void reverseDifferencing(byte[] buf, int bufOffset, int srcHeight, int rowSamples, int stride) {
        for (int j = 0; j < srcHeight; j++) {
            final int rowStart = bufOffset + j * rowSamples;
            final int rowEnd = rowStart + rowSamples;
            byte acc = buf[rowStart];
            for (int p = rowStart + stride; p < rowEnd; p += stride) {
                acc += buf[p];
                buf[p] = acc;
            }
        }
    }

    /**
     * Reverses 8-bit horizontal differencing on band interleaved rows, in place.
     *
     * <p>Each band has its own prefix sum, as in {@link #reverseDifferencing}, but the row is walked once in address
     * order instead of once per band: a walk of stride samplesPerPixel reads every cache line samplesPerPixel times to
     * use 64/samplesPerPixel bytes of it each time, and that measured about twice as slow. Consecutive samples belong
     * to different bands, so the accumulators in acc still form independent dependency chains; acc is samplesPerPixel
     * bytes wide and stays in L1.
     */
    private static void reverseDifferencingInterleaved(
            byte[] buf, int bufOffset, int srcHeight, int rowSamples, int samplesPerPixel) {
        final byte[] acc = new byte[samplesPerPixel];
        for (int j = 0; j < srcHeight; j++) {
            final int rowStart = bufOffset + j * rowSamples;
            final int rowEnd = rowStart + rowSamples;
            System.arraycopy(buf, rowStart, acc, 0, samplesPerPixel);
            int band = 0;
            for (int p = rowStart + samplesPerPixel; p < rowEnd; p++) {
                byte v = (byte) (acc[band] + buf[p]);
                acc[band] = v;
                buf[p] = v;
                if (++band == samplesPerPixel) band = 0;
            }
        }
    }

    /**
     * Reverses 8-bit horizontal differencing on three band rows, in place, a whole pixel per step with one running sum
     * per band in a local. stride is always 3, taken as an argument for the same reason as in
     * {@link #reverseDifferencing}.
     */
    private static void reverseDifferencingThreeBands(
            byte[] buf, int bufOffset, int srcHeight, int rowSamples, int stride) {
        for (int j = 0; j < srcHeight; j++) {
            final int rowStart = bufOffset + j * rowSamples;
            final int rowEnd = rowStart + rowSamples;
            byte b0 = buf[rowStart];
            byte b1 = buf[rowStart + 1];
            byte b2 = buf[rowStart + 2];
            for (int p = rowStart + stride; p < rowEnd; p += stride) {
                b0 += buf[p];
                b1 += buf[p + 1];
                b2 += buf[p + 2];
                buf[p] = b0;
                buf[p + 1] = b1;
                buf[p + 2] = b2;
            }
        }
    }

    /**
     * Reverses 8-bit horizontal differencing on four band interleaved rows, in place, a whole pixel per step. The pixel
     * is read as an int and added byte by byte with no carry between bytes, so the four band sums take one int add.
     * Byte order does not matter, as every byte is summed on its own.
     */
    private static void reverseDifferencingFourBands(byte[] buf, int bufOffset, int srcHeight, int rowSamples) {
        for (int j = 0; j < srcHeight; j++) {
            final int rowStart = bufOffset + j * rowSamples;
            final int rowEnd = rowStart + rowSamples;
            int acc = (int) INT_VIEW.get(buf, rowStart);
            for (int p = rowStart + 4; p < rowEnd; p += 4) {
                int v = (int) INT_VIEW.get(buf, p);
                // low 7 bits of each byte add without leaving the byte, the top bit is the xor of the two top bits
                acc = ((acc & 0x7F7F7F7F) + (v & 0x7F7F7F7F)) ^ ((acc ^ v) & 0x80808080);
                INT_VIEW.set(buf, p, acc);
            }
        }
    }

    /**
     * Reverses 8-bit horizontal differencing on two band rows, as {@link #reverseDifferencingFourBands} on a short.
     * stride is always 2, taken as an argument for the same reason as in {@link #reverseDifferencing}: with a literal 2
     * the loop measured five times slower.
     */
    private static void reverseDifferencingTwoBands(
            byte[] buf, int bufOffset, int srcHeight, int rowSamples, int stride) {
        for (int j = 0; j < srcHeight; j++) {
            final int rowStart = bufOffset + j * rowSamples;
            final int rowEnd = rowStart + rowSamples;
            short acc = (short) SHORT_VIEW.get(buf, rowStart);
            for (int p = rowStart + stride; p < rowEnd; p += stride) {
                short v = (short) SHORT_VIEW.get(buf, p);
                acc = (short) (((acc & 0x7F7F) + (v & 0x7F7F)) ^ ((acc ^ v) & 0x8080));
                SHORT_VIEW.set(buf, p, acc);
            }
        }
    }

    /**
     * Reverses 16-bit horizontal differencing in place, one band at a time with its running sum in a local. Reading the
     * previous sample back from the array, as {@link #reverseDifferencingInts} does, measured from 6 to 95 ms on the
     * same input depending on the JIT's choices. With {@link #swap}, samples are reversed to their value before the add
     * and back before the write.
     */
    private void reverseDifferencingShorts(byte[] buf, int offset, int rows, int rowBytes, int pixelBytes) {
        for (int j = 0; j < rows; j++) {
            int rowStart = offset + j * rowBytes;
            int rowEnd = rowStart + rowBytes;
            for (int band = rowStart; band < rowStart + pixelBytes; band += 2) {
                short first = (short) SHORT_VIEW.get(buf, band);
                short acc = swap ? Short.reverseBytes(first) : first;
                for (int p = band + pixelBytes; p < rowEnd; p += pixelBytes) {
                    short curr = (short) SHORT_VIEW.get(buf, p);
                    acc += swap ? Short.reverseBytes(curr) : curr;
                    SHORT_VIEW.set(buf, p, swap ? Short.reverseBytes(acc) : acc);
                }
            }
        }
    }

    /**
     * Reverses 32-bit horizontal differencing in place. With {@link #swap}, samples are reversed to their value before
     * the add and back after it.
     */
    private void reverseDifferencingInts(byte[] buf, int offset, int rows, int rowBytes, int pixelBytes) {
        for (int j = 0; j < rows; j++) {
            int rowStart = offset + j * rowBytes;
            int rowEnd = rowStart + rowBytes;
            for (int p = rowStart + pixelBytes; p < rowEnd; p += 4) {
                int prev = (int) INT_VIEW.get(buf, p - pixelBytes);
                int curr = (int) INT_VIEW.get(buf, p);
                int sum = swap
                        ? Integer.reverseBytes(Integer.reverseBytes(prev) + Integer.reverseBytes(curr))
                        : prev + curr;
                INT_VIEW.set(buf, p, sum);
            }
        }
    }

    /** Reverses 64-bit horizontal differencing in place, as {@link #reverseDifferencingInts}. */
    private void reverseDifferencingLongs(byte[] buf, int offset, int rows, int rowBytes, int pixelBytes) {
        for (int j = 0; j < rows; j++) {
            int rowStart = offset + j * rowBytes;
            int rowEnd = rowStart + rowBytes;
            for (int p = rowStart + pixelBytes; p < rowEnd; p += 8) {
                long prev = (long) LONG_VIEW.get(buf, p - pixelBytes);
                long curr = (long) LONG_VIEW.get(buf, p);
                long sum = swap ? Long.reverseBytes(Long.reverseBytes(prev) + Long.reverseBytes(curr)) : prev + curr;
                LONG_VIEW.set(buf, p, sum);
            }
        }
    }

    /** Validate the current predictor setup */
    public void validate() throws IIOException {
        // Check bitsPerSample.
        if (predictor == BaselineTIFFTagSet.PREDICTOR_HORIZONTAL_DIFFERENCING) {
            int len = bitsPerSample.length;
            final int bps = bitsPerSample[0];
            if (bps != 8 && bps != 16 && bps != 32 && bps != 64) {
                throw new IIOException(
                        bps + "-bit samples " + "are not supported for Horizontal " + "differencing Predictor");
            }
            for (int i = 0; i < len; i++) {
                if (bitsPerSample[i] != bps) {
                    throw new IIOException("Varying sample width is not " + "supported for Horizontal "
                            + "differencing Predictor (first: "
                            + bps
                            + ", unexpected:" + bitsPerSample[i] + ")");
                }
            }
        } else if (predictor == BaselineTIFFTagSet.PREDICTOR_FLOATING_POINT) {
            int len = bitsPerSample.length;
            final int bps = bitsPerSample[0];
            if (bps != 16 && bps != 24 && bps != 32 && bps != 64) {
                throw new IIOException(bps + "-bit samples " + "are not supported for Floating " + "point Predictor");
            }
            for (int i = 0; i < len; i++) {
                if (bitsPerSample[i] != bps) {
                    throw new IIOException("Varying sample width is not " + "supported for Floating "
                            + "point Predictor (first: "
                            + bps
                            + ", unexpected:" + bitsPerSample[i] + ")");
                }
            }
            for (int sf : sampleFormat) {
                if (sf != BaselineTIFFTagSet.SAMPLE_FORMAT_FLOATING_POINT) {
                    throw new IIOException("Floating point Predictor not supported with " + sf + " data format");
                }
            }
        }
    }
}
