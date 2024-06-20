package com.suprnation

import actor.Actor.Receive
import actor.fsm.FSM.Event
import actor.fsm.{FSM, FSMConfig}
import actor.props.{Props, PropsF}
import actor.{Actor, ActorRef, ActorSystem}

import cats.effect.{ExitCode, IO, IOApp}

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Random


sealed trait CatState

case object Happy extends CatState

case object Sleepy extends CatState

case object Hungry extends CatState

case class Greet(from: ActorRef[IO])

sealed trait Action

case object Play extends Action

case object Nap extends Action

case object Eat extends Action

object Cat {
  type CatName = String

  def apply(name: String): IO[Actor[IO]] = {
    FSM[IO, CatState, CatName]
      .when(Happy) {
        case (Event(Greet(from), name), sM) =>
          (from ! s"$name purrs and rubs against your leg.") >> sM.stay()
        case (Event(Play, _), sM) =>
          IO.println(s"$name is already happy!") >> sM.stay()
        case (Event(Nap, _), sM) =>
          IO.println(s"$name curls up in a sunbeam for a nap.") >> sM.goto(Sleepy)
        case (Event(Eat, _), sM) =>
          IO.println(s"$name is not hungry yet.") >> sM.stay()
      }
      .when(Sleepy) {
        case (Event(Greet(from), name), sM) =>
          (from ! s"$name yawns and ignores you.") >> sM.stay()
        case (Event(Play, _), sM) =>
          IO.println(s"$name is too sleepy to play.") >> sM.stay()
        case (Event(Nap, _), sM) =>
          IO.println(s"$name is already sleeping.") >> sM.stay()
        case (Event(Eat, _), sM) =>
          IO.println(s"$name wakes up and eats some food.") >> sM.goto(Hungry)
      }
      .when(Hungry) {
        case (Event(Greet(from), name), sM) =>
          (from ! s"$name looks at you expectantly.") >> sM.stay()
        case (Event(Play, _), sM) =>
          IO.println(s"$name is too hungry to play.") >> sM.stay()
        case (Event(Nap, _), sM) =>
          IO.println(s"$name is too hungry to nap.") >> sM.stay()
        case (Event(Eat, _), sM) =>
          IO.println(s"$name devours a bowl of tuna.") >> sM.goto(Happy)
      }
      // .withConfig(FSMConfig.withConsoleInformation)
      .withConfig(FSMConfig.noDebug)
      .startWith(Happy, name)
      .initialize
  }
}


object CatCafeFSM extends IOApp {
  def randomCatAction(cat: ActorRef[IO]): IO[Unit] = {
    val actions: List[Action] = List(Play, Nap, Eat)
    val randomAction: Action = actions(Random.nextInt(actions.length))
    cat ! randomAction
  }

  override def run(args: List[String]): IO[ExitCode] = {
    ActorSystem[IO]("CatCafe").use { system =>
      for {
        whiskers <- system.actorOf(PropsF(Cat("Whiskers 🐱")), "Whiskers")
        shadow <- system.actorOf(PropsF(Cat("Shadow 🐈‍⬛")), "Shadow")
        chester <- system.actorOf(PropsF(Cat("Chester 🐈")), "Chester")
        patron <- system.actorOf(Props(new Actor[IO] {
          override def receive: Receive[IO] = {
            case msg: String => IO.println(s"Patron: $msg")
          }
        }))
        _ <- whiskers ! Greet(patron)
        _ <- shadow ! Greet(patron)
        _ <- chester ! Greet(patron)
        _ <- IO.sleep(2 seconds)
        _ <- randomCatAction(whiskers).delayBy(1 second).foreverM.start
        _ <- randomCatAction(shadow).delayBy(1 second).foreverM.start
        _ <- randomCatAction(chester).delayBy(1 second).foreverM.start
        _ <- system.waitForTermination
      } yield ExitCode.Success
    }
  }
}

