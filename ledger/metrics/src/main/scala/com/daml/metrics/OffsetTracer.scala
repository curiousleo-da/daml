// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.metrics

import com.daml.ledger.participant.state.v1.Offset
import io.opentelemetry.OpenTelemetry
import io.opentelemetry.common.Attributes
import io.opentelemetry.trace.Span

/**
  * Reports telemetry for the index DB -> Ledger API read path.
  */
object OffsetTracer {
  private val tracer = OpenTelemetry.getTracer("offset-tracer")
  private val spanCache = scala.collection.mutable.SortedMap[Offset, Span]()

  def observeHead(head: Offset): Unit = spanCache.synchronized {
    val span = tracer
      .spanBuilder("daml.participant.read_path.index_db_to_ledger_api")
      .setNoParent()
      .setAttribute(SpanAttribute.Offset.key, head.toHexString)
      .startSpan()
    val _ = spanCache.put(head, span)
  }

  def observeEnd(offset: Offset, attributeMap: Map[String, String]): Unit = spanCache.synchronized {
    val nextHead = spanCache.iteratorFrom(offset)
    if (nextHead.hasNext) {
      val (head, span) = nextHead.next()
      span.addEvent("send_to_client", {
        val attributes = Attributes.newBuilder()
        attributeMap.foreach { case (k, v) => attributes.setAttribute(k, v) }
        attributes.build()
      })
      if (head == offset) {
        span.end()
        val _ = spanCache.remove(head)
      }
    } else {
      println(s"observeEnd(${offset.toHexString}): no head found")
    }
  }
}
