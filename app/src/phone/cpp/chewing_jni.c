#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "chewing.h"

typedef struct {
    struct ChewingContext *ctx;
    int candidate_window_for_display;
    int user_selected;
    char selected_text[512];
    char selected_zhuyin[1024];
    char pending_zhuyin[128];
} NativeChewing;
static NativeChewing *state_of(jlong handle) {
    return handle == 0 ? NULL : (NativeChewing *)(intptr_t)handle;
}
static struct ChewingContext *ctx_of(jlong handle) {
    NativeChewing *state = state_of(handle);
    return state == NULL ? NULL : state->ctx;
}
static void append_selected_zhuyin(NativeChewing *state, const char *zhuyin) {
    if (state == NULL || zhuyin == NULL || zhuyin[0] == '\0') return;
    size_t code_len = strlen(state->selected_zhuyin), syllable_len = strlen(zhuyin);
    if (code_len > 0 && code_len >= syllable_len
            && strcmp(state->selected_zhuyin + code_len - syllable_len, zhuyin) == 0) return;
    if (code_len + syllable_len + 2 >= sizeof(state->selected_zhuyin)) return;
    if (code_len > 0) state->selected_zhuyin[code_len++] = ' ';
    memcpy(state->selected_zhuyin + code_len, zhuyin, syllable_len + 1);
}
static void track_zhuyin_key(NativeChewing *state, int key) {
    static const char *symbols[] = {"ㄅ","ㄆ","ㄇ","ㄈ","ㄉ","ㄊ","ㄋ","ㄌ","ㄍ","ㄎ","ㄏ","ㄐ","ㄑ","ㄒ","ㄓ","ㄔ","ㄕ","ㄖ","ㄗ","ㄘ","ㄙ","ㄧ","ㄨ","ㄩ","ㄚ","ㄛ","ㄜ","ㄝ","ㄞ","ㄟ","ㄠ","ㄡ","ㄢ","ㄣ","ㄤ","ㄥ","ㄦ","ˊ","ˇ","ˋ","˙"};
    static const char keys[] = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-6347";
    for (size_t i = 0; i < sizeof(keys) - 1; ++i) {
        if ((unsigned char)keys[i] == key) {
            size_t len = strlen(state->pending_zhuyin), add = strlen(symbols[i]);
            if (len + add + 1 < sizeof(state->pending_zhuyin)) memcpy(state->pending_zhuyin + len, symbols[i], add + 1);
            return;
        }
    }
}
static void accept_pending_zhuyin(NativeChewing *state) {
    if (state->pending_zhuyin[0] == '\0') return;
    append_selected_zhuyin(state, state->pending_zhuyin);
    state->pending_zhuyin[0] = '\0';
}
static int utf8_codepoint_count(const char *value) {
    int count = 0;
    if (value == NULL) return 0;
    for (const unsigned char *p = (const unsigned char *)value; *p != '\0'; ++p)
        if ((*p & 0xc0) != 0x80) ++count;
    return count;
}
static jbyteArray utf8_bytes(JNIEnv *env, const char *value) {
    if (value == NULL) value = "";
    size_t length = strlen(value);
    if (length > (size_t)INT32_MAX) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)length);
    if (result != NULL && length > 0)
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte *)value);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_simon_voiceime_ChewingEngine_nativeCreate(JNIEnv *env, jclass type, jstring system_path, jstring user_path) {
    (void)type;
    if (system_path == NULL || user_path == NULL) return 0;
    const char *system = (*env)->GetStringUTFChars(env, system_path, NULL);
    const char *user = (*env)->GetStringUTFChars(env, user_path, NULL);
    if (system == NULL || user == NULL) {
        if (system != NULL) (*env)->ReleaseStringUTFChars(env, system_path, system);
        if (user != NULL) (*env)->ReleaseStringUTFChars(env, user_path, user);
        return 0;
    }
    struct ChewingContext *ctx = chewing_new3(system, user, "word.dat,tsi.dat", NULL, NULL);
    /* Keep native behavior identical to the host evaluation: new consonants
       delimit a tone-less syllable instead of overwriting its initial. */
    if (ctx != NULL) {
        chewing_config_set_int(ctx, "chewing.conversion_engine", FUZZY_CHEWING_CONVERSION_ENGINE);
        chewing_set_maxChiSymbolLen(ctx, MAX_CHI_SYMBOL_LEN); /* 39 symbols */
    }
    NativeChewing *state = ctx == NULL ? NULL : (NativeChewing *)calloc(1, sizeof(NativeChewing));
    if (state != NULL) { state->ctx = ctx; chewing_set_autoLearn(ctx, 1); }
    else if (ctx != NULL) chewing_delete(ctx);
    (*env)->ReleaseStringUTFChars(env, system_path, system);
    (*env)->ReleaseStringUTFChars(env, user_path, user);
    return (jlong)(intptr_t)state;
}

