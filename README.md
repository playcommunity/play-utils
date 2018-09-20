# Welcome!
For chinese introduction, please refer to [README-CN.md](https://github.com/playcommunity/play-utils/blob/master/README-CN.md).

# Introduction
`Play-Utils` is a set of utilities for developing with [Play Framework](https://www.playframework.com/), including the following features:
-  `Retry` retry request automatically with different strategies

# 1 Retry 
`Retry` utility is used to retry request automatically with different strategies, and finally return the success result or the last retried result.

## 1.1 Get started
Add the following dependency to your `build.sbt`:
```
libraryDependencies += "cn.playscala" %% "play-utils" % "0.1.0"
```
FixedDelayRetry is the simplest retry strategy, it retries the next request with the same delay. Before coding, the instance of `Retry` should be injected where is needed: 
```
class ExternalService @Inject()(retry: Retry)
```
The following codes retry every second and 3 times at most:
```
import scala.concurrent.duration._

retry.withFixedDelay[Int](3, 1 seconds) { () =>
  Future.successful(0)
}.stopWhen(_ == 10)
```
`stopWhen` is used to set the stop condition, that means, it will return a successful result when the result value is 10 otherwise continue to retry. You can also use `retryWhen` method to set retry condition:
```
import scala.concurrent.duration._

retry.withFixedDelay[Int](3, 1 seconds) { () =>
  Future.successful(0)
}.retryWhen(_ != 10)
```
Notice that, it will retry automatically when an exception is thrown.

In addition to injected instance, you can also use the singleton object `Retry` directly with two implicit objects in current scope:
```
implicit val ec: ExecutionContext = ...
implicit val scheduler: Scheduler = ...

Retry.withFixedDelay[Int](3, 1 seconds).apply { () =>
  Future.successful(0)
}.retryWhen(s => s != 10)
```
> Unless stated, the following codes use the injected instance which is named `retry`.

You can set the customized execution context and scheduler with `withExecutionContext` and `withScheduler` methods:
```
import scala.concurrent.duration._

retry.withFixedDelay[Int](3, 1 seconds) { () =>
  Future.successful(0)
}.withExecutionContext(ec)
 .withScheduler(s)
 .retryWhen(_ != 10)
```

## 1.2 Retry Strategies
In some scenarios, fixed-time retry may impact remote services. So there are several useful candidate strategies.

### 1.2.1 BackoffRetry
`BackoffRetry` contains 2 parameters, `delay` parameter is for setting the initial delay, `factor` parameter is a product factor, used for adjusting the next delay time.
```
import scala.concurrent.duration._

retry.withBackoffDelay[Int](3, 1 seconds, 2.0) { () =>
  Future.successful(0)
}.retryWhen(_ != 10)
```
The retry delay times are: `1 seconds`, `2 seconds` and `4 seconds`.

### 1.2.2 JitterRetry
`JitterRetry` contains 2 parameters, `minDelay` parameter sets the lower bound, and `maxDelay` parameter sets the upper bound. The retry delay time will fluctuate between these two values:
```
import scala.concurrent.duration._

retry.withJitterDelay[Int](3, 1 seconds, 1 hours) { () =>
  Future.successful(0)
}.retryWhen(_ != 10)
```

### 1.2.3 FibonacciRetry
`FibonacciRetry` calculates the delay time based on Fibonacci algorithm.
```
import scala.concurrent.duration._

retry.withFibonacciDelay[Int](4, 1 seconds) { () =>
  Future.successful(0)
}.retryWhen(_ != 10)
```
The retry delay times are: `0 seconds`, `1 seconds`, `1 seconds` and `2 seconds`。   

Notice that, you can adjust the `baseDelay` parameter to control the interval between each delay:
```
import scala.concurrent.duration._

retry.withFibonacciDelay[Int](4, 2 seconds) { () =>
  Future.successful(0)
}.retryWhen(_ != 10)
```
The retry delay times are: `0 seconds`, `2 seconds`, `2 seconds` and `4 seconds`.