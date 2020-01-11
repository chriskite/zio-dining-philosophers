import zio.console.putStrLn
import zio.duration._
import zio.{RefM, Semaphore, ZIO}

case class Chopstick(id: Long, sem: Semaphore, philosopherId: RefM[Option[Long]])
case class Philosopher(id: Long, leftChopstick: Chopstick, rightChopstick: Chopstick)

class DiningRoom(philosophers: Seq[Philosopher]) {
  def philosopherTask(philosopher: Philosopher) = {
    def loop = {
      philosopher.leftChopstick.sem
        .withPermit {
          for {
            _ <- philosopher.leftChopstick.philosopherId.set(Some(philosopher.id))
            _ <- putStrLn(s"Philosopher ${philosopher.id} picked up left stick")
            _ <- philosopher.rightChopstick.sem
              .withPermit {
                for {
                  _ <- philosopher.rightChopstick.philosopherId.set(Some(philosopher.id))
                  _ <- putStrLn(s"Philosopher ${philosopher.id} picked up right stick")
                  _ <- putStrLn(s"Philosopher ${philosopher.id} eating")
                  _ <- ZIO.sleep(2.seconds)
                  _ <- putStrLn(s"Philosopher ${philosopher.id} finished eating")
                  _ <- philosopher.rightChopstick.philosopherId.set(None)
                  _ <- putStrLn(s"Philosopher ${philosopher.id} put down right stick")
                } yield ()
              }
              .timeout(3.seconds)
            _ <- philosopher.leftChopstick.philosopherId.set(None)
            _ <- putStrLn(s"Philosopher ${philosopher.id} put down left stick")
            _ <- putStrLn(s"Philosopher ${philosopher.id} thinking")
            _ <- ZIO.sleep(3.seconds)
          } yield ()
        }
        .timeout(3.seconds)
    }
    loop.forever
  }

  def simulation =
    for {
      fibers <- ZIO.traverse(philosophers)(philosopherTask(_).fork)
      _      <- ZIO.traverse(fibers)(_.join)
    } yield ()
}

object DiningRoom {
  def apply(numPhilosophers: Int) = {
    if (numPhilosophers <= 0) {
      ZIO.dieMessage("numPhilosophers must be > 0")
    } else {
      for {
        chopsticks <- ZIO.traverse(1 to numPhilosophers + 1) { i =>
          for {
            sem              <- Semaphore.make(1)
            philosopherIdRef <- RefM.make[Option[Long]](None)
          } yield Chopstick(i, sem, philosopherIdRef)
        }
        philosophers <- ZIO.sequence {
          chopsticks.sliding(2).toList.zipWithIndex.map {
            case (stickList, index) =>
              for {
                stickTuple <- ZIO.fromOption(stickList.headOption.zip(stickList.lastOption))
              } yield Philosopher(index, stickTuple._1, stickTuple._2)
          }
        }
      } yield new DiningRoom(philosophers)
    }
  }
}

object DiningPhilosophers extends zio.App {
  def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {
    val program = for {
      diningRoom <- DiningRoom(10)
      _          <- diningRoom.simulation
    } yield ()

    program.fold(_ => 1, _ => 0)
  }
}
