package com.daml.metrics

import com.daml.logging.{ContextualizedLogger, LoggingContext}
import com.google.common.cache.{CacheBuilder, CacheLoader, RemovalCause}
import io.opentelemetry.trace.Span

class OffsetTracer[K](spanBuilder: Span.Builder)(implicit loggingCtx: LoggingContext) {

  private val logger = ContextualizedLogger.get(this.getClass)

  private val cache = CacheBuilder.newBuilder()
    .maximumSize(200)
    .removalListener[K, Span](notification => {
      notification.getCause match {
        case RemovalCause.EXPLICIT => // Nothing to do
        case RemovalCause.SIZE =>
          logger.warn(s"Span for offset ${notification.getKey} evicted due to size.")
          notification.getValue.end()
        case RemovalCause.REPLACED | RemovalCause.COLLECTED | RemovalCause.EXPIRED =>
          // We don't do any of the things that could lead to these removal causes.
          logger.error(s"Span for offset ${notification.getKey} evicted due to ${notification.getCause}.")
      }
    })
    .build[K, Span](new CacheLoader[K, Span] {
      override def load(key: K): Span = spanBuilder.startSpan()
    })

  def addEvent(key: K, event: Event): Unit =
    cache.get(key).addEvent(event)

  def setAttribute(key: K, attribute: SpanAttribute, value: String): Unit =
    cache.get(key).setAttribute(attribute.key, value)

  def end(key: K): Unit = {
    val span = cache.asMap().remove(key)
    if (span != null) {
      span.end()
    }
  }
}
