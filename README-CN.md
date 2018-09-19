# Play-Utils 介绍
`Play-Utils` 是一个专门为 [Play Framework](https://www.playframework.com/) 开发的实用工具包模块，目前已实现如下功能：
-  `Retry` 自动请求重试 

# 1 Retry 
`Retry` 工具包可以帮助你设置不同的重试策略，自动重试失败的请求，最终返回成功的结果或者是最后一次重试结果。

## 1.1 基本用法
最简单的重试策略是固定时间重试，即每次重试的时间间隔相同。 在开始编码之前，你需要将`Retry`实例依赖注入到需要的地方：
```
class ExternalService @Inject()(retry: Retry)
```
下面的代码使用固定时间重试策略，每秒重试一次，最多重试3次：
```
import scala.concurrent.duration._

retry.withFixedDelay[Int](3, 1 seconds) { () =>
  Future.successful(0)
}.stopWhen(_ == 10)
```
`stopWhen` 用于设置重试终止条件，即当 Future 结果为 10 时直接返回该Future。你也可以使用 `retryWhen` 设置重试条件：
```
import scala.concurrent.duration._

retry.withFixedDelay[Int](3, 1 seconds) { () =>
  Future.successful(0)
}.retryWhen(_ != 10)
```
需要特别注意的是，如果在重试过程中发生异常，则会自动继续进行下一次重试。

除了采用依赖注入方式，你也可以直接使用单例对象`Retry`， 但是需要注意的是，选择单例对象方式需要在当前作用域内提供如下两个隐式对象：
```
implicit val ec: ExecutionContext = ...
implicit val scheduler: Scheduler = ...

Retry.withFixedDelay[Int](3, 1 seconds).apply { () =>
  Future.successful(0)
}.retryWhen(s => s != 10)
```

> 下文中如无特殊说明，默认为采用依赖注入方式，注入实例变量名为`retry`。

你可以通过 `withExecutionContext` 和 `withScheduler` 两个方法设置自定义的线程池和定时器:
```
import scala.concurrent.duration._

retry.withFixedDelay[Int](3, 1 seconds) { () =>
  Future.successful(0)
}.withExecutionContext(ec)
 .withScheduler(s)
 .retryWhen(_ != 10)
```

## 1.2 重试策略
某些场景下，固定时间重试可能会对远程服务造成冲击，因此`Retry`提供了多种策略供你选择。

### 1.2.1 BackoffRetry
`BackoffRetry`包含两个参数，参数`delay`用于设置第一次延迟时间，参数`factor`是一个乘积因子，用于延长下一次的重试时间:
```
import scala.concurrent.duration._

retry.withBackoffDelay[Int](3, 1 seconds, 2.0) { () =>
  Future.successful(0)
}.retryWhen(_ != 10)
```
重试的延迟时间依次为：`1 seconds`, `2 seconds` 和 `4 seconds`。

### 1.2.2 JitterRetry
`JitterRetry`包含两个参数`minDelay`和`maxDelay`，用于控制延迟时间的上限和下限，真实的延迟时间会在这两个值之间波动:
```
import scala.concurrent.duration._

retry.withJitterDelay[Int](3, 1 seconds, 1 hours) { () =>
  Future.successful(0)
}.retryWhen(_ != 10)
```

### 1.2.3 FibonacciRetry
`FibonacciRetry`使用斐波纳契算法计算下一次的延迟时间：
```
import scala.concurrent.duration._

retry.withFibonacciDelay[Int](4, 1 seconds) { () =>
  Future.successful(0)
}.retryWhen(_ != 10)
```
重试的延迟时间依次为：`0 seconds`, `1 seconds`, `1 seconds` 和 `2 seconds`。   

需要注意的是，你可以设置`baseDelay`参数控制延迟的时间间隔：
```
import scala.concurrent.duration._

retry.withFibonacciDelay[Int](4, 2 seconds) { () =>
  Future.successful(0)
}.retryWhen(_ != 10)
```
重试的延迟时间依次为：`0 seconds`, `2 seconds`, `2 seconds` 和 `4 seconds`。