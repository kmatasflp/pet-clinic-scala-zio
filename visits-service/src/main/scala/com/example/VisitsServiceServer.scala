package com.example

import cats.instances.option._
import cats.syntax.apply._
import com.example.config.Configuration.DbConfig
import com.example.model.{DbTransactor, VisitDao}
import com.examples.proto.api.visits_service._
import com.google.protobuf.timestamp.Timestamp
import io.grpc.protobuf.services.ProtoReflectionService
import io.grpc.{ServerBuilder, Status}
import scalapb.zio_grpc.{Server, ServerLayer}
import zio._
import zio.console.putStrLn

import java.time.{Instant, LocalTime, ZoneOffset}

object VisitsService {

  val live: URLayer[VisitDao, Has[ZioVisitsService.Visits]] =
    ZLayer.fromService(dao =>
      new ZioVisitsService.Visits {
        def getVisitsForPet(request: GetVisitsForPetRequest): IO[Status, GetVisitsResponse] =
          dao
            .findByPetId(request.petId)
            .map(createGetVisitsResponse)
            .mapError(_ => Status.INTERNAL)

        def getVisitsForPets(request: GetVisitsForPetsRequest): IO[Status, GetVisitsResponse] =
          dao
            .findByPetIdIn(request.petIds)
            .map(createGetVisitsResponse)
            .mapError(_ => Status.INTERNAL)

        private val emptyCreateVisitResponse =
          IO(CreateVisitResponse()).mapError(_ => Status.INTERNAL)

        def createVisit(request: CreateVisitRequest): IO[Status, CreateVisitResponse] =
          request
            .visit
            .fold(emptyCreateVisitResponse)(v =>
              v.visitDate
                .fold(emptyCreateVisitResponse)(t =>
                  dao
                    .save(
                      model.Visit(
                        petId = v.petId,
                        visitDate = Instant
                          .ofEpochSecond(t.seconds, t.nanos)
                          .atZone(ZoneOffset.UTC)
                          .toLocalDate(),
                        description = v.description
                      )
                    )
                    .map(savedVisit => CreateVisitResponse(visit = Some(toServiceVisit(savedVisit))))
                    .mapError(_ => Status.INTERNAL)
                )
            )

        private def toServiceVisit(v: model.Visit): Visit =
          Visit(
            id = Some(VisitId(v.id)),
            petId = v.petId,
            visitDate = Some(
              Timestamp.of(
                seconds = v
                  .visitDate
                  .atTime(LocalTime.MIDNIGHT)
                  .atZone(ZoneOffset.UTC)
                  .toEpochSecond(),
                nanos = 0
              )
            ),
            description = v.description
          )

        private def createGetVisitsResponse(vs: List[com.example.model.Visit]) =
          GetVisitsResponse(visits = vs.map(toServiceVisit))

      }
    )
}

object VisitsServiceServer extends zio.App {

  def welcome: ZIO[ZEnv, Throwable, Unit] =
    putStrLn("Server is running. Press Ctrl-C to stop.")

  val app
      : ZIO[zio.system.System, SecurityException, ZLayer[zio.system.System with ZEnv, Throwable, Has[Server.Service]]] =
    system.envOrElse("server.port", "9001").map(_.toInt).map { port =>
      val builder = ServerBuilder.forPort(port).addService(ProtoReflectionService.newInstance())

      val server =
        ServerLayer.fromServiceLayer(builder)(VisitsService.live)

      val dbConf = ZLayer.fromEffect(
        ZIO
          .mapN(
            system.env("jdbc.driver.name"),
            system.env("jdbc.url"),
            system.env("db.user"),
            system.env("db.pass")
          )((_, _, _, _).mapN(DbConfig))
          .someOrFail(new NoSuchElementException)
      )

      ((dbConf >>> DbTransactor.live >>> VisitDao.mySql) ++ ZEnv.any) >>> server
    }

  def run(args: List[String]): zio.URIO[zio.ZEnv, ExitCode] =
    (welcome *> app.flatMap(_.build.useForever)).exitCode
}
