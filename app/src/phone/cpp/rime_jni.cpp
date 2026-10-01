#include <jni.h>
#include <rime_api.h>
#include <cstdint>
#include <string>
#include <mutex>
#include <chrono>
#include <vector>
#include <cstring>
#include <climits>
#include "rime_regroup.h"

struct Session {
    const RimeApi* api;
    RimeSessionId id;
    std::string unparsed;  // physical keys, displayed/committed ONLY as Zhuyin
    std::string pending;
    size_t unparsed_caret = 0;
    RegroupState regroup;
};

// Native enforcement of the commit invariant in ZhuyinInputController.
// Space is tone 1; the entire physical-key set renders to glyphs.
// This changes no speller, abbreviation, dictionary or syllable rules.
static const std::string kPhysical = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347";
static const char* kGlyphs[] = {
    "ㄅ","ㄆ","ㄇ","ㄈ","ㄉ","ㄊ","ㄋ","ㄌ","ㄍ","ㄎ","ㄏ","ㄐ","ㄑ","ㄒ",
    "ㄓ","ㄔ","ㄕ","ㄖ","ㄗ","ㄘ","ㄙ","ㄧ","ㄨ","ㄩ","ㄚ","ㄛ","ㄜ","ㄝ",
    "ㄞ","ㄟ","ㄠ","ㄡ","ㄢ","ㄣ","ㄤ","ㄥ","ㄦ","ˉ","ˊ","ˇ","ˋ","˙"
};
static std::string glyph_text(const std::string& text) {
    std::string out;
    for (unsigned char c : text) {
        size_t index = kPhysical.find(c);
        if (index != std::string::npos) out += kGlyphs[index];
        else if (c >= 128) out += static_cast<char>(c);
        // ASCII not in the Zhuyin keymap is not a user-entered character here.
    }
    return out;
}
static bool has_unparsed_preedit_keys(const std::string& text) {
    // Rime inserts spaces as preedit syllable separators. Detect raw non-space
    // keys here; glyph_text handles physical spaces at every commit boundary.
    for (unsigned char c : text)
        if (c < 128 && c != ' ' && kPhysical.find(c) != std::string::npos) return true;
    return false;
}
static std::string preedit(Session* s, bool commit_preview = false) {
    RIME_STRUCT(RimeContext, c);
    if (!s->api->get_context(s->id, &c)) return {};
    const char* value = commit_preview ? c.commit_text_preview : c.composition.preedit;
    std::string text = value ? value : "";
    s->api->free_context(&c);
    return text;
}
static size_t trailing_syllable_keys(std::string text) {
    // Rime's own formatted preedit supplies the syllable boundary; do not
    // approximate valid syllables or restrict its abbreviation derivations.
    size_t count = 0;
    while (!text.empty()) {
        bool found = false;
        for (const char* glyph : kGlyphs) {
            size_t n = std::strlen(glyph);
            if (text.size() >= n && text.compare(text.size()-n, n, glyph) == 0) {
                text.resize(text.size()-n); ++count; found = true; break;
            }
        }
        if (!found) break;
    }
    return count;
}
static std::string drain_commit(Session* s) {
    RIME_STRUCT(RimeCommit, c);
    if (!s->api->get_commit(s->id, &c)) return {};
    std::string text = c.text ? c.text : "";
    s->api->free_commit(&c);
    return glyph_text(text);
}
static std::mutex g_mutex;
static int g_users = 0;
static std::string jstr(JNIEnv* env, jstring value) {
    if (!value) return {};
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};
    const jsize n = env->GetStringLength(value);
    std::string out;
    for (jsize i=0;i<n;i++) {
        uint32_t c=chars[i];
        if(c>=0xd800 && c<=0xdbff && i+1<n && chars[i+1]>=0xdc00 && chars[i+1]<=0xdfff)
            c=0x10000+((c-0xd800)<<10)+(chars[++i]-0xdc00);
        if(c<0x80) out.push_back((char)c);
        else if(c<0x800){out.push_back((char)(0xc0|(c>>6)));out.push_back((char)(0x80|(c&63)));}
        else if(c<0x10000){out.push_back((char)(0xe0|(c>>12)));out.push_back((char)(0x80|((c>>6)&63)));out.push_back((char)(0x80|(c&63)));}
        else {out.push_back((char)(0xf0|(c>>18)));out.push_back((char)(0x80|((c>>12)&63)));out.push_back((char)(0x80|((c>>6)&63)));out.push_back((char)(0x80|(c&63)));}
    }
    env->ReleaseStringChars(value, chars); return out;
}
static jbyteArray bytes(JNIEnv* env, const std::string& s) {
    jbyteArray out=env->NewByteArray((jsize)s.size());
    if(out && !s.empty()) env->SetByteArrayRegion(out,0,(jsize)s.size(),reinterpret_cast<const jbyte*>(s.data()));
    return out;
}
static Session* state(jlong h){return reinterpret_cast<Session*>(static_cast<intptr_t>(h));}
static jstring ascii(JNIEnv* env,const char* value){std::vector<jchar> chars;while(*value)chars.push_back(static_cast<unsigned char>(*value++));return env->NewString(chars.data(),static_cast<jsize>(chars.size()));}
static void init_event(JNIEnv* env,jobject listener,const char* step,long long ms,bool ok,const char* message){
    if(!listener)return; jclass cls=env->GetObjectClass(listener);if(!cls)return;
    jmethodID method=env->GetMethodID(cls,"onNativeInitStep","(Ljava/lang/String;JZLjava/lang/String;)V");
    if(method){jstring s=ascii(env,step),m=ascii(env,message?message:"");env->CallVoidMethod(listener,method,s,static_cast<jlong>(ms),static_cast<jboolean>(ok),m);env->DeleteLocalRef(s);env->DeleteLocalRef(m);}
    env->DeleteLocalRef(cls);
}
using InitClock=std::chrono::steady_clock;
static long long elapsed(InitClock::time_point start){return std::chrono::duration_cast<std::chrono::milliseconds>(InitClock::now()-start).count();}
static std::string jsonq(const char* p) {
    std::string o="\""; if(p) for(const unsigned char* s=(const unsigned char*)p;*s;s++) {
        if(*s=='"'||*s=='\\'){o+='\\';o+=(char)*s;} else if(*s=='\n')o+="\\n"; else if(*s=='\r')o+="\\r"; else if(*s<32)o+=' '; else o+=(char)*s;
    } o+='"'; return o;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeCreate(JNIEnv* env,jclass,jstring shared,jstring user){
    std::lock_guard<std::mutex> lock(g_mutex);
    RimeTraits t{}; RIME_STRUCT_INIT(RimeTraits,t);
    std::string sh=jstr(env,shared), us=jstr(env,user);
    t.shared_data_dir=sh.c_str(); t.user_data_dir=us.c_str(); t.app_name="rime.simon.voiceime"; t.min_log_level=2; t.log_dir="";
    const RimeApi* api=rime_get_api(); if(!api)return 0;
    if(g_users++==0){api->setup(&t);api->initialize(&t);}
    RimeSessionId id=api->create_session();
    if(!id){if(--g_users==0)api->finalize();return 0;}
    if(!api->select_schema(id,"bopomofo_express")){api->destroy_session(id);if(--g_users==0)api->finalize();return 0;}
    return (jlong)(intptr_t)new Session{api,id,{}, {}};
}
// Reload the installed table only at an idle boundary; in-flight selections keep their menu.
extern "C" JNIEXPORT jboolean JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeRefreshVocabulary(JNIEnv*,jclass,jlong h) {
    Session* s=state(h); if(!s)return false;
    std::lock_guard<std::mutex> lock(g_mutex);
    const char* input=s->api->get_input(s->id);
    if(g_users!=1 || (input && *input) || !s->unparsed.empty() || !s->pending.empty())return false;
    s->api->destroy_session(s->id);
    s->id=s->api->create_session();
    return s->id && s->api->select_schema(s->id,"bopomofo_express");
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeVocabularyReading(JNIEnv* env,jclass,jlong h) {
    Session* s=state(h);
    std::string reading=s && s->unparsed.empty() ? preedit(s) : "";
    if(has_unparsed_preedit_keys(reading))reading=glyph_text(reading);
    return bytes(env,reading);
}
extern "C" JNIEXPORT void JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeDestroy(JNIEnv*,jclass,jlong h){
    Session*s=state(h);if(!s)return;std::lock_guard<std::mutex> lock(g_mutex);if(s->regroup.probe)s->api->destroy_session(s->regroup.probe);s->api->destroy_session(s->id);if(--g_users==0)s->api->finalize();delete s;
}
extern "C" JNIEXPORT void JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeProcessKey(JNIEnv*,jclass,jlong h,jint key) {
    Session* s=state(h); if(!s) return;
    s->regroup.options.clear();s->regroup.boundary=-1;s->regroup.focus_start=s->regroup.focus_end=-1;
    if (!s->unparsed.empty()) {
        if (key == 0xff08) {
            if (s->unparsed_caret > 0) s->unparsed.erase(--s->unparsed_caret, 1);
        }
        else if (key == 0xff0d || key == 0x20) {
            s->pending += glyph_text(s->unparsed); s->unparsed.clear();
        } else if (key >= 0 && key < 128 && kPhysical.find(static_cast<char>(key)) != std::string::npos) {
            s->unparsed.insert(s->unparsed_caret++, 1, static_cast<char>(key));
        }
        return;
    }
    const char* input = s->api->get_input(s->id);
    std::string before = input ? input : "";
    size_t last_syllable = trailing_syllable_keys(preedit(s));
    s->api->process_key(s->id,key,0);
    if (key < 128 && key != 0x20 && has_unparsed_preedit_keys(preedit(s, true))) {
        input = s->api->get_input(s->id);
        std::string raw = input ? input : "";
        size_t boundary = before.size() >= last_syllable ? before.size()-last_syllable : 0;
        boundary = std::min(boundary, raw.size());
        // An invalid tone must not invalidate earlier syllables. Recompose
        // only the prefix at the boundary that Rime displayed before this key.
        s->pending += drain_commit(s);
        s->api->set_input(s->id, raw.substr(0, boundary).c_str());
        s->api->commit_composition(s->id);
        s->pending += drain_commit(s);
        s->api->clear_composition(s->id);
        s->unparsed = raw.substr(boundary);
        s->unparsed_caret = s->unparsed.size();
    }
}
extern "C" JNIEXPORT void JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeSelect(JNIEnv*,jclass,jlong h,jint index){Session*s=state(h);if(s&&index>=0)s->api->select_candidate(s->id,(size_t)index);}
extern "C" JNIEXPORT void JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeClear(JNIEnv*,jclass,jlong h){Session*s=state(h);if(s){s->api->clear_composition(s->id);s->unparsed.clear();s->pending.clear();s->regroup.input.clear();s->regroup.stops.clear();s->regroup.cache.clear();s->regroup.glyph_preedit.clear();s->regroup.options.clear();s->regroup.boundary=-1;s->regroup.focus_start=s->regroup.focus_end=-1;drain_commit(s);}}
extern "C" JNIEXPORT jint JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeCursor(JNIEnv*,jclass,jlong h){Session*s=state(h);return s ? static_cast<jint>(s->unparsed.empty() ? s->api->get_caret_pos(s->id) : s->unparsed_caret) : 0;}
extern "C" JNIEXPORT void JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeMoveCursor(JNIEnv*,jclass,jlong h,jboolean right){Session*s=state(h);if(!s)return;
    if(!s->unparsed.empty()) {
        if(right && s->unparsed_caret<s->unparsed.size()) ++s->unparsed_caret;
        else if(!right && s->unparsed_caret>0) --s->unparsed_caret;
        return;
    }
    size_t p=s->api->get_caret_pos(s->id);if(right)++p;else if(p)--p;s->api->set_caret_pos(s->id,p);}
static int utf8_codepoints_before(const char* text, int byte_position) {
    if (!text || byte_position <= 0) return 0;
    int length = (int)std::strlen(text);
    int end = std::min(byte_position, length), count = 0;
    for (int i = 0; i < end; ++i)
        if ((static_cast<unsigned char>(text[i]) & 0xc0) != 0x80) ++count;
    return count;
}
static bool current_selection(Session* s, int* start, int* end) {
    if (!s || !start || !end) return false;
    RIME_STRUCT(RimeContext, c);
    if (!s->api->get_context(s->id, &c)) return false;
    const char* preedit = c.composition.preedit ? c.composition.preedit : "";
    *start = utf8_codepoints_before(preedit, c.composition.sel_start);
    *end = utf8_codepoints_before(preedit, c.composition.sel_end);
    s->api->free_context(&c);
    return *end > *start;
}
extern "C" JNIEXPORT jintArray JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativePreviewSelectionRange(JNIEnv* env,jclass,jlong h) {
    Session* s=state(h); int start=0,end=0; current_selection(s,&start,&end);
    if(s&&s->regroup.boundary>=0){end=s->regroup.boundary;start=std::max(0,end-1);}
    if(s&&s->regroup.focus_start>=0){auto& r=s->regroup;start=r.glyph_stops[r.focus_start];end=r.glyph_stops[r.focus_end];while(start<end&&cp_slice(r.preedit,start,start+1)==" ")++start;}
    jint values[2]={start,end}; jintArray out=env->NewIntArray(2);
    if(out) env->SetIntArrayRegion(out,0,2,values);
    return out;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeMoveCursorToPreviewCharacter(JNIEnv*,jclass,jlong h,jint target) {
    Session* s=state(h); if(!s || target<0) return JNI_FALSE;
    if(!s->unparsed.empty()) {
        if(static_cast<size_t>(target)>s->unparsed.size()) return JNI_FALSE;
        s->unparsed_caret=static_cast<size_t>(target); return JNI_TRUE;
    }
    const char* raw=s->api->get_input(s->id); if(!raw) return JNI_FALSE;
    const std::string input(raw);
    const size_t input_length=input.size(), original=s->api->get_caret_pos(s->id);
    const bool toned=input.find_first_of(" 6347")!=std::string::npos;
    size_t best=input_length+1; int best_span=INT_MAX;
    for(size_t pos=1;pos<=input_length;++pos) {
        // A caret inside a complete syllable splits its raw keys and causes
        // Rime to show ASCII suffixes. Only completed-tone boundaries and the
        // unfinished tail end are valid targets for an already toned stream.
        if(toned && pos!=input_length && std::string(" 6347").find(input[pos-1])==std::string::npos) continue;
        s->api->set_caret_pos(s->id,pos);
        if(has_unparsed_preedit_keys(preedit(s))) continue;
        int start=0,end=0;
        if(current_selection(s,&start,&end) && target>=start && target<end) {
            int span=end-start;
            if(span<best_span || (span==best_span && pos>best)) { best=pos; best_span=span; }
        }
    }
    if(best>input_length) { s->api->set_caret_pos(s->id,original); return JNI_FALSE; }
    s->api->set_caret_pos(s->id,best);
    return JNI_TRUE;
}
extern "C" JNIEXPORT void JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeMoveCursorToEnd(JNIEnv*,jclass,jlong h) {
    Session* s=state(h); if(!s) return;
    s->regroup.boundary=-1;s->regroup.options.clear();s->regroup.focus_start=s->regroup.focus_end=-1;
    if(!s->unparsed.empty()) {s->unparsed_caret=s->unparsed.size();return;}
    const char* input=s->api->get_input(s->id);
    if(input) s->api->set_caret_pos(s->id,std::strlen(input));
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeComposing(JNIEnv* e,jclass,jlong h) {
    Session* s=state(h); if(!s) return bytes(e, "");
    return bytes(e, (s->regroup.boundary>=0||s->regroup.focus_start>=0?s->regroup.preedit:preedit(s)) + glyph_text(s->unparsed));
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeCandidates(JNIEnv*e,jclass,jlong h){Session*s=state(h);jclass b=e->FindClass("[B");if(!b)return nullptr;std::vector<std::string> values;if(s){RimeCandidateListIterator iterator{};if(s->api->candidate_list_begin(s->id,&iterator)){do{if(iterator.candidate.text)values.emplace_back(iterator.candidate.text);}while(values.size()<200&&s->api->candidate_list_next(&iterator));s->api->candidate_list_end(&iterator);}}jobjectArray out=e->NewObjectArray((jsize)values.size(),b,nullptr);for(size_t i=0;i<values.size();i++){jbyteArray v=bytes(e,values[i]);e->SetObjectArrayElement(out,(jsize)i,v);e->DeleteLocalRef(v);}return out;}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeTakeCommit(JNIEnv* e,jclass,jlong h) {
    Session* s=state(h); if(!s) return bytes(e, "");
    std::string text; text.swap(s->pending);
    text += drain_commit(s);
    return bytes(e, glyph_text(text));
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeRegroup(JNIEnv*,jclass,jlong h,jint boundary) {
    Session* s=state(h);return s&&s->unparsed.empty()&&regroup(s->regroup,s->api,s->id,boundary);
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeChooseRegroup(JNIEnv*,jclass,jlong h,jint index) {
    Session* s=state(h);return s&&select_regroup(s->regroup,s->id,index);
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeRegroupLabels(JNIEnv* env,jclass,jlong h) {
    Session* s=state(h);jclass b=env->FindClass("[B");int n=s?s->regroup.options.size():0;
    jobjectArray out=env->NewObjectArray(n,b,nullptr);
    for(int i=0;i<n;++i){auto v=bytes(env,s->regroup.options[i].label);env->SetObjectArrayElement(out,i,v);env->DeleteLocalRef(v);}return out;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativePreview(JNIEnv* env,jclass,jlong h) {
    Session* s=state(h);if(!s)return bytes(env,"");
    if(s->regroup.boundary>=0||s->regroup.focus_start>=0)return bytes(env,s->regroup.original);
    return bytes(env,preedit(s,true)+glyph_text(s->unparsed));
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeReading(JNIEnv* env,jclass,jlong h) {
    Session* s=state(h);if(!s)return bytes(env,"");const char* raw=s->api->get_input(s->id);
    std::string input=raw?raw:"",out;size_t cursor=s->api->get_caret_pos(s->id);
    for(size_t i=0;i<=input.size();++i){if(s->regroup.boundary>=0&&i==cursor)out+="│";if(i<input.size())out+=glyph_text(input.substr(i,1));}
    return bytes(env,out+glyph_text(s->unparsed));
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeFocusCharacter(JNIEnv* env,jclass cls,jlong h,jint target) {
    Session* s=state(h);if(!s||target<0)return JNI_FALSE;
    if(focus_character(s->regroup,s->id,target))return JNI_TRUE;
    return Java_com_simon_voiceime_RimeZhuyinNative_nativeMoveCursorToPreviewCharacter(env,cls,h,target);
}

extern "C" JNIEXPORT jintArray JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeEditRange(JNIEnv* env,jclass,jlong h) {
    Session* s=state(h);if(!s)return nullptr;auto& r=s->regroup;
    int start=r.focus_start,end=r.focus_end;
    if(r.boundary>0){start=r.boundary-1;end=r.boundary;}
    if(start<0||end<=start)return nullptr;
    jint v[2]={start,end};auto out=env->NewIntArray(2);env->SetIntArrayRegion(out,0,2,v);return out;
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeReadingSyllables(JNIEnv* env,jclass,jlong h) {
    Session* s=state(h);jclass b=env->FindClass("[B");std::vector<std::string> parts;
    if(s){auto& r=s->regroup;
        if((r.boundary>=0||r.focus_start>=0||capture(r,s->id))&&!r.stops.empty())
            for(size_t i=1;i<r.stops.size();++i)parts.push_back(glyph_text(r.input.substr(r.stops[i-1],r.stops[i]-r.stops[i-1])));
    }
    auto out=env->NewObjectArray(parts.size(),b,nullptr);
    for(size_t i=0;i<parts.size();++i){auto v=bytes(env,parts[i]);env->SetObjectArrayElement(out,i,v);env->DeleteLocalRef(v);}return out;
}
