#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
extern void *t9_create(void);extern void t9_destroy(void*);extern bool t9_push(void*,uint8_t);extern uint16_t t9_mask(void*);extern void t9_backspace(void*);extern bool t9_complete(void*);extern bool t9_closed(void*);
extern size_t t9_copy(void*,uint8_t,uint8_t*,size_t);extern bool t9_restore(void*,const uint8_t*,size_t);extern size_t t9_check(const uint8_t*,size_t,const uint16_t*,size_t,uint8_t*);extern size_t t9_data(uint8_t,uint16_t,uint32_t,uint8_t*,size_t);
#define API(name) Java_com_simon_voiceime_t9_T9Core_##name
JNIEXPORT jlong JNICALL API(create)(JNIEnv*e,jclass c){return (jlong)(intptr_t)t9_create();}
JNIEXPORT void JNICALL API(destroy)(JNIEnv*e,jclass c,jlong h){t9_destroy((void*)(intptr_t)h);}
JNIEXPORT jboolean JNICALL API(push)(JNIEnv*e,jclass c,jlong h,jint k){return k>=0&&k<=10&&t9_push((void*)(intptr_t)h,k);}
JNIEXPORT jint JNICALL API(mask)(JNIEnv*e,jclass c,jlong h){return t9_mask((void*)(intptr_t)h);}
JNIEXPORT void JNICALL API(backspace)(JNIEnv*e,jclass c,jlong h){t9_backspace((void*)(intptr_t)h);}
JNIEXPORT jboolean JNICALL API(complete)(JNIEnv*e,jclass c,jlong h){return t9_complete((void*)(intptr_t)h);}
JNIEXPORT jboolean JNICALL API(closed)(JNIEnv*e,jclass c,jlong h){return t9_closed((void*)(intptr_t)h);}
JNIEXPORT jbyteArray JNICALL API(copy)(JNIEnv*e,jclass c,jlong h,jint kind){size_t n=t9_copy((void*)(intptr_t)h,kind,NULL,0);jbyteArray out=(*e)->NewByteArray(e,n);if(!out)return NULL;uint8_t* b=malloc(n?n:1);if(!b)return NULL;t9_copy((void*)(intptr_t)h,kind,b,n);(*e)->SetByteArrayRegion(e,out,0,n,(jbyte*)b);free(b);return out;}
JNIEXPORT jboolean JNICALL API(restore)(JNIEnv*e,jclass c,jlong h,jbyteArray data){if(!data)return false;jsize n=(*e)->GetArrayLength(e,data);jbyte* b=(*e)->GetByteArrayElements(e,data,NULL);if(!b)return false;bool ok=t9_restore((void*)(intptr_t)h,(uint8_t*)b,n);(*e)->ReleaseByteArrayElements(e,data,b,JNI_ABORT);return ok;}
JNIEXPORT jboolean JNICALL API(check)(JNIEnv*e,jclass c,jbyteArray text,jintArray codes){if(!text||!codes)return false;jsize n=(*e)->GetArrayLength(e,text),m=(*e)->GetArrayLength(e,codes);jbyte*b=(*e)->GetByteArrayElements(e,text,NULL);jint*k=(*e)->GetIntArrayElements(e,codes,NULL);if(!b||!k){if(b)(*e)->ReleaseByteArrayElements(e,text,b,JNI_ABORT);if(k)(*e)->ReleaseIntArrayElements(e,codes,k,JNI_ABORT);return false;}uint16_t* seq=calloc(m?m:1,sizeof(uint16_t));uint8_t* result=calloc(n?n:1,1);bool ok=seq&&result;if(ok){for(int i=0;i<m;i++){if(k[i]<0||k[i]>999)ok=false;seq[i]=k[i];}if(ok){size_t count=t9_check((uint8_t*)b,n,seq,m,result);ok=count==(size_t)m;for(int i=0;ok&&i<m;i++)ok=result[i];}}free(seq);free(result);(*e)->ReleaseByteArrayElements(e,text,b,JNI_ABORT);(*e)->ReleaseIntArrayElements(e,codes,k,JNI_ABORT);return ok;}
JNIEXPORT jbyteArray JNICALL API(table)(JNIEnv*e,jclass c,jint kind,jint code,jint ch){size_t n=t9_data(kind,code,ch,NULL,0);jbyteArray out=(*e)->NewByteArray(e,n);if(!out)return NULL;uint8_t* b=malloc(n?n:1);if(!b)return NULL;t9_data(kind,code,ch,b,n);(*e)->SetByteArrayRegion(e,out,0,n,(jbyte*)b);free(b);return out;}
