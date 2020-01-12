import zio.console.putStrLn
import zio.duration._
import zio.{Ref, Semaphore, ZIO}

case class Chopstick(id: Long, sem: Semaphore, philosopherId: Ref[Option[Long]])

class Philosopher(id: Long, leftChopstick: Chopstick, rightChopstick: Chopstick, timesAte: Ref[Long]) {
  import Philosopher._

  private def withChopstick[R, E, A](chopstick: Chopstick)(f: => ZIO[R, E, A]) =
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

  private def withChopsticks[R, E, A](chopsticks: (Chopstick, Chopstick))(f: => ZIO[R, E, A]) = {
    if (0 == id % 2) {
      withChopstick(chopsticks._1)(withChopstick(chopsticks._2)(f))
    } else {
      withChopstick(chopsticks._2)(withChopstick(chopsticks._1)(f))
    }
  }

  private def eat = {
    withChopsticks(leftChopstick, rightChopstick) {
      for {
        _ <- putStrLn(s"Philosopher $id eating")
        _ <- ZIO.sleep(EatTime)
        _ <- timesAte.update(_ + 1)
        _ <- putStrLn(s"Philosopher $id finished eating")
      } yield ()
    }
  }

  private def think =
    for {
      _ <- putStrLn(s"Philosopher $id thinking")
      _ <- ZIO.sleep(ThinkTime)
      _ <- putStrLn(s"Philosopher $id finished thinking")
    } yield ()

  def dine =
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
  def simulation(numPhilosophers: Int) =
    for {
      philosophers <- makePhilosophers(numPhilosophers)
      fiber        <- ZIO.forkAll(philosophers.map(_.dine))
      _            <- fiber.join
    } yield ()

  def makePhilosophers(numPhilosophers: Int) = {
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
                stickTuple <- ZIO.fromOption(stickList.headOption zip stickList.lastOption)
                timesAte   <- Ref.make[Long](0)
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
