/*
 * curl_stub.cpp — Android libcurl easy-GET compatibility layer.
 *
 * RecompFrontend only needs a small subset of libcurl for mod discovery and
 * downloads. Rather than bundle a second TLS stack, HTTPS is delegated to
 * HttpBridge.java / HttpURLConnection so Android's system trust store is used.
 * Java streams each response into an app-private temp file; this native layer
 * then feeds the file through the normal CURLOPT_WRITEFUNCTION callback.
 */
#include <curl/curl.h>

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <mutex>
#include <new>
#include <string>
#include <vector>

namespace {

#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, "DK64Recomp", __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, "DK64Recomp", __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DK64Recomp", __VA_ARGS__)

struct CurlHandle {
    std::string url;
    std::string userAgent;
    std::vector<std::string> headers;
    curl_write_callback writeCallback = nullptr;
    void *writeData = nullptr;
    long timeoutSeconds = 0;
    bool followRedirects = false;
    bool verifyPeer = true;
    long verifyHost = 2;
    long responseCode = 0;
};

std::mutex g_jni_mutex;
JavaVM *g_vm = nullptr;
jclass g_http_class = nullptr;
jmethodID g_mid_perform_get = nullptr;

constexpr const char *kPerformGetSignature =
    "(Ljava/lang/String;IZLjava/lang/String;[Ljava/lang/String;)[Ljava/lang/String;";

struct JavaResult {
    CURLcode curlCode = CURLE_FAILED_INIT;
    long httpCode = 0;
    std::string tempPath;
    std::string error;
};

static std::string jstring_to_string(JNIEnv *env, jstring value) {
    if (env == nullptr || value == nullptr) return {};
    const char *utf = env->GetStringUTFChars(value, nullptr);
    if (utf == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return {};
    }
    std::string out(utf);
    env->ReleaseStringUTFChars(value, utf);
    return out;
}

static JavaResult call_java_get(const CurlHandle &handle) {
    JavaVM *vm = nullptr;
    jclass httpClass = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_jni_mutex);
        vm = g_vm;
        httpClass = g_http_class;
        mid = g_mid_perform_get;
    }

    JavaResult out;
    if (vm == nullptr || httpClass == nullptr || mid == nullptr) {
        out.error = "Android HTTP bridge is not initialized";
        return out;
    }
    if (handle.url.empty()) {
        out.curlCode = CURLE_UNSUPPORTED_PROTOCOL;
        out.error = "No URL specified";
        return out;
    }

    JNIEnv *env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
            out.error = "AttachCurrentThread failed";
            return out;
        }
        attached = true;
    }

    auto finish = [&]() {
        if (attached) vm->DetachCurrentThread();
    };

    jstring jUrl = env->NewStringUTF(handle.url.c_str());
    jstring jUserAgent = handle.userAgent.empty()
        ? nullptr
        : env->NewStringUTF(handle.userAgent.c_str());

    jclass stringClass = env->FindClass("java/lang/String");
    if (jUrl == nullptr || stringClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (jUrl != nullptr) env->DeleteLocalRef(jUrl);
        if (jUserAgent != nullptr) env->DeleteLocalRef(jUserAgent);
        if (stringClass != nullptr) env->DeleteLocalRef(stringClass);
        out.curlCode = CURLE_OUT_OF_MEMORY;
        out.error = "Failed to allocate JNI request objects";
        finish();
        return out;
    }

    jobjectArray jHeaders = env->NewObjectArray(
        static_cast<jsize>(handle.headers.size()), stringClass, nullptr);
    if (jHeaders == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(stringClass);
        env->DeleteLocalRef(jUrl);
        if (jUserAgent != nullptr) env->DeleteLocalRef(jUserAgent);
        out.curlCode = CURLE_OUT_OF_MEMORY;
        out.error = "Failed to allocate JNI header array";
        finish();
        return out;
    }

    for (jsize i = 0; i < static_cast<jsize>(handle.headers.size()); ++i) {
        jstring item = env->NewStringUTF(handle.headers[static_cast<size_t>(i)].c_str());
        if (item != nullptr) {
            env->SetObjectArrayElement(jHeaders, i, item);
            env->DeleteLocalRef(item);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            out.curlCode = CURLE_OUT_OF_MEMORY;
            out.error = "Failed to build JNI header array";
            env->DeleteLocalRef(jHeaders);
            env->DeleteLocalRef(stringClass);
            env->DeleteLocalRef(jUrl);
            if (jUserAgent != nullptr) env->DeleteLocalRef(jUserAgent);
            finish();
            return out;
        }
    }

    long timeoutMsLong = handle.timeoutSeconds > 0
        ? handle.timeoutSeconds * 1000L
        : 120000L;
    timeoutMsLong = std::clamp<long>(
        timeoutMsLong, 1L, static_cast<long>(std::numeric_limits<jint>::max()));

    jobjectArray result = static_cast<jobjectArray>(
        env->CallStaticObjectMethod(httpClass, mid,
                                    jUrl,
                                    static_cast<jint>(timeoutMsLong),
                                    handle.followRedirects ? JNI_TRUE : JNI_FALSE,
                                    jUserAgent,
                                    jHeaders));

    env->DeleteLocalRef(jHeaders);
    env->DeleteLocalRef(stringClass);
    env->DeleteLocalRef(jUrl);
    if (jUserAgent != nullptr) env->DeleteLocalRef(jUserAgent);

    if (env->ExceptionCheck()) {
        ALOGE("HTTP bridge: Java exception during request");
        env->ExceptionDescribe();
        env->ExceptionClear();
        out.curlCode = CURLE_FAILED_INIT;
        out.error = "Java exception during HTTP request";
        if (result != nullptr) env->DeleteLocalRef(result);
        finish();
        return out;
    }

    if (result == nullptr || env->GetArrayLength(result) < 4) {
        out.curlCode = CURLE_FAILED_INIT;
        out.error = "Invalid response from Android HTTP bridge";
        if (result != nullptr) env->DeleteLocalRef(result);
        finish();
        return out;
    }

    auto getElement = [&](jsize index) -> std::string {
        jstring value = static_cast<jstring>(env->GetObjectArrayElement(result, index));
        std::string str = jstring_to_string(env, value);
        if (value != nullptr) env->DeleteLocalRef(value);
        return str;
    };

    const std::string curlCodeText = getElement(0);
    const std::string httpCodeText = getElement(1);
    out.tempPath = getElement(2);
    out.error = getElement(3);

    out.curlCode = static_cast<CURLcode>(std::atoi(curlCodeText.c_str()));
    out.httpCode = std::strtol(httpCodeText.c_str(), nullptr, 10);

    env->DeleteLocalRef(result);
    finish();
    return out;
}

