import java.util.concurrent.atomic.AtomicInteger

import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import retry.Retry
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class RetrySpec extends PlaySpec with GuiceOneAppPerSuite {
  val retry = app.injector.instanceOf[Retry]

  "FixedDelayRetry" should {
    "return the last result when exceed the max retry count" in {
      val i = new AtomicInteger(0)
      val result = Await.result(
        retry.withFixedDelay[Int](3, 1 seconds){ () =>
          Future.successful(i.addAndGet(1))
        }.stopWhen(_ == 10)
      ,10 seconds)

      result mustBe 4
    }


    "continue retrying when an exception is thrown" in {
      val i = new AtomicInteger(0)
      val result = Await.result(
        retry.withFixedDelay[Int](3, 1 seconds){ () =>
          i.addAndGet(1)
          if (i.get() % 2 == 1) {
            Future.failed(new Exception)
          } else {
            Future.successful(i.get())
          }
        }.stopWhen(_ == 10)
        ,10 seconds)

      result mustBe 4
    }
  }

  "BackoffRetry" should {
    "stop retrying after 3 seconds" in {
      val startTime = System.currentTimeMillis()
      val result = Await.result(
        retry.withBackoffDelay[Int](2, 1 seconds, 2){ () =>
          Future.successful(0)
        }.stopWhen(_ == 10)
        ,10 seconds)

      result mustBe 0
      (System.currentTimeMillis() - startTime)/1000 mustBe 3
    }
  }

  "JitterRetry" should {
    "stop retrying between 1 and 3 seconds" in {
      val startTime = System.currentTimeMillis()
      val result = Await.result(
        retry.withJitterDelay[Int](1, 1 seconds, 3 seconds){ () =>
          Future.successful(0)
        }.stopWhen(_ == 10)
        ,10 seconds)

      result mustBe 0
      val secs = (System.currentTimeMillis() - startTime)/1000
      secs >= 1 mustBe true
      secs < 3 mustBe true
    }
  }

  "FibonacciRetry" should {
    "stop retrying after 3 seconds" in {
      val startTime = System.currentTimeMillis()
      val result = Await.result(
        retry.withFibonacciDelay[Int](4, 1 seconds){ () =>
          Future.successful(0)
        }.stopWhen(_ == 10)
        ,10 seconds)

      result mustBe 0
      (System.currentTimeMillis() - startTime)/1000 mustBe 4
    }
  }

}
