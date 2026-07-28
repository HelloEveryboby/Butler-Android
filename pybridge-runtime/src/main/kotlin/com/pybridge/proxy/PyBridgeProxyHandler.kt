package com.pybridge.proxy

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * PyBridge 动态代理 InvocationHandler
 *
 * 将 Java 接口方法调用转发到 Python 端。
 * 每个 Handler 实例对应一个 Python 端的 PyClassProxy。
 *
 * 工作流程：
 *   1. Python 端创建 PyClassProxy，调用 JNI 创建 Java 动态代理
 *   2. Java 动态代理的所有方法调用进入此 Handler
 *   3. Handler 通过 nativeInvoke() 将调用转发到 Python 端
 *   4. Python 端执行实际方法并返回结果
 *
 * 使用方式：
 * ```kotlin
 * // 通常不需要直接使用此类，由 Python 端自动创建
 * val handler = PyBridgeProxyHandler(proxyId)
 * val proxy = Proxy.newProxyInstance(
 *     classLoader,
 *     arrayOf(Runnable::class.java),
 *     handler
 * ) as Runnable
 * proxy.run()
 * ```
 */
class PyBridgeProxyHandler(private val proxyId: Int) : InvocationHandler {

    companion object {
        init {
            System.loadLibrary("pybridge")
        }
    }

    /**
     * Native 方法：将代理调用转发到 Python 端
     *
     * @param proxyId Python 端代理 ID
     * @param method  被调用的 Java 方法
     * @param args    方法参数
     * @return 方法返回值
     */
    private external fun nativeInvoke(
        proxyId: Int,
        method: Method,
        args: Array<Any?>?
    ): Any?

    override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
        // 处理 Object 方法
        return when (method.name) {
            "toString" -> "PyBridgeProxy#$proxyId"
            "hashCode" -> proxyId.hashCode()
            "equals" -> {
                if (args != null && args.size == 1) {
                    proxy === args[0]
                } else {
                    false
                }
            }
            else -> {
                // 转发到 Python
                try {
                    nativeInvoke(proxyId, method, args)
                } catch (e: Exception) {
                    throw RuntimeException(
                        "PyBridge proxy invoke failed: ${method.name}",
                        e
                    )
                }
            }
        }
    }
}

/**
 * PyProxy — 从 Python 对象创建 Java 代理的工具类
 *
 * 提供 Kotlin 端的便捷 API：
 * ```kotlin
 * val pyObj = Python.module("my_module").callAttr("get_callback")
 * val runnable = PyProxy.create(Runnable::class.java, pyObj)
 * Thread(runnable).start()
 * ```
 */
object PyProxy {

    /**
     * 从 Python 对象创建 Java 接口代理
     *
     * @param interfaceClass 要实现的 Java 接口类
     * @param pythonObject   实现接口方法的 Python 对象
     * @return Java 代理实例
     */
    @JvmStatic
    fun <T> create(interfaceClass: Class<T>, pythonObject: com.pybridge.core.PyObject): T {
        // 通过 Python 端创建代理
        val proxyId = createProxyInternal(interfaceClass.name, pythonObject)
        val handler = PyBridgeProxyHandler(proxyId)
        return Proxy.newProxyInstance(
            interfaceClass.classLoader,
            arrayOf<Class<*>>(interfaceClass),
            handler
        ) as T
    }

    /**
     * 从 Python 对象创建多个接口的 Java 代理
     *
     * @param interfaces 要实现的 Java 接口类数组
     * @param pythonObject 实现接口方法的 Python 对象
     * @return Java 代理实例
     */
    @JvmStatic
    fun create(
        interfaces: Array<Class<*>>,
        pythonObject: com.pybridge.core.PyObject
    ): Any {
        val proxyId = createProxyInternal(interfaces[0].name, pythonObject)
        val handler = PyBridgeProxyHandler(proxyId)
        return Proxy.newProxyInstance(
            interfaces[0].classLoader,
            interfaces,
            handler
        )
    }

    /**
     * 通过 Python 端创建代理（内部）
     */
    private fun createProxyInternal(interfaceName: String, pythonObject: com.pybridge.core.PyObject): Int {
        // 调用 Python 端的 static_proxy.create_java_proxy()
        val mod = com.pybridge.core.Python.module("pybridge_java.static_proxy")
        return mod.callAttr("_create_proxy_for_object", interfaceName, pythonObject).toInt()
    }
}