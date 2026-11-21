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
static int context_size = 3072;

static void logmsg(const std::string &s) { __android_log_print(ANDROID_LOG_INFO,"PrometheusEngine","%s",s.c_str()); }
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
static void stage(JNIEnv *env,jobject thiz,jmethodID callback,const char *text) {
    if (!callback || env->ExceptionCheck()) return;
    jstring j=env->NewStringUTF(text);
    if(j){env->CallVoidMethod(thiz,callback,j);env->DeleteLocalRef(j);}
    if(env->ExceptionCheck())env->ExceptionClear();
}
extern "C" JNIEXPORT jstring JNICALL
Java_ai_prometheus_android_MainActivity_nativeLoad(JNIEnv *env,jobject,jstring path) {
    std::lock_guard<std::mutex> g(engine_lock);
    free_engine();
    if(!initialized) { llama_backend_init(); initialized = true; }
    const char * p=env->GetStringUTFChars(path,nullptr);
    auto params=llama_model_default_params();params.n_gpu_layers=0;params.load_mode=LLAMA_LOAD_MODE_MMAP;
    model=llama_model_load_from_file(p,params);
    env->ReleaseStringUTFChars(path,p);
    if(!model) return env->NewStringUTF("ERROR: Model load failed. Check GGUF integrity, available RAM and model format.");
    auto cparams=llama_context_default_params();
    cparams.n_ctx=context_size; cparams.n_batch=128; cparams.n_ubatch=128;
    cparams.n_threads=4; cparams.n_threads_batch=4;
    ctx=llama_init_from_model(model,cparams);
    if(!ctx){free_engine();return env->NewStringUTF("ERROR: Context allocation failed. Android may be low on memory.");}
    auto sp=llama_sampler_chain_default_params();sampler=llama_sampler_chain_init(sp);
    llama_sampler_chain_add(sampler,llama_sampler_init_top_p(0.92f,1));
    llama_sampler_chain_add(sampler,llama_sampler_init_temp(0.65f));
    llama_sampler_chain_add(sampler,llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    logmsg("4B model and 3072-token context loaded");
    return env->NewStringUTF("OK");
}
extern "C" JNIEXPORT jstring JNICALL
Java_ai_prometheus_android_MainActivity_nativeGenerateStreaming(JNIEnv *env,jobject thiz,jstring input,jint maxOut) {
    std::lock_guard<std::mutex> g(engine_lock);
    request_stop.store(false);
    if(!model || !ctx || !sampler) return env->NewStringUTF("ERROR: Model not loaded. Run prometheus-sync --model-only in Termux.");
    jclass clazz=env->GetObjectClass(thiz);
    jmethodID on_chunk=env->GetMethodID(clazz,"onNativeChunk","([B)V");
    jmethodID on_status=env->GetMethodID(clazz,"onNativeStatus","(Ljava/lang/String;)V");
    if(env->ExceptionCheck())env->ExceptionClear();
    const char * raw=env->GetStringUTFChars(input,nullptr);
    std::string user=raw?raw:"";
    if(raw)env->ReleaseStringUTFChars(input,raw);
    const char * sys="You are Prometheus, a local offline assistant. Respond directly in natural conversation. Follow the user's saved preferences and corrections. Treat retrieved references as evidence rather than copying them. When a user corrects you, incorporate the correction. Do not claim you modified files without doing so. Start with the answer, not a reasoning preamble; answer in visible conversational text right away.";
    llama_chat_message chat[2]={{"system",sys},{"user",user.c_str()}};
    const char * templ=llama_model_chat_template(model,nullptr);
    int32_t sz=templ?llama_chat_apply_template(templ,chat,2,true,nullptr,0):-1;
    std::string prompt;
    if(sz>0 && sz<1000000){
        std::vector<char> buf(sz+1);
        int32_t got=llama_chat_apply_template(templ,chat,2,true,buf.data(),(int32_t)buf.size());
        if(got>0 && got<=(int32_t)buf.size())prompt.assign(buf.data(),got);
    }
    std::string fallback="<|im_start|>system\n"+std::string(sys)+"<|im_end|>\n<|im_start|>user\n"+user+"<|im_end|>\n<|im_start|>assistant\n";
    if(prompt.empty())prompt=fallback;
    const auto * vocab=llama_model_get_vocab(model);
    auto generate=[&](const std::string &formatted,int limit,std::string &failure)->int{
        stage(env,thiz,on_status,"Reading context on device...");
        int n=llama_tokenize(vocab,formatted.c_str(),formatted.size(),nullptr,0,true,true);
        n=n<0?-n:n;
        if(n<=0){failure="ERROR: Tokenizer returned no tokens";return -1;}
        if(n>context_size-320){failure="ERROR: Conversation is too long for available memory. Start a new chat or reduce stored context.";return -1;}
        std::vector<llama_token> tokens(n);
        if(llama_tokenize(vocab,formatted.c_str(),formatted.size(),tokens.data(),n,true,true)<0){failure="ERROR: Could not tokenize prompt";return -1;}
        llama_memory_clear(llama_get_memory(ctx),false);llama_sampler_reset(sampler);
        auto *batch=llama_batch_ext_init(ctx);
        bool decoded=true;
        for(int start=0;start<n && !request_stop.load();start+=128){
            int len=std::min(128,n-start);
            fill(batch,tokens.data()+start,len,start,start+len==n);
            if(llama_process(ctx,LLAMA_PROCESS_TYPE_DECODE,batch)){decoded=false;break;}
            if(start==0 || (start / 128) % 4 == 0) {
                std::string progress = "Reading prompt "+std::to_string(std::min(start+len,n))+"/"+std::to_string(n)+" tokens...";
                stage(env,thiz,on_status,progress.c_str());
            }
        }
        if(!decoded){llama_batch_ext_free(batch);failure="ERROR: The model failed to process this prompt. Try a shorter question.";return -1;}
        stage(env,thiz,on_status,"Generating answer on device...");
        int pieces=0, sampled=0;
        for(int i=0;i<limit && n+i<context_size-8 && !request_stop.load();i++){
            llama_token token=llama_sampler_sample(sampler,ctx,-1);
            llama_sampler_accept(sampler,token);
            if(llama_vocab_is_eog(vocab,token))break;
            char buf[512];int size=llama_token_to_piece(vocab,token,buf,sizeof(buf),0,true);
            if(size>0 && on_chunk){
                jbyteArray bytes=env->NewByteArray(size);
                if(bytes){env->SetByteArrayRegion(bytes,0,size,reinterpret_cast<jbyte*>(buf));
                    env->CallVoidMethod(thiz,on_chunk,bytes);env->DeleteLocalRef(bytes);
                    if(env->ExceptionCheck()){env->ExceptionClear();request_stop.store(true);}
                    pieces+=size;
                }
            }
            fill(batch,&token,1,n+i,true);
            if(llama_process(ctx,LLAMA_PROCESS_TYPE_DECODE,batch)){failure="ERROR: Decode failed while generating tokens";break;}
            sampled++;
        }
        llama_batch_ext_free(batch);
        logmsg("Generation complete; tokens="+std::to_string(sampled)+" visible_bytes="+std::to_string(pieces));
        return pieces;
    };
    std::string failure;
    int budget=std::min(std::max((int)maxOut,24),640);
    int bytes=generate(prompt,budget,failure);
    if(bytes==0 && !request_stop.load() && prompt!=fallback){
        stage(env,thiz,on_status,"Retrying with a simpler chat template...");
        failure.clear();bytes=generate(fallback,budget,failure);
    }
    if(request_stop.load())return env->NewStringUTF("__PROMETHEUS_STOPPED__");
    if(bytes<0)return env->NewStringUTF(failure.c_str());
    if(bytes==0)return env->NewStringUTF("ERROR: Model ended without producing text. Check GGUF compatibility and native logs.");
    if(!failure.empty())logmsg(failure);
    return env->NewStringUTF("__PROMETHEUS_STREAMED__");
}
extern "C" JNIEXPORT void JNICALL
Java_ai_prometheus_android_MainActivity_nativeStop(JNIEnv *,jobject){request_stop.store(true);}
extern "C" JNIEXPORT void JNICALL
Java_ai_prometheus_android_MainActivity_nativeUnload(JNIEnv *,jobject){std::lock_guard<std::mutex> g(engine_lock);free_engine();}
