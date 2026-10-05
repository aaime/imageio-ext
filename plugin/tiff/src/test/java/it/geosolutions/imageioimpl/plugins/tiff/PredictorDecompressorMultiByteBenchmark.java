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

import it.geosolutions.imageio.plugins.tiff.BaselineTIFFTagSet;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.Ignore;
import org.junit.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Measures {@link PredictorDecompressor} on 16, 32 and 64 bit samples, for both byte orders and both predictors: one
 * band as in elevation models, three bands as in imagery.
 *
 * <p>Ignored for the same reasons as {@link PredictorDecompressorBenchmark}: run it by hand on an idle machine. One
 * operation decodes {@link PredictorDecompressorBenchmark#TOTAL_BYTES} of samples, so 48 ms/op is 1000 MB/s.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class PredictorDecompressorMultiByteBenchmark {

    @Param({"16", "32", "64"})
    public int bitsPerSample;

    @Param({"1", "3"})
    public int samplesPerPixel;

    @Param({"LITTLE_ENDIAN", "BIG_ENDIAN"})
    public String byteOrder;

    @Param({"HORIZONTAL", "FLOATING_POINT"})
    public String predictor;

    private byte[] buf;
    private int width;
    private int height;
    private int rowBytes;
    private PredictorDecompressor decompressor;

    /** Decodes in place and never resets, see {@link PredictorDecompressorBenchmark#setup}. */
    @Setup(Level.Trial)
    public void setup() {
        width = 4096;
        rowBytes = width * samplesPerPixel * bitsPerSample / 8;
        height = PredictorDecompressorBenchmark.TOTAL_BYTES / rowBytes;
        buf = new byte[rowBytes * height];
        new Random(20261004).nextBytes(buf);

        boolean floatingPoint = predictor.equals("FLOATING_POINT");
        int[] bits = new int[samplesPerPixel];
        Arrays.fill(bits, bitsPerSample);
        int[] format = new int[samplesPerPixel];
        Arrays.fill(
                format,
                floatingPoint
                        ? BaselineTIFFTagSet.SAMPLE_FORMAT_FLOATING_POINT
                        : BaselineTIFFTagSet.SAMPLE_FORMAT_UNSIGNED_INTEGER);
        decompressor = new PredictorDecompressor(
                floatingPoint
                        ? BaselineTIFFTagSet.PREDICTOR_FLOATING_POINT
                        : BaselineTIFFTagSet.PREDICTOR_HORIZONTAL_DIFFERENCING,
                bits,
                format,
                samplesPerPixel,
                byteOrder.equals("BIG_ENDIAN") ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
    }

    @Benchmark
    public byte[] decode() throws Exception {
        decompressor.decompress(buf, 0, 0, height, width, rowBytes);
        return buf;
    }

    @Test
    @Ignore
    public void runBenchmarks() throws Exception {
        new Runner(new OptionsBuilder().include(getClass().getName()).build()).run();
    }
}
