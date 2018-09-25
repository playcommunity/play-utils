package retry

import akka.actor.{ActorSystem, Scheduler}
import akka.pattern.after
import javax.inject.Inject
import play.api.Logger

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success}

trait Retryable[T] {

  /**
    * Execute the block codes that produces a Future[T], returns a Future containing the result of T, unless an exception is thrown,
    * in which case the operation will be retried after  delay time, if there are more possible retries, which is configured through
    * the retries parameter. If the operation does not succeed and there is no retries left, the resulting Future will contain the last failure.
    * @param block
    * @return
    */
  def retryWhen(predicate: T => Boolean): Future[T]

  /**
    * Set the stop condition.
    * @param predicate
    * @return the successful Future[T] or the last retried result.
    */
  def stopWhen(predicate: T => Boolean): Future[T]

  //TODO
  //def cancel()
  //def stopOnException
}

/**
  * The base abstract class for different retry strategies.
  * The original inspiration comes from https://gist.github.com/viktorklang/9414163, thanks to Viktor Klang and Chad Selph.
  * @param retries the max retry count.
  * @param baseDelay the initial delay for first retry.
  * @param ec execution context.
  * @param s scheduler.
  * @tparam T
  */
case class RetryTask[T](retries: Int, baseDelay: FiniteDuration, nextDelay: Int => FiniteDuration, block: () => Future[T] = null, predicate : T => Boolean = null, taskName: String = "default", enableLogging: Boolean = true, ec: ExecutionContext, scheduler: Scheduler) extends Retryable[T] {
  protected val logger = Logger("retry")
  /**
    * Set an block/operation that will produce a Future[T].
    * @param block
    * @return current retryable instance.
    */
  def apply(block: () => Future[T]): Retryable[T] = {
    this.copy(block = block)
  }

  /**
    * Set the retry condition.
    * @param predicate
    * @return the successful Future[T] or the last retried result.
    */
  def retryWhen(predicate: T => Boolean): Future[T] = {
    val instance = this.copy(predicate = predicate)
    Future(instance.retry(0)(ec, scheduler))(ec).flatMap(f => f)(ec)
  }

  /**
    * Set the stop condition.
    * @param predicate
    * @return the successful Future[T] or the last retried result.
    */
  def stopWhen(predicate: T => Boolean): Future[T] = {
    val instance = this.copy(predicate = predicate)
    Future(instance.retry(0)(ec, scheduler))(ec).flatMap(f => f)(ec)
  }

  /**
    * Retry and check the result with the predicate condition. Continue retrying if an exception is thrown.
    * @param retried the current retry number.
    * @return the successful Future[T] or the last retried result.
    */
  private def retry(retried: Int)(implicit ec: ExecutionContext, scheduler: Scheduler): Future[T] = {
    val f = try { block() } catch { case t => Future.failed(t) }
    f transformWith {
      case Success(res) =>
        val isSuccess = predicate(res)
        if (retried >= retries || isSuccess) {
          if (retried > retries && !isSuccess) error(s"Oops! retry finished with unexpected result: ${res}")
          if (retried > 0 && isSuccess) info(s"congratulations! retry finished with expected result: ${res}")
          f
        } else {
          val nextDelayTime = nextDelay(retried + 1)
          warn(s"invalid result ${res}, retry after ${nextDelayTime} for the ${retried} time.")
          after(nextDelayTime, scheduler)(retry(retried + 1))
        }
      case Failure(t) =>
        if (retried < retries) {
          val nextDelayTime = nextDelay(retried + 1)
          error(s"${t.getMessage} error occurred, retry after ${nextDelayTime} for the ${retried} time.", t)
          after(nextDelayTime, scheduler)(retry(retried + 1))
        } else {
          error(s"Oops! retry finished with unexpected error: ${t.getMessage}", t)
          Future.failed(t)
        }
    }
  }

  protected def debug(msg: String): Unit = if (enableLogging) logger.debug(s"${taskName} - ${msg}")

  protected def info(msg: String): Unit = if (enableLogging) logger.info(s"${taskName} - ${msg}")

  protected def warn(msg: String): Unit = if (enableLogging) logger.warn(s"${taskName} - ${msg}")

  protected def error(msg: String): Unit = if (enableLogging) logger.error(s"${taskName} - ${msg}")

  protected def error(msg: String, t: Throwable): Unit = if (enableLogging) logger.error((s"${taskName} - ${msg}"), t)
}

