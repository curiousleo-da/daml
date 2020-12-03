// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.metrics

trait OffsetTracer {
  def observeHead(head: String): Unit

  def observeEnd(offset: String): Unit
}

class NoOpOffsetTracer extends OffsetTracer {
  override def observeHead(head: String): Unit = ()

  override def observeEnd(offset: String): Unit = ()
}
