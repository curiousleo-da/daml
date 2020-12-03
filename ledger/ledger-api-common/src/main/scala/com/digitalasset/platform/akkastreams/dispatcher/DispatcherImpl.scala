// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.akkastreams.dispatcher

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.metrics.{Event, SpanAttribute}
import com.github.ghik.silencer.silent
import io.opentelemetry.OpenTelemetry
import io.opentelemetry.trace.Span
import org.slf4j.LoggerFactory

import scala.collection.immutable

final class DispatcherImpl[Index: Ordering](
    name: String,
    zeroIndex: Index,
    headAtInitialization: Index)
    extends Dispatcher[Index] {

  private val logger = LoggerFactory.getLogger(getClass)

  private val spanBuilder: Span.Builder = OpenTelemetry.getTracer("dispatcher-impl").spanBuilder("everything").setNoParent()

  private var span = spanBuilder.startSpan()

  private var lastSpan = Instant.now()

  require(
    !indexIsBeforeZero(headAtInitialization),
    s"head supplied at Dispatcher initialization $headAtInitialization is before zero index $zeroIndex. " +
      s"This would imply that the ledger end is before the ledger begin, which makes this invalid configuration."
  )

  private sealed abstract class State extends Product with Serializable {
    def getSignalDispatcher: Option[SignalDispatcher]

    def getLastIndex: Index
  }

  // the following silent are due to
  // <https://github.com/scala/bug/issues/4440>
  @silent("The outer reference in this type test cannot be checked at run time")
  private final case class Running(lastIndex: Index, signalDispatcher: SignalDispatcher)
      extends State {
    override def getLastIndex: Index = lastIndex

    override def getSignalDispatcher: Option[SignalDispatcher] = Some(signalDispatcher)
  }

  @silent("The outer reference in this type test cannot be checked at run time")
  private final case class Closed(lastIndex: Index) extends State {
    override def getLastIndex: Index = lastIndex

    override def getSignalDispatcher: Option[SignalDispatcher] = None
  }

  // So why not broadcast the actual new index, instead of using a signaller?
  // The reason is if we do that, the new indices race with readHead
  // in a way that makes it hard to start up new subscriptions. In particular,
  // we can tolerate NewIndexSignals being out of order or dropped, maintaining the weaker invariant that,
  // if head is updated, at least one NewIndexSignal eventually arrives.

  private val state = new AtomicReference[State](Running(headAtInitialization, SignalDispatcher()))

  /** returns the head index where this Dispatcher is at */
  override def getHead(): Index = state.get.getLastIndex

  /**
    * Signal to this Dispatcher that there's a new head `Index`.
    * The Dispatcher will emit values on all streams until the new head is reached.
    */
  override def signalNewHead(head: Index): Unit =
    state
      .getAndUpdate {
        case original @ Running(prev, disp) =>
          if (Ordering[Index].gt(head, prev)) Running(head, disp) else original
        case c: Closed => c
      } match {
      case Running(prev, disp) =>
        if (Ordering[Index].gt(head, prev)) {
          span.addEvent(Event("new-head", Map((SpanAttribute.Offset.key, head.toString.slice("Offset(Bytes(".length, "Offset(Bytes(000000000000048a0000000000000000".length)))))
          if (Instant.now().isAfter(lastSpan.plus(5, ChronoUnit.SECONDS))) {
            span.end()
            span = spanBuilder.startSpan()
            lastSpan = Instant.now()
          }
          disp.signal()
        }
      case _: Closed =>
        logger.debug(s"$name: Failed to update Dispatcher HEAD: instance already closed.")
    }

  // noinspection MatchToPartialFunction, ScalaUnusedSymbol
  override def startingAt[T](
      startExclusive: Index,
      subsource: SubSource[Index, T],
      endInclusive: Option[Index] = None): Source[(Index, T), NotUsed] =
    if (indexIsBeforeZero(startExclusive))
      Source.failed(
        new IllegalArgumentException(
          s"$name: Invalid start index: '$startExclusive' before zero index '$zeroIndex'"))
    else if (endInclusive.exists(Ordering[Index].gt(startExclusive, _)))
      Source.failed(
        new IllegalArgumentException(
          s"$name: Invalid index section: start '$startExclusive' is after end '$endInclusive'"))
    else {
//      val lastIndex = new AtomicReference[Index](startExclusive)
      val subscription = state.get.getSignalDispatcher.fold(Source.failed[Index](closedError))(
        _.subscribe(signalOnSubscribe = true)
        // This needs to call getHead directly, otherwise this subscription might miss a Signal being emitted
          .map(_ => getHead()))

      val withOptionalEnd =
        endInclusive.fold(subscription)(
          maxLedgerEnd =>
            // If we detect that the __signal__ (i.e. new ledger end updates) goes beyond the provided max ledger end,
            // we can complete the stream from the upstream direction and are not dependent on doing the filtering
            // on actual ledger entries (which might not even exist, e.g. due to duplicate submissions or
            // the ledger end having moved after a package upload or party allocation)
            subscription
            // accept ledger end signals until the signal exceeds the requested max ledger end.
            // the last offset that we should emit here is maxLedgerEnd-1, but we cannot do this
            // because here we only know that the Index type is order-able.
              .takeWhile(Ordering[Index].lt(_, maxLedgerEnd), inclusive = true)
              .map(Ordering[Index].min(_, maxLedgerEnd))
//              .wireTap(index => {
//                val _ = lastIndex.getAndUpdate(lastIndex =>
//                  if (Ordering[Index].gt(index, lastIndex)) {
//                    val span = OpenTelemetry
//                      .getTracer("pkv")
//                      .spanBuilder("starting-at-span2")
//                      .setNoParent()
//                      .setAttribute(SpanAttribute.Offset.key, index.toString.slice("Offset(Bytes(".length, "Offset(Bytes(000000000000048a0000000000000000".length))
//                      .startSpan()
//                    Spans.addEventToCurrentSpan(Event("starting-at2", Map(("daml.offset", index.toString.slice("Offset(Bytes(".length, "Offset(Bytes(000000000000048a0000000000000000".length)))))
//                    span.end()
//                    index
//                  } else {
//                    lastIndex
//                  })
//              })
        )

      withOptionalEnd
        .statefulMapConcat(() => new ContinuousRangeEmitter(startExclusive))
        .flatMapConcat {
          case (previousHead, head) => subsource(previousHead, head)
        }
        .wireTap(_ match {
          case (index, _) =>
            span.addEvent(Event("subsource", Map((SpanAttribute.Offset.key, index.toString.slice("Offset(Bytes(".length, "Offset(Bytes(000000000000048a0000000000000000".length)))))
        })
//        .wireTap(_ match {
//          case (index, _) =>
//            val span = OpenTelemetry
//              .getTracer("pkv")
//              .spanBuilder("starting-at-span3")
//              .setNoParent()
//              .setAttribute(SpanAttribute.Offset.key, index.toString.slice("Offset(Bytes(".length, "Offset(Bytes(000000000000048a0000000000000000".length))
//              .startSpan()
//            Spans.addEventToCurrentSpan(Event("starting-at3", Map(("daml.offset", index.toString.slice("Offset(Bytes(".length, "Offset(Bytes(000000000000048a0000000000000000".length)))))
//            span.end()
//        })
    }

  private class ContinuousRangeEmitter(private var max: Index) // var doesn't need to be synchronized, it is accessed in a GraphStage.
      extends (Index => immutable.Iterable[(Index, Index)]) {

    /**
      * @return if param  > [[max]] : a list with a single pair representing a ([max, param[) range, and also stores param in [[max]].
      *         Nil otherwise.
      */
    override def apply(newHead: Index): immutable.Iterable[(Index, Index)] =
      if (Ordering[Index].gt(newHead, max)) {
        val intervalBegin = max
        max = newHead
        List(intervalBegin -> newHead)
      } else Nil
  }

  private def indexIsBeforeZero(checkedIndex: Index): Boolean =
    Ordering[Index].gt(zeroIndex, checkedIndex)

  def close(): Unit =
    state.getAndUpdate {
      case Running(idx, _) => Closed(idx)
      case c: Closed => c
    } match {
      case Running(_, disp) =>
        disp.signal()
        disp.close()
        span.end()
      case _: Closed => ()
    }

  private def closedError: IllegalStateException =
    new IllegalStateException(s"$name: Dispatcher is closed")

}
