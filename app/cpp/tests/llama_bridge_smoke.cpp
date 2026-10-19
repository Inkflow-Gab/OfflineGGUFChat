#include "llama_bridge.h"

#include <iostream>
#include <string>

int main() {
    if (!ogg::BridgeAvailable()) {
        std::cerr << "real llama.cpp bridge reports unavailable\n";
        return 1;
    }

    const std::string version = ogg::BridgeEngineVersion();
    if (version.empty() || version.find("not linked") != std::string::npos ||
        version.find("not compiled") != std::string::npos) {
        std::cerr << "unexpected llama.cpp version string: " << version << '\n';
        return 2;
    }

    ogg::BridgeSession *session = ogg::BridgeCreate();
    if (!session) {
        std::cerr << "BridgeCreate returned null\n";
        return 3;
    }
    if (ogg::BridgeIsLoaded(session)) {
        std::cerr << "a fresh bridge session claims to have a model loaded\n";
        ogg::BridgeDestroy(session);
        return 4;
    }

    // Exercise the real load/error/unload boundary without downloading a model.
    // This catches a bridge that merely compiles but does not actually call into
    // llama.cpp, and verifies that a bad model path is reported rather than
    // leaving a half-loaded session behind.
    ogg::BridgeLoadParams params;
    params.n_ctx = 128;
    params.n_batch = 32;
    params.n_threads = 2;
    std::string error;
    const bool loaded = ogg::BridgeLoad(
        session, "/offlinegguf-test-does-not-exist/model.gguf", params, &error);
    if (loaded || error.empty() || ogg::BridgeIsLoaded(session)) {
        std::cerr << "nonexistent model path did not fail cleanly; error='" << error << "'\n";
        ogg::BridgeDestroy(session);
        return 5;
    }

    const char *last_error = ogg::BridgeLastError(session);
    if (!last_error || std::string(last_error).empty()) {
        std::cerr << "bridge did not retain its load error\n";
        ogg::BridgeDestroy(session);
        return 6;
    }

    ogg::BridgeDestroy(session);
    std::cout << "real llama.cpp bridge smoke passed: " << version << '\n';
    return 0;
}
