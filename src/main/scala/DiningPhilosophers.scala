import zio.clock.Clock
import zio.console.{Console, putStrLn}
import zio.duration._
import zio.{IO, Ref, Semaphore, URIO, ZIO}

case class Chopstick(id: Long, sem: Semaphore, philosopherId: Ref[Option[Long]])

class Philosopher(id: Long, leftChopstick: Chopstick, rightChopstick: Chopstick, timesAte: Ref[Long]) {
  import Philosopher._

  private def withChopstick[R, E, A](chopstick: Chopstick)(
      f: => ZIO[R, E, A]): ZIO[Console with R with Clock, E, Option[A]] =
    chopstick.sem
      .withPermit {
        for {
          _ <- putStrLn(s"Philosopher $id picked up chopstick ${chopstick.id}")
          _ <- chopstick.philosopherId.set(Some(id))
          r <- f
          _ <- chopstick.philosopherId.set(None)
          _ <- putStrLn(s"Philosopher $id put down chopstick ${chopstick.id}")
        } yield r
      }
      .timeout(WaitTimeout)
      .tap {
        case None => putStrLn(s"Philosopher $id gave up waiting for chopstick ${chopstick.id}")
        case _    => ZIO.unit
      }

  private def withChopsticks[R, E, A](chopsticks: (Chopstick, Chopstick))(
      f: => ZIO[R, E, A]): ZIO[Console with R with Clock, E, Option[A]] =
    (if (0 == id % 2) {
       withChopstick(chopsticks._1)(withChopstick(chopsticks._2)(f))
     } else {
       withChopstick(chopsticks._2)(withChopstick(chopsticks._1)(f))
     }).map(_.flatten)

  private def eat: URIO[Console with Clock, Option[Unit]] =
    withChopsticks(leftChopstick, rightChopstick) {
      for {
        _ <- putStrLn(s"Philosopher $id eating")
        _ <- ZIO.sleep(EatTime)
        _ <- timesAte.update(_ + 1)
        _ <- putStrLn(s"Philosopher $id finished eating")
      } yield ()
    }

  private def think: URIO[Console with Clock, Unit] =
    for {
      _ <- putStrLn(s"Philosopher $id thinking")
      _ <- ZIO.sleep(ThinkTime)
      _ <- putStrLn(s"Philosopher $id finished thinking")
    } yield ()

  def dine: URIO[Console with Clock, Nothing] =
    (eat *> think).forever.onInterrupt {
      for {
        ate <- timesAte.get
        _   <- putStrLn(s"Philosopher $id ate $ate times")
      } yield ()
    }
}

object Philosopher {
  val EatTime     = 2.seconds
  val ThinkTime   = 3.seconds
  val WaitTimeout = EatTime * 2
}

object DiningPhilosophers extends zio.App {
  def simulation(numPhilosophers: Int): ZIO[Console with Clock, Unit, Unit] =
    for {
      philosophers <- makePhilosophers(numPhilosophers)
      fiber        <- ZIO.forkAll(philosophers.map(_.dine))
      _            <- fiber.join
    } yield ()

  def makePhilosophers(numPhilosophers: Int): IO[Unit, List[Philosopher]] = {
    def circularIterator[A](s: Seq[A]) = Iterator.continually(s).flatten
    if (numPhilosophers <= 0) {
      ZIO.dieMessage("numPhilosophers must be > 0")
    } else {
      for {
        chopsticks <- ZIO.traverse(0 to numPhilosophers - 1) { i =>
          for {
            sem              <- Semaphore.make(1)
            philosopherIdRef <- Ref.make[Option[Long]](None)
          } yield Chopstick(i, sem, philosopherIdRef)
        }
        philosophers <- ZIO.sequence {

          /**
            * Set out a chopstick on both sides of each philosopher seated around a circular table.
            * Since the table is round, the first philosopher's left chopstick is the last philosopher's
            * right chopstick.
            */
          circularIterator(chopsticks).take(numPhilosophers + 1).sliding(2).toList.zipWithIndex.map {
            case (stickList, index) =>
              for {
                stickTuple <- ZIO.fromOption(stickList.headOption.zip(stickList.lastOption))
                timesAte   <- Ref.make(0L)
              } yield new Philosopher(index, stickTuple._1, stickTuple._2, timesAte)
          }
        }
      } yield philosophers
    }
  }

  def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {
    simulation(10).fold(_ => 1, _ => 0)
  }
}
