// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf.engine.script.test

import akka.actor.ActorSystem
import akka.http.scaladsl.Http.ServerBinding
import akka.stream.Materializer
import io.grpc.Channel
import java.io.File
import org.scalatest._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scalaz.{-\/, \/-}
import scalaz.syntax.traverse._
import spray.json._

import com.digitalasset.api.util.TimeProvider
import com.digitalasset.daml.bazeltools.BazelRunfiles._
import com.digitalasset.daml.lf.archive.DarReader
import com.digitalasset.daml.lf.archive.Decode
import com.digitalasset.daml.lf.data.Ref._
import com.digitalasset.daml.lf.engine.script._
import com.digitalasset.daml.lf.iface.EnvironmentInterface
import com.digitalasset.daml.lf.iface.reader.InterfaceReader
import com.digitalasset.daml.lf.speedy.SValue
import com.digitalasset.daml.lf.speedy.SValue._
import com.digitalasset.grpc.adapter.{AkkaExecutionSequencerPool, ExecutionSequencerFactory}
import com.digitalasset.http.HttpService
import com.digitalasset.jwt.JwtSigner
import com.digitalasset.jwt.domain.DecodedJwt
import com.digitalasset.ledger.api.domain.LedgerId
import com.digitalasset.ledger.api.refinements.ApiTypes.ApplicationId
import com.digitalasset.ledger.api.testing.utils.{
  OwnedResource,
  Resource => TestResource,
  SuiteResource,
  SuiteResourceManagementAroundAll,
  MockMessages,
}
import com.digitalasset.platform.common.LedgerIdMode
import com.digitalasset.platform.sandbox.{AbstractSandboxFixture, SandboxServer}
import com.digitalasset.platform.sandbox.config.SandboxConfig
import com.digitalasset.platform.sandbox.services.{GrpcClientResource, TestCommands}
import com.digitalasset.ports.Port
import com.digitalasset.resources.{Resource, ResourceOwner}

trait JsonApiFixture
    extends AbstractSandboxFixture
    with SuiteResource[(SandboxServer, Channel, ServerBinding)] {
  self: Suite =>

  override protected def darFile = new File(rlocation("daml-script/test/script-test.dar"))
  protected def server: SandboxServer = suiteResource.value._1
  override protected def serverPort: Port = server.port
  override protected def channel: Channel = suiteResource.value._2
  override protected def config: SandboxConfig =
    super.config
      .copy(ledgerIdMode = LedgerIdMode.Static(LedgerId("MyLedger")))
  def httpPort: Int = suiteResource.value._3.localAddress.getPort

  // We have to use a different actorsystem for the JSON API since package reloading
  // blocks everything so it will timeout as sandbox cannot make progres simultaneously.
  private val jsonApiActorSystem: ActorSystem = ActorSystem("json-api")
  private val jsonApiMaterializer: Materializer = Materializer(system)
  private val jsonApiExecutionSequencerFactory: ExecutionSequencerFactory =
    new AkkaExecutionSequencerPool(poolName = "json-api", actorCount = 1)

  override protected def afterAll(): Unit = {
    jsonApiExecutionSequencerFactory.close()
    materializer.shutdown()
    Await.result(jsonApiActorSystem.terminate(), 30.seconds)
    super.afterAll()
  }

  override protected lazy val suiteResource
    : TestResource[(SandboxServer, Channel, ServerBinding)] = {
    implicit val ec: ExecutionContext = system.dispatcher
    new OwnedResource[(SandboxServer, Channel, ServerBinding)](
      for {
        jdbcUrl <- database
          .fold[ResourceOwner[Option[String]]](ResourceOwner.successful(None))(_.map(info =>
            Some(info.jdbcUrl)))
        server <- SandboxServer.owner(config.copy(jdbcUrl = jdbcUrl))
        channel <- GrpcClientResource.owner(server.port)
        httpService <- new ResourceOwner[ServerBinding] {
          override def acquire()(implicit ec: ExecutionContext): Resource[ServerBinding] = {
            Resource[ServerBinding]({
              HttpService
                .start(
                  "localhost",
                  server.port.value,
                  ApplicationId(MockMessages.applicationId),
                  "localhost",
                  0,
                  None,
                  None)(
                  jsonApiActorSystem,
                  jsonApiMaterializer,
                  jsonApiExecutionSequencerFactory,
                  jsonApiActorSystem.dispatcher)
                .flatMap({
                  case -\/(e) => Future.failed(new IllegalStateException(e.toString))
                  case \/-(a) => Future.successful(a)
                })
            })((binding: ServerBinding) => binding.unbind().map(done => ()))
          }
        }
      } yield (server, channel, httpService)
    )
  }
}

final class JsonApiIt
    extends AsyncWordSpec
    with TestCommands
    with JsonApiFixture
    with Matchers
    with SuiteResourceManagementAroundAll
    with TryValues {

  private val dar = DarReader().readArchiveFromFile(darFile).get.map {
    case (pkgId, archive) => Decode.readArchivePayload(pkgId, archive)
  }
  private val ifaceDar = dar.map(pkg => InterfaceReader.readInterface(() => \/-(pkg))._2)
  private val envIface = EnvironmentInterface.fromReaderInterfaces(ifaceDar)

  def getToken(party: String): String = {
    val payload =
      s"""{"https://daml.com/ledger-api": {"ledgerId": "MyLedger", "applicationId": "foobar", "actAs": ["$party"]}}"""
    val header = """{"alg": "HS256", "typ": "JWT"}"""
    val jwt = DecodedJwt[String](header, payload)
    JwtSigner.HMAC256.sign(jwt, "secret") match {
      case -\/(e) => throw new IllegalStateException(e.toString)
      case \/-(a) => a.value
    }
  }

  private def getClients() = {
    val participantParams =
      Participants(Some(ApiParameters("http://localhost", httpPort)), Map.empty, Map.empty)
    Runner.jsonClients(participantParams, getToken(party), envIface)
  }

  private val party = "Alice"

  private def run(
      clients: Participants[ScriptLedgerClient],
      name: QualifiedName): Future[SValue] = {
    val scriptId = Identifier(packageId, name)
    Runner.run(
      dar,
      scriptId,
      Some(JsString(party)),
      clients,
      ApplicationId(MockMessages.applicationId),
      TimeProvider.UTC)
  }

  "DAML Script over JSON API" can {
    "Basic" should {
      "return 42" in {
        for {
          clients <- getClients
          result <- run(clients, QualifiedName.assertFromString("ScriptTest:jsonBasic"))
        } yield {
          assert(result == SInt64(42))
        }
      }
    }
    "CreateAndExercise" should {
      "return 42" in {
        for {
          clients <- getClients
          result <- run(clients, QualifiedName.assertFromString("ScriptTest:jsonCreateAndExercise"))
        } yield {
          assert(result == SInt64(42))
        }
      }
    }
    "ExerciseByKey" should {
      "return equal contract ids" in {
        for {
          clients <- getClients
          result <- run(clients, QualifiedName.assertFromString("ScriptTest:jsonExerciseByKey"))
        } yield {
          result match {
            case SRecord(_, _, vals) if vals.size == 2 =>
              assert(vals.get(0) == vals.get(1))
            case _ => fail(s"Expected Tuple2 but got $result")
          }
        }
      }
    }
  }
}