/*
 * SB EQ32 legacy AudioEffect adapter.
 *
 * This file is intentionally built by an Android platform/vendor audio tree,
 * not by the ordinary SB APK Gradle build. It consumes the AOSP effect_api.h
 * supplied by the target platform and delegates PCM processing to Eq32.
 *
 * The adapter is kept separate because an ordinary third-party APK cannot
 * register a new AudioFlinger effect library in /vendor/lib(64)/soundfx.
 */
#include "sb_eq32.h"
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <new>

#if __has_include(<audio_effect.h>)
#include <audio_effect.h>
#else
#error "Build this adapter inside an Android platform/vendor tree that provides audio_effect.h"
#endif

namespace {
static const effect_uuid_t kUuid = {
    0x53b7e032, 0x5b5a, 0x4e51, 0x9a, 0x21, {0x53,0x42,0x5a,0x45,0x51,0x33,0x32,0x01}
};
struct Context {
    sb::Eq32 eq;
    uint32_t sample_rate = 48000;
    uint32_t channels = 2;
    bool enabled = true;
};

static int32_t process(effect_handle_t self, audio_buffer_t* in, audio_buffer_t* out) {
    auto* c = reinterpret_cast<Context*>(self);
    if (!c || !in || !out || !in->f32 || !out->f32) return -EINVAL;
    const size_t samples = std::min(in->frameCount, out->frameCount) * c->channels;
    if (in->f32 != out->f32) std::memcpy(out->f32, in->f32, samples * sizeof(float));
    if (c->enabled) c->eq.process(out->f32, std::min(in->frameCount, out->frameCount), c->channels);
    out->frameCount = std::min(in->frameCount, out->frameCount);
    return 0;
}

static int32_t command(effect_handle_t self, uint32_t cmd, uint32_t cmdSize, void* pCmd, uint32_t* replySize, void* pReply) {
    auto* c = reinterpret_cast<Context*>(self);
    if (!c) return -EINVAL;
    switch (cmd) {
        case EFFECT_CMD_ENABLE: c->enabled = true; return 0;
        case EFFECT_CMD_DISABLE: c->enabled = false; return 0;
        case EFFECT_CMD_RESET: c->eq.reset(); return 0;
        default: return 0;
    }
}

static int32_t getDescriptor(effect_handle_t, effect_descriptor_t* desc) {
    if (!desc) return -EINVAL;
    std::memset(desc, 0, sizeof(*desc));
    desc->type = kUuid;
    desc->uuid = kUuid;
    std::strncpy(desc->name, "SB EQ32", EFFECT_STRING_LEN-1);
    std::strncpy(desc->implementor, "SB", EFFECT_STRING_LEN-1);
    desc->apiVersion = EFFECT_CONTROL_API_VERSION;
    desc->flags = EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_FIRST;
    return 0;
}

static int32_t release(effect_handle_t self) {
    delete reinterpret_cast<Context*>(self);
    return 0;
}

static int32_t getVersion(effect_handle_t, uint32_t* v) { if (!v) return -EINVAL; *v = EFFECT_LIBRARY_API_VERSION; return 0; }
static const effect_interface_s kInterface = { getDescriptor, process, command, release, nullptr, getVersion };
}

extern "C" int32_t sb_eq32_create(const effect_uuid_t* uuid, int32_t, int32_t, effect_handle_t* handle) {
    if (!uuid || !handle || std::memcmp(uuid, &kUuid, sizeof(kUuid)) != 0) return -EINVAL;
    auto* c = new (std::nothrow) Context();
    if (!c) return -ENOMEM;
    c->eq.configure(48000.f, {});
    *handle = reinterpret_cast<effect_handle_t>(c);
    return 0;
}
extern "C" int32_t sb_eq32_release(effect_handle_t handle) { return release(handle); }
extern "C" int32_t sb_eq32_get_descriptor(const effect_uuid_t* uuid, effect_descriptor_t* desc) {
    if (!uuid || std::memcmp(uuid, &kUuid, sizeof(kUuid)) != 0) return -EINVAL;
    return getDescriptor(nullptr, desc);
}
