package com.example

import com.example.fixture.RunningMysql
import com.examples.proto.api.vets_service.GetVetsRequest
import com.examples.proto.api.vets_service.Specialty
import com.examples.proto.api.vets_service.Vet
import com.examples.proto.api.vets_service.ZioVetsService
import io.grpc.ManagedChannelBuilder
import scalapb.zio_grpc.ZManagedChannel
import zio.ExitCode
import zio.Task
import zio.duration._
import zio.test.Assertion.hasSameElements
import zio.test.TestAspect._
import zio.test._
import zio.test.environment._

object VetsServiceSpec extends DefaultRunnableSpec {

  def spec =
    suite("VetsService")(
      testM("should return list of Vets") {

        val runServerInTheBackground = VetsServiceServer
          .run(List.empty)
          .flatMap {
            case ExitCode.failure => Task.fail(new IllegalStateException("App crashed"))
            case _                => Task.unit
          }
          .fork

        for {
          dbUser <- RunningMysql.username
          dbPassword <- RunningMysql.password
          dbUrl <- RunningMysql.jdbcUrl
          jdbcClassName <- RunningMysql.driverClassName
          _ <- TestSystem.putEnv("db.user", dbUser)
          _ <- TestSystem.putEnv("db.pass", dbPassword)
          _ <- TestSystem.putEnv("jdbc.url", dbUrl)
          _ <- TestSystem.putEnv("jdbc.driver.name", jdbcClassName)
          _ <- TestSystem.putEnv("server.port", "9000")
          _ <- runServerInTheBackground
          vets = ZioVetsService.VetsClient.getVets(GetVetsRequest()).map(_.vets)
          testResult <- assertM(vets)(
            hasSameElements(
              List(
                Vet(id = 1, firstName = "James", lastName = "Carter", specialties = List.empty),
                Vet(
                  id = 2,
                  firstName = "Helen",
                  lastName = "Leary",
                  specialties = List(Specialty("radiology"))
                ),
                Vet(
                  id = 3,
                  firstName = "Linda",
                  lastName = "Douglas",
                  specialties = List(Specialty("surgery"), Specialty("dentistry"))
                ),
                Vet(
                  id = 4,
                  firstName = "Rafael",
                  lastName = "Ortega",
                  specialties = List(Specialty("surgery"))
                ),
                Vet(
                  id = 5,
                  firstName = "Henry",
                  lastName = "Stevens",
                  specialties = List(Specialty("radiology"))
                ),
                Vet(id = 6, firstName = "Sharon", lastName = "Jenkins", specialties = List.empty)
              )
            )
          ).eventually
        } yield {
          testResult
        }
      } @@ timeout(25.seconds)
    ).provideCustomLayerShared(
      RunningMysql
        .live
        .and(
          ZioVetsService
            .VetsClient
            .live(
              ZManagedChannel(
                ManagedChannelBuilder.forAddress("localhost", 9000).usePlaintext()
              )
            )
        )
        .mapError(TestFailure.fail)
    )
}
