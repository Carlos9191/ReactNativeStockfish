package com.loloof64.reactnativestockfish

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule

import kotlinx.coroutines.*

@ReactModule(name = ReactNativeStockfishModule.NAME)
class ReactNativeStockfishModule(reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  // Process-wide state. The native engine (fakein/fakeout queues, the UCI
  // thread) is a singleton, so exactly one launch may own it at a time. Every
  // launch gets a fresh id; readers from an older launch exit as soon as they
  // observe a newer id, even if they were parked inside a blocking native read
  // when the launch changed. This is what prevents the "two engines + two
  // readers" state after a Metro reload or an explicit restart.
  companion object {
    const val NAME = "ReactNativeStockfish"
    private const val TAG = "ReactNativeStockfish"
    private const val ENGINE_JOIN_TIMEOUT_MS = 2000L

    @Volatile
    private var globalIsRunning = false
    @Volatile
    private var globalLaunchId = 0L
    private var globalOutputReaderCoroutineScope: CoroutineScope? = null
    private var globalErrorReaderCoroutineScope: CoroutineScope? = null
    private var globalStockfishThread: Thread? = null
    @Volatile
    private var globalReactContext: ReactApplicationContext? = null

    // JNI entry points. Declared @JvmStatic so the companion can drive the
    // engine during teardown; the JNI symbols
    // (Java_com_loloof64_reactnativestockfish_ReactNativeStockfishModule_<name>)
    // are unchanged and already take a jclass parameter.
    @JvmStatic external fun prepareLaunch()
    @JvmStatic external fun main()
    @JvmStatic external fun stdoutRead(): String?
    @JvmStatic external fun stderrRead(): String?
    @JvmStatic external fun stdinWrite(command: String)

    // Tear down whatever launch currently owns the engine. Safe to call when
    // nothing is running. Blocks (bounded) until the old UCI thread has exited
    // so the next launch starts from quiescent streams.
    @Synchronized
    private fun stopGlobalInstance() {
      // Invalidate the launch first so any reader that wakes up exits.
      globalLaunchId += 1
      globalIsRunning = false

      globalOutputReaderCoroutineScope?.cancel()
      globalErrorReaderCoroutineScope?.cancel()
      globalOutputReaderCoroutineScope = null
      globalErrorReaderCoroutineScope = null

      val thread = globalStockfishThread
      globalStockfishThread = null
      if (thread != null && thread.isAlive) {
        // Ask the UCI loop to exit; the engine closes its streams on the way out,
        // which also unblocks any reader parked in a native read.
        try {
          stdinWrite("quit\n")
        } catch (e: Throwable) {
          android.util.Log.w(TAG, "Failed to send quit to old engine", e)
        }
        try {
          thread.join(ENGINE_JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
        }
        if (thread.isAlive) {
          android.util.Log.w(TAG, "Old Stockfish thread did not exit within ${ENGINE_JOIN_TIMEOUT_MS}ms")
        }
      }
    }
  }

  private var outputReaderCoroutineScope: CoroutineScope? = null
  private var errorReaderCoroutineScope: CoroutineScope? = null
  private var stockfishThread: Thread? = null
  @Volatile
  private var isRunning = false

  override fun getName(): String {
    return NAME
  }

  init {
    System.loadLibrary("react-native-stockfish")
    // A new module instance (e.g. Metro reload) must not coexist with a running
    // engine owned by the previous instance.
    if (globalIsRunning) {
      android.util.Log.w(TAG, "New module instance created while old one is running - stopping old instance")
      stopGlobalInstance()
    }
    globalReactContext = reactContext
  }

  override fun onCatalystInstanceDestroy() {
    super.onCatalystInstanceDestroy()
    stopGlobalInstance()
    isRunning = false
  }

  @ReactMethod
  fun stockfishLoop() {
    // Any previous launch (this instance or another) is torn down first; this
    // blocks until its UCI thread exits so the fresh engine never shares the
    // stdin queue with a stale one.
    stopGlobalInstance()

    // Same monitor as the @Synchronized companion helpers.
    val launchId = synchronized(Companion) {
      globalLaunchId += 1
      globalIsRunning = true
      globalReactContext = reactApplicationContext
      globalLaunchId
    }
    isRunning = true
    val delayTimeMs = 1L

    // Re-arm the streams now (the previous run closed them on exit) so that
    // commands JS sends right after this call are queued, not dropped.
    prepareLaunch()

    val outputScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val errorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    outputReaderCoroutineScope = outputScope
    errorReaderCoroutineScope = errorScope
    globalOutputReaderCoroutineScope = outputScope
    globalErrorReaderCoroutineScope = errorScope

    val thread = Thread {
      Thread.sleep(delayTimeMs)
      main()
      // Only clear the running flags if this launch is still the current one;
      // a newer launch may already own them.
      if (globalLaunchId == launchId) {
        isRunning = false
        globalIsRunning = false
      }
    }
    stockfishThread = thread
    globalStockfishThread = thread
    thread.start()

    outputScope.launch {
      while (isActive && globalLaunchId == launchId && globalIsRunning) {
        val output = stdoutRead()
        // Re-check after the (blocking) read: the launch may have changed while
        // we were parked, in which case this token belongs to nobody.
        if (globalLaunchId != launchId) {
          break
        }
        if (output == null) {
          delay(delayTimeMs)
          continue
        }
        emit("stockfish-output", output)
        delay(delayTimeMs)
      }
    }
    errorScope.launch {
      while (isActive && globalLaunchId == launchId && globalIsRunning) {
        val output = stderrRead()
        if (globalLaunchId != launchId) {
          break
        }
        if (output == null) {
          delay(delayTimeMs)
          continue
        }
        emit("stockfish-error", output)
        delay(delayTimeMs)
      }
    }
  }

  private fun emit(eventName: String, body: String) {
    val contextToUse = globalReactContext ?: reactApplicationContext
    try {
      contextToUse
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit(eventName, body)
    } catch (e: Exception) {
      // React context may be invalid during a reload; keep draining so the
      // native queue never backs up.
    }
  }

  @ReactMethod
  fun sendCommandToStockfish(command: String) {
    if (isRunning && globalIsRunning) {
      stdinWrite(command)
    }
  }

  @ReactMethod
  fun stopStockfish() {
    // stopGlobalInstance sends `quit` while the engine is still flagged as
    // running and waits for the UCI thread to exit, so the engine really stops
    // (previously the quit was gated behind an already-cleared flag and never
    // reached the engine, leaving it alive alongside its successor).
    stopGlobalInstance()
    isRunning = false
    outputReaderCoroutineScope = null
    errorReaderCoroutineScope = null
    stockfishThread = null
  }
}
