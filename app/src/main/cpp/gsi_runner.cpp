#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#define TAG "h3cknn-gsi-runner"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

using qemu_init_fn = void (*)(int, char**, char**);
using qemu_main_loop_fn = void (*)();
using qemu_cleanup_fn = void (*)();
using qemu_shutdown_fn = void (*)();

struct Runner {
    void* library = nullptr;
    pthread_t thread{};
    int argc = 0;
    char** argv = nullptr;
};

void free_args(Runner* runner) {
    if (runner == nullptr || runner->argv == nullptr) return;
    for (int i = 0; i < runner->argc; ++i) free(runner->argv[i]);
    free(runner->argv);
    runner->argv = nullptr;
}

void* qemu_thread(void* opaque) {
    auto* runner = static_cast<Runner*>(opaque);
    auto init = reinterpret_cast<qemu_init_fn>(dlsym(runner->library, "qemu_init"));
    auto loop = reinterpret_cast<qemu_main_loop_fn>(dlsym(runner->library, "qemu_main_loop"));
    auto cleanup = reinterpret_cast<qemu_cleanup_fn>(dlsym(runner->library, "qemu_cleanup"));
    const char* error = dlerror();

    if (init != nullptr && loop != nullptr && cleanup != nullptr && error == nullptr) {
        init(runner->argc, runner->argv, nullptr);
        loop();
        cleanup();
    } else {
        dlerror();
        auto legacy_main = reinterpret_cast<qemu_init_fn>(dlsym(runner->library, "main"));
        error = dlerror();
        if (legacy_main == nullptr || error != nullptr) {
            LOGE("QEMU entry point not found: %s", error == nullptr ? "unknown error" : error);
        } else {
            legacy_main(runner->argc, runner->argv, nullptr);
        }
    }
    return nullptr;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_realheckerrr_gsilab_QemuRunner_nativeStart(
        JNIEnv* env, jclass, jstring engine_path, jobjectArray java_args) {
    if (engine_path == nullptr || java_args == nullptr) return 0;

    const char* path = env->GetStringUTFChars(engine_path, nullptr);
    void* library = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    env->ReleaseStringUTFChars(engine_path, path);
    if (library == nullptr) {
        LOGE("Cannot load QEMU engine: %s", dlerror());
        return 0;
    }

    auto* runner = new Runner();
    runner->library = library;
    jsize arg_count = env->GetArrayLength(java_args);
    runner->argc = static_cast<int>(arg_count) + 1;
    runner->argv = static_cast<char**>(calloc(static_cast<size_t>(runner->argc) + 1, sizeof(char*)));
    runner->argv[0] = strdup("qemu-system-aarch64");
    for (jsize i = 0; i < arg_count; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(java_args, i));
        const char* text = env->GetStringUTFChars(value, nullptr);
        runner->argv[i + 1] = strdup(text);
        env->ReleaseStringUTFChars(value, text);
        env->DeleteLocalRef(value);
    }

    if (pthread_create(&runner->thread, nullptr, qemu_thread, runner) != 0) {
        LOGE("Cannot create QEMU thread");
        free_args(runner);
        dlclose(runner->library);
        delete runner;
        return 0;
    }
    return reinterpret_cast<jlong>(runner);
}

extern "C" JNIEXPORT void JNICALL
Java_com_realheckerrr_gsilab_QemuRunner_nativeStop(JNIEnv*, jclass, jlong handle) {
    auto* runner = reinterpret_cast<Runner*>(handle);
    if (runner == nullptr) return;
    dlerror();
    auto shutdown = reinterpret_cast<qemu_shutdown_fn>(dlsym(runner->library, "qemu_system_shutdown_request"));
    if (shutdown != nullptr && dlerror() == nullptr) shutdown();
    pthread_join(runner->thread, nullptr);
    free_args(runner);
    dlclose(runner->library);
    delete runner;
}
