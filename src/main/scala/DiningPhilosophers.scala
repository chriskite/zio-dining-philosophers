import zio.clock.Clock
import zio.console.{Console, putStrLn}
import zio.duration._
import zio.{ExitCode, IO, Ref, Semaphore, UIO, URIO, ZIO}

case class Chopstick(id: Long, sem: Semaphore, philosopherId: Ref[Option[Long]])

class Philosopher(id: Long, leftChopstick: Chopstick, rightChopstick: Chopstick, timesAte: Ref[Long]) {
  import Philosopher._

  /**
    * Acquire the semaphore for the chopstick, use it with function `fn`, then release it.
    * @param chopstick
    * @param fn
    * @tparam R
    * @tparam E
    * @tparam A
    * @return [[Some[A]] if the semaphore was acquired, [[None]] if acquisition timed out
    */
  private def withChopstick[R, E, A](chopstick: Chopstick)(
      fn: => ZIO[R, E, A]): ZIO[Console with R with Clock, E, Option[A]] =
    chopstick.sem
      .withPermit {
        for {
          _ <- putStrLn(s"Philosopher $id picked up chopstick ${chopstick.id}")
          _ <- chopstick.philosopherId.set(Some(id))
          r <- fn
          _ <- chopstick.philosopherId.set(None)
          _ <- putStrLn(s"Philosopher $id put down chopstick ${chopstick.id}")
        } yield r
      }
      .timeout(WaitTimeout)
      .tap {
        case None => putStrLn(s"Philosopher $id gave up waiting for chopstick ${chopstick.id}")
        case _    => ZIO.unit
      }

  /**
    * Acquire the semaphores of both chopsticks, choosing which to acquire first using the id of the [[Philosopher]]
    * in order to avoid deadlock. Uses the semaphore with function `fn`.
    * @param chopsticks
    * @param f
    * @tparam R
    * @tparam E
    * @tparam A
    * @return [[Some[A]] if both semaphores were acquired, [[None]] if either acquisition timed out
    */
  private def withChopsticks[R, E, A](chopsticks: (Chopstick, Chopstick))(
      f: => ZIO[R, E, A]): ZIO[Console with R with Clock, E, Option[A]] =
    (if (0 == id % 2) {
       withChopstick(chopsticks._1)(withChopstick(chopsticks._2)(f))
     } else {
       withChopstick(chopsticks._2)(withChopstick(chopsticks._1)(f))
     }).map(_.flatten)

  /**
    * Pick up the left and right chopstick, spend time eating, then put them down.
    * @return [[Some]] if the chopsticks' semaphores were acquired, [[None]] if either acquisition timed out
    */
  private def eat: URIO[Console with Clock, Option[Unit]] =
    withChopsticks(leftChopstick, rightChopstick) {
      for {
        _ <- putStrLn(s"Philosopher $id eating")
        _ <- ZIO.sleep(EatTime)
        _ <- timesAte.update(_ + 1)
        _ <- putStrLn(s"Philosopher $id finished eating")
      } yield ()
    }

  /**
    * Sleep this fiber to simulate thinking
    */
  private def think: URIO[Console with Clock, Unit] =
    for {
      _ <- putStrLn(s"Philosopher $id thinking")
      _ <- ZIO.sleep(ThinkTime)
      _ <- putStrLn(s"Philosopher $id finished thinking")
    } yield ()

  /**
    * Alternate between eating and thinking, forever.
    */
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

  def make(index: Long, leftStick: Chopstick, rightStick: Chopstick): UIO[Philosopher] =
    for {
      timesAte <- Ref.make(0L)
    } yield new Philosopher(index, leftStick, rightStick, timesAte)
}

object DiningPhilosophers extends zio.App {

  /**
    * Simulate a round table of [[Philosopher]]s eating with chopsticks and thinking.
    * @param numPhilosophers number of [[Philosopher]]s to simulate
    */
  def simulation(numPhilosophers: Int): ZIO[Console with Clock, Unit, Unit] =
    for {
      philosophers <- makePhilosophers(numPhilosophers)
      fiber        <- ZIO.forkAll(philosophers.map(_.dine))
      _            <- fiber.join
    } yield ()

  /**
    * Make the [[Philosopher]]s and their [[Chopstick]]s
    * @param numPhilosophers number of [[Philosopher]]s to make
    * @return the [[List]] of [[Philosopher]]s
    */
  def makePhilosophers(numPhilosophers: Int): IO[Unit, List[Philosopher]] = {
    if (numPhilosophers <= 0) {
      ZIO.dieMessage("numPhilosophers must be > 0")
    } else {
      for {
        chopsticks <- ZIO.foreach((0 to numPhilosophers - 1).toList) { i =>
          for {
            sem              <- Semaphore.make(1)
            philosopherIdRef <- Ref.make[Option[Long]](None)
          } yield Chopstick(i, sem, philosopherIdRef)
        }
        philosophers <- ZIO.collectAll {

          /**
            * Set out a chopstick on both sides of each philosopher seated around a circular table.
            * Since the table is round, the first philosopher's left chopstick is the last philosopher's
            * right chopstick.
            */
          val tableSticks = chopsticks :+ chopsticks(0)
          tableSticks.zip(tableSticks.drop(1)).zipWithIndex.map {
            case ((leftStick, rightStick), index) =>
              Philosopher.make(index, leftStick, rightStick)
          }
        }
      } yield philosophers
    }
  }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    simulation(10).fold(_ => ExitCode.failure, _ => ExitCode.success)
}
