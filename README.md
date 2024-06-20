# Cats-Actors Samples

This repository contains various examples demonstrating the usage of `cats-actors`. Each sample illustrates different features and capabilities of the `cats-actors` library. Below are the descriptions for each sample:

Framework: https://github.com/suprnation/cats-actors/

## Sample 1: Jungle Chaos

This sample demonstrates a scenario where multiple actors interact with each other in a jungle setting, involving tourists and banana-snatching monkeys. Tourists arrive with a certain number of bananas, which are then targeted by the `BananaSnatcher` actors. The `BananaGuardian` oversees this interaction, ensuring the tourists are appropriately handled.

## Sample 2: Cat Café FSM

This sample showcases the use of finite state machines (FSM) within the `cats-actors` library. The actors represent cats with different states such as Happy, Sleepy, and Hungry. Each state defines specific behaviors and transitions triggered by events like greeting, playing, napping, and eating. The example simulates interactions in a cat café, with patrons greeting the cats and the cats responding based on their current state.

## Sample 3: Cat Circus Supervision

This sample highlights the supervision strategy in `cats-actors`. The `CatJuggler` actor juggles `BallOfYarn` objects, and the `Ringmaster` oversees the juggling act. If a ball is dropped, the actor throws an error, and the supervision strategy determines how to handle the failure (e.g., restarting the actor). The `Ringmaster` ensures the show continues by restarting the juggler when necessary.

## Sample 4: Logic Circuit Simulator

This sample demonstrates how to use `cats-actors` to simulate a digital logic circuit. The actors represent various logic gates (e.g., AND, OR, NOT) and wires that carry boolean signals. The sample constructs a demultiplexer (demux) circuit, showing how the actors can be wired together to simulate complex logic circuits. This example also illustrates the power of combining actors with functional programming to create dynamic and reactive systems.

These samples provide a comprehensive overview of the capabilities of `cats-actors`, showcasing how actors can be used to model complex interactions, manage state transitions, handle failures, and simulate real-world scenarios.
