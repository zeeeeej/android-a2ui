#include <jni.h>
#include <atomic>
#include <chrono>
#include <string>
#include <cstring>
extern "C" {
#include "quickjs/quickjs.h"
}

namespace {
std::atomic<long> cancellation{0};
struct Limits {
    long generation;
    std::chrono::steady_clock::time_point deadline;
    bool timedOut = false;
    bool cancelled = false;
    std::string fallback;
};
int interrupt(JSRuntime *, void *opaque) {
    auto *l = static_cast<Limits *>(opaque);
    l->cancelled = cancellation.load() != l->generation;
    l->timedOut = std::chrono::steady_clock::now() >= l->deadline;
    return l->cancelled || l->timedOut;
}
std::string bytes(JNIEnv *env, jbyteArray input) {
    std::string result(env->GetArrayLength(input), '\0');
    env->GetByteArrayRegion(input, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    return result;
}
jbyteArray output(JNIEnv *env, const std::string &value) {
    auto result = env->NewByteArray(value.size());
    if (result) env->SetByteArrayRegion(result, 0, value.size(), reinterpret_cast<const jbyte *>(value.data()));
    return result;
}
// This pure callback is passed only to the trusted runner closure, never to game code.
JSValue checkpoint(JSContext *ctx, JSValueConst, int argc, JSValueConst *argv) {
    if (argc != 1 || !JS_IsString(argv[0])) return JS_ThrowTypeError(ctx, "Invalid checkpoint");
    size_t length = 0;
    const char *text = JS_ToCStringLen(ctx, &length, argv[0]);
    if (!text) return JS_EXCEPTION;
    auto *limits = static_cast<Limits *>(JS_GetContextOpaque(ctx));
    if (length <= 262144) limits->fallback.assign(text, length);
    JS_FreeCString(ctx, text);
    return JS_UNDEFINED;
}
JSModuleDef *denyModule(JSContext *ctx, const char *, void *) {
    JS_ThrowTypeError(ctx, "Module imports are disabled");
    return nullptr;
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_xpl_a2uichat_games_script_NativeScript_cancel(JNIEnv *, jobject) { cancellation.fetch_add(1); }

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xpl_a2uichat_games_script_NativeScript_run(JNIEnv *env, jobject, jbyteArray sourceBytes,
    jbyteArray runnerBytes, jbyteArray requestBytes, jlong budgetMs) {
    const std::string error = "{\"ok\":false,\"error\":\"脚本执行失败或资源超限\"}";
    if (!sourceBytes || !runnerBytes || !requestBytes || env->GetArrayLength(sourceBytes) > 65536 ||
        env->GetArrayLength(requestBytes) > 196608 || env->GetArrayLength(runnerBytes) > 65536)
        return output(env, error);
    Limits limits{cancellation.load(), std::chrono::steady_clock::now() +
        std::chrono::milliseconds(budgetMs < 1 ? 1 : budgetMs > 2000 ? 2000 : budgetMs)};
    JSRuntime *rt = JS_NewRuntime();
    if (!rt) return output(env, error);
    JS_SetMemoryLimit(rt, 32 * 1024 * 1024);
    JS_SetMaxStackSize(rt, 512 * 1024);
    JS_SetInterruptHandler(rt, interrupt, &limits);
    JS_SetModuleLoaderFunc(rt, nullptr, denyModule, nullptr);
    JS_SetCanBlock(rt, false);
    JSContext *ctx = JS_NewContext(rt);
    if (!ctx) { JS_FreeRuntime(rt); return output(env, error); }
    JS_SetContextOpaque(ctx, &limits);
    auto source = bytes(env, sourceBytes), runner = bytes(env, runnerBytes), request = bytes(env, requestBytes);
    std::string result;
    // Capture trusted intrinsics before evaluating untrusted source. No libc helpers are registered.
    JSValue driver = JS_Eval(ctx, runner.c_str(), runner.size(), "runner.js", JS_EVAL_TYPE_GLOBAL);
    JSValue game = JS_UNDEFINED, input = JS_UNDEFINED, save = JS_UNDEFINED, answer = JS_UNDEFINED;
    if (!JS_IsException(driver)) {
        game = JS_Eval(ctx, source.c_str(), source.size(), "game.js", JS_EVAL_TYPE_GLOBAL);
        if (!JS_IsException(game)) {
            input = JS_ParseJSON(ctx, request.c_str(), request.size(), "request.json");
            save = JS_NewCFunction(ctx, checkpoint, "checkpoint", 1);
            if (!JS_IsException(input)) {
                JSValue args[] = {game, input, save};
                answer = JS_Call(ctx, driver, JS_UNDEFINED, 3, args);
                if (!JS_IsException(answer) && JS_IsString(answer)) {
                    size_t length = 0;
                    const char *text = JS_ToCStringLen(ctx, &length, answer);
                    if (text && length <= 262144) result.assign(text, length);
                    if (text) JS_FreeCString(ctx, text);
                }
            }
        }
    }
    if (result.empty()) {
        if (limits.timedOut && !limits.cancelled && !limits.fallback.empty()) result = limits.fallback;
        else {
            // Error conversion is also inside the native deadline and service watchdog.
            JSValue exception = JS_GetException(ctx);
            const char *message = JS_ToCString(ctx, exception);
            if (message && !limits.timedOut && !limits.cancelled) {
                JSValue obj = JS_NewObject(ctx);
                JS_SetPropertyStr(ctx, obj, "ok", JS_FALSE);
                JS_SetPropertyStr(ctx, obj, "error", JS_NewStringLen(ctx, message, strnlen(message, 300)));
                JSValue json = JS_JSONStringify(ctx, obj, JS_UNDEFINED, JS_UNDEFINED);
                const char *encoded = JS_ToCString(ctx, json);
                if (encoded) { result = encoded; JS_FreeCString(ctx, encoded); }
                JS_FreeValue(ctx, json); JS_FreeValue(ctx, obj);
            }
            if (message) JS_FreeCString(ctx, message);
            JS_FreeValue(ctx, exception);
        }
    }
    JS_FreeValue(ctx, answer); JS_FreeValue(ctx, save); JS_FreeValue(ctx, input);
    JS_FreeValue(ctx, game); JS_FreeValue(ctx, driver);
    JS_FreeContext(ctx); JS_FreeRuntime(rt);
    if (limits.cancelled) return output(env, "{\"ok\":false,\"error\":\"脚本操作已取消\"}");
    return output(env, result.empty() ? error : result);
}
