/*
 * Copyright (C) 2015-2017  The Async BigTable Authors.  All rights reserved.
 * This file is part of Async BigTable.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the StumbleUpon nor the names of its contributors
 *     may be used to endorse or promote products derived from this software
 *     without specific prior written permission.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.hbase.async;

import java.lang.IllegalArgumentException;
import java.util.Collection;
import java.util.List;

import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.util.Pair;

import com.google.common.collect.Lists;

/**
 * FuzzyRowFilter is a server-side fast-forward filter that allows skipping
 * whole range of rows when scanning. The feature is available in HBase
 * 0.94.5 and above.
 * <p>
 * It takes two byte array to match a rowkey, one to hold the fixed value
 * and one to hold a mask indicating which bytes of the rowkey must match the
 * fixed value. The two arrays must have the same length.
 * <p>
 * Bytes in the mask can take two values, 0 meaning that the corresponding byte
 * in the rowkey must match the corresponding fixed byte and 1 meaning that the
 * corresponding byte in the rowkey can take any value.
 * <p>
 * One can combine several {@link FuzzyFilterPair} to match multiple patterns at
 * once.
 * <p>
 * Example :
 * You store logs with this rowkey design :
 *   group(3bytes)timestamp(4bytes)severity(1byte)
 *
 * You want to get all FATAL("5") logs :
 *   * Build a FuzzyFilterPair with
 *     - rowkey     : "????????5"
 *     - fuzzy mask : "111111110"
 * And CRITICAL("4") logs only for web servers :
 *   * Add another FuzzyFilterPair with
 *     - rowkey     : "web????4"
 *     - fuzzy mask : "00011110"
 *
 * @since 1.7
 */
public final class FuzzyRowFilter extends ScanFilter {

  private static final byte[] NAME =
      Bytes.UTF8("org.apache.hadoop.hbase.filter.FuzzyRowFilter");

  private final Collection<FuzzyFilterPair> filter_pairs;

  /**
   * Holds a pair of (row_key,fuzzy_mask) to use with {@link FuzzyRowFilter}
   */
  public static class FuzzyFilterPair {
    private final byte[] row_key;
    private final byte[] fuzzy_mask;

    /** 
     * Default Ctor
     * @param row_key The row key to use for matching
     * @param fuzzy_mask The row key mask to determine which of the bytes in
     * {@code row_key} should be matched.
     * @throws IllegalArgumentException if the keys are not the same length
     */
    public FuzzyFilterPair(final byte[] row_key, final byte[] fuzzy_mask) {
      if ( row_key.length != fuzzy_mask.length ) {
        throw new IllegalArgumentException("Row key and fuzzy mask length " +
            "must match");
      }
      this.row_key = row_key;
      this.fuzzy_mask = fuzzy_mask;
    }

    /** @return the row key base filter */
    public byte[] getRowKey() {
      return row_key;
    }

    /** @return the row key mask */
    public byte[] getFuzzyMask() {
      return fuzzy_mask;
    }
  
    @Override
    public String toString() {
      final StringBuilder buf = new StringBuilder();
      buf.append("FuzzyFilterPair{row_key=")
         .append(Bytes.pretty(row_key))
         .append(", mask=")
         .append(Bytes.pretty(fuzzy_mask))
         .append("}");
      return buf.toString();
    }
  }

  /**
   * Default ctor that applies a single fuzzy filter against all row keys
   * @param filter_pair A single filter to apply to all row keys
   */
  public FuzzyRowFilter(final FuzzyFilterPair filter_pair) {
    this.filter_pairs = java.util.Collections.singleton(filter_pair);
  }

  /**
   * Ctor to take a list of filters to apply against row keys
   * @param filter_pairs One or more filter pairs in a collection to apply
   * against the row keys
   */
  public FuzzyRowFilter(final Collection<FuzzyFilterPair> filter_pairs) {
    this.filter_pairs = filter_pairs;
  }

  @Override
  byte[] name() {
    return NAME;
  }

  @Override
  public String toString() {
    final StringBuilder buf = new StringBuilder();
    buf.append("FuzzyFilter{")
       .append(filter_pairs)
       .append("}");
    return buf.toString();
  }

  @Override
  Filter getBigtableFilter() {
    List<Pair<byte[], byte[]>> pairs = Lists.newArrayListWithCapacity(filter_pairs.size());
    for (final FuzzyFilterPair pair : filter_pairs) {
      pairs.add(new Pair<byte[], byte[]>(pair.row_key, pair.fuzzy_mask));
    }
    return new org.apache.hadoop.hbase.filter.FuzzyRowFilter(pairs);
  }
  
}