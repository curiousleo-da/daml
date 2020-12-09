package com.daml.metrics

import java.util.concurrent.atomic.AtomicInteger

import io.opentelemetry.trace.Span

class StreamTracer(span: Span) {
  val pending = new AtomicInteger(0)

  def started(newPending: Int): Unit = {
    val _ = pending.addAndGet(newPending)
  }

  def finished(event: Event): Unit = {
    span.addEvent(event)
    if (pending.decrementAndGet() == 0) {
      span.end()
    }
  }
}