/**
  * The entrance class for working with DI containers.
  * @param ec the injected execution context.
  * @param actorSystem the injected actorSystem.
  */
class Retry @Inject() (ec: ExecutionContext, actorSystem: ActorSystem) {

  /**
    * Retry with a fixed delay strategy.
    * @param retries the max retry count.
    * @param delay the fixed delay between each retry.
    * @param ec execution context.
    * @param s scheduler.
    * @tparam T
    */
  def withFixedDelay[T](retries: Int, delay: FiniteDuration, taskName: String = "default",  enableLogging: Boolean = true, executionContext: ExecutionContext = ec, scheduler: Scheduler = actorSystem.scheduler): RetryTask[T] = {
    /**
      *  Calc the next delay based on the previous delay.
      * @param retries the current retry number.
      * @return the next delay.
      */
    def nextDelay(retries: Int): FiniteDuration = delay
    RetryTask[T](retries, delay, nextDelay, taskName = taskName, enableLogging = enableLogging, ec = executionContext, scheduler = scheduler)
  }

  /**
    * Retry with  a back-off delay strategy.
    * @param retries the max retry count.
    * @param baseDelay the initial delay for first retry.
    * @param factor the product factor for the calculation of next delay.
    * @param ec execution context.
    * @param scheduler
    * @tparam T
    */
  def withBackoffDelay[T](retries: Int, baseDelay: FiniteDuration, factor: Double, taskName: String = "default",  enableLogging: Boolean = true, executionContext: ExecutionContext = ec, scheduler: Scheduler = actorSystem.scheduler): RetryTask[T] = {
    def nextDelay(retried: Int): FiniteDuration = {
      if (retried == 1) {
        baseDelay
      } else {
        Duration((baseDelay.length * Math.pow(factor, retried - 1)).toLong, baseDelay.unit)
      }
    }
    RetryTask[T](retries, baseDelay, nextDelay, taskName = taskName, enableLogging = enableLogging, ec = executionContext, scheduler = scheduler)
  }

  /**
    * Retry with a jitter delay strategy.
    * @param retries the max retry count.
    * @param minDelay min delay.
    * @param maxDelay max delay.
    * @param ec execution context.
    * @param scheduler
    * @tparam T
    */
  def withJitterDelay[T](retries: Int, minDelay: FiniteDuration, maxDelay: FiniteDuration,  taskName: String = "default",  enableLogging: Boolean = true, executionContext: ExecutionContext = ec, scheduler: Scheduler = actorSystem.scheduler): RetryTask[T] = {
    def nextDelay(retried: Int): FiniteDuration = {
      val interval = maxDelay - minDelay
      minDelay + Duration((interval.length * Random.nextDouble).toLong, interval.unit)
    }

    RetryTask[T](retries, minDelay, nextDelay, taskName = taskName, enableLogging = enableLogging, ec = executionContext, scheduler = scheduler)
  }

  /**
    * Retry with a fibonacci delay strategy.
    * @param retries the max retry count.
    * @param baseDelay the initial delay for first retry.
    * @param ec execution context.
    * @param scheduler
    * @tparam T
    */
  def withFibonacciDelay[T](retries: Int, baseDelay: FiniteDuration, taskName: String = "default",  enableLogging: Boolean = true, executionContext: ExecutionContext = ec, scheduler: Scheduler = actorSystem.scheduler): RetryTask[T] = {
    def nextDelay(retried: Int): FiniteDuration = {
      def fib(n: Int): Int = {
        def fib_tail(n: Int, a: Int, b: Int): Int = n match {
          case 0 => a
          case _ => fib_tail(n - 1, b, a + b)
        }
        return fib_tail(n, 0 , 1)
      }

      val next = baseDelay * fib(retries - retried)
      next
    }

    RetryTask[T](retries, baseDelay, nextDelay, taskName = taskName, enableLogging = enableLogging, ec = executionContext, scheduler = scheduler)
  }
}

