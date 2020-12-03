// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.metrics

import com.daml.ledger.participant.state.v1.Offset

trait OffsetTracer {
  def observeHead(head: Offset): Unit

  def observeEnd(offset: Offset): Unit
}

class NoOpOffsetTracer extends OffsetTracer {
  override def observeHead(head: Offset): Unit = ()

  override def observeEnd(offset: Offset): Unit = ()
}
