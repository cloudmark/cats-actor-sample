package com.suprnation

import actor.Actor.Receive
import actor.SupervisorStrategy.{Decider, Restart}
import actor._
import actor.props.{Props, PropsF}

import cats.effect._

import scala.concurrent.duration._
import scala.language.postfixOps

object Juggle

case class BallOfYarn(id: Int) {
  override def toString: String = s"Yarn Ball $id"
}

class CatJuggler(balls: Ref[IO, List[BallOfYarn]]) extends Actor[IO] {

  override val preStart: IO[Unit] =
    IO.println("Meow and welcome to the show!") >>
      IO.println("Let the juggling commence!")

  override def postRestart(reason: Option[Throwable]): IO[Unit] =
    IO.println(s"Restarted Juggler! Reason: ${reason.map(_.toString).getOrElse("N/A")}")

  override val receive: Receive[IO] = {
    case Juggle =>
      for {
        currentBalls <- balls.get
        _ <- if (currentBalls.nonEmpty) {
          val droppedBall: BallOfYarn = currentBalls.head
          balls.update(_.tail) >>
            IO.println(s"Oops, dropped ball! ${droppedBall.id}") >>
            IO.raiseError(new Error(s"Oops, dropped $droppedBall!"))
        } else IO.unit
      } yield ()
    case newBall: BallOfYarn =>
      for {
        latestBalls <- balls.updateAndGet(newBall :: _)
        _ <- IO.println(s"Caught $newBall! [Balls: $latestBalls]")
      } yield ()
  }
}

object Ringmaster {
  def apply(): IO[Actor[IO]] = {
    Ref[IO].of(0).map(counter => {
      new Actor[IO] {
        override val preStart: IO[Unit] = for {
          balls <- counter.set(3) >> Ref[IO].of(List(BallOfYarn(1), BallOfYarn(2), BallOfYarn(3)))
          juggler <- context.actorOf(Props(new CatJuggler(balls)), "juggler")
          // Every second the Ring master will tell every juggler to juggle the balls.
          _ <- (juggler ! Juggle).delayBy(1 second).foreverM
        } yield ()


        override val supervisorStrategy: SupervisionStrategy[IO] = new SupervisionStrategy[IO] {
          override def decider: Decider = {
            case _ => Restart
          }

          override def handleChildTerminated(context: ActorContext[IO], child: ActorRef[IO], children: Iterable[ActorRef[IO]]): IO[Unit] = IO.unit

          override def processFailure(context: ActorContext[IO], restart: Boolean, child: ActorRef[IO], cause: Option[Throwable], stats: ChildRestartStats[IO], children: List[ChildRestartStats[IO]]): IO[Unit] =
            if (restart) {
              counter.flatModify(current =>
                current + 1 -> (child ! BallOfYarn(current + 1))
              ) >> restartChild(child, cause, suspendFirst = false)
            } else
              context.stop(child)
        }
      }
    })
  }
}

object CatCircus extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    ActorSystem[IO]("CatCircus").use { system =>
      system.actorOf(PropsF[IO](Ringmaster()), "ringmaster") >>
        system.waitForTermination.as(ExitCode.Success)
    }
  }
}