static CURLcode stream_temp_file(const std::string &path, CurlHandle &handle) {
    FILE *input = std::fopen(path.c_str(), "rb");
    if (input == nullptr) {
        ALOGE("HTTP bridge: could not open response temp file '%s'", path.c_str());
        return CURLE_FAILED_INIT;
    }

    CURLcode result = CURLE_OK;
    std::vector<unsigned char> buffer(64 * 1024);

    while (true) {
        const size_t n = std::fread(buffer.data(), 1, buffer.size(), input);
        if (n > 0) {
            size_t consumed = 0;
            if (handle.writeCallback != nullptr) {
                consumed = handle.writeCallback(buffer.data(), 1, n, handle.writeData);
            } else {
                consumed = std::fwrite(buffer.data(), 1, n, stdout);
            }
            if (consumed != n) {
                result = CURLE_WRITE_ERROR;
                break;
            }
        }

        if (n < buffer.size()) {
            if (std::ferror(input)) result = CURLE_FAILED_INIT;
            break;
        }
    }

    std::fclose(input);
    return result;
}

static CurlHandle *as_handle(CURL *curl) {
    return reinterpret_cast<CurlHandle *>(curl);
}

} // namespace

extern "C" {

CURLcode curl_global_init(long) {
    return CURLE_OK;
}

void curl_global_cleanup(void) {
}

CURL *curl_easy_init(void) {
    CurlHandle *handle = new (std::nothrow) CurlHandle();
    return reinterpret_cast<CURL *>(handle);
}

CURLcode curl_easy_setopt(CURL *curl, CURLoption option, ...) {
    CurlHandle *handle = as_handle(curl);
    if (handle == nullptr) return CURLE_FAILED_INIT;

    va_list args;
    va_start(args, option);

    switch (option) {
    case CURLOPT_URL: {
        const char *value = va_arg(args, const char *);
        handle->url = value ? value : "";
        break;
    }
    case CURLOPT_WRITEFUNCTION:
        handle->writeCallback = va_arg(args, curl_write_callback);
        break;
    case CURLOPT_WRITEDATA:
        handle->writeData = va_arg(args, void *);
        break;
    case CURLOPT_FOLLOWLOCATION:
        handle->followRedirects = va_arg(args, long) != 0;
        break;
    case CURLOPT_USERAGENT: {
        const char *value = va_arg(args, const char *);
        handle->userAgent = value ? value : "";
        break;
    }
    case CURLOPT_HTTPHEADER: {
        const curl_slist *list = va_arg(args, const curl_slist *);
        handle->headers.clear();
        for (const curl_slist *item = list; item != nullptr; item = item->next) {
            if (item->data != nullptr) handle->headers.emplace_back(item->data);
        }
        break;
    }
    case CURLOPT_TIMEOUT:
        handle->timeoutSeconds = va_arg(args, long);
        break;
    case CURLOPT_SSL_VERIFYPEER:
        handle->verifyPeer = va_arg(args, long) != 0;
        break;
    case CURLOPT_SSL_VERIFYHOST:
        handle->verifyHost = va_arg(args, long);
        break;
    default:
        va_end(args);
        return CURLE_UNSUPPORTED_PROTOCOL;
    }

    va_end(args);
    return CURLE_OK;
}

CURLcode curl_easy_perform(CURL *curl) {
    CurlHandle *handle = as_handle(curl);
    if (handle == nullptr) return CURLE_FAILED_INIT;

    // HttpURLConnection always performs normal platform certificate and
    // hostname verification. RecompFrontend requests verification explicitly;
    // refuse any hypothetical attempt to disable it rather than weakening TLS.
    if (!handle->verifyPeer || handle->verifyHost == 0) {
        ALOGW("HTTP bridge: ignoring request to weaken TLS verification");
    }

    handle->responseCode = 0;
    JavaResult response = call_java_get(*handle);
    handle->responseCode = response.httpCode;

    if (response.curlCode != CURLE_OK) {
        ALOGE("HTTP bridge request failed (curl=%d, url=%s): %s",
              static_cast<int>(response.curlCode), handle->url.c_str(),
              response.error.c_str());
        return response.curlCode;
    }

    if (response.tempPath.empty()) {
        ALOGE("HTTP bridge returned no response file for %s", handle->url.c_str());
        return CURLE_FAILED_INIT;
    }

    CURLcode streamResult = stream_temp_file(response.tempPath, *handle);
    std::remove(response.tempPath.c_str());
    return streamResult;
}

CURLcode curl_easy_getinfo(CURL *curl, CURLINFO info, ...) {
    CurlHandle *handle = as_handle(curl);
    if (handle == nullptr) return CURLE_FAILED_INIT;

    va_list args;
    va_start(args, info);

    CURLcode result = CURLE_OK;
    if (info == CURLINFO_RESPONSE_CODE) {
        long *out = va_arg(args, long *);
        if (out != nullptr) *out = handle->responseCode;
    } else {
        result = CURLE_UNSUPPORTED_PROTOCOL;
    }

    va_end(args);
    return result;
}

void curl_easy_cleanup(CURL *curl) {
    delete as_handle(curl);
}

const char *curl_easy_strerror(CURLcode code) {
    switch (code) {
    case CURLE_OK:
        return "No error";
    case CURLE_UNSUPPORTED_PROTOCOL:
        return "Unsupported protocol or option";
    case CURLE_FAILED_INIT:
        return "HTTP bridge initialization failed";
    case CURLE_COULDNT_RESOLVE_HOST:
        return "Could not resolve host";
    case CURLE_COULDNT_CONNECT:
        return "Could not connect";
    case CURLE_WRITE_ERROR:
        return "Failed writing received data";
    case CURLE_OUT_OF_MEMORY:
        return "Out of memory";
    case CURLE_OPERATION_TIMEDOUT:
        return "Operation timed out";
    case CURLE_PEER_FAILED_VERIFICATION:
        return "TLS certificate verification failed";
    default:
        return "Android HTTP bridge error";
    }
}

struct curl_slist *curl_slist_append(struct curl_slist *list, const char *data) {
    curl_slist *item = static_cast<curl_slist *>(std::malloc(sizeof(curl_slist)));
    if (item == nullptr) return list;

    const char *source = data ? data : "";
    const size_t length = std::strlen(source);
    item->data = static_cast<char *>(std::malloc(length + 1));
    if (item->data == nullptr) {
        std::free(item);
        return list;
    }
    std::memcpy(item->data, source, length + 1);
    item->next = nullptr;

    if (list == nullptr) return item;

    curl_slist *end = list;
    while (end->next != nullptr) end = end->next;
    end->next = item;
    return list;
}

void curl_slist_free_all(struct curl_slist *list) {
    while (list != nullptr) {
        curl_slist *next = list->next;
        std::free(list->data);
        std::free(list);
        list = next;
    }
}

// Called from HttpBridge.init() on the Java UI thread after libmain.so has
// been loaded. Cache the class and method here because FindClass from native
// worker threads would use the boot class loader and not see app classes.
JNIEXPORT void JNICALL
Java_com_deivid22srk_dk64recomp_HttpBridge_nativeInit(JNIEnv *env, jclass clazz) {
    JavaVM *vm = nullptr;
    if (env == nullptr || env->GetJavaVM(&vm) != JNI_OK || vm == nullptr) {
        ALOGE("HTTP bridge: GetJavaVM failed");
        return;
    }

    jmethodID mid = env->GetStaticMethodID(clazz, "performGet", kPerformGetSignature);
    if (mid == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        ALOGE("HTTP bridge: performGet method not found");
        return;
    }

    jclass globalClass = static_cast<jclass>(env->NewGlobalRef(clazz));
    if (globalClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        ALOGE("HTTP bridge: NewGlobalRef failed");
        return;
    }

    std::lock_guard<std::mutex> lock(g_jni_mutex);
    if (g_http_class != nullptr) env->DeleteGlobalRef(g_http_class);
    g_vm = vm;
    g_http_class = globalClass;
    g_mid_perform_get = mid;
    ALOGI("HTTP bridge native cache ready");
}

} // extern "C"