JNIEXPORT void JNICALL Java_com_simon_voiceime_ChewingEngine_nativeDestroy(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    NativeChewing *state = state_of(handle);
    if (state != NULL) { chewing_delete(state->ctx); free(state); }
}

JNIEXPORT void JNICALL Java_com_simon_voiceime_ChewingEngine_nativeKey(JNIEnv *env, jclass type, jlong handle, jint key) {
    (void)env; (void)type;
    struct ChewingContext *ctx = ctx_of(handle);
    if (ctx != NULL && key >= 0 && key <= 127) {
        if (chewing_cand_TotalChoice(ctx) > 0) chewing_cand_close(ctx);
        state_of(handle)->candidate_window_for_display = 0;
        chewing_handle_Default(ctx, key);
        track_zhuyin_key(state_of(handle), key);
    }
}

JNIEXPORT void JNICALL Java_com_simon_voiceime_ChewingEngine_nativeBackspace(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type; struct ChewingContext *ctx = ctx_of(handle);
    if (ctx != NULL) {
        if (chewing_cand_TotalChoice(ctx) > 0) chewing_cand_close(ctx);
        state_of(handle)->candidate_window_for_display = 0;
        chewing_handle_Backspace(ctx);
        NativeChewing *state = state_of(handle); state->user_selected = 0;
        state->selected_text[0] = '\0'; state->selected_zhuyin[0] = '\0'; state->pending_zhuyin[0] = '\0';
    }
}
JNIEXPORT void JNICALL Java_com_simon_voiceime_ChewingEngine_nativeSpace(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type; struct ChewingContext *ctx = ctx_of(handle);
    if (ctx != NULL) {
        NativeChewing *state = state_of(handle);
        if (chewing_cand_TotalChoice(ctx) > 0 && state->candidate_window_for_display) {
            chewing_cand_close(ctx);
            state->candidate_window_for_display = 0;
            chewing_handle_Space(ctx);
        } else if (chewing_cand_TotalChoice(ctx) > 0) {
            if (chewing_cand_choose_by_index(ctx, 0) == 0) {
                state->candidate_window_for_display = 0;
                state->user_selected = 1; accept_pending_zhuyin(state);
            }
        }
        else chewing_handle_Space(ctx);
    }
}
JNIEXPORT void JNICALL Java_com_simon_voiceime_ChewingEngine_nativeEnter(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type; struct ChewingContext *ctx = ctx_of(handle);
    if (ctx != NULL) {
        NativeChewing *state = state_of(handle);
        if (chewing_cand_TotalChoice(ctx) > 0 && state->candidate_window_for_display) {
            chewing_cand_close(ctx);
            state->candidate_window_for_display = 0;
        } else if (chewing_cand_TotalChoice(ctx) > 0) {
            chewing_cand_choose_by_index(ctx, 0);
        }
        chewing_handle_Enter(ctx);
    }
}
JNIEXPORT void JNICALL Java_com_simon_voiceime_ChewingEngine_nativeChoose(JNIEnv *env, jclass type, jlong handle, jint index) {
    (void)env; (void)type; struct ChewingContext *ctx = ctx_of(handle);
    if (ctx != NULL && index >= 0 && index < chewing_cand_TotalChoice(ctx)) {
        NativeChewing *state = state_of(handle);
        const char *candidate = chewing_cand_string_by_index_static(ctx, index);
        if (candidate != NULL && chewing_cand_choose_by_index(ctx, index) == 0) {
            state->candidate_window_for_display = 0;
            size_t text_len = strlen(state->selected_text);
            if (text_len + strlen(candidate) < sizeof(state->selected_text)) {
                memcpy(state->selected_text + text_len, candidate, strlen(candidate) + 1);
                accept_pending_zhuyin(state);
                state->user_selected = 1;
            }
        }
    }
}

