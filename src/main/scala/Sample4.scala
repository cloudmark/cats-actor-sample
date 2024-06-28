package com.suprnation.samples.logic

import cats.effect.{ExitCode, IO, IOApp, Ref}
import cats.implicits._
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor._
import com.suprnation.samples.logic.Logic.LogicCircuits.{demux, wire}
import com.suprnation.samples.logic.Logic.{GetValue, Value}

import scala.concurrent.duration.{Duration, DurationInt}
import scala.language.postfixOps

object Logic {

  // Observations: here is the delay map, as an example the inverter
  // delay is set to 1 millisecond which means that when the input changes the inverter will take 1 millisecond to compute and propagate the result.
  val inverterDelay = 1 milliseconds
  val andDelay = 1 milliseconds
  val orDelay = 10 milliseconds


  trait LogicRequest

  case class AddComponent(wireName: String, actor: ActorRef[IO, LogicRequest]) extends LogicRequest

  case class StateChange(name: String, state: Boolean) extends LogicRequest

  case class Value(current: Boolean) extends LogicRequest

  case object GetValue extends LogicRequest


  object LogicCircuits {
    def wire(_currentState: Boolean): IO[Actor[IO, LogicRequest]] = {
      for {
        associationsRef <- Ref[IO].of(Map.empty[ActorRef[IO, LogicRequest], String])
        currentStateRef <- Ref[IO].of(_currentState)
      } yield new Actor[IO, LogicRequest] {
        override def receive: Receive[IO, LogicRequest] = {
          case AddComponent(name: String, b: ActorRef[IO, LogicRequest]) =>
            for {
              _ <- associationsRef.update(_ + (b -> name))
              currentState <- currentStateRef.get
              _ <- b ! StateChange(name, currentState)
            } yield ()

          case GetValue => currentStateRef.get

          case Value(s) =>
            currentStateRef.get.map(_ != s).ifM(
              for {
                currentState <- currentStateRef.updateAndGet(_ => s)
                associations <- associationsRef.get
                // Observation: we send to all associations in parallel.
                _ <- associations.toList.parTraverse_ { case (ref: ActorRef[IO, LogicRequest], name: String) =>
                  ref ! StateChange(name, currentState)
                }
              } yield (),
              IO.unit
            )
        }
      }

    }

    def inverter(input: ActorRef[IO, LogicRequest], output: ActorRef[IO, LogicRequest]): Actor[IO, LogicRequest] = new Actor[IO, LogicRequest] {
      override def preStart: IO[Unit] = {
        // Observation: we register with the input ourselves, we will receive updates from the input under the name "in"
        input ! AddComponent("in", self)
      }

      override def receive: Actor.Receive[IO, LogicRequest] = {
        case StateChange(_, s: Boolean) =>
          // Observation: Note that we do a `start` here so that this is run in the background and we do not stop processing.
          // this is a great example of how cat-effects compliments actors
          (IO.sleep(inverterDelay) >> (output ! Value(!s))).start
      }
    }

    def and(input0: ActorRef[IO, LogicRequest], input1: ActorRef[IO, LogicRequest], output: ActorRef[IO, LogicRequest]): IO[Actor[IO, LogicRequest]] = {
      for {
        in0 <- Ref[IO].of[Option[Boolean]](None)
        in1 <- Ref[IO].of[Option[Boolean]](None)
      } yield new Actor[IO, LogicRequest] {
        override def preStart: IO[Unit] = {
          // Observations: Registration for both components is done in parallel.
          (input0 ! AddComponent("in0", self), input1 ! AddComponent("in1", self)).parTupled.void
        }

        override def receive: Actor.Receive[IO, LogicRequest] = {
          // Observations: once again state changes are done in the background.
          case StateChange("in0", b: Boolean) => in0.set(b.some) >> sendMessage.start
          case StateChange("in1", b: Boolean) => in1.set(b.some) >> sendMessage.start
        }

        private val sendMessage: IO[Unit] =
          (in0.get, in1.get).flatMapN {
            case (Some(first), Some(second)) =>
              IO.sleep(andDelay) >> (output ! Value(first && second))
            case _ => IO.unit
          }
      }
    }

