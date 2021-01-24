package com.xander.performance;

import android.os.Binder;
import android.os.Parcel;

import com.xander.asu.aLog;
import com.xander.performance.hook.HookBridge;
import com.xander.performance.hook.core.MethodParam;
import com.xander.performance.hook.core.MethodHook;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;


/**
 * @author Xander Wang Created on 2020/11/4.
 * @Description 利用 hook 方法， hook android.os.BinderProxy 类的 transact 方法，
 * 从而获取 ipc 调用链 这样可以知道系统的瓶颈在哪里
 */
class IPCTool {

  private static final String TAG = "IPCTool";

  static void start() {
    aLog.e(TAG, "start");
    // if (Build.VERSION.SDK_INT >= 29) {
    //   hookTransactListener();
    // } else {
    //   hookWithEpic();
    // }
    hookWithEpic();
  }

  private static void hookWithEpic() {
    try {
      // 这个方法  epic hook 的话会报错，很奇怪，理论上是一个比较好的 hook 点
      HookBridge.findAndHookMethod(Class.forName("android.os.BinderProxy"),
          "transact",
          int.class,
          Parcel.class,
          Parcel.class,
          int.class,
          new BinderTransactProxyHook()
      );
      // 除了每次调用 android.os.BinderProxy.transact 方法，
      // Parcel.writeInterfaceToken 方法也会被调用，暂时用这个方法来判断是否有 IPC 调用
      // 这个一开始没问题，但是后面发现有个平台一直有问题，在不同的平台上，好像 epic hook
      // 如果涉及到了 Parcel 实例的数据读写貌似就会出问题，这个方案暂时舍弃
      // HookBridge.findAndHookMethod(Parcel.class,
      //     "writeInterfaceToken",
      //     String.class,
      //     new ParcelWriteInterfaceTokenHook()
      // );
      // 观察 AIDL 生成的代码，发现每次 IPC 调用也会调用 Parcel.readException 方法
      // 故初步用这个方法作为切入点来监控系统的 IPC 调用情况
      // HookBridge.findAndHookMethod(Parcel.class, "readException", new HookParcelReadException());
      aLog.e(TAG, "hookWithEpic");
    } catch (Exception e) {
      aLog.e(TAG, "hookWithEpic Exception", e);
    }
  }

  static class HookParcelReadException extends MethodHook {
    @Override
    public void beforeHookedMethod(MethodParam param) throws Throwable {
      super.beforeHookedMethod(param);
      Issue ipcIssue = new Issue(Issue.TYPE_IPC, "IPC 123", StackTraceUtils.list());
      ipcIssue.print();
    }
  }

  static class BinderTransactProxyHook extends MethodHook {
    @Override
    public void beforeHookedMethod(MethodParam param) throws Throwable {
      // getInterfaceDescriptor
      String ipcInterface = null;
      if ("android.os.BinderProxy".equals(param.getThisObject().getClass().getName())) {
        Method g = param.getThisObject().getClass().getDeclaredMethod("getInterfaceDescriptor");
        g.setAccessible(true);
        Object o = g.invoke(param.getThisObject());
        aLog.e(TAG, "%s", o);
        if (o instanceof String) {
          ipcInterface = (String) o;
        }
      }
      if (null != ipcInterface) {
        IPCIssue ipcIssue = new IPCIssue(ipcInterface, "IPC", StackTraceUtils.list());
        ipcIssue.print();
      } else {
        Issue ipcIssue = new Issue(Issue.TYPE_IPC, "IPC", StackTraceUtils.list());
        ipcIssue.print();
      }
      super.beforeHookedMethod(param);
    }
  }

  static class ParcelWriteInterfaceTokenHook extends MethodHook {
    @Override
    public void beforeHookedMethod(MethodParam param) throws Throwable {
      super.beforeHookedMethod(param);
      aLog.e(TAG, "WriteInterfaceTokenHook:" + param.getArgs()[0]);
      // aLog.e(TAG, "WriteInterfaceTokenHook:", new Throwable());
      IPCIssue ipcIssue = new IPCIssue(param.getArgs()[0], "IPC", StackTraceUtils.list());
      ipcIssue.print();
    }
  }

  static class ParcelReadExceptionHook extends MethodHook {
    @Override
    public void beforeHookedMethod(MethodParam param) throws Throwable {
      super.beforeHookedMethod(param);
      Issue ipcIssue = new Issue(Issue.TYPE_IPC, "IPC", StackTraceUtils.list());
      ipcIssue.print();
    }
  }

  private static TransactListenerHandler gTransactListenerHandler = null;

  private static void hookTransactListener() {
    setTransactListener(null);
    try {
      HookBridge.findAndHookMethod(Class.forName("android.os.BinderProxy"),
          "setTransactListener",
          Class.forName("android.os.Binder$ProxyTransactListener"),
          new SetTransactListenerHook()
      );
      aLog.e(TAG, "hookTransactListener");
    } catch (Exception e) {
      aLog.e(TAG, "hookTransactListener", e);
    }
  }

  /**
   * hook transact 方法总是碰到各种问题，所以换一种方式，但是这个只有 Android 10 以上的版本
   */
  private static void setTransactListener(Object target) {
    try {
      if (null == gTransactListenerHandler) {
        synchronized (IPCTool.class) {
          if (null == gTransactListenerHandler) {
            gTransactListenerHandler = new TransactListenerHandler();
          }
        }
      }
      Class binderProxy = Class.forName("android.os.BinderProxy");
      Class transactListener = Class.forName("android.os.Binder$ProxyTransactListener");
      Method setMethod = binderProxy.getDeclaredMethod("setTransactListener", transactListener);
      setMethod.setAccessible(true);
      gTransactListenerHandler.setTarget(target);
      Object proxyInstance = Proxy.newProxyInstance(Binder.class.getClassLoader(),
          new Class[]{transactListener},
          gTransactListenerHandler
      );
      setMethod.invoke(null, proxyInstance);
      // aLog.e(TAG, "invoke setTransactListener");
      Field listener = binderProxy.getDeclaredField("sTransactListener");
      listener.setAccessible(true);
      aLog.e(TAG, "android.os.BinderProxy.sTransactListener is:" + listener.get(null));
    } catch (Exception e) {
      aLog.e(TAG, "setTransactListener error", e);
    }
  }

  static class TransactListenerHandler implements InvocationHandler {

    private Object target;

    public TransactListenerHandler() {
    }

    public void setTarget(Object target) {
      this.target = target;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      String methodName = method.getName();
      if ("onTransactStarted".equals(methodName)) {
        Issue ipcIssue = new Issue(Issue.TYPE_IPC, "IPC", StackTraceUtils.list());
        ipcIssue.print();
      }
      if (null != target) {
        method.setAccessible(true);
        return method.invoke(target, args);
      }
      return null;
    }
  }

  static class SetTransactListenerHook extends MethodHook {
    @Override
    public void afterHookedMethod(MethodParam param) throws Throwable {
      super.afterHookedMethod(param);
      setTransactListener(param.getArgs()[0]);
    }
  }

  static class IPCIssue extends Issue {

    Object ipcInterface;

    public IPCIssue(Object ipcInterface, String msg, Object data) {
      super(Issue.TYPE_IPC, msg, data);
      this.ipcInterface = ipcInterface;
    }

    @Override
    protected void buildOtherString(StringBuilder sb) {
      if (null != ipcInterface) {
        sb.append("ipc interface: ").append(ipcInterface).append('\n');
      }
    }
  }

}