JNIEXPORT jint JNICALL Java_com_simon_voiceime_ChewingEngine_nativeLearnPhrase(JNIEnv *env, jclass type,
        jlong handle, jstring word, jstring pronunciation) {
    (void)type;
    struct ChewingContext *ctx = ctx_of(handle);
    if (ctx == NULL || word == NULL || pronunciation == NULL) return 0;
    const char *w = (*env)->GetStringUTFChars(env, word, NULL);
    const char *p = (*env)->GetStringUTFChars(env, pronunciation, NULL);
    if (w == NULL || p == NULL) {
        if (w != NULL) (*env)->ReleaseStringUTFChars(env, word, w);
        if (p != NULL) (*env)->ReleaseStringUTFChars(env, pronunciation, p);
        return 0;
    }
    int result = chewing_userphrase_add(ctx, w, p);
    if (result <= 0) {
        /* libchewing reports zero when the phrase already exists; lookup distinguishes
           that idempotent first-run seed from a failed insertion. */
        result = chewing_userphrase_lookup(ctx, w, p) ? 1 : result;
    }
    (*env)->ReleaseStringUTFChars(env, word, w);
    (*env)->ReleaseStringUTFChars(env, pronunciation, p);
    return result;
}

JNIEXPORT jobjectArray JNICALL Java_com_simon_voiceime_ChewingEngine_nativePersonalPhrases(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    struct ChewingContext *ctx = ctx_of(handle);
    jclass byte_array_class = (*env)->FindClass(env, "[B");
    if (byte_array_class == NULL) return NULL;
    if (ctx == NULL || chewing_userphrase_enumerate(ctx) != 0) {
        jobjectArray empty = (*env)->NewObjectArray(env, 0, byte_array_class, NULL);
        (*env)->DeleteLocalRef(env, byte_array_class);
        return empty;
    }
    char **rows = NULL;
    size_t count = 0, capacity = 0;
    unsigned int phrase_len = 0, bopomofo_len = 0;
    while (chewing_userphrase_has_next(ctx, &phrase_len, &bopomofo_len)) {
        if (phrase_len == 0 || bopomofo_len == 0 || phrase_len > 65536 || bopomofo_len > 65536 || count >= 10000) break;
        char *phrase = (char *)calloc(phrase_len, 1);
        char *bopomofo = (char *)calloc(bopomofo_len, 1);
        if (phrase == NULL || bopomofo == NULL) { free(phrase); free(bopomofo); break; }
        if (chewing_userphrase_get(ctx, phrase, phrase_len, bopomofo, bopomofo_len) != 0) {
            free(phrase); free(bopomofo); continue;
        }
        size_t total = strlen(phrase) + strlen(bopomofo) + 2;
        char *row = (char *)malloc(total);
        if (row == NULL) { free(phrase); free(bopomofo); break; }
        snprintf(row, total, "%s\t%s", phrase, bopomofo);
        free(phrase); free(bopomofo);
        if (count == capacity) {
            size_t next = capacity == 0 ? 32 : capacity * 2;
            char **grown = (char **)realloc(rows, next * sizeof(char *));
            if (grown == NULL) { free(row); break; }
            rows = grown; capacity = next;
        }
        rows[count++] = row;
    }
    jobjectArray result = (*env)->NewObjectArray(env, (jsize)count, byte_array_class, NULL);
    (*env)->DeleteLocalRef(env, byte_array_class);
    size_t freed = 0;
    if (result != NULL) for (size_t i = 0; i < count; i++) {
        jbyteArray value = utf8_bytes(env, rows[i]);
        if (value != NULL) { (*env)->SetObjectArrayElement(env, result, (jsize)i, value); (*env)->DeleteLocalRef(env, value); }
        free(rows[i]);
        freed = i + 1;
        if ((*env)->ExceptionCheck(env)) break;
    }
    for (size_t i = freed; i < count; i++) free(rows[i]);
    free(rows);
    return result;
}

JNIEXPORT void JNICALL Java_com_simon_voiceime_ChewingEngine_nativeMoveCursor(JNIEnv *env, jclass type,
        jlong handle, jboolean right) {
    (void)env; (void)type;
    struct ChewingContext *ctx = ctx_of(handle);
    if (ctx == NULL || !chewing_buffer_Check(ctx)) return;
    if (chewing_cand_TotalChoice(ctx) > 0) chewing_cand_close(ctx);
    state_of(handle)->candidate_window_for_display = 0;
    if (right) chewing_handle_Right(ctx); else chewing_handle_Left(ctx);
    if (chewing_buffer_Check(ctx) && chewing_cand_TotalChoice(ctx) <= 0) {
        chewing_handle_Down(ctx);
        if (chewing_cand_TotalChoice(ctx) > 0) state_of(handle)->candidate_window_for_display = 1;
    }
}