    def or(input0: ActorRef[IO, LogicRequest], input1: ActorRef[IO, LogicRequest], output: ActorRef[IO, LogicRequest]): IO[Actor[IO, LogicRequest]] = {
      for {
        in0 <- Ref[IO].of[Option[Boolean]](None)
        in1 <- Ref[IO].of[Option[Boolean]](None)
      } yield new Actor[IO, LogicRequest] {
        override def preStart: IO[Unit] = {
          (input0 ! AddComponent("in0", self), input1 ! AddComponent("in1", self)).parTupled.void
        }

        override def receive: Receive[IO, LogicRequest] = {
          case StateChange("in0", b: Boolean) => in0.set(b.some) >> sendMessage.start
          case StateChange("in1", b: Boolean) => in1.set(b.some) >> sendMessage.start
        }

        private def sendMessage: IO[Unit] = {
          (in0.get, in1.get).flatMapN {
            case (Some(first), Some(second)) =>
              // Observations: Note how all the logic here is very similar to the and logic, we can extract all
              // of this in a common gate called reducer which takes out the commonalities.
              IO.sleep(orDelay) >> (output ! Value(first || second))
            case _ => IO.unit
          }
        }
      }
    }

    // Observations: This is a higher order logic circuit which merges the logic of the and and or by taking in the reduceFn and the delay
    // and keeping the logic for the rest intact.
    def reducer(reduceFn: (Boolean, Boolean) => Boolean, delay: Duration)(input0: ActorRef[IO, LogicRequest], input1: ActorRef[IO, LogicRequest], output: ActorRef[IO, LogicRequest]): IO[Actor[IO, LogicRequest]] = {
      for {
        in0 <- Ref[IO].of[Option[Boolean]](None)
        in1 <- Ref[IO].of[Option[Boolean]](None)
      } yield new Actor[IO, LogicRequest] {
        override def preStart: IO[Unit] =
          (input0 ! AddComponent("in0", self), input1 ! AddComponent("in1", self)).parTupled.void

        override def receive: Actor.Receive[IO, LogicRequest] = {
          case StateChange("in0", b: Boolean) => in0.set(b.some) >> sendMessage.start
          case StateChange("in1", b: Boolean) => in1.set(b.some) >> sendMessage.start
        }

        private def sendMessage: IO[Unit] = {
          (in0.get, in1.get).flatMapN {
            case (Some(first), Some(second)) =>
              IO.sleep(delay) >> (output ! Value(reduceFn(first, second)))
            case _ => IO.unit
          }
        }
      }
    }

    // Observations: Here is how we would write the and using the new hof reducer
    val andWithReduce = reducer(_ && _, andDelay)(_, _, _)
    // Observations: Here is how we would write the or using the new hof reducer
    val orWithReduce = reducer(_ || _, orDelay)(_, _, _)

