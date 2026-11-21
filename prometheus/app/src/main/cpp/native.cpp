#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

static std::mutex engine_lock;
static std::atomic<bool> request_stop{false};
static llama_model * model = nullptr;
static llama_context * ctx = nullptr;
static llama_sampler * sampler = nullptr;
static bool initialized = false;
static std::string error;

static void free_engine() {
    if (sampler) { llama_sampler_free(sampler); sampler = nullptr; }
    if (ctx) { llama_free(ctx); ctx = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; }
}
static void fill(llama_batch_ext * b, const llama_token *tokens, int n, int start, bool logits) {
    llama_batch_ext_clear(b);
    for (int i=0;i<n;i++) {
        int idx=llama_batch_ext_add_token(b,0,tokens[i]);
        llama_pos pos = start + i;
        llama_batch_ext_set_pos(b,idx,&pos);
    }
    if(logits && n>0) llama_batch_ext_set_output_logits(b,n-1,true);
}
extern "C" JNIEXPORT jstring JNICALL
Java_ai_prometheus_android_MainActivity_nativeLoad(JNIEnv *env,jobject,jstring path) {
    std::lock_guard<std::mutex> g(engine_lock);
    free_engine();
    if(!initialized) { llama_backend_init(); initialized = true; }
    const char * p=env->GetStringUTFChars(path,nullptr);
    auto params=llama_model_default_params(); params.n_gpu_layers=0; params.load_mode=LLAMA_LOAD_MODE_MMAP;
    model=llama_model_load_from_file(p,params);
    env->ReleaseStringUTFChars(path,p);
    if(!model) return env->NewStringUTF("Unable to load model. Check GGUF compatibility, free RAM and model integrity.");
    auto cparams=llama_context_default_params();
    cparams.n_ctx=4096; cparams.n_batch=192; cparams.n_ubatch=192;
    cparams.n_threads=4; cparams.n_threads_batch=4;
    ctx=llama_init_from_model(model,cparams);
    if(!ctx) { free_engine();return env->NewStringUTF("Could not allocate context. Try a smaller model."); }
    auto sp=llama_sampler_chain_default_params();
    sampler=llama_sampler_chain_init(sp);
    llama_sampler_chain_add(sampler,llama_sampler_init_top_p(0.90f,1));
    llama_sampler_chain_add(sampler,llama_sampler_init_temp(0.65f));
    llama_sampler_chain_add(sampler,llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return env->NewStringUTF("OK");
}
extern "C" JNIEXPORT jstring JNICALL
Java_ai_prometheus_android_MainActivity_nativeGenerateStreaming(JNIEnv *env,jobject thiz,jstring input,jint maxOut) {
    std::lock_guard<std::mutex> g(engine_lock);
    request_stop.store(false);
    if(!model || !ctx) return env->NewStringUTF("ERROR: No model loaded");
    const char * u=env->GetStringUTFChars(input,nullptr);
    // Prefer the embedded template from the user's GGUF; Qwen's ChatML is a fallback.
    const char * sys = "You are Prometheus, an offline personal AI assistant. Answer conversationally and directly. Use references when relevant, not as raw answer dumps. Keep project context and behavioral corrections. Do not claim to modify code without the user's approval.";
    llama_chat_message chat[2]={{"system",sys},{"user",u}};
    const char * templ=llama_model_chat_template(model,nullptr);
    int32_t sz=templ?llama_chat_apply_template(templ,chat,2,true,nullptr,0):-1;
    std::string prompt;
    if(sz>0 && sz<1000000){
        std::vector<char> buf(sz+1);
        int32_t got=llama_chat_apply_template(templ,chat,2,true,buf.data(),buf.size());
        if(got>0 && got<=(int32_t)buf.size())prompt.assign(buf.data(),got);
    }
    if(prompt.empty()){
        prompt="<|im_start|>system\n"; prompt+=sys;
        prompt+="<|im_end|>\n<|im_start|>user\n";prompt+=u;
        prompt+="<|im_end|>\n<|im_start|>assistant\n";
    }
    env->ReleaseStringUTFChars(input,u);
    const auto * vocab=llama_model_get_vocab(model);
    int n=llama_tokenize(vocab,prompt.c_str(),prompt.size(),nullptr,0,true,true);
    n=n<0?-n:n;
    if(n<=0 || n>=3800) return env->NewStringUTF("ERROR: Prompt too large for this model context");
    std::vector<llama_token> tokens(n);
    if(llama_tokenize(vocab,prompt.c_str(),prompt.size(),tokens.data(),n,true,true)<0) return env->NewStringUTF("ERROR: Tokenization failed");
    llama_memory_clear(llama_get_memory(ctx),false);
    llama_sampler_reset(sampler);
    auto *batch=llama_batch_ext_init(ctx);
    std::string result;
    jclass clazz=env->GetObjectClass(thiz);
    jmethodID on_chunk=env->GetMethodID(clazz,"onNativeChunk","([B)V");
    for(int start=0;start<n && !request_stop.load();start+=192) {
        int len=std::min(192,n-start);
        fill(batch,tokens.data()+start,len,start,start+len==n);
        if(llama_process(ctx,LLAMA_PROCESS_TYPE_DECODE,batch)) {
            llama_batch_ext_free(batch);return env->NewStringUTF("ERROR: Prompt decoding failed");
        }
    }
    int out=std::min(std::max((int)maxOut,1),768);
    for(int i=0;i<out && n+i<4090 && !request_stop.load();i++) {
        llama_token token=llama_sampler_sample(sampler,ctx,-1);
        if(llama_vocab_is_eog(vocab,token)) break;
        char buf[512]; int size=llama_token_to_piece(vocab,token,buf,sizeof(buf),0,true);
        if(size>0) {
            result.append(buf,size);
            if(on_chunk){
                jbyteArray chunk=env->NewByteArray(size);
                if(chunk){env->SetByteArrayRegion(chunk,0,size,reinterpret_cast<jbyte*>(buf));
                    env->CallVoidMethod(thiz,on_chunk,chunk);env->DeleteLocalRef(chunk);
                    if(env->ExceptionCheck()){env->ExceptionClear();request_stop.store(true);}
                }
            }
        }
        fill(batch,&token,1,n+i,true);
        if(llama_process(ctx,LLAMA_PROCESS_TYPE_DECODE,batch)) break;
    }
    llama_batch_ext_free(batch);
    return env->NewStringUTF(result.c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_ai_prometheus_android_MainActivity_nativeStop(JNIEnv *,jobject) {
    request_stop.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_ai_prometheus_android_MainActivity_nativeUnload(JNIEnv *,jobject) {
    std::lock_guard<std::mutex> g(engine_lock); free_engine();
}
