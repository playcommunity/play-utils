package retry

import akka.actor.{ActorSystem, Scheduler}
import akka.pattern.after
import javax.inject.Inject
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Random

trait Retryable[T] {

  /**
    * Set a name for this retry task for logging.
    * @param taskName
    * @return current retryable instance.
    */
  def withTaskName(taskName: String): Retryable[T]

  /**
    * Enable/disable logging.
    * @return current retryable instance.
    */
  def withLoggingEnabled(enabled: Boolean): Retryable[T]

  /**
    * Set customized execution context.
    * @param ec
    * @return current retryable instance.
    */
  def withExecutionContext(ec: ExecutionContext): Retryable[T]

  /**
    * Set customized execution context.
    * @param ec
    * @return current retryable instance.
    */
  def withScheduler(scheduler: Scheduler): Retryable[T]

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
}

/**
  * The base abstract class for different retry strategies.
  * The original inspiration comes from https://gist.github.com/viktorklang/9414163, thanks to Viktor Klang and Chad Selph.
  * @FIXME Todo:
  * - Remove mutable by builder pattern.
  *
  * @param retries the max retry count.
  * @param initialDelay the initial delay for first retry.
  * @param ec execution context.
  * @param s scheduler.
  * @tparam T
  */
abstract class BaseRetry[T](retries: Int, initialDelay: FiniteDuration, ec: ExecutionContext, scheduler: Scheduler) extends Retryable[T] {
  @volatile
  private var _retries = retries
  private var _ec: ExecutionContext = ec
  private var _scheduler: Scheduler = scheduler
  private var _taskName: Option[String] = None
  private var _isLoggingEnabled: Boolean = true
  protected var block : () => Future[T] = _
  protected var predicate : T => Boolean = _
  protected val logger = Logger("retry")
  /**
    * Set an block/operation that will produce a Future[T].
    * @param block
    * @return current retryable instance.
    */
  def apply(block: () => Future[T]): Retryable[T] = {
    this.block = block
    this
  }

  /**
    * Set a name for this retry task for logging..
    * @param ec
    * @return current retryable instance.
    */
  def withTaskName(taskName: String): Retryable[T] = {
    _taskName = Some(taskName)
    this
  }

  /**
    * Enable/disable logging.
    * @return current retryable instance.
    */
  def withLoggingEnabled(enabled: Boolean): Retryable[T] = {
    _isLoggingEnabled = enabled
    this
  }

  protected def debug(msg: String): Unit = if (_isLoggingEnabled) logger.debug( _taskName.map(n => s"${n} - ${msg}").getOrElse(msg))

  protected def info(msg: String): Unit = if (_isLoggingEnabled) logger.info( _taskName.map(n => s"${n} - ${msg}").getOrElse(msg))

  protected def warn(msg: String): Unit = if (_isLoggingEnabled) logger.warn( _taskName.map(n => s"${n} - ${msg}").getOrElse(msg))

  protected def error(msg: String): Unit = if (_isLoggingEnabled) logger.error( _taskName.map(n => s"${n} - ${msg}").getOrElse(msg))

  protected def error(msg: String, t: Throwable): Unit = if (_isLoggingEnabled) logger.error( _taskName.map(n => s"${n} - ${msg}").getOrElse(msg), t)

  /**
    * Set customized execution context.
    * @param ec
    * @return current retryable instance.
    */
  def withExecutionContext(ec: ExecutionContext): Retryable[T] = {
    _ec = ec
    this
  }

  /**
    * Set customized execution scheduler.
    * @param scheduler
    * @return current retryable instance.
    */
  def withScheduler(scheduler: Scheduler): Retryable[T] = {
    _scheduler = scheduler
    this
  }

  /**
    * Set the retry condition.
    * @param predicate
    * @return the successful Future[T] or the last retried result.
    */
  def retryWhen(predicate: T => Boolean): Future[T] = {
    this.predicate = predicate
    _retries = retries
    Future(retry(initialDelay, () => block())(_ec, _scheduler))(_ec).flatMap(f => f)(_ec)
  }

