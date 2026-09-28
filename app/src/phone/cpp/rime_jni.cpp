#include <jni.h>
#include <rime_api.h>
#include <cstdint>
#include <string>
#include <mutex>
#include <chrono>
#include <vector>

struct Session { const RimeApi* api; RimeSessionId id; };
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
extern "C" JNIEXPORT jlong JNICALL Java_com_simon_voiceime_T9RimeEngine_nativeCreate(JNIEnv* env,jclass,jstring shared,jstring user,jobject listener){
    std::lock_guard<std::mutex> lock(g_mutex);
    RimeTraits t{}; RIME_STRUCT_INIT(RimeTraits,t);
    std::string sh=jstr(env,shared), us=jstr(env,user);
    t.shared_data_dir=sh.c_str(); t.user_data_dir=us.c_str(); t.app_name="rime.simon.voiceime"; t.min_log_level=2; t.log_dir="";
    const RimeApi* api=rime_get_api(); if(!api){init_event(env,listener,"get_api",0,false,"rime_get_api returned null");return 0;}
    if(g_users++==0){auto start=InitClock::now();api->setup(&t);init_event(env,listener,"setup",elapsed(start),true,"");start=InitClock::now();api->initialize(&t);init_event(env,listener,"initialize",elapsed(start),true,"");}
    auto start=InitClock::now();
    RimeSessionId id=api->create_session();
    init_event(env,listener,"create_session",elapsed(start),id!=0,id?"":"create_session returned zero");
    if(!id){if(--g_users==0)api->finalize();return 0;}
    start=InitClock::now();bool selected=api->select_schema(id,"bopomofo_t9_simon");
    init_event(env,listener,"select_schema",elapsed(start),selected,selected?"":"schema selection failed");
    if(!selected){api->destroy_session(id);if(--g_users==0)api->finalize();return 0;}
    return (jlong)(intptr_t)new Session{api,id};
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
    return (jlong)(intptr_t)new Session{api,id};
}
extern "C" JNIEXPORT void JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeDestroy(JNIEnv*,jclass,jlong h){
    Session*s=state(h);if(!s)return;std::lock_guard<std::mutex> lock(g_mutex);s->api->destroy_session(s->id);if(--g_users==0)s->api->finalize();delete s;
}
extern "C" JNIEXPORT void JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeProcessKey(JNIEnv*,jclass,jlong h,jint key){Session*s=state(h);if(s)s->api->process_key(s->id,key,0);}
extern "C" JNIEXPORT void JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeSelect(JNIEnv*,jclass,jlong h,jint index){Session*s=state(h);if(s&&index>=0)s->api->select_candidate(s->id,(size_t)index);}
extern "C" JNIEXPORT void JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeClear(JNIEnv*,jclass,jlong h){Session*s=state(h);if(s)s->api->clear_composition(s->id);}
extern "C" JNIEXPORT jint JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeCursor(JNIEnv*,jclass,jlong h){Session*s=state(h);return s?(jint)s->api->get_caret_pos(s->id):0;}
extern "C" JNIEXPORT void JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeMoveCursor(JNIEnv*,jclass,jlong h,jboolean right){Session*s=state(h);if(!s)return;size_t p=s->api->get_caret_pos(s->id);if(right)++p;else if(p)--p;s->api->set_caret_pos(s->id,p);}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeComposing(JNIEnv*e,jclass,jlong h){Session*s=state(h);if(!s)return bytes(e,"");RIME_STRUCT(RimeContext,c);if(!s->api->get_context(s->id,&c))return bytes(e,"");std::string v=c.composition.preedit?c.composition.preedit:"";s->api->free_context(&c);return bytes(e,v);}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeCandidates(JNIEnv*e,jclass,jlong h){Session*s=state(h);jclass b=e->FindClass("[B");if(!b)return nullptr;std::vector<std::string> values;if(s){RimeCandidateListIterator iterator{};if(s->api->candidate_list_begin(s->id,&iterator)){do{if(iterator.candidate.text)values.emplace_back(iterator.candidate.text);}while(values.size()<200&&s->api->candidate_list_next(&iterator));s->api->candidate_list_end(&iterator);}}jobjectArray out=e->NewObjectArray((jsize)values.size(),b,nullptr);for(size_t i=0;i<values.size();i++){jbyteArray v=bytes(e,values[i]);e->SetObjectArrayElement(out,(jsize)i,v);e->DeleteLocalRef(v);}return out;}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_simon_voiceime_RimeZhuyinNative_nativeTakeCommit(JNIEnv*e,jclass,jlong h){Session*s=state(h);if(!s)return bytes(e,"");RIME_STRUCT(RimeCommit,c);if(!s->api->get_commit(s->id,&c))return bytes(e,"");std::string v=c.text?c.text:"";s->api->free_commit(&c);return bytes(e,v);}
extern "C" JNIEXPORT void JNICALL Java_com_simon_voiceime_T9RimeEngine_nativeDestroy(JNIEnv*,jclass,jlong h){
    Session*s=state(h);if(!s)return;std::lock_guard<std::mutex> lock(g_mutex);s->api->destroy_session(s->id);if(--g_users==0)s->api->finalize();delete s;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_simon_voiceime_T9RimeEngine_nativeQuery(JNIEnv*e,jclass,jstring shared,jstring user,jstring keys){
    std::string sh=jstr(e,shared),us=jstr(e,user),seq=jstr(e,keys);const RimeApi*api=rime_get_api();if(!api)return bytes(e,"[]");
    RimeSessionId id=api->create_session();if(!id)return bytes(e,"[]");api->select_schema(id,"bopomofo_t9_simon");
    for(unsigned char c:seq)api->process_key(id,c,0);
    RIME_STRUCT(RimeContext,ctx);std::string out="[";if(api->get_context(id,&ctx)){
        for(int i=0;i<ctx.menu.num_candidates;i++){if(i)out+=',';out+=jsonq(ctx.menu.candidates[i].text);}api->free_context(&ctx);
    }out+="]";api->destroy_session(id);return bytes(e,out);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_simon_voiceime_T9RimeEngine_nativeRoundTrip(JNIEnv*e,jclass,jstring text){return bytes(e,jstr(e,text));}
