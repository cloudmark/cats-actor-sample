package com.suprnation

import Example.{Data, producer}
import StreamExtensions.{Ack, Fail}
import actor.Actor.{Actor, Receive, ReplyingReceive}
import actor.{Actor, ActorRefProvider, ActorSystem, ReplyingActor, ReplyingActorRef}

import cats.effect._
import cats.implicits._
import cats.effect.implicits._
import cats.effect.std.{Console, Queue}
import cats.implicits.catsSyntaxEitherId
import fs2.Stream

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps


object StreamExtensions {

  sealed trait Message
  case object Ack extends Message
  case object Fail extends Message

  implicit class BoundedStreamOps[F[+_] : Console: Concurrent : Temporal](refProvider: ActorRefProvider[F]) {
    def actorRefWithBackpressure[A](maxSize: Int): F[(ReplyingActorRef[F, A, Either[Fail.type, Ack.type]], Stream[F, A])] = {
      for {
        queue <- Queue.bounded[F, A](maxSize)
        streamActor = new ReplyingActor[F, A, Either[Fail.type, Ack.type]] {
          override def receive: ReplyingReceive[F, A, Either[Fail.type, Ack.type]] = {
            case data: A =>
              for {
                size <- queue.size
                result <- if (size >= maxSize) Console[F].println(s"Max Size Reached: ($size >= $maxSize)") >> Fail.asLeft[Ack.type].pure[F]
                else Console[F].println(s"Still capacity: ${maxSize - size}") >> queue.offer(data) >> Ack.asRight[Fail.type].pure[F]
              } yield result
          }
        }
        actorRef <- refProvider.replyingActorOf(streamActor)
        stream = Stream.fromQueueUnterminated[F, A](queue)
      } yield (actorRef, stream)
    }
  }
}


object Example {
  case class Data(value: Int)

  def producer(target: ReplyingActorRef[IO, Data, Either[Fail.type, Ack.type]]): Actor[IO, Data] = {
    def process(data: Data): IO[Unit] = for {
      response <- target ? data
      _ <- response match {
        case Left(Fail) => IO.println("Failed to send data: buffer full, retrying") >> process(data).delayBy(1 second)
        case Right(Ack) => IO.unit
      }
    } yield ()

    Actor.withReceive(process(_))
  }
}

object Sample6 extends IOApp {

  import StreamExtensions._

  override def run(args: List[String]): IO[ExitCode] =
    ActorSystem[IO]("actorRefWithBackpressureSystem").use { implicit system: ActorSystem[IO] =>
      for {
        // Create a stream and wrap it with an actor
        tuple <- system.actorRefWithBackpressure[Data](10)

        // Split the tuple
        (streamActorRef, stream) = tuple

        // Create the ProducerActor
        producerActorRef <- system.actorOf(producer(streamActorRef))

        // Run the stream (just printing the data for demonstration)
        streamFiber <- stream.evalMap(data => IO.println(s"Stream received (waiting to simulate slow process): ${data.value}") >> IO.sleep(1 second)).compile.drain.start

        // Send messages from ProducerActor to StreamWrapperActor and wait for acks
        _ <- Stream.iterate(1)(_ + 1).covary[IO].map(Data).evalMap(producerActorRef ! _).take(200).compile.drain

        // Join the stream fiber to ensure it completes
        _ <- streamFiber.join
      } yield ExitCode.Success
    }
}
