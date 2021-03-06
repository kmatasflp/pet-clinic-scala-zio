package com.example.model

import com.example.fixture.{RunningMysql, mysqlDbConf}
import zio.test.Assertion.hasSameElements
import zio.test.environment.TestEnvironment
import zio.test.{DefaultRunnableSpec, _}

import java.time.LocalDate

object PetDaoSpec extends DefaultRunnableSpec {

  def spec: Spec[TestEnvironment, TestFailure[Throwable], TestSuccess] =
    suite("PetDao.mySql")(
      testM("should return a pet from mysql db") {
        assertM(PetDao.findById(7))(
          hasSameElements(
            List(
              (
                Pet(
                  id = 7,
                  name = "Samantha",
                  birthDate = LocalDate.of(1995, 9, 4),
                  typeId = 1,
                  ownerId = 6
                ),
                PetType(id = 1, name = "cat"),
                Owner(
                  id = 6,
                  firstName = "Jean",
                  lastName = "Coleman",
                  address = "105 N. Lake St.",
                  city = "Monona",
                  telephone = "6085552654"
                )
              )
            )
          )
        )
      },
      testM("should return pet types from mysql db") {
        assertM(PetDao.getPetTypes)(
          hasSameElements(
            List(
              PetType(id = 1, name = "cat"),
              PetType(id = 2, name = "dog"),
              PetType(id = 3, name = "lizard"),
              PetType(id = 4, name = "snake"),
              PetType(id = 5, name = "bird"),
              PetType(id = 6, name = "hamster")
            )
          )
        )
      },
      testM("should insert pet to mysql db") {
        PetDao
          .save(
            Pet(
              name = "Ghost",
              birthDate = LocalDate.of(2020, 1, 5),
              typeId = 2,
              ownerId = 6
            )
          )
          .flatMap(p =>
            assertM(PetDao.findById(p.id))(
              hasSameElements(
                List(
                  (
                    Pet(
                      id = 14,
                      name = "Ghost",
                      birthDate = LocalDate.of(2020, 1, 5),
                      typeId = 2,
                      ownerId = 6
                    ),
                    PetType(id = 2, name = "dog"),
                    Owner(
                      id = 6,
                      firstName = "Jean",
                      lastName = "Coleman",
                      address = "105 N. Lake St.",
                      city = "Monona",
                      telephone = "6085552654"
                    )
                  )
                )
              )
            )
          )
      }
    ).provideCustomLayerShared(
      (RunningMysql.live >>> mysqlDbConf >>> DbTransactor.live >>> PetDao.mySql)
        .mapError(TestFailure.fail)
    )

}