/**
  * The entrance object for directly usage. There should be an implicit execution context and an implicit scheduler in scope.
  */
object Retry {

  /**
    * Retry with a fixed delay strategy.
    * @param retries the max retry count.
    * @param delay the fixed delay between each retry.
    * @param ec execution context.
    * @param s scheduler.
    * @tparam T
    */
  def withFixedDelay[T](retries: Int, delay: FiniteDuration, taskName: String = "default",  enableLogging: Boolean = true)(implicit executionContext: ExecutionContext, scheduler: Scheduler): RetryTask[T] = {
    /**
      *  Calc the next delay based on the previous delay.
      * @param retries the current retry number.
      * @return the next delay.
      */
    def nextDelay(retries: Int): FiniteDuration = delay
    RetryTask[T](retries, delay, nextDelay, taskName = taskName, enableLogging = enableLogging, ec = executionContext, scheduler = scheduler)
  }

  /**
    * Retry with  a back-off delay strategy.
    * @param retries the max retry count.
    * @param baseDelay the initial delay for first retry.
    * @param factor the product factor for the calculation of next delay.
    * @param ec execution context.
    * @param scheduler
    * @tparam T
    */
  def withBackoffDelay[T](retries: Int, baseDelay: FiniteDuration, factor: Double, taskName: String = "default",  enableLogging: Boolean = true)(implicit executionContext: ExecutionContext, scheduler: Scheduler): RetryTask[T] = {
    def nextDelay(retried: Int): FiniteDuration = {
      if (retried == 1) {
        baseDelay
      } else {
        Duration((baseDelay.length * Math.pow(factor, retried - 1)).toLong, baseDelay.unit)
      }
    }
    RetryTask[T](retries, baseDelay, nextDelay, taskName = taskName, enableLogging = enableLogging, ec = executionContext, scheduler = scheduler)
  }

  /**
    * Retry with a jitter delay strategy.
    * @param retries the max retry count.
    * @param minDelay min delay.
    * @param maxDelay max delay.
    * @param ec execution context.
    * @param scheduler
    * @tparam T
    */
  def withJitterDelay[T](retries: Int, minDelay: FiniteDuration, maxDelay: FiniteDuration,  taskName: String = "default",  enableLogging: Boolean = true)(implicit executionContext: ExecutionContext, scheduler: Scheduler): RetryTask[T] = {
    def nextDelay(retried: Int): FiniteDuration = {
      val interval = maxDelay - minDelay
      minDelay + Duration((interval.length * Random.nextDouble).toLong, interval.unit)
    }

    RetryTask[T](retries, minDelay, nextDelay, taskName = taskName, enableLogging = enableLogging, ec = executionContext, scheduler = scheduler)
  }

  /**
    * Retry with a fibonacci delay strategy.
    * @param retries the max retry count.
    * @param baseDelay the initial delay for first retry.
    * @param ec execution context.
    * @param scheduler
    * @tparam T
    */
  def withFibonacciDelay[T](retries: Int, baseDelay: FiniteDuration, taskName: String = "default",  enableLogging: Boolean = true)(implicit executionContext: ExecutionContext, scheduler: Scheduler): RetryTask[T] = {
    def nextDelay(retried: Int): FiniteDuration = {
      def fib(n: Int): Int = {
        @tailrec
        def fib_tail(n: Int, a: Int, b: Int): Int = n match {
          case 0 => a
          case _ => fib_tail(n - 1, b, a + b)
        }
        return fib_tail(n, 0 , 1)
      }

      val next = baseDelay * fib(retries - retried)
      next
    }

    RetryTask[T](retries, baseDelay, nextDelay, taskName = taskName, enableLogging = enableLogging, ec = executionContext, scheduler = scheduler)
  }
}