  /**
    * Set the stop condition.
    * @param predicate
    * @return the successful Future[T] or the last retried result.
    */
  def stopWhen(predicate: T => Boolean): Future[T] = {
    this.predicate = predicate
    _retries = retries
    Future(retry(initialDelay, () => block())(_ec, _scheduler))(_ec).flatMap(f => f)(_ec)
  }

  /**
    * Retry and check the result with the predicate condition. Continue retrying if an exception is thrown.
    * Imagine that, if the retry method contains a retries parameter, when the result of after(...) expression is a Future[Throwable], then the body of recoverWith will continue with the same retries.
    * So retries parameter should be removed form retry method, we use an internal _retries to track the retry count.
    * @FIXME Todo:
    * - Remove _retries by Future.transform() with retries parameter.
    *
    * @param block the operation which returns Future[T].
    * @return the successful Future[T] or the last retried result.
    */
  private def retry(delay: FiniteDuration, block: () => Future[T])(implicit ec: ExecutionContext, scheduler: Scheduler): Future[T] = {
    _retries -= 1
    val f = try { block() } catch { case t => Future.failed(t) }
    f.flatMap{ res =>
      val isSuccess = predicate(res)
      if (_retries < 0 || isSuccess) {
        if (_retries < 0 && !isSuccess) error(s"Oops! retry finished with unexpected result: ${res}")
        if (_retries != retries -1 && isSuccess) info(s"congratulations! retry finished with expected result: ${res}")
        f
      } else {
        val nextDelayTime = nextDelay(delay)
        warn(s"invalid result ${res}, retry after ${nextDelayTime} for the ${retries - _retries} time.")
        after(nextDelayTime, scheduler)(retry(nextDelayTime, block))
      }
    }.recoverWith {
      case t if _retries >= 0 => {
        val nextDelayTime = nextDelay(delay)
        error(s"${t.getMessage} error occurred, retry after ${nextDelayTime} for the ${retries - _retries} time.", t)
        after(nextDelayTime, scheduler)(retry(nextDelayTime, block))
      }

      case t if _retries < 0 => {
        error(s"Oops! retry finished with unexpected error: ${t.getMessage}", t)
        Future.failed(t)
      }
    }
  }

  /**
    *  Calc the next delay based on the previous delay.
    * @param delay the previous delay.
    * @return the next delay.
    */
  protected def nextDelay(delay: FiniteDuration): FiniteDuration
}

/**
  * Retry strategy with fixed delay.
  * @param retries the max retry count.
  * @param delay the fixed delay between each retry.
  * @param ec execution context.
  * @param s scheduler.
  * @tparam T
  */
class FixedDelayRetry[T](retries: Int, delay: FiniteDuration, ec: ExecutionContext, scheduler: Scheduler) extends BaseRetry[T](retries, delay, ec, scheduler) {
  override def nextDelay(delay: FiniteDuration): FiniteDuration = delay
}

/**
  * Retry strategy with back-off delay.
  * @param retries the max retry count.
  * @param delay the initial delay for first retry.
  * @param factor the product factor for the calculation of next delay.
  * @param ec execution context.
  * @param scheduler
  * @tparam T
  */
class BackoffRetry[T](retries: Int, delay: FiniteDuration, factor: Double, ec: ExecutionContext, scheduler: Scheduler) extends BaseRetry[T](retries, delay, ec, scheduler) {
  private var n = 0
  override def nextDelay(delay: FiniteDuration): FiniteDuration = {
    if (n == 0) {
      n += 1
      delay
    } else {
      Duration((delay.length * factor).toLong, delay.unit)
    }
  }
}

/**
  * Retry strategy with jitter delay.
  * @param retries the max retry count.
  * @param minDelay min delay.
  * @param maxDelay max delay.
  * @param ec execution context.
  * @param scheduler
  * @tparam T
  */
class JitterRetry[T](retries: Int, minDelay: FiniteDuration, maxDelay: FiniteDuration, ec: ExecutionContext, scheduler: Scheduler) extends BaseRetry[T](retries, minDelay, ec, scheduler) {
  override def nextDelay(delay: FiniteDuration): FiniteDuration = {
    val interval = maxDelay - minDelay
    minDelay + Duration((interval.length * Random.nextDouble).toLong, interval.unit)
  }
}

