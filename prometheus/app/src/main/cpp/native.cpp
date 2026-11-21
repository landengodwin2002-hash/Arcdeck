// Lifecycle adapter only. All inference and chat processing are upstream llama.cpp.
#include <jni.h>
#include <android/log.h>
#include <exception>
#include <string>
#include <vector>

int llama_server(int argc, char ** argv);
void llama_server_terminate();

extern "C" JNIEXPORT jint JNICALL
Java_ai_prometheus_android_LlamaRuntime_serve(JNIEnv * env, jclass, jobjectArray arguments) {
    try {
        std::vector<std::string> strings;
        const int count = env->GetArrayLength(arguments);
        for (int i = 0; i < count; ++i) {
            auto arg = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
            const char * text = env->GetStringUTFChars(arg, nullptr);
            if (!text) return -1;
            strings.emplace_back(text);
            env->ReleaseStringUTFChars(arg, text);
            env->DeleteLocalRef(arg);
        }
        std::vector<char *> argv;
        for (auto & arg : strings) argv.push_back(arg.data());
        argv.push_back(nullptr);
        return llama_server(count, argv.data());
    } catch (const std::exception & error) {
        __android_log_print(ANDROID_LOG_ERROR, "PrometheusRuntime", "%s", error.what());
        return -1;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_ai_prometheus_android_LlamaRuntime_terminate(JNIEnv *, jclass) {
    llama_server_terminate();
}
