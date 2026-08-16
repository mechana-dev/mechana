/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include <AudioToolbox/AudioToolbox.h>
#include <CoreAudio/CoreAudio.h>
#include <CoreFoundation/CoreFoundation.h>
#include <errno.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static volatile bool playback_finished;

static UInt32 output_channels(AudioDeviceID device) {
    AudioObjectPropertyAddress address = {
        kAudioDevicePropertyStreamConfiguration,
        kAudioDevicePropertyScopeOutput,
        kAudioObjectPropertyElementMain
    };
    UInt32 size = 0;
    if (AudioObjectGetPropertyDataSize(device, &address, 0, NULL, &size) != noErr || size == 0)
        return 0;
    AudioBufferList *buffers = malloc(size);
    if (buffers == NULL)
        return 0;
    UInt32 channels = 0;
    if (AudioObjectGetPropertyData(device, &address, 0, NULL, &size, buffers) == noErr)
        for (UInt32 index = 0; index < buffers->mNumberBuffers; ++index)
            channels += buffers->mBuffers[index].mNumberChannels;
    free(buffers);
    return channels;
}

static bool string_property(AudioDeviceID device, AudioObjectPropertySelector selector, char *text, size_t capacity) {
    AudioObjectPropertyAddress address = {
        selector,
        kAudioObjectPropertyScopeGlobal,
        kAudioObjectPropertyElementMain
    };
    CFStringRef value = NULL;
    UInt32 size = sizeof(value);
    if (AudioObjectGetPropertyData(device, &address, 0, NULL, &size, &value) != noErr || value == NULL)
        return false;
    bool converted = CFStringGetCString(value, text, (CFIndex) capacity, kCFStringEncodingUTF8);
    CFRelease(value);
    return converted;
}

static CFStringRef default_output_uid(void) {
    AudioObjectPropertyAddress default_address = {
        kAudioHardwarePropertyDefaultOutputDevice,
        kAudioObjectPropertyScopeGlobal,
        kAudioObjectPropertyElementMain
    };
    AudioDeviceID device = kAudioObjectUnknown;
    UInt32 device_size = sizeof(device);
    if (AudioObjectGetPropertyData(kAudioObjectSystemObject, &default_address, 0, NULL, &device_size, &device)
            != noErr || device == kAudioObjectUnknown)
        return NULL;
    AudioObjectPropertyAddress uid_address = {
        kAudioDevicePropertyDeviceUID,
        kAudioObjectPropertyScopeGlobal,
        kAudioObjectPropertyElementMain
    };
    CFStringRef uid = NULL;
    UInt32 uid_size = sizeof(uid);
    if (AudioObjectGetPropertyData(device, &uid_address, 0, NULL, &uid_size, &uid) != noErr)
        return NULL;
    return uid;
}

static int list_outputs(void) {
    AudioObjectPropertyAddress address = {
        kAudioHardwarePropertyDevices,
        kAudioObjectPropertyScopeGlobal,
        kAudioObjectPropertyElementMain
    };
    UInt32 size = 0;
    if (AudioObjectGetPropertyDataSize(kAudioObjectSystemObject, &address, 0, NULL, &size) != noErr)
        return 2;
    AudioDeviceID *devices = malloc(size);
    if (devices == NULL)
        return 2;
    if (AudioObjectGetPropertyData(kAudioObjectSystemObject, &address, 0, NULL, &size, devices) != noErr) {
        free(devices);
        return 2;
    }
    size_t count = size / sizeof(AudioDeviceID);
    for (size_t index = 0; index < count; ++index) {
        if (output_channels(devices[index]) == 0)
            continue;
        char uid[1024];
        char name[1024];
        if (string_property(devices[index], kAudioDevicePropertyDeviceUID, uid, sizeof(uid))
                && string_property(devices[index], kAudioObjectPropertyName, name, sizeof(name))) {
            for (char *character = name; *character != '\0'; ++character)
                if (*character == '\t' || *character == '\n' || *character == '\r')
                    *character = ' ';
            printf("%s\t%s\n", uid, name);
        }
    }
    free(devices);
    return 0;
}

static void fill_buffer(void *context, AudioQueueRef queue, AudioQueueBufferRef buffer) {
    (void) context;
    ssize_t count;
    do {
        count = read(STDIN_FILENO, buffer->mAudioData, buffer->mAudioDataBytesCapacity);
    } while (count < 0 && errno == EINTR);
    if (count <= 0) {
        playback_finished = true;
        AudioQueueStop(queue, false);
        return;
    }
    buffer->mAudioDataByteSize = (UInt32) count;
    if (AudioQueueEnqueueBuffer(queue, buffer, 0, NULL) != noErr)
        playback_finished = true;
}

static int play(const double sample_rate, const UInt32 channels, const char *device_uid) {
    AudioStreamBasicDescription format = {0};
    format.mSampleRate = sample_rate;
    format.mFormatID = kAudioFormatLinearPCM;
    format.mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked;
    format.mBytesPerPacket = channels * 2;
    format.mFramesPerPacket = 1;
    format.mBytesPerFrame = channels * 2;
    format.mChannelsPerFrame = channels;
    format.mBitsPerChannel = 16;
    AudioQueueRef queue = NULL;
    OSStatus status = AudioQueueNewOutput(&format, fill_buffer, NULL, NULL, NULL, 0, &queue);
    if (status != noErr)
        return 3;
    {
        CFStringRef uid = device_uid != NULL && device_uid[0] != '\0'
                ? CFStringCreateWithCString(NULL, device_uid, kCFStringEncodingUTF8)
                : default_output_uid();
        if (uid == NULL) {
            AudioQueueDispose(queue, true);
            return 4;
        }
        status = AudioQueueSetProperty(queue, kAudioQueueProperty_CurrentDevice, &uid, sizeof(uid));
        CFRelease(uid);
        if (status != noErr) {
            AudioQueueDispose(queue, true);
            return 4;
        }
    }
    const UInt32 capacity = 32768;
    for (int index = 0; index < 3; ++index) {
        AudioQueueBufferRef buffer = NULL;
        if (AudioQueueAllocateBuffer(queue, capacity, &buffer) != noErr) {
            AudioQueueDispose(queue, true);
            return 5;
        }
        fill_buffer(NULL, queue, buffer);
        if (playback_finished)
            break;
    }
    if (!playback_finished && AudioQueueStart(queue, NULL) != noErr) {
        AudioQueueDispose(queue, true);
        return 6;
    }
    while (!playback_finished)
        usleep(10000);
    AudioQueueDispose(queue, false);
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--list") == 0)
        return list_outputs();
    if (argc < 4 || strcmp(argv[1], "--play") != 0) {
        fprintf(stderr, "usage: mechana-preview-audio --list | --play sample-rate channels [device-uid]\n");
        return 1;
    }
    double sample_rate = strtod(argv[2], NULL);
    unsigned long parsed_channels = strtoul(argv[3], NULL, 10);
    if (sample_rate < 8000 || sample_rate > 384000 || parsed_channels < 1 || parsed_channels > 2)
        return 1;
    return play(sample_rate, (UInt32) parsed_channels, argc > 4 ? argv[4] : NULL);
}
