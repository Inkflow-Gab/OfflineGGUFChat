// Null backend: compiled when the APK was built without llama.cpp.
//
// Every entry point fails with an actionable message instead of handing back a
// null model handle for the caller to dereference. The app stays fully usable
// (GGUF scan/inspect/import, history, settings) and the Settings screen shows
// this exact string in its engine card.
#include "llama_bridge.h"

#include <atomic>
#include <string>

namespace ogg {
namespace {

constexpr const char *kMessage =
    "Inference engine not compiled into this build.\n\n"
    "This APK contains the GGUF tooling (scan, inspect, import, chat history) but "
    "no llama.cpp backend, so generation is unavailable.\n\n"
    "To build with inference:\n"
    "  1. ./scripts/sync-llama.sh\n"
    "  2. ./gradlew assembleRelease -Pofflinegguf.llama=true\n\n"
    "See docs/NATIVE.md.";

struct NullSession {
    std::atomic<bool> running{false};
    std::string last_error{kMessage};
};

NullSession *AsSession(BridgeSession *session) {
    return reinterpret_cast<NullSession *>(session);
}

}  // namespace

bool BridgeAvailable() { return false; }

std::string BridgeEngineVersion() { return "none (llama.cpp not linked)"; }

BridgeSession *BridgeCreate() { return reinterpret_cast<BridgeSession *>(new NullSession()); }

void BridgeDestroy(BridgeSession *session) { delete AsSession(session); }

bool BridgeLoad(BridgeSession *session, const std::string &, const BridgeLoadParams &,
                std::string *error) {
    if (auto *s = AsSession(session)) s->last_error = kMessage;
    if (error) *error = kMessage;
    return false;
}

void BridgeUnload(BridgeSession *) {}
bool BridgeIsLoaded(BridgeSession *) { return false; }

bool BridgeGenerate(BridgeSession *session, const std::string &, const BridgeSampleParams &,
                    const BridgeCallbacks &callbacks, BridgeStats *) {
    if (auto *s = AsSession(session)) s->last_error = kMessage;
    if (callbacks.on_error) callbacks.on_error(callbacks.user, kMessage);
    return false;
}

void BridgeRequestStop(BridgeSession *session) {
    if (auto *s = AsSession(session)) s->running = false;
}

bool BridgeIsBusy(BridgeSession *) { return false; }
void BridgeWaitIdle(BridgeSession *, int64_t) {}

int32_t BridgeVocabSize(BridgeSession *) { return 0; }
int32_t BridgeNCtxTrain(BridgeSession *) { return 0; }
int32_t BridgeContextSize(BridgeSession *) { return 0; }
int32_t BridgeContextUsed(BridgeSession *) { return 0; }
int64_t BridgeParamCount(BridgeSession *) { return 0; }
int64_t BridgeWeightBytes(BridgeSession *) { return 0; }
std::string BridgeModelDescription(BridgeSession *) { return "unknown"; }
std::string BridgeChatTemplate(BridgeSession *) { return std::string(); }
bool BridgeHasMultimodalProjector(BridgeSession *) { return false; }
bool BridgeSupportsAdapters(BridgeSession *) { return false; }
void BridgeRewindContext(BridgeSession *, int32_t) {}

const char *BridgeLastError(BridgeSession *session) {
    if (auto *s = AsSession(session)) return s->last_error.c_str();
    return kMessage;
}

}  // namespace ogg
