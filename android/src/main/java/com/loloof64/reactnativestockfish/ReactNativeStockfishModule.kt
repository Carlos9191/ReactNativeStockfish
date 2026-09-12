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

  // Static/companion state to ensure only one instance runs at a time
  // This is critical for Metro reload where new module instances are created
  companion object {
    const val NAME = "ReactNativeStockfish"
    
    @Volatile
    private var globalIsRunning = false
    private var globalOutputReaderCoroutineScope: CoroutineScope? = null
    private var globalErrorReaderCoroutineScope: CoroutineScope? = null
    private var globalStockfishThread: Thread? = null
    private var globalReactContext: ReactApplicationContext? = null
    
    private fun stopGlobalInstance() {
      globalIsRunning = false
      globalOutputReaderCoroutineScope?.cancel()
      globalErrorReaderCoroutineScope?.cancel()
      globalStockfishThread?.interrupt()
      globalOutputReaderCoroutineScope = null
      globalErrorReaderCoroutineScope = null
      globalStockfishThread = null
    }
  }

  private var mainCoroutineScope = CoroutineScope(Dispatchers.Default)
  private var outputReaderCoroutineScope = CoroutineScope(Dispatchers.Default)
  private var errorReaderCoroutineScope = CoroutineScope(Dispatchers.Default)
  private var stockfishThread: Thread? = null
  private var isRunning = false

  external fun main()
  external fun stdoutRead(): String?
  external fun stderrRead(): String?
  external fun stdinWrite(command: String)


  override fun getName(): String {
    return NAME
  }

  init {
    System.loadLibrary("react-native-stockfish")
    // On module creation (including Metro reload), stop any existing global instance
    // This ensures old coroutines don't interfere with new ones
    if (globalIsRunning) {
      android.util.Log.w("ReactNativeStockfish", "New module instance created while old one is running - stopping old instance")
      stopGlobalInstance()
      // Give a moment for cleanup
      Thread.sleep(300)
    }
    globalReactContext = reactContext
  }
  
  // Cleanup when module is destroyed (though this might not be called on Metro reload)
  override fun onCatalystInstanceDestroy() {
    super.onCatalystInstanceDestroy()
    stopGlobalInstance()
  }

  @ReactMethod
  fun stockfishLoop() {
    // Always stop global instance first (critical for Metro reload)
    if (globalIsRunning) {
      android.util.Log.w("ReactNativeStockfish", "Stopping existing global instance before starting new one")
      stopGlobalInstance()
      Thread.sleep(300)
    }
    
    // Stop local instance if running
    if (isRunning) {
      stopStockfish()
      Thread.sleep(200)
    }
    
    // Update global state
    globalIsRunning = true
    isRunning = true
    globalReactContext = reactApplicationContext
    val delayTimeMs = 1L  // Reduced from 10ms to 1ms for 10x faster response
    
    if (!mainCoroutineScope.isActive) {
      mainCoroutineScope = CoroutineScope(Dispatchers.Default)
    }
    if (!outputReaderCoroutineScope.isActive) {
      outputReaderCoroutineScope = CoroutineScope(Dispatchers.Default)
    }
    if (!errorReaderCoroutineScope.isActive) {
      errorReaderCoroutineScope = CoroutineScope(Dispatchers.Default)
    }

    stockfishThread = Thread {
      Thread.sleep(delayTimeMs)
      main()
      isRunning = false
      globalIsRunning = false
    }
    globalStockfishThread = stockfishThread
    stockfishThread?.start()
    
    outputReaderCoroutineScope.launch {
      while (globalIsRunning && isRunning) {
        val output = stdoutRead()
        if (output == null) {
          delay(delayTimeMs)
          continue
        }
        // Use the current reactApplicationContext (might be updated on Metro reload)
        val contextToUse = globalReactContext ?: reactApplicationContext
        try {
          // Always try to emit - don't check for activity as it might be null during Metro reload
          contextToUse
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("stockfish-output", output)
        } catch (e: Exception) {
          // React context might be invalid during Metro reload, just continue reading
          // This prevents buffer overflow even if events can't be emitted
        }
        delay(delayTimeMs)
      }
    }
    errorReaderCoroutineScope.launch {
      while (globalIsRunning && isRunning) {
        val output = stderrRead()
        if (output == null) {
          delay(delayTimeMs)
          continue
        }
        // Use the current reactApplicationContext (might be updated on Metro reload)
        val contextToUse = globalReactContext ?: reactApplicationContext
        try {
          // Always try to emit - don't check for activity as it might be null during Metro reload
          contextToUse
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("stockfish-error", output)
        } catch (e: Exception) {
          // React context might be invalid during Metro reload, just continue reading
        }
        delay(delayTimeMs)
      }
    }
  }

  @ReactMethod
  fun sendCommandToStockfish(command: String) {
    if (isRunning) {
      stdinWrite(command)
    }
  }

  @ReactMethod
  fun stopStockfish() {
    isRunning = false
    globalIsRunning = false
    outputReaderCoroutineScope.cancel()
    errorReaderCoroutineScope.cancel()
    globalOutputReaderCoroutineScope?.cancel()
    globalErrorReaderCoroutineScope?.cancel()
    sendCommandToStockfish("quit\n")
    stockfishThread?.interrupt()
    globalStockfishThread?.interrupt()
    stockfishThread = null
    globalStockfishThread = null
    globalOutputReaderCoroutineScope = null
    globalErrorReaderCoroutineScope = null
  }
}
