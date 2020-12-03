// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.metrics

import java.time.Instant

import com.daml.ledger.participant.state.v1.Offset
import io.opentelemetry.OpenTelemetry

class OffsetTracerImpl extends OffsetTracer {
  private val tracer = OpenTelemetry.getTracer("offset-tracer")
  private val spanBuilder = () =>
    tracer
      .spanBuilder("daml.ledger.read.indexer")
      .setNoParent()

  private val clock = new NanoClock

//  private val logger = ContextualizedLogger.get(this.getClass)

//  private val cache: Cache[Offset, Instant] = CacheBuilder
//    .newBuilder()
//    .maximumSize(1024)
//    .removalListener(notification => {
//      notification.getCause match {
//        case RemovalCause.EXPLICIT => // Nothing to do
//        case RemovalCause.SIZE =>
////          logger.warn(s"Timestamp for offset ${notification.getKey} evicted due to size.")
//        case RemovalCause.REPLACED | RemovalCause.COLLECTED | RemovalCause.EXPIRED =>
//          // We don't do any of the things that could lead to these removal causes.
////          logger.error(
////            s"Timestamp for offset ${notification.getKey} evicted due to ${notification.getCause}.")
//      }
//    })
//    .build()

  private val cache = scala.collection.mutable.SortedMap[Offset, Instant]()

  def observeHead(head: Offset): Unit = cache.synchronized {
    val _ = cache.put(head, clock.instant())
  }

  def observeEnd(offset: Offset): Unit = cache.synchronized {
    val it = cache.iteratorFrom(offset)
    if (it.hasNext) {
      val (head, start) = it.next()
      spanBuilder()
        .setStartTimestamp(start.getEpochSecond * 1000 * 1000 * 1000 + start.getNano)
        .startSpan()
        .end()
      if (head == offset) {
        // We're done with this timestamp
        val _ = cache.remove(head)
      }
    } else {
      println(s"observeEnd(${offset.toHexString}): no head found")
    }
  }
}

object OffsetTracerImpl {
  def apply(): OffsetTracer = new OffsetTracerImpl()
}

class NanoClock(initialInstant: Instant = Instant.now(), initialNanos: Long = System.nanoTime()) {
  def instant(): Instant = initialInstant.plusNanos(System.nanoTime() - initialNanos)
}
