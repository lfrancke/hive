/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.common;

/**
 * Models the list of transactions that should be included in a snapshot.
 * It is modelled as a high water mark, which is the largest transaction id that
 * has been committed and a list of transactions that are not included.
 */
public interface ValidTxnList {

  /**
   * Key used to store valid txn list in a
   * {@link org.apache.hadoop.conf.Configuration} object.
   */
  String VALID_TXNS_KEY = "hive.txn.valid.txns";

  /**
   * The response to a range query.  NONE means no values in this range match,
   * SOME mean that some do, and ALL means that every value does.
   */
  enum RangeResponse {NONE, SOME, ALL};

  /**
   * Indicates whether a given transaction has been committed and should be
   * viewed as valid for read.
   * @param txnid id for the transaction
   * @return true if committed, false otherwise
   */
  boolean isTxnCommitted(long txnid);

  /**
   * Find out if a range of transaction ids have been committed.
   * @param minTxnId minimum txnid to look for, inclusive
   * @param maxTxnId maximum txnid to look for, inclusive
   * @return Indicate whether none, some, or all of these transactions have been
   * committed.
   */
  RangeResponse isTxnRangeCommitted(long minTxnId, long maxTxnId);

  /**
   * Write this validTxnList into a string. This should produce a string that
   * can be used by {@link #readFromString(String)} to populate a validTxnsList.
   */
  String writeToString();

  /**
   * Populate this validTxnList from the string.  It is assumed that the string
   * was created via {@link #writeToString()}.
   * @param src source string.
   */
  void readFromString(String src);

  /**
   * Get the largest committed transaction id.
   * @return largest committed transaction id
   */
  long getHighWatermark();

  /**
   * Get the list of transactions under the high water mark that are still
   * open.
   * @return a list of open transaction ids
   */
  long[] getOpenTransactions();
}
