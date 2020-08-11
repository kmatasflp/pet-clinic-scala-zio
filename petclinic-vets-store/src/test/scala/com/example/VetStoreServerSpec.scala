package com.example

import zio.duration._
import zio.test._
import Assertion.hasSameElements
import zio.test.TestAspect._
import zio.test.environment._
import io.grpc.ManagedChannelBuilder
import scalapb.zio_grpc.ZManagedChannel
import com.examples.proto.api.vet_store.{ GetVetsRequest, Specialty, Vet, ZioVetStore }
import zio.Task
import com.dimafeng.testcontainers.MySQLContainer

import scala.jdk.CollectionConverters._
import zio.ZManaged

object VetStoreServerSpec extends DefaultRunnableSpec {

  private val mysqlC =
    Task {

      val mysql = MySQLContainer().configure { c =>
        c.withUsername("root")
        c.withPassword("")
        c.withInitScript("db/mysql/init.sql")
        c.withTmpFs(Map("/testtmpfs" -> "rw").asJava)
        c.withDatabaseName("petclinic")
      }

      mysql.start()

      mysql
    }

  def spec = suite("VetStoreServer")(
    testM("should return list of Vets") {

      val beforeAll = for {
        mc <- ZManaged.make(mysqlC)(m => Task(m.stop()).orDie)
        fiber <- ZManaged
          .make(
            for {
              _ <- TestSystem.putEnv("jdbc.driver.name", mc.driverClassName)
              _ <- TestSystem.putEnv("jdbc.url", mc.jdbcUrl)
              _ <- TestSystem.putEnv("db.user", mc.username)
              _ <- TestSystem.putEnv("db.pass", mc.password)
              f <- VetStoreServer.run(List.empty).forkDaemon
            } yield {
              f
            }
          )(_.interruptFork)
      } yield {
        fiber
      }

      beforeAll.use_ {
        val vets = ZioVetStore.VetsStoreClient.getVets(GetVetsRequest()).map(_.vets)

        assertM(vets)(
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
        ).provideCustomLayer(
            ZioVetStore
              .VetsStoreClient
              .live(
                ZManagedChannel(
                  ManagedChannelBuilder.forAddress("localhost", 9000).usePlaintext()
                )
              )
          )
          .eventually
      }
    } @@ timeout(15.seconds)
  )
}