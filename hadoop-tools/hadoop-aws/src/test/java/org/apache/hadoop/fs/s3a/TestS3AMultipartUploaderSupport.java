/*
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

package org.apache.hadoop.fs.s3a;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.test.HadoopTestBase;
import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;

import static org.apache.hadoop.fs.s3a.S3AMultipartUploader.buildPartHandlePayload;
import static org.apache.hadoop.fs.s3a.S3AMultipartUploader.parsePartHandlePayload;
import static org.apache.hadoop.test.LambdaTestUtils.intercept;

/**
 * Test multipart upload support methods and classes.
 */
public class TestS3AMultipartUploaderSupport extends HadoopTestBase {

  @Test
  public void testRoundTrip() throws Throwable {
    Pair<Long, Pair<Integer, String>> result = roundTrip("tag", 1, 1);
    assertEquals("tag", result.getRight().getRight());
    assertEquals(1, result.getRight().getLeft().intValue());
    assertEquals(1, result.getLeft().longValue());
  }

  @Test
  public void testRoundTrip2() throws Throwable {
    long len = 1L + Integer.MAX_VALUE;
    Pair<Long, Pair<Integer, String>> result = roundTrip("11223344", 1, len);
    assertEquals("11223344", result.getRight().getRight());
    assertEquals(1, result.getRight().getLeft().intValue());
    assertEquals(len, result.getLeft().longValue());
  }

  @Test
  public void testNoEtag() throws Throwable {
    intercept(IllegalArgumentException.class,
        () -> buildPartHandlePayload("", 1, 1));
  }

  @Test
  public void testNoLen() throws Throwable {
    intercept(IllegalArgumentException.class,
        () -> buildPartHandlePayload("tag", 1, -1));
  }

  @Test
  public void testBadPayload() throws Throwable {
    intercept(EOFException.class,
        () -> parsePartHandlePayload(new byte[0]));
  }

  @Test
  public void testBadHeader() throws Throwable {
    byte[] bytes = buildPartHandlePayload("tag", 1, 1);
    bytes[2]='f';
    intercept(IOException.class, "header",
        () -> parsePartHandlePayload(bytes));
  }

  private Pair<Long, Pair<Integer, String>> roundTrip(final String tag, final int partNumber, final long len) throws
          IOException {
    byte[] bytes = buildPartHandlePayload(tag, partNumber, len);
    return parsePartHandlePayload(bytes);
  }
}
