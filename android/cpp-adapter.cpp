#include <jni.h>
#include <string>
#include "react-native-stockfish.h"

#define STR_SIZE 1024
// Per-thread: overlapping reader coroutines must never share a copy buffer.
thread_local char conv_buffer[STR_SIZE + 1];
thread_local char err_conv_buffer[STR_SIZE + 1];

extern "C" JNIEXPORT void JNICALL
Java_com_loloof64_reactnativestockfish_ReactNativeStockfishModule_prepareLaunch(JNIEnv *env, jclass type)
{
    reactnativestockfish::stockfish_prepare_launch();
}

// Kotlin declares `external fun main()` (void); match that signature.
extern "C" JNIEXPORT void JNICALL
Java_com_loloof64_reactnativestockfish_ReactNativeStockfishModule_main(JNIEnv *env, jclass type)
{
    reactnativestockfish::stockfish_main();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_loloof64_reactnativestockfish_ReactNativeStockfishModule_stdinWrite(JNIEnv *env, jclass type, jstring command)
{
    ssize_t result;

    jboolean isCopy;
    const char *str = env->GetStringUTFChars(command, &isCopy);

    result = reactnativestockfish::stockfish_stdin_write(str);
    env->ReleaseStringUTFChars(command, str);

    if (result < 0)
    {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_loloof64_reactnativestockfish_ReactNativeStockfishModule_stdoutRead(JNIEnv *env, jclass type)
{
    char *output = reactnativestockfish::stockfish_stdout_read();
    // An error occured
    if (output == NULL)
    {
        return NULL;
    }

    std::strncpy(conv_buffer, output, STR_SIZE);

    return env->NewStringUTF(conv_buffer);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_loloof64_reactnativestockfish_ReactNativeStockfishModule_stderrRead(JNIEnv *env, jclass type)
{
    char *output = reactnativestockfish::stockfish_stderr_read();
    // An error occured
    if (output == NULL)
    {
        return NULL;
    }

    std::strncpy(err_conv_buffer, output, STR_SIZE);

    return env->NewStringUTF(err_conv_buffer);
}
