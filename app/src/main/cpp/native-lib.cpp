// Liga o Node.js (nodejs-mobile) ao app. Baseado no exemplo oficial do nodejs-mobile.
#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include "node.h"

static const char *TAG = "FiguritasRobo";
static int pipe_out[2];
static pthread_t thread_out;

static void *read_output(void *) {
    char buf[2048];
    ssize_t n;
    while ((n = read(pipe_out[0], buf, sizeof buf - 1)) > 0) {
        if (buf[n - 1] == '\n') n--;
        buf[n] = 0;
        __android_log_write(ANDROID_LOG_INFO, TAG, buf);
    }
    return nullptr;
}

static void redirect_output() {
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    pipe(pipe_out);
    dup2(pipe_out[1], STDOUT_FILENO);
    dup2(pipe_out[1], STDERR_FILENO);
    if (pthread_create(&thread_out, nullptr, read_output, nullptr) == 0) pthread_detach(thread_out);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_juliobrunno_figurinhas_NodeRunner_startNodeWithArguments(JNIEnv *env, jclass, jobjectArray arguments) {
    jsize count = env->GetArrayLength(arguments);
    // O Node exige que os argumentos fiquem um atrás do outro na memória
    size_t total = 0;
    for (jsize i = 0; i < count; i++) {
        auto s = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *c = env->GetStringUTFChars(s, nullptr);
        total += strlen(c) + 1;
        env->ReleaseStringUTFChars(s, c);
    }
    char *buffer = (char *) calloc(total, 1);
    char **argv = (char **) calloc(count + 1, sizeof(char *));
    char *p = buffer;
    for (jsize i = 0; i < count; i++) {
        auto s = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *c = env->GetStringUTFChars(s, nullptr);
        size_t len = strlen(c);
        memcpy(p, c, len);
        argv[i] = p;
        p += len + 1;
        env->ReleaseStringUTFChars(s, c);
    }
    redirect_output();
    return jint(node::Start(count, argv));
}