    // Observations: the or gate can be optimised by using demorgan's.  This is because the native or gate has a
    // delay of 10ms.  If we use de-morgans, we will need an not gate on each input, an and gate to combine these negated inputs
    // and another notGate on top of everything.  Given that all of these run in parallel, we would have a delay from the input
    // perturbations of 3ms.
    def orAlt(input0: ActorRef[IO, LogicRequest], input1: ActorRef[IO, LogicRequest], output: ActorRef[IO, LogicRequest]): Actor[IO, LogicRequest] =
      new Actor[IO, LogicRequest] {

        override def preStart: IO[Unit] = for {
          // Observations: Note that now we do not deal with the lower level
          // details of the StateChange, AddComponent we are now at a higher level.
          // We are working with actors but we have also managed to wire components together
          // logically.

          // This is the power of cats actors.  When I interact with functional programmers at times
          // there is an obstacle to get people to see that the actor system can work in harmony with
          // functional programming and typelevel.  Most of the time these developers are obsessed with
          // using FS2 and managing these wiring's manually.

          // I am not claiming that FS2 is wrong, but achieving the same level of abstraction will be hard.
          // Note make this as non confrontational as possible.  I'm sure we will trigger a lot of people with this.

          notInput0 <- context.actorOf(wire(false), "notInput0")
          notInput1 <- context.actorOf(wire(false), "notInput1")
          notOutput0 <- context.actorOf(wire(false), "notOutput0")

          _ <- context.actorOf(inverter(input0, notInput0), "A")
          _ <- context.actorOf(inverter(input1, notInput1), "B")
          _ <- context.actorOf(and(notInput0, notInput1, notOutput0), "notAB")
          _ <- context.actorOf(inverter(notOutput0, output), "notNotAB")
        } yield ()
      }

    def halfAdder(a: ActorRef[IO, LogicRequest], b: ActorRef[IO, LogicRequest], s: ActorRef[IO, LogicRequest], c: ActorRef[IO, LogicRequest]): Actor[IO, LogicRequest] = {
      new Actor[IO, LogicRequest] {
        override val preStart: IO[Unit] = for {
          // Observation: Once again that we focus on the wiring of the half adder and we do not have to worry about internals.
          d <- context.actorOf(wire(false), "d")
          e <- context.actorOf(wire(false), "e")
          _ <- context.actorOf(or(a, b, d), "Or")
          _ <- context.actorOf(and(a, b, c), "And")
          _ <- context.actorOf(inverter(c, e), "Inverter")
          _ <- context.actorOf(and(d, e, s), "And2")
        } yield ()
      }
    }

    def fullAdder(a: ActorRef[IO, LogicRequest], b: ActorRef[IO, LogicRequest], cin: ActorRef[IO, LogicRequest], sum: ActorRef[IO, LogicRequest], cout: ActorRef[IO, LogicRequest]): Actor[IO, LogicRequest] = new Actor[IO, LogicRequest] {
      override val preStart: IO[Unit] = for {
        // Observation: Notice how we now use the halfAdder as a component itself, the half adder became a part of our DSL and
        // we can use this to create higher order components.   .
        s <- context.actorOf(wire(false))
        c1 <- context.actorOf(wire(false))
        c2 <- context.actorOf(wire(false))
        _ <- context.actorOf(halfAdder(a, cin, s, c1), "HalfAdder1")
        _ <- context.actorOf(halfAdder(b, s, sum, c2), "HalfAdder2")
        _ <- context.actorOf(or(c1, c2, cout), "OrGate")
      } yield ()
    }

    def demux2(in: ActorRef[IO, LogicRequest], c: ActorRef[IO, LogicRequest], out1: ActorRef[IO, LogicRequest], out0: ActorRef[IO, LogicRequest]): Actor[IO, LogicRequest] = new Actor[IO, LogicRequest] {
      override def preStart: IO[Unit] = for {
        notC <- context.actorOf(wire(false), "not")
        _ <- context.actorOf(inverter(c, notC), "InverterA")
        _ <- context.actorOf(and(in, notC, out1), "AndGateA")
        _ <- context.actorOf(and(in, c, out0), "AndGateB")
      } yield ()

    }