JNIEXPORT jint JNICALL Java_com_simon_voiceime_ChewingEngine_nativeCursor(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    struct ChewingContext *ctx = ctx_of(handle);
    if (ctx == NULL) return 0;
    int cursor = chewing_cursor_Current(ctx);
    int phonetic_len = 0;
    char *phonetic = chewing_zuin_String(ctx, &phonetic_len);
    if (phonetic != NULL) {
        cursor += utf8_codepoint_count(phonetic);
        chewing_free(phonetic);
    }
    return cursor;
}

JNIEXPORT jbyteArray JNICALL Java_com_simon_voiceime_ChewingEngine_nativeComposing(JNIEnv *env, jclass type, jlong handle) {
    (void)type; struct ChewingContext *ctx = ctx_of(handle);
    if (ctx == NULL) return utf8_bytes(env, "");
    const char *preedit = chewing_buffer_String_static(ctx);
    int count = 0;
    char *phonetic = chewing_zuin_String(ctx, &count);
    if (preedit == NULL) preedit = "";
    if (phonetic == NULL) phonetic = strdup("");
    if (phonetic == NULL) return utf8_bytes(env, preedit);
    size_t a = strlen(preedit), b = strlen(phonetic);
    char *joined = (char *)malloc(a + b + 1);
    if (joined == NULL) { chewing_free(phonetic); return utf8_bytes(env, preedit); }
    memcpy(joined, preedit, a); memcpy(joined + a, phonetic, b + 1);
    jbyteArray result = utf8_bytes(env, joined);
    free(joined); chewing_free(phonetic);
    return result;
}

JNIEXPORT jobjectArray JNICALL Java_com_simon_voiceime_ChewingEngine_nativeCandidates(JNIEnv *env, jclass type, jlong handle) {
    (void)type; struct ChewingContext *ctx = ctx_of(handle);
    jclass byte_array_class = (*env)->FindClass(env, "[B");
    if (byte_array_class == NULL) return NULL;
    int count = ctx == NULL ? 0 : chewing_cand_TotalChoice(ctx);
    if (ctx != NULL && count <= 0 && chewing_zuin_Check(ctx) != 0 && chewing_buffer_Check(ctx)) {
        chewing_handle_Down(ctx);
        count = chewing_cand_TotalChoice(ctx);
        if (count > 0) state_of(handle)->candidate_window_for_display = 1;
    }
    if (count < 0) count = 0;
    jobjectArray result = (*env)->NewObjectArray(env, count, byte_array_class, NULL);
    (*env)->DeleteLocalRef(env, byte_array_class);
    if (result == NULL) return NULL;
    for (int i = 0; i < count; ++i) {
        const char *candidate = chewing_cand_string_by_index_static(ctx, i);
        jbyteArray value = utf8_bytes(env, candidate == NULL ? "" : candidate);
        if (value != NULL) { (*env)->SetObjectArrayElement(env, result, i, value); (*env)->DeleteLocalRef(env, value); }
        if ((*env)->ExceptionCheck(env)) return result;
    }
    return result;
}

JNIEXPORT jbyteArray JNICALL Java_com_simon_voiceime_ChewingEngine_nativeTakeCommit(JNIEnv *env, jclass type, jlong handle) {
    (void)type; struct ChewingContext *ctx = ctx_of(handle);
    if (ctx == NULL || !chewing_commit_Check(ctx)) return utf8_bytes(env, "");
    NativeChewing *state = state_of(handle);
    const char *text = chewing_commit_String_static(ctx);
    jbyteArray result = utf8_bytes(env, text == NULL ? "" : text);
    if (state->user_selected && text != NULL && strlen(state->selected_text) >= 6
            && strcmp(text, state->selected_text) == 0 && state->selected_zhuyin[0] != '\0') {
        int learned = chewing_userphrase_add(ctx, state->selected_text, state->selected_zhuyin);
        (void)learned;
    }
    state->user_selected = 0;
    state->selected_text[0] = '\0';
    state->selected_zhuyin[0] = '\0';
    state->pending_zhuyin[0] = '\0';
    chewing_ack(ctx);
    return result;
}

JNIEXPORT void JNICALL Java_com_simon_voiceime_ChewingEngine_nativeClear(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type; struct ChewingContext *ctx = ctx_of(handle);
    if (ctx != NULL) {
        if (chewing_cand_TotalChoice(ctx) > 0) chewing_cand_close(ctx);
        state_of(handle)->candidate_window_for_display = 0;
        chewing_clean_preedit_buf(ctx); chewing_clean_bopomofo_buf(ctx);
        NativeChewing *state = state_of(handle); state->user_selected = 0;
        state->selected_text[0] = '\0'; state->selected_zhuyin[0] = '\0'; state->pending_zhuyin[0] = '\0';
    }
}
