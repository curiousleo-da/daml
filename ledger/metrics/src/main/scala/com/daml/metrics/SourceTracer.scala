package com.daml.metrics

import java.util.concurrent.atomic.AtomicInteger

import io.opentelemetry.trace.Span

/**
  * Wraps an already started span, adding events to it and ending it when there are no more pending items.
  *
  * @param span The wrapped [[Span]]
  */
class SourceTracer(span: Span) {
  val pending = new AtomicInteger(0)

  /**
    * Upstream items have been produced.
    *
    * @param newPending The number of items that have been produced
    */
  def produced(newPending: Int): Unit = {
    val _ = pending.addAndGet(newPending)
  }

  /**
    * Downstream item has been consumed.
    *
    * @param event Metadata for the consumption event
    */
  def consumed(event: Event): Unit = {
    span.addEvent(event)
    if (pending.decrementAndGet() == 0) {
      span.end()
    }
  }
}
