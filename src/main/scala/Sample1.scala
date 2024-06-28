package com.suprnation

import actor.Actor.{Actor, Receive}
import actor.ActorRef.ActorRef
import actor.ActorSystem

import cats.effect.implicits._
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._

import scala.concurrent.duration._
import scala.language.postfixOps


trait JungleRequest

case class Tourist(name: String, bananaCount: Int) extends JungleRequest

case class TakeBanana(tourist: Tourist) extends JungleRequest

case object MonkeySleeping extends JungleRequest



class BananaSnatcher extends Actor[IO, JungleRequest] {
  override def receive: Receive[IO, JungleRequest] = {
    case TakeBanana(tourist) =>
      if (tourist.bananaCount > 0) {
        IO.println(s"🍌  ${context.self.path.name} stole a banana from ${tourist.name}!")
      } else {
        IO.println(s"🙅  ${context.self.path.name} is disappointed. No bananas left for ${tourist.name}!")
      }
    case _ => IO.unit
  }
}


class BananaGuardian(bananaSnatchers: List[ActorRef[IO, JungleRequest]]) extends Actor[IO, JungleRequest] {
  override def preStart: IO[Unit] =
    context.setReceiveTimeout(5.seconds, MonkeySleeping)

  override def receive: Receive[IO, JungleRequest] = {
    case tourist: Tourist =>
      IO.println(s"🐒  ${context.self.path.name} sees ${tourist.name} with ${tourist.bananaCount} bananas.") >>
        (if (tourist.bananaCount > 0) {
          bananaSnatchers.take(tourist.bananaCount) parTraverse_ (snatcher =>
            snatcher ! TakeBanana(tourist)
            )
        } else {
          IO.println(s"😪 ${context.self.path.name} is bored. No bananas for the monkeys!")
        })
    case MonkeySleeping =>
      IO.println(s"😴  ${context.self.path.name} is dozing off. Nothing is happening!")
    case _ => IO.unit
  }
}


trait TouristRequest

case class TouristArrival(name: String, bananaCount: Int) extends TouristRequest

case class TouristLeaving(name: String) extends TouristRequest


class TouristActor(bananaGuardian: ActorRef[IO, JungleRequest]) extends Actor[IO, TouristRequest] {

  override def receive: Receive[IO, TouristRequest] = {
    case TouristArrival(name, count) =>
      bananaGuardian ! Tourist(name, count)
    case TouristLeaving(name) =>
      IO.println(s"🚶‍${context.self.path.name} [$name] is leaving the jungle! ")
    case _ => IO.unit
  }
}

object JungleChaos extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {

    val actorSystemResource: Resource[IO, ActorSystem[IO]] = ActorSystem[IO]("JungleSystem")

    actorSystemResource.use { system =>
      for {
        bananaSnatchers <- (1 to 3).toList.map(i =>
          system.actorOf(new BananaSnatcher(), s"banana-snatcher-$i")
        ).sequence

        bananaGuardian <- system.actorOf(
          new BananaGuardian(bananaSnatchers), "banana-guardian"
        )

        touristActor1 <- system.actorOf(new TouristActor(bananaGuardian), "tourist-1")
        touristActor2 <- system.actorOf(new TouristActor(bananaGuardian), "tourist-2")
        touristActor3 <- system.actorOf(new TouristActor(bananaGuardian), "tourist-3")

        _ <- touristActor1 ! TouristArrival("Tourist-1", 3)
        _ <- IO.sleep(3 seconds)
        _ <- touristActor2 ! TouristArrival("Tourist-2", 2)
        _ <- IO.sleep(2 seconds)
        _ <- touristActor3 ! TouristArrival("Tourist-3", 1)
        _ <- system.waitForTermination
      } yield ExitCode.Success
    }
  }
}
