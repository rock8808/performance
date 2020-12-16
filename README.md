# Performance

这是一个性能检测库，可以检测

- [x] ANR
- [x] FPS
- [x] 线程和线程池的监控
- [x] IPC(进程间通讯)
- [x] 主线程耗时任务检测

## 检测 ANR 的原理

主要参考了 ANR-WatchDog 的思路，利用一个线程，先向主线程投放一个 msg 。
然后 sleep 指定的时间，时间到了之后，检测这个 msg 是否被处理过，
如果被处理过，说明这段时间内没有阻塞。
如果这个 msg 没有被处理，那么说明这段时间内有阻塞，可能发生了 ANR 。
如果发生了 ANR 可以把主线程的调用栈打印出来，作为一个 ANR 问题的 log 信息参考。

检测完后，这个线程继续投放下一个 msg ，然后重复做之前的检测。这样就可以监控 ANR 是否发生了。

但是这个检测有个问题，就是打印的堆栈不一定是指定的时间段内最耗时的堆栈，
这个时候，可以考虑缩短检测时间段，多次采样来提高准确率。

## 检测 FPS 的原理

FPS 检测的原理，利用了 Android 的屏幕绘制原理。

这里简单说下 Android 的屏幕绘制原理。

系统每隔 16 ms 就会发送一个 VSync 信号，告诉应用，该开始准备绘制画面了。
如果准备顺利，也就是 cpu 准备好数据， gpu 栅格化完成。如果这些任务在 16 ms 之内完成，
那么下一个 VSync 信号到来的时候就可以绘制这一帧界面了。这个准备好的画面就会被显示出来。
如果没准备好，可能就需要 32 ms 后或者更久的时间后，才能准备好，这个画面才能显示出来，
这种情况下就发生了丢帧。

上面提到了 VSync 信号，当 VSync 信号到来的时候会通知应用开始准备绘制，
具体的通知细节不做表述，大概就是用到了 Handler ，往 MessageQueue 里面放
一个异步屏障，然后注册 VSync 信号监听，当 VSync 信号到达的时候，发送一个异步 msg 
来通知应用开始 measure layout and  draw。

检测 FPS 的原理其实挺简单的，就是通过一段时间内，
比如 1s，统计绘制了多少个画面，就可以计算出 FPS 。

那如何知道应用 1s 内绘制了多少个界面呢？这个就要靠 VSync 信号监听了。假设 1s 内，
应用没有做任何绘制，我们通过 Choreographer 注册 VSync 信号监听。 16ms 后，
我们收到了 VSync 的信号，我们不做特别处理，只是做一个计数，然后监听下一次的 VSync 
信号，这样，我们就可以知道 1s 内我们监听到了多少个 VSync 信号，就可以得出帧率。

为什么监听到的 VSync 信号数量就是帧率呢？由于 looper 处理 msg 是串型的，
一次只能处理一个 msg ，而绘制的时候，绘制任务 msg 是异步消息，
会优先执行，绘制任务 msg 执行完成后，才会执行上面说的统计 VSync 的任务，
所以 VSync 信号数量可以认为是某段时间内绘制的帧数。然后就可以通过这段时间的长度
和 VSync 信号数量来计算帧率了。


## 线程和线程池的监控的原理

线程和线程池的监控，主要是监控线程和线程池在哪里创建和执行的，如果我们可以知道这些信息，
我们就可以比较清楚线程和线程池的创建是否合理。

一个比较容易想到的方法就是，应用代码里面的所有线程和线程池继承同一个线程基类和线程池基类。
然后在构造函数和启动函数里面打印方法调用栈，这样我们就知道哪里创建和执行了线程或者线程池。

让应用所有的线程和线程池继承同一个基类，可以通过 gradle 编译插件来实现，通过 ASM 编辑
生成的字节码来改变继承关系。但是，这个方法有一定的上手难度。

除了这个方法，我们还有另外一种方法，就是 hook 方法。通过 hook 构造方法和启动方法，在原本
调用每个线程和线程池的实例的构造方法和启动发放的时候，实际调用 hook 后的方法。hook 后的
方法，我们可以先打印方法调用栈，然后继续 hook 前的逻辑，这样我们也可以知道线程和线程池的
创建和启动了。

本项目采用的 hook 方案是 epic 库，由于一些原因，本人对 epic 库做了一点小修改，主要是
注释了一个 log 的打印。

由于线程池会创建线程，所以线程池和其创建的线程需要关联下，否则会干扰我们分析线程的创建问题。
我是通过如下的方法来创建关联的。线程池执行 task 的时候，如果需要创建线程，会先创建一个内部类
Worker 的实例，由于是内部类，可以知道 Worker 和 线程池的关联，然后 Worker 里面创建的线程会
把 Worker 实例作为Runnable 传入进入，这样 Worker 里面创建的线程和 Worker 就对应上了，然后
通过 Worker ，就把线程池和线程关联上了。

之前看到一个方案是通过线程的 group thread 来建立关联，但是我发现，我们工程里面的代码，有些人
写的线程池在创建线程的时候，并没有传入 group thread ,这种情况下，就统计的不准确了。所以
用了上面的方法来建立线程和线程池的关联。

## 检测 IPC(进程间通讯)的原理

这个的话，需要对 Binder 进程间通讯机制有教深入的理解。
其实原理也不复杂，就是找到所有 IPC 方法调用链的共同点，
然后 hook 这个点，在这个点里面打印当前的方法调用栈，
这样就可以知道什么时候，什么代码做了 IPC 。 

## 主线程耗时任务检测的原理

这个的话，和 ANR 检测有点相关，都是用到了 Handler ，
主线程如果耗时了，就会导致界面卡顿。AndroidPerformanceMonitor(BlockCanary) 
虽然能检测到哪些任务耗时，但是无法检测到时谁，在哪里往主线程放了这个耗时的任务。

通过对 Handler 的分析发现，往主线程放入耗时操作，
一定会调用 Handler.sendMessageAtTime 方法，如果我们在这个方法里面记录调用堆栈，
然后在 Handler.dispatchMessage 方法里面统计耗时，超过阈值的任务，
打印之前记录的放入这个 msg 的调用堆栈，我们就可以知道是谁在哪里往主线程里面放入了这个耗时的任务。