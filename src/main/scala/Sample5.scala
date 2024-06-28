package com.suprnation.samples

import cats.effect.{ExitCode, IO, IOApp, Ref}
import cats.implicits._
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor._
import com.suprnation.samples.WalletActor.{Deposit, GetBalance}

object WalletActor {
  sealed trait Command
  case class GetBalance(replyTo: ActorRef[IO, Balance]) extends Command
  case class Deposit(amount: BigDecimal, replyTo: Option[ActorRef[IO, Response]]) extends Command
  case class Withdraw(amount: BigDecimal, replyTo: Option[ActorRef[IO, Response]]) extends Command

  sealed trait Response
  case class TransactionFailed(reason: String) extends Response
  case class Balance(amount: BigDecimal) extends Response

  def create(walletId: String): IO[Actor[IO, Command]] =
    for {
      balance <- Ref[IO].of(BigDecimal.decimal(0.0))
    } yield new Actor[IO, Command] {
      override def receive: Receive[IO, Command] = {
        case Deposit(amount, replyTo) =>
          balance.update(_ + amount) >>
            balance.get.flatMap(newBalance =>
              IO.println(
                s"[${context.self.path.name}] Deposited $amount to $walletId. New balance: $newBalance"
              ) >>
                replyTo.fold(IO.unit)(_ ! Balance(newBalance))
            )

        case Withdraw(amount, replyTo) =>
          balance.get.flatMap { currentBalance =>
            if (currentBalance >= amount) {
              balance.update(_ - amount) >>
                balance.get.flatMap(newBalance =>
                  IO.println(
                    s"[${context.self.path.name}] Withdrew $amount from $walletId. New balance: $newBalance"
                  ) >>
                    replyTo.fold(IO.unit)(_ ! Balance(newBalance))
                )
            } else replyTo.fold(IO.unit)(_ ! TransactionFailed(s"Insufficient funds in $walletId"))
          }

        case GetBalance(replyTo) =>
          for {
            currentBalance <- balance.get
            _ <- replyTo ! Balance(currentBalance)
          } yield ()
      }
    }
}

object TransactionActor {
  sealed trait Response
  case class TransactionSuccess(transactionId: String) extends Response
  case class TransactionFailed(reason: String) extends Response

  def create(
              transactionId: String,
              parent: ActorRef[IO, Response],
              fromWallet: ActorRef[IO, WalletActor.Command],
              toWallet: ActorRef[IO, WalletActor.Command],
              amount: BigDecimal
            ): Actor[IO, Nothing] = new Actor[IO, Nothing] {

    override def preStart: IO[Unit] = for {
      _ <- IO.println(
        s"~~~ [Tx: $transactionId] => Sending withdraw request to [${fromWallet.path.name}].  Awaiting confirmation.  ~~~"
      )
      _ <- fromWallet ! WalletActor.Withdraw(amount, context.self.widenRequest.some)
      _ <- context.become(awaitWithdraw)
    } yield ()

    def awaitWithdraw: Receive[IO, WalletActor.Response] = {
      case WalletActor.Balance(_) =>
        for {
          _ <- IO.println(
            s"~~~ [Tx: $transactionId] => Withdraw from [${fromWallet.path.name}] confirmed.  Depositing to [${toWallet.path.name}]. ~~~"
          )
          _ <- toWallet ! WalletActor.Deposit(amount, context.self.widenRequest.some)
          _ <- context.become(awaitDeposit)
        } yield ()

      case WalletActor.TransactionFailed(reason) =>
        parent ! TransactionFailed(s"Withdraw from source wallet failed: $reason")
        context.stop(context.self)
    }

    def awaitDeposit: Receive[IO, WalletActor.Response] = {
      case WalletActor.Balance(_) =>
        for {
          _ <- IO.println(
            s"~~~ [Tx: $transactionId] => Deposit to [${toWallet.path.name}] confirmed.  Killing actor - transaction complete! ~~~"
          )
          _ <- parent ! TransactionSuccess(transactionId)
          _ <- context.stop(context.self)
        } yield ()

      case WalletActor.TransactionFailed(reason) =>
        parent ! TransactionFailed(s"Deposit to destination wallet failed: $reason")
        context.stop(context.self)
    }
  }
}

object Main extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    ActorSystem[IO]("actor-system").use { system =>
      for {
        auditor <- system.actorOf(new Actor[IO, Any] {
          override def receive: Receive[IO, Any] = { case response =>
            val sender: String = context.sender.map(_.path.name).getOrElse("N/A")
            IO.println(s"[From: $sender] => $response")
          }
        }, "reporter")

        alice <- system.actorOf(WalletActor.create("alice"), "alice-actor")
        _ <- alice ! Deposit(100, auditor.some)

        bob <- system.actorOf(WalletActor.create("bob"), "bob-actor")

        // The transaction actor does not receive any messages, it co-ordinates flow between two actors.
        _ <- system.actorOf[Nothing](
          TransactionActor.create("alice->bob::1", auditor, alice, bob, BigDecimal(100)), "tx-coordinator"
        )

        // Retrieve the new balances
        _ <- alice ! GetBalance(auditor)
        _ <- bob ! GetBalance(auditor)

      } yield ExitCode.Success
    }
}
