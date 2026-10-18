// Host test for the UTF-8 framer shared by the JNI layer and the bridge.
//
// The Kotlin side (util/StreamingUtf8Decoder.kt) implements the same rule, and
// the JVM unit test asserts the same table. Keeping both here means a change to
// one framing rule breaks a build somewhere instead of corrupting an answer.
#include "util/utf8.h"

#include <algorithm>
#include <cstdint>
#include <initializer_list>
#include <iostream>
#include <string>
#include <vector>

namespace {

int failures = 0;

void Check(const std::string &name, size_t got, size_t want) {
    if (got != want) {
        std::cout << "FAIL " << name << ": got " << got << ", want " << want << "\n";
        ++failures;
    }
}

// Bytes from a list, so invalid sequences are easy to write down.
std::string Bytes(std::initializer_list<int> values) {
    std::string out;
    for (int v : values) out.push_back(static_cast<char>(static_cast<uint8_t>(v)));
    return out;
}

}  // namespace

int main() {
    using ogg::CompleteUtf8Length;

    // ---- trivial ----
    Check("null", CompleteUtf8Length(nullptr, 0), 0);
    Check("empty", CompleteUtf8Length("", 0), 0);
    Check("ascii", CompleteUtf8Length("hello", 5), 5);
    Check("ascii partial", CompleteUtf8Length("hello", 3), 3);

    // ---- two bytes: é = C3 A9 ----
    {
        const std::string s = Bytes({0xC3, 0xA9});
        Check("2-byte complete", CompleteUtf8Length(s.data(), s.size()), 2);
        Check("2-byte truncated", CompleteUtf8Length(s.data(), 1), 0);
    }

    // ---- three bytes: € = E2 82 AC ----
    {
        const std::string s = Bytes({0xE2, 0x82, 0xAC});
        Check("3-byte complete", CompleteUtf8Length(s.data(), s.size()), 3);
        Check("3-byte one byte", CompleteUtf8Length(s.data(), 1), 0);
        Check("3-byte two bytes", CompleteUtf8Length(s.data(), 2), 0);
    }

    // ---- four bytes: 😀 = F0 9F 98 80 ----
    {
        const std::string s = Bytes({0xF0, 0x9F, 0x98, 0x80});
        Check("4-byte complete", CompleteUtf8Length(s.data(), s.size()), 4);
        for (size_t cut = 1; cut < 4; ++cut) {
            Check("4-byte cut " + std::to_string(cut), CompleteUtf8Length(s.data(), cut), 0);
        }
    }

    // ---- text, then a split emoji, then more text ----
    {
        const std::string s = "hi " + Bytes({0xF0, 0x9F, 0x98, 0x80}) + " there";
        Check("mixed whole", CompleteUtf8Length(s.data(), s.size()), s.size());
        Check("mixed split emoji", CompleteUtf8Length(s.data(), 5), 3);   // "hi " kept
        Check("mixed after emoji", CompleteUtf8Length(s.data(), 8), 8);   // emoji + next space
    }

    // ---- damage control: one bad piece must not stall the stream ----
    {
        const std::string stray = Bytes({0x80, 0x41});          // lone continuation, then 'A'
        Check("stray continuation skipped", CompleteUtf8Length(stray.data(), stray.size()), 2);

        const std::string badLead = Bytes({0xFF, 0x41});        // invalid lead byte
        Check("invalid lead skipped", CompleteUtf8Length(badLead.data(), badLead.size()), 2);

        const std::string badTail = Bytes({0xC3, 0x41});        // 2-byte lead + ASCII
        Check("bad continuation skipped", CompleteUtf8Length(badTail.data(), badTail.size()), 2);

        const std::string overlong = Bytes({0xE2, 0x82});       // truncated 3-byte at the end
        Check("truncated tail kept", CompleteUtf8Length(overlong.data(), overlong.size()), 0);
    }

    // ---- the case that actually happens: a whole answer reassembled ----
    {
        const std::string whole = "The answer is 😀 — done.";
        std::string rebuilt;
        std::string pending;
        size_t pos = 0;
        // Feed it 3 bytes at a time, which splits the emoji in half.
        while (pos < whole.size() || !pending.empty()) {
            const size_t take = std::min<size_t>(3, whole.size() - pos);
            if (take > 0) {
                pending += whole.substr(pos, take);
                pos += take;
            }
            const size_t complete = CompleteUtf8Length(pending.data(), pending.size());
            rebuilt += pending.substr(0, complete);
            pending = pending.substr(complete);
            if (take == 0 && complete == 0) break;  // nothing left to consume
        }
        // Whatever is left over can only be an invalid tail; the text must match.
        if (rebuilt != whole) {
            std::cout << "FAIL reassembly: \"" << rebuilt << "\" != \"" << whole << "\"\n";
            ++failures;
        }
    }

    if (failures == 0) {
        std::cout << "utf8: all checks passed\n";
        return 0;
    }
    std::cout << failures << " utf8 check(s) failed\n";
    return 1;
}