/**
  * Retry strategy with fibonacci delay.
  * @param retries the max retry count.
  * @param baseDelay the initial delay for first retry.
  * @param ec execution context.
  * @param scheduler
  * @tparam T
  */
class FibonacciRetry[T](retries: Int, baseDelay: FiniteDuration, ec: ExecutionContext, scheduler: Scheduler) extends BaseRetry[T](retries, baseDelay, ec, scheduler) {
  private var n = 0
  override def nextDelay(delay: FiniteDuration): FiniteDuration = {
    val next = baseDelay * fib(n)
    n += 1
    next
  }

  private def fib(n: Int): Int = {
    def fib_tail(n: Int, a: Int, b: Int): Int = n match {
      case 0 => a
      case _ => fib_tail(n - 1, b, a + b)
    }
    return fib_tail(n, 0 , 1)
  }
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
  def withFixedDelay[T](retries: Int, delay: FiniteDuration): BaseRetry[T] = new FixedDelayRetry[T](retries, delay, ec, actorSystem.scheduler)

  /**
    * Retry with  a back-off delay strategy.
    * @param retries the max retry count.
    * @param baseDelay the initial delay for first retry.
    * @param factor the product factor for the calculation of next delay.
    * @param ec execution context.
    * @param scheduler
    * @tparam T
    */
  def withBackoffDelay[T](retries: Int, baseDelay: FiniteDuration, factor: Double): BaseRetry[T] = new BackoffRetry[T](retries, baseDelay, factor, ec, actorSystem.scheduler)

  /**
    * Retry with a jitter delay strategy.
    * @param retries the max retry count.
    * @param minDelay min delay.
    * @param maxDelay max delay.
    * @param ec execution context.
    * @param scheduler
    * @tparam T
    */
  def withJitterDelay[T](retries: Int, minDelay: FiniteDuration, maxDelay: FiniteDuration): BaseRetry[T] = new JitterRetry[T](retries, minDelay, maxDelay, ec, actorSystem.scheduler)

  /**
    * Retry with a fibonacci delay strategy.
    * @param retries the max retry count.
    * @param baseDelay the initial delay for first retry.
    * @param ec execution context.
    * @param scheduler
    * @tparam T
    */
  def withFibonacciDelay[T](retries: Int, baseDelay: FiniteDuration): BaseRetry[T] = new FibonacciRetry[T](retries, baseDelay, ec, actorSystem.scheduler)
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
  def withFixedDelay[T](retries: Int, delay: FiniteDuration)(implicit ec: ExecutionContext, scheduler: Scheduler): BaseRetry[T] = new FixedDelayRetry[T](retries, delay, ec, scheduler)

  /**
    * Retry with  a back-off delay strategy.
    * @param retries the max retry count.
    * @param baseDelay the initial delay for first retry.
    * @param factor the product factor for the calculation of next delay.
    * @param ec execution context.
    * @param scheduler
    * @tparam T
    */
  def withBackoffDelay[T](retries: Int, baseDelay: FiniteDuration, factor: Double)(implicit ec: ExecutionContext, scheduler: Scheduler): BaseRetry[T] = new BackoffRetry[T](retries, baseDelay, factor, ec, scheduler)

  /**
    * Retry with a jitter delay strategy.
    * @param retries the max retry count.
    * @param minDelay min delay.
    * @param maxDelay max delay.
    * @param ec execution context.
    * @param scheduler
    * @tparam T
    */
  def withJitterDelay[T](retries: Int, minDelay: FiniteDuration, maxDelay: FiniteDuration)(implicit ec: ExecutionContext, scheduler: Scheduler): BaseRetry[T] = new JitterRetry[T](retries, minDelay, maxDelay, ec, scheduler)

  /**
    * Retry with a fibonacci delay strategy.
    * @param retries the max retry count.
    * @param baseDelay the initial delay for first retry.
    * @param ec execution context.
    * @param scheduler
    * @tparam T
    */
  def withFibonacciDelay[T](retries: Int, baseDelay: FiniteDuration)(implicit ec: ExecutionContext, scheduler: Scheduler): BaseRetry[T] = new FibonacciRetry[T](retries, baseDelay, ec, scheduler)

}