    def demux(in: ActorRef[IO, LogicRequest], c: List[ActorRef[IO, LogicRequest]], out: List[ActorRef[IO, LogicRequest]]): Actor[IO, LogicRequest] = new Actor[IO, LogicRequest] {
      // Observation: Note how we can create a demux of any size, by recursively wiring demux2 actors.
      // This is the power of cats actors, we can use actors and FP in unison, we can use also recursion to create
      // dynamic structures.

      // I can achieve the same with FS2! Ofc you can, the trick here is that the cats-actor abstraction helps you create
      // dynamic stream graphs without having to worry about the innate knowledge of how to wire up these streams.

      // cats-actors is beneficial when you have a dynamic graph of streams which you want to construct / modify at runtime.
      // When the graph is static use FS2 - although you can still use cats-actors.
      override def preStart: IO[Unit] = c match {
        case c_n :: Nil =>
          context.actorOf(demux2(in, c_n, out.head, out(1))).void

        case c_n :: c_rest =>
          for {
            out1 <- context.actorOf(wire(false))
            out0 <- context.actorOf(wire(false))
            _ <- context.actorOf(demux2(in, c_n, out1, out0))
            _ <- context.actorOf(demux(out1, c_rest, out.take(out.size / 2)))
            _ <- context.actorOf(demux(out0, c_rest, out.drop(out.size / 2)))
          } yield ()

        case Nil => IO.unit
      }
    }
  }
}

object LogicCircuitSimulator extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    // Observation: we will setup the board simulator and test out a demux of 2 inputs which results in 4 outputs.
    ActorSystem[IO]("De-multiplexer Board Simulator").use(system =>
      for {
        // Observation: create all the wires.
        in1 <- system.actorOf(wire(false), "in1")
        c1 <- system.actorOf(wire(false), "c1")
        c2 <- system.actorOf(wire(false), "c2")
        out1 <- system.actorOf(wire(false), "out1")
        out2 <- system.actorOf(wire(false), "out2")
        out3 <- system.actorOf(wire(false), "out3")
        out4 <- system.actorOf(wire(false), "out4")

        allActors = List(in1, c1, c2, out1, out2, out3, out4)
        printTruthTable =
          for {
            // Observation: Note how powerful this is, we are querying all actors to retrieve the value in
            // parallel and cats-effects will automatically retrieve these results in order.
            outputs <- allActors.parTraverse(_ ? GetValue)
            // Observations: We will use these sorted results and pair them with the actor names to give the output to the user.
            result = allActors.zip(outputs).map {
              case (actor, value) => s"${actor.path.name}::$value"
            }.mkString("\t")
            _ <- IO.println(result)
          } yield ()


        // Observations: Create the de-multiplexer which we want to test.
        _ <- system.actorOf(demux(in1, List(c2, c1), List(out4, out3, out2, out1)))

        // Observations: Set in1 to true, we should expect out4 to be true.
        // If you run the program you will get the following output
        // in1::true	c1::false	c2::false	out1::false	out2::false	out3::false	out4::true
        _ <- in1 ! Value(true)
        _ <- printTruthTable.delayBy(1 second)

        // Observations: Set c1 to true, we expect out3 to be true
        // If you run the program you will get the following output
        // in1::true	c1::true	c2::false	out1::false	out2::false	out3::true	out4::false
        _ <- c1 ! Value(true)
        _ <- printTruthTable.delayBy(1 second)

        // Observations: Set c1 to false and c2 to true, we expect out2 to be true
        // If you run the program you will get the following output
        // in1::true	c1::false	c2::true	out1::false	out2::true	out3::false	out4::false
        _ <- c1 ! Value(false)
        _ <- c2 ! Value(true)
        _ <- printTruthTable.delayBy(1 second)

        // Observations: Set c1 to true and c2 to true, we expect out2 to be true
        // If you run the program you will get the following output
        // in1::true	c1::true	c2::true	out1::true	out2::false	out3::false	out4::false
        _ <- c1 ! Value(true)
        _ <- printTruthTable.delayBy(1 second)

        // Observations: Set in1 to false, we expect out1 to be false
        // If you run the program you will get the following output
        // in1::false	c1::true	c2::true	out1::false	out2::false	out3::false	out4::false
        _ <- in1 ! Value(false)
        _ <- printTruthTable.delayBy(1 second)

        // Observation: after the system exits, the resource is collected and all actors are stopped and garbage collected.
      } yield ()
    ).as(ExitCode.Success)
  }
}
