// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.metrics

import com.daml.ledger.participant.state.v1.Offset

class OffsetTracerImpl extends OffsetTracer {

//  private val logger = ContextualizedLogger.get(this.getClass)

//  private val cache: LoadingCache[K, Span] = CacheBuilder
//    .newBuilder()
//    .maximumSize(200)
//    .removalListener[K, Span](notification => {
//      notification.getCause match {
//        case RemovalCause.EXPLICIT => // Nothing to do
//        case RemovalCause.SIZE =>
//          logger.warn(s"Span for offset ${notification.getKey} evicted due to size.")
//          notification.getValue.end()
//        case RemovalCause.REPLACED | RemovalCause.COLLECTED | RemovalCause.EXPIRED =>
//          // We don't do any of the things that could lead to these removal causes.
//          logger.error(
//            s"Span for offset ${notification.getKey} evicted due to ${notification.getCause}.")
//      }
//    })
//    .build[K, Span](new CacheLoader[K, Span] {
//      override def load(key: K): Span = spanBuilder.startSpan()
//    })

  def observeHead(key: Offset): Unit = {}

  def observeEnd(key: Offset): Unit = {}
}

object OffsetTracerImpl {
  def apply[K](): OffsetTracer = new OffsetTracerImpl()
}
