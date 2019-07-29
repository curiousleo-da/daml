// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.server.damlonx.services.backport

import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.daml.lf.data.Ref.Party
import com.digitalasset.ledger.api.domain.TransactionFilter
import com.digitalasset.ledger.api.v1.event.Event.Event.{Archived, Created}
import com.digitalasset.ledger.api.v1.event._
import com.digitalasset.ledger.api.v1.value
import com.digitalasset.platform.server.api.validation.FieldValidations._

import scala.collection.mutable.ArrayBuffer
import scala.collection.{breakOut, immutable}

object EventFilter {

  /**
    * Creates a filter which lets only such events through, where the template id is equal to the given one
    * and the interested party is affected.
    **/
  def byTemplates(transactionFilter: TransactionFilter): TemplateAwareFilter =
    TemplateAwareFilter(transactionFilter)

  final case class TemplateAwareFilter(transactionFilter: TransactionFilter) {

    def isSubmitterSubscriber(submitterParty: Party): Boolean =
      transactionFilter.filtersByParty.contains(submitterParty)

    private val subscribersByTemplateId: Map[Ref.Identifier, Set[Party]] = {
      val (specificSubscriptions, globalSubscriptions) = getSpecificAndGlobalSubscriptions(
        transactionFilter)
      specificSubscriptions
        .groupBy(_._1)
        .map { // Intentionally not using .mapValues to fully materialize the map
          case (templateId, pairs) =>
            val setOfParties: Set[Party] = pairs.map(_._2)(breakOut)
            templateId -> (setOfParties union globalSubscriptions)
        }
        .withDefaultValue(globalSubscriptions)
    }

    def filterEvent(event: Event): Option[Event] = {
      val servedEvent = event.event match {
        case Created(CreatedEvent(_, _, Some(templateId), _, _, _, _, _, _)) =>
          applyRequestingWitnesses(event, templateId)

        case Archived(ArchivedEvent(_, _, Some(templateId), _)) =>
          applyRequestingWitnesses(event, templateId)
        case _ => None
      }
      servedEvent
    }

    private def applyRequestingWitnesses(
        event: Event,
        templateId: value.Identifier): Option[Event] = {
      import com.digitalasset.platform.api.v1.event.EventOps._
      // The events are generated by the engine, then
      //  - we can assert identifier are always in `New Style`
      //  - witnesses are party
      val tid = validateIdentifier(templateId).fold(throw _, identity)
      val requestingWitnesses = event.witnesses.filter(e =>
        subscribersByTemplateId(tid).contains(Party.assertFromString(e)))
      if (requestingWitnesses.nonEmpty)
        Some(event.withWitnesses(requestingWitnesses))
      else
        None
    }

  }

  private def getSpecificAndGlobalSubscriptions(
      transactionFilter: TransactionFilter): (ArrayBuffer[(Ref.Identifier, Party)], Set[Party]) = {
    val specificSubscriptions = new ArrayBuffer[(Ref.Identifier, Party)]
    val globalSubscriptions = immutable.Set.newBuilder[Party]
    transactionFilter.filtersByParty.foreach {
      case (party, filters) =>
        filters.inclusive.fold[Unit] {
          globalSubscriptions += party
        } { inclusive =>
          inclusive.templateIds.foreach { tid =>
            specificSubscriptions += tid -> party
          }
        }
    }
    (specificSubscriptions, globalSubscriptions.result())
  }
}
