// UTF-8 helpers shared by the JNI layer and the inference bridge.
#pragma once

#include <cstddef>
#include <cstdint>

namespace ogg {

// Number of leading bytes of `s` that form complete UTF-8 code points.
//
// llama.cpp hands us one tokenizer piece at a time, and a piece can end in the
// middle of a multi-byte sequence (e.g. the first 2 bytes of an emoji). Handing
// such a fragment to NewStringUTF() would replace it with U+FFFD and visibly
// corrupt the answer, so callers flush only this many bytes and keep the rest.
inline size_t CompleteUtf8Length(const char *data, size_t size) {
    if (data == nullptr || size == 0) return 0;

    size_t i = 0;
    size_t last_complete = 0;
    while (i < size) {
        const unsigned char c = static_cast<unsigned char>(data[i]);
        size_t len = 1;
        if ((c & 0x80) == 0x00) {
            len = 1;
        } else if ((c & 0xE0) == 0xC0) {
            len = 2;
        } else if ((c & 0xF0) == 0xE0) {
            len = 3;
        } else if ((c & 0xF8) == 0xF0) {
            len = 4;
        } else {
            // Stray continuation byte or invalid lead: drop it so a single bad
            // piece cannot stall the stream forever.
            ++i;
            ++last_complete;
            continue;
        }
        if (i + len > size) break;  // truncated tail -> keep for next token
        // Validate continuation bytes.
        bool valid = true;
        for (size_t k = 1; k < len; ++k) {
            if ((static_cast<unsigned char>(data[i + k]) & 0xC0) != 0x80) {
                valid = false;
                break;
            }
        }
        if (!valid) {
            ++i;
            ++last_complete;
            continue;
        }
        i += len;
        last_complete = i;
    }
    return last_complete;
}

}  // namespace ogg
