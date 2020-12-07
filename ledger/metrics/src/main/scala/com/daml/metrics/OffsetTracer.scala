// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.metrics

import java.util.concurrent.TimeUnit

import com.daml.ledger.participant.state.v1.Offset
import io.opentelemetry.OpenTelemetry
import io.opentelemetry.common.Attributes
import io.opentelemetry.trace.{EndSpanOptions, Span}

import scala.collection.mutable

/**
  * Reports telemetry for the index DB -> Ledger API read path.
  */
object OffsetTracer {
  private val tracer = OpenTelemetry.getTracer("offset-tracer")
  private val headWindow = mutable.SortedMap[Offset, (Span, Option[Long])]()

  def observeHead(head: Offset): Unit = headWindow.synchronized {
    val span = tracer
      .spanBuilder("daml.participant.read_path.index_db_to_ledger_api")
      .setNoParent()
      .setAttribute(SpanAttribute.Offset.key, head.toHexString)
      .startSpan()
    val _ = headWindow.put(head, (span, None))
  }

  def observeEnd(offset: Offset, attributeMap: Map[String, String]): Unit = headWindow.synchronized {
    val nextHead = headWindow.iteratorFrom(offset)
    if (nextHead.hasNext) {
      val (head, (span, end)) = nextHead.next()
      span.addEvent("send_to_client", {
        val attributes = Attributes.newBuilder()
        attributeMap.foreach { case (k, v) => attributes.setAttribute(k, v) }
        attributes.build()
      })
      end match {
        case Some(value) => headWindow.put(head, (span, Some(value.max(now))))
        case None if head == offset => headWindow.put(head, (span, Some(now)))
        case _ => ()
      }
    } else {
      println(s"observeEnd(${offset.toHexString}): no head found")
    }
    while (headWindow.size > 10) {
      val (span, end) = headWindow.remove(headWindow.firstKey).get
      end match {
        case Some(value) => span.end(EndSpanOptions.builder().setEndTimestamp(value).build())
        case None => println(s"span $span has not yet ended")
      }
    }
  }

  private def now: Long = {
    TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis())
  }
}
