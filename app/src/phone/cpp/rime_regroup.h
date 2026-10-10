// Uses the packaged Rime engine's native syllabifier, candidates and sentence weights.
// Pinned upstream headers are BSD licensed; no translator/schema/word-list changes.
#include <rime/service.h>
#include <rime/context.h>
#include <rime/menu.h>
#include <rime/gear/translator_commons.h>
#include <rime/gear/grammar.h>
#include <rime/schema.h>
#include <algorithm>
#include <cmath>
#include <rime/dict/prism.h>
#include <cstring>
#include <tuple>
#include <fstream>


// Resolve the engine's RTTI rather than the JNI DSO's duplicate weak RTTI.
// Both Android libraries statically link libc++; pointer identity based RTTI
// in the JNI copy otherwise rejects real native Phrase/Sentence/factories.
// Unknown/incompatible types fail the actual ABI dynamic cast (no unchecked cast).
extern "C" void* __dynamic_cast(const void*,const void*,const void*,std::ptrdiff_t);
#include <dlfcn.h>
template<class T,class B> static T* engine_cast(B* value) {
    if(!value)return nullptr;
    // Locally constructed edit wrappers carry this DSO's RTTI.
    if(auto* local=dynamic_cast<T*>(value))return local;
#ifdef __ANDROID__
    static void* library=dlopen("librime.so",RTLD_NOW|RTLD_NOLOAD);
    if(!library)return nullptr;
    auto lookup=[](const std::type_info& type){return dlsym(library,(std::string("_ZTI")+type.name()).c_str());};
    static auto source=lookup(typeid(B)),target=lookup(typeid(T));
    if(!source||!target)return nullptr;
    return static_cast<T*>(::__dynamic_cast(value,source,target,-1));
#else
    return dynamic_cast<T*>(value);
#endif
}
// Edited candidates are wrapped by JNI, while translator candidates belong
// to librime. Unwrap both RTTI domains before asking for native word spans.
static rime::an<rime::Candidate> genuine_candidate(rime::an<rime::Candidate> candidate) {
    while(candidate) {
        if(auto* shadow=engine_cast<rime::ShadowCandidate>(candidate.get())) {
            candidate=shadow->item();continue;
        }
        if(auto* unique=engine_cast<rime::UniquifiedCandidate>(candidate.get())) {
            if(!unique->items().empty()){candidate=unique->items().front();continue;}
        }
        break;
    }
    return candidate;
}
static rime::an<rime::Phrase> native_phrase(const rime::an<rime::Candidate>& candidate) {
    if(!candidate)return nullptr;
    auto genuine=genuine_candidate(candidate);
    auto* result=engine_cast<rime::Phrase>(genuine.get());
    return result?rime::an<rime::Phrase>(genuine,result):nullptr;
}
static rime::an<rime::Sentence> native_sentence(const rime::an<rime::Candidate>& candidate) {
    if(!candidate)return nullptr;
    auto genuine=genuine_candidate(candidate);
    auto* result=engine_cast<rime::Sentence>(genuine.get());
    return result?rime::an<rime::Sentence>(genuine,result):nullptr;
}
struct RegroupOption {
    int start, end;
    std::string label;
    rime::an<rime::Candidate> candidate;
    double weight;
    std::vector<rime::an<rime::Candidate>> pieces;
    std::string repaired_input, repaired_code;
    int changed_key=-1;
    double touch_score=0;
    bool neighbour=false;
    bool caret_neighbour=false;
    bool literal=false;
    bool homophone=false;
    bool character=false;
    int grouping_start=-1,grouping_end=-1;
    rime::an<rime::Candidate> repaired_grouping;

};
struct RepairTouch {char literal;std::map<char,double> probability;std::set<char> neighbours;};
struct RegroupState {
    RimeSessionId probe = 0;
    std::string user_path;
    std::unique_ptr<rime::Prism> prism;
    std::map<size_t,RepairTouch> touches;
    std::string input, original, preedit, glyph_preedit;
    std::vector<int> glyph_stops;
    std::vector<size_t> stops;
    std::vector<RegroupOption> options;
    std::vector<rime::an<rime::Candidate>> literal_pieces;
    int boundary = -1;
    bool unparsed_mode=false;
    int focus_start=-1,focus_end=-1;
    std::unique_ptr<rime::Grammar> grammar;
    std::string cache_input,cache_preview;
    std::map<int,std::vector<RegroupOption>> cache;
    std::string sandhi_cache_input,sandhi_cache_preview;
    std::vector<size_t> sandhi_cache_stops;
    std::map<int,std::vector<RegroupOption>> sandhi_cache;

};
static rime::Context* context(RimeSessionId id) {
    auto session = rime::Service::instance().GetSession(id);
    return session ? session->context() : nullptr;
}
static size_t cp_byte(const std::string& text, int cp) {
    size_t i=0;
    while(i<text.size() && cp>0) {++i;while(i<text.size()&&(static_cast<unsigned char>(text[i])&0xc0)==0x80)++i;--cp;}
    return i;
}
static int cp_count(const std::string& text) {
    int n=0;for(unsigned char c:text)if((c&0xc0)!=0x80)++n;return n;
}
static std::string cp_slice(const std::string& text,int start,int end) {
    size_t a=cp_byte(text,start),b=cp_byte(text,end);return text.substr(a,b-a);
}
static void pin(rime::Composition& comp, size_t start,size_t end,rime::an<rime::Candidate> candidate) {
    if(end<=start)return;
    rime::Segment seg(start,end);seg.status=rime::Segment::kConfirmed;seg.tags.insert("abc");
    seg.menu=rime::New<rime::Menu>();seg.menu->AddTranslation(rime::New<rime::UniqueTranslation>(candidate));
    seg.menu->Prepare(1);comp.push_back(seg);
}
static void pin_text(rime::Composition& comp,size_t start,size_t end,const std::string& text) {
    pin(comp,start,end,rime::New<rime::SimpleCandidate>("regroup_context",start,end,text));
}
// Literal context retains the alignment already supplied by native decoding.
// Candidate type/text/rank stay unchanged; no reading boundaries are guessed.
struct AlignedTextCandidate : rime::SimpleCandidate {
    const std::vector<size_t> syllable_stops;
    const std::string source_keys;
    AlignedTextCandidate(size_t start,size_t end,const std::string& text,
                         std::vector<size_t> stops,std::string keys)
        : rime::SimpleCandidate("regroup_context",start,end,text),
          syllable_stops(std::move(stops)),source_keys(std::move(keys)) {}
};
static bool valid_alignment(const std::vector<size_t>& stops,size_t start,size_t end,
                            const std::string& text,const std::string& raw) {
    if(end<=start||end>raw.size()||stops.size()!=static_cast<size_t>(cp_count(text)+1)||
       stops.empty()||stops.front()!=start||stops.back()!=end)return false;
    for(size_t i=1;i<stops.size();++i)if(stops[i]<=stops[i-1])return false;
    return true;
}
static std::vector<size_t> aligned_slice(const std::vector<size_t>& stops,size_t first,size_t last,int delta=0) {
    if(first>last||last>=stops.size())return {};
    std::vector<size_t> result;
    for(size_t i=first;i<=last;++i){long long at=static_cast<long long>(stops[i])+delta;if(at<0)return {};result.push_back(static_cast<size_t>(at));}
    return result;
}
static void pin_aligned_text(rime::Composition& comp,size_t start,size_t end,const std::string& text,
                             const std::string& raw,std::vector<size_t> stops) {
    if(end<=start)return;
    if(!valid_alignment(stops,start,end,text,raw)){pin_text(comp,start,end,text);return;}
    pin(comp,start,end,rime::New<AlignedTextCandidate>(start,end,text,std::move(stops),raw.substr(start,end-start)));
}
// Rime's own PhraseSyllabifier supplies all raw-key boundaries, including untoned input.
static bool capture(RegroupState& r,RimeSessionId id) {
    auto* ctx=context(id);if(!ctx||ctx->input().empty())return false;
    if(r.input!=ctx->input()) {r.input=ctx->input();r.stops.clear();}
    if(ctx->caret_pos()!=r.input.size())ctx->set_caret_pos(r.input.size());
    r.original=ctx->GetCommitText();r.preedit=ctx->GetPreedit().text;
    r.literal_pieces.clear();for(auto& seg:ctx->composition())if(auto cand=seg.GetSelectedCandidate())r.literal_pieces.push_back(cand);
    if(r.stops.empty()) {
        r.stops.push_back(0);
        for(auto& seg:ctx->composition()) {
            if(seg.start==seg.end)continue;
            auto cand=seg.GetSelectedCandidate();
            auto phrase=cand?native_phrase(cand):nullptr;
            if(!cand){r.stops.clear();return false;}
            std::vector<size_t> ends;
            auto genuine=genuine_candidate(cand);
            if(auto* aligned=dynamic_cast<AlignedTextCandidate*>(genuine.get())) {
                if(!valid_alignment(aligned->syllable_stops,cand->start(),cand->end(),cand->text(),r.input)||
                   r.input.substr(cand->start(),cand->end()-cand->start())!=aligned->source_keys){r.stops.clear();return false;}
                ends.assign(aligned->syllable_stops.begin()+1,aligned->syllable_stops.end());
            }else if(phrase){auto spans=phrase->spans();size_t at=spans.start();
                while(at<spans.end()){size_t next=spans.NextStop(at);if(next<=at)break;ends.push_back(next);at=next;}
            }
            if(ends.size()!=static_cast<size_t>(cp_count(cand->text()))||ends.empty()||ends.back()!=cand->end()) {
                // user_table phrases have no dictionary spans. Only recover
                // boundaries explicitly present in the typed reading; ambiguous
                // multi-syllable inputs stay with Rime's ordinary menu.
                ends.clear();
                if(genuine_candidate(cand)->type()=="user_table"){
                    for(size_t at=cand->start();at<cand->end();++at)
                        if(std::string(" 6347").find(r.input[at])!=std::string::npos)ends.push_back(at+1);
                    if(ends.empty()||ends.back()!=cand->end())ends.push_back(cand->end());
                }
                if(ends.size()!=static_cast<size_t>(cp_count(cand->text()))||ends.empty()) {r.stops.clear();return false;}
            }
            r.stops.insert(r.stops.end(),ends.begin(),ends.end());
        }
    }
    return r.stops.size()==static_cast<size_t>(cp_count(r.original)+1) && r.stops.back()==r.input.size();
}
static std::string grouping(const rime::an<rime::Candidate>& cand) {
    auto sentence=native_sentence(cand);
    if(!sentence)return cand->text();
    std::string out;for(const auto& word:sentence->components()){if(!out.empty())out+="｜";out+=word.text;}
    return out;
}
static std::vector<rime::DictEntry> words(const rime::an<rime::Candidate>& cand) {
    if(!cand)return {}; // Deleting the only vowel can leave an untranslated segment.
    auto genuine=genuine_candidate(cand);
    auto sentence=native_sentence(genuine);if(sentence)return sentence->components();
    auto phrase=native_phrase(genuine);if(phrase)return {phrase->entry()};
    rime::DictEntry word;word.text=cand->text();word.weight=0;return {word};
}
static double sentence_score(RegroupState& r,const RegroupOption& option) {
    std::vector<rime::DictEntry> sentence;
    auto append=[&](const std::string& text){if(!text.empty()){rime::DictEntry word;word.text=text;word.weight=0;sentence.push_back(word);}};
    // Preserve the engine's word boundaries and weights outside the window.
    // Concatenating them into an unknown zero-weight pseudo-word makes scores
    // for different window sizes incomparable.
    auto outside=[&](int from,int to) {
        if(r.literal_pieces.empty()){append(cp_slice(r.original,from,to));return;}
        int position=0;
        for(const auto& piece:r.literal_pieces)for(const auto& word:words(piece)) {
            int end=position+cp_count(word.text);
            int a=std::max(from,position),b=std::min(to,end);
            if(a<b) {
                if(a==position&&b==end)sentence.push_back(word);
                else append(cp_slice(word.text,a-position,b-position));
            }
            position=end;
        }
    };
    outside(0,option.start);
    if(option.pieces.empty()){auto entries=words(option.candidate);sentence.insert(sentence.end(),entries.begin(),entries.end());}
    else for(const auto& piece:option.pieces){auto entries=words(piece);sentence.insert(sentence.end(),entries.begin(),entries.end());}
    outside(option.end,cp_count(r.original));
    double score=0;
    for(size_t i=0;i<sentence.size();++i){
        std::string preceding;if(i>1)preceding+=sentence[i-2].text;if(i>0)preceding+=sentence[i-1].text;
        score+=rime::Grammar::Evaluate(preceding,sentence[i].text,sentence[i].weight,i+1==sentence.size(),r.grammar.get());
    }
    return score;
}

// Only normal dictionary spellings with an explicitly typed tone qualify.
static bool normal_toned(RegroupState& r,const std::string& code) {
    if(!r.prism||code.empty()||std::string(" 6347").find(code.back())==std::string::npos)return false;
    int value=-1;if(!r.prism->GetValue(code,&value))return false;
    auto accessor=r.prism->QuerySpelling(value);
    for(;!accessor.exhausted();accessor.Next())if(accessor.properties().type==rime::kNormalSpelling)return true;
    return false;
}
static bool splits_toned(RegroupState& r,size_t boundary) {
    for(size_t a=boundary>3?boundary-3:0;a<boundary;++a)
        for(size_t b=boundary+1;b<=std::min(r.input.size(),a+4);++b)
            if(normal_toned(r,r.input.substr(a,b-a)))return true;
    return false;
}
static bool default_neighbour(char from,char to) {
    const std::string rows[]={"1234567890-","qwertyuiop","asdfghjkl;","zxcvbnm,./"};
    int ax=-1,ay=-1,bx=-1,by=-1;
    for(int y=0;y<4;y++){auto a=rows[y].find(from),b=rows[y].find(to);if(a!=std::string::npos){ax=a;ay=y;}if(b!=std::string::npos){bx=b;by=y;}}
    return ax>=0&&bx>=0&&std::abs(ax-bx)+std::abs(ay-by)==1;
}
static void append_repairs(RegroupState& r,rime::Context* probe,int boundary,int count) {
    // A broken syllable may have been decoded as multiple abbreviated words.
    // Join their native spans, replace exactly one key, then require one full
    // normal toned syllable. No insertion, deletion or implicit missing tone.
    const std::string alphabet="1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347";
    for(int a=std::max(0,boundary-2);a<=std::min(boundary,count-1);++a){
        for(int b=std::max(a+1,boundary);b<=std::min(count,boundary+(r.unparsed_mode?4:2));++b){
            size_t from=r.stops[a],to=r.stops[b];if(to-from>4||to<=from)continue;
            std::string code=r.input.substr(from,to-from);
            for(size_t p=0;p<code.size();++p)for(char key:alphabet){
                if(key==code[p])continue;
                auto corrected=code;corrected[p]=key;if(!normal_toned(r,corrected))continue;
                // The dictionary check is the slot test: invalid initial,
                // medial/final ordering or duplicated slots cannot pass it.
                bool nearby=default_neighbour(code[p],key);double touch=0;
                auto sample=r.touches.find(from+p);
                if(sample!=r.touches.end()&&sample->second.literal==code[p]){
                    nearby=sample->second.neighbours.count(key)>0;
                    auto prob=sample->second.probability.find(key);
                    auto literal=sample->second.probability.find(code[p]);
                    double lp=literal==sample->second.probability.end()?1:std::max(1e-30,literal->second);
                    touch=std::log((prob==sample->second.probability.end()?1e-30:std::max(1e-30,prob->second))/lp);
                }
                std::string input=r.input;input.replace(from,to-from,corrected);
                const auto probe_input=input.substr(0,to);probe->Clear();
                rime::Composition prefix;prefix.Reset(probe_input);pin_text(prefix,0,from,cp_slice(r.original,0,a));prefix.Forward();
                probe->set_composition(std::move(prefix));probe->set_input(probe_input);
                if(probe->composition().empty())continue;
                for(int i=0;i<5;++i){
                    auto candidate=probe->composition().back().GetCandidateAt(i);if(!candidate)break;
                    auto phrase=native_phrase(candidate);
                    if(!phrase||candidate->start()!=from||candidate->end()!=to||cp_count(candidate->text())!=1)continue;
                    if(genuine_candidate(candidate)->type()!="user_table"){auto spans=phrase->spans();if(spans.start()!=from||spans.NextStop(from)!=to)continue;}
                    bool duplicate=false;for(auto& old:r.options)if(old.start==a&&old.end==b&&old.label==candidate->text()&&old.repaired_code==corrected){duplicate=true;break;}
                    if(duplicate)continue;
                    RegroupOption option{a,b,candidate->text(),candidate,phrase->weight()};
                    option.repaired_input=input;option.repaired_code=corrected;option.changed_key=from+p;option.touch_score=touch;option.neighbour=nearby;
                    r.options.push_back(std::move(option));
                }
            }
        }
    }
}

// Re-decode the same bounded neighbourhood with the corrected reading. Keep
// the single-key repair span separate from the grouping carried by selection.
using RepairMenus=std::map<std::tuple<std::string,int,int>,std::vector<rime::an<rime::Candidate>>>;
static void regroup_repair(RegroupState& r,rime::Context* probe,RegroupOption& option,int boundary,int count,RepairMenus& menus) {
    double best=sentence_score(r,option);
    const size_t from=r.stops[option.start],to=r.stops[option.end];
    for(int a=std::max(0,boundary-2);a<=option.start;++a) {
        for(int b=option.end;b<=std::min(count,boundary+2);++b) {
            // A repaired syllable may replace several broken native spans.
            if(b-a-(option.end-option.start)+1>3)continue;
            auto key=std::make_tuple(option.repaired_input,a,b);
            auto found=menus.find(key);
            if(found==menus.end()) {
                std::vector<rime::an<rime::Candidate>> candidates;
                const auto probe_input=option.repaired_input.substr(0,r.stops[b]);
                if(probe->input()!=probe_input)probe->Clear();
                rime::Composition prefix;prefix.Reset(probe_input);
                pin_text(prefix,0,r.stops[a],cp_slice(r.original,0,a));
                prefix.Forward();probe->set_composition(std::move(prefix));probe->set_input(probe_input);
                if(!probe->composition().empty())for(int i=0;i<12;++i) {
                    auto candidate=probe->composition().back().GetCandidateAt(i);if(!candidate)break;
                    candidates.push_back(candidate);
                }
                found=menus.emplace(std::move(key),std::move(candidates)).first;
            }
            for(const auto& candidate:found->second) {
                auto phrase=native_phrase(candidate);
                if(!phrase||candidate->start()!=r.stops[a]||candidate->end()!=r.stops[b])continue;
                auto spans=phrase->spans();size_t at=spans.start();int character=0;bool contains=false;
                while(at<spans.end()) {
                    size_t next=spans.NextStop(at);if(next<=at)break;
                    if(at==from&&next==to&&cp_slice(candidate->text(),character,character+1)==option.label)contains=true;
                    at=next;++character;
                }
                if(!contains||character!=cp_count(candidate->text()))continue;
                RegroupOption grouped{a,b,grouping(candidate),candidate,0};
                double score=sentence_score(r,grouped);
                if(score>best) {
                    best=score;option.grouping_start=a;option.grouping_end=b;
                    option.repaired_grouping=candidate;
                }
            }
        }
    }
}

static bool regroup(RegroupState& r,const RimeApi* api,RimeSessionId id,int boundary,bool unparsed=false) {
    bool alreadyFocused=r.boundary>=0;
    r.options.clear();r.boundary=-1;r.focus_start=r.focus_end=-1;
    auto* real=context(id);
    if(!unparsed&&!(alreadyFocused&&real&&real->input()==r.input&&!r.stops.empty())&&!capture(r,id))return false;
    int count=static_cast<int>(r.stops.size())-1;
    if(boundary<0||boundary>count)return false;
    if(!r.probe) {r.probe=api->create_session();api->select_schema(r.probe,"bopomofo_express");}
    auto* probe=context(r.probe);if(!probe)return false;
    if(!r.grammar){auto* factory=engine_cast<rime::Grammar::Component>(rime::Registry::instance().Find("grammar"));if(factory)r.grammar.reset(factory->Create(rime::Service::instance().GetSession(id)->schema()->config()));}
    if(!r.grammar)return false; // Ranking without the packaged grammar is not a substitute.

    if(r.cache_input!=r.input||r.cache_preview!=r.original){r.cache.clear();r.cache_input=r.input;r.cache_preview=r.original;}
    auto cached=r.cache.find(boundary);
    if(cached!=r.cache.end())r.options=cached->second;
    else {
    // Query native menus for bounded windows ending/starting/spanning the caret.
    for(int a=std::max(0,boundary-2);a<=std::min(boundary,count-1);++a) {
        for(int b=std::max(a+1,boundary);b<=std::min(count,boundary+2);++b) {
            if(b-a>3||splits_toned(r,r.stops[a])||splits_toned(r,r.stops[b]))continue;
            const auto probe_input=r.input.substr(0,r.stops[b]);
            if(probe->input()!=probe_input)probe->Clear();
            // set_input translates synchronously; pin context before that update.
            rime::Composition prefix;prefix.Reset(probe_input);
            pin_text(prefix,0,r.stops[a],cp_slice(r.original,0,a));
            prefix.Forward();probe->set_composition(std::move(prefix));probe->set_input(probe_input);
            auto& comp=probe->composition();if(comp.empty())continue;
            auto& seg=comp.back();
            for(size_t i=0;i<12;++i) {
                auto cand=seg.GetCandidateAt(i);if(!cand)break;
                if(cand->start()!=r.stops[a]||cand->end()!=r.stops[b]||(cp_count(cand->text())<=0||cp_count(cand->text())>b-a))continue;
                auto phrase=native_phrase(cand);
                if(!phrase)continue;
                std::string label=grouping(cand);
                bool duplicate=false;
                for(const auto& old:r.options)if(old.start==a&&old.end==b&&old.label==label){duplicate=true;break;}
                if(!duplicate)r.options.push_back({a,b,label,cand,phrase->weight()});
            }
            // Explicit native split: a learned whole phrase must not hide a
            // neighbouring grouping. Fix a native left word, then ask the same
            // translator to score the right word using that left word as context.
            for(int split=a+1;split<b;++split){
                const auto left_input=r.input.substr(0,r.stops[split]);probe->Clear();
                rime::Composition leftPrefix;leftPrefix.Reset(left_input);
                pin_text(leftPrefix,0,r.stops[a],cp_slice(r.original,0,a));
                leftPrefix.Forward();probe->set_composition(std::move(leftPrefix));probe->set_input(left_input);
                if(probe->composition().empty())continue;
                std::vector<rime::an<rime::Candidate>> lefts;
                for(int i=0;i<4;i++){auto cand=probe->composition().back().GetCandidateAt(i);if(!cand)break;
                    if(cand->start()==r.stops[a]&&cand->end()==r.stops[split]&&cp_count(cand->text())==split-a)lefts.push_back(cand);
                    if(lefts.size()==1)break;
                }
                for(const auto& left:lefts){
                    auto lp=native_phrase(left);if(!lp)continue;
                    probe->Clear();rime::Composition selected;selected.Reset(probe_input);
                    pin_text(selected,0,r.stops[a],cp_slice(r.original,0,a));pin(selected,r.stops[a],r.stops[split],left);
                    selected.Forward();probe->set_composition(std::move(selected));probe->set_input(probe_input);
                    if(probe->composition().empty())continue;
                    for(int i=0;i<4;i++){auto right=probe->composition().back().GetCandidateAt(i);if(!right)break;
                        if(right->start()!=r.stops[split]||right->end()!=r.stops[b]||cp_count(right->text())!=b-split)continue;
                        auto rp=native_phrase(right);if(!rp)continue;
                        std::string label=grouping(left)+"｜"+grouping(right);
                        bool duplicate=false;for(const auto& old:r.options)if(old.start==a&&old.end==b&&old.label==label){duplicate=true;break;}
                        if(!duplicate)r.options.push_back({a,b,label,nullptr,lp->weight()+rp->weight(),{left,right}});
                        break;
                    }
                }
            }
        }
    }
    append_repairs(r,probe,boundary,count);
    RepairMenus repair_menus;
    for(auto& option:r.options)if(!option.repaired_input.empty())regroup_repair(r,probe,option,boundary,count,repair_menus);
    RegroupOption literal{0,count,r.original,rime::New<rime::SimpleCandidate>("literal",0,r.input.size(),r.original),0,r.literal_pieces};literal.literal=true;r.options.push_back(std::move(literal));
    for(auto& option:r.options) {
        if(option.repaired_grouping) {
            RegroupOption grouped{option.grouping_start,option.grouping_end,"",option.repaired_grouping,0};
            option.weight=sentence_score(r,grouped);
        } else option.weight=sentence_score(r,option);
    }
    // Compare engine sentence scores with touch likelihood across windows.
    std::stable_sort(r.options.begin(),r.options.end(),[boundary](const auto& x,const auto& y){
        bool xf=!x.repaired_input.empty()&&!x.neighbour,yf=!y.repaired_input.empty()&&!y.neighbour;
        if(xf!=yf)return !xf;
        double xs=x.weight+x.touch_score,ys=y.weight+y.touch_score;
        if(xs!=ys)return xs>ys;
        if(x.neighbour!=y.neighbour)return x.neighbour;
        return x.weight>y.weight;
    });
    r.cache[boundary]=r.options;
    }
    // Boundary edits address the decoded preview directly. Mapping every
    // native caret back through the translator would re-decode untouched words.
    // Both boundary and word focus retain the live composition until a choice.
    r.boundary=boundary;return true;
}
static bool select_regroup(RegroupState& r,RimeSessionId id,int index) {
    auto* ctx=context(id);
    if(!ctx||index<0||index>=static_cast<int>(r.options.size())||(ctx->input()!=r.input&&!r.unparsed_mode))return false;
    if(r.unparsed_mode)ctx->set_input(r.input);
    auto option=r.options[index];
    if(option.repaired_grouping) {
        option.start=option.grouping_start;option.end=option.grouping_end;
        option.candidate=option.repaired_grouping;option.pieces.clear();
    }
    if(!option.repaired_input.empty())ctx->set_input(option.repaired_input);
    const int delta=option.repaired_input.empty()?0:static_cast<int>(option.repaired_input.size())-static_cast<int>(r.input.size());
    rime::Composition fixed;fixed.Reset(ctx->input());
    pin_aligned_text(fixed,0,r.stops[option.start],cp_slice(r.original,0,option.start),ctx->input(),aligned_slice(r.stops,0,option.start));
    if(option.pieces.empty())pin(fixed,r.stops[option.start],r.stops[option.end]+delta,option.candidate);
    else for(const auto& piece:option.pieces)pin(fixed,piece->start(),piece->end(),piece);
    pin_aligned_text(fixed,r.stops[option.end]+delta,r.input.size()+delta,cp_slice(r.original,option.end,cp_count(r.original)),ctx->input(),aligned_slice(r.stops,option.end,r.stops.size()-1,delta));
    ctx->set_composition(std::move(fixed));ctx->set_caret_pos(ctx->input().size());
    // Preserve known outside alignment even though those words are pinned as
    // SimpleCandidates. Re-syllabify only the accepted native candidate span.
    std::vector<size_t> next(r.stops.begin(),r.stops.begin()+option.start+1);
    auto append_stops=[&](const rime::an<rime::Candidate>& candidate){
        auto phrase=native_phrase(candidate);if(!phrase)return;
        auto spans=phrase->spans();size_t at=spans.start();while(at<spans.end()){size_t stop=spans.NextStop(at);if(stop<=at)break;next.push_back(stop);at=stop;}
    };
    if(option.literal||option.homophone)next=r.stops;
    else if(option.pieces.empty())append_stops(option.candidate);else for(const auto& piece:option.pieces)append_stops(piece);
    if(!option.literal&&!option.homophone)for(size_t i=option.end+1;i<r.stops.size();++i)next.push_back(r.stops[i]+delta);
    r.stops=std::move(next);r.input=ctx->input();r.original=ctx->GetCommitText();r.cache.clear();r.touches.erase(option.changed_key);
    r.options.clear();r.boundary=-1;r.focus_start=r.focus_end=-1;return true;
}

// Mandarin surface tones are alternate dictionary readings only. Keep raw keys
// and literal-tone candidates intact; accept only 不/一 at the changed syllable.
static std::vector<RegroupOption> sandhi_words(RegroupState& r,int target) {
    std::vector<RegroupOption> result;
    const int count=static_cast<int>(r.stops.size())-1;
    if(count<2)return result;
    struct Change {int syllable;char tone;std::string glyph;};
    std::vector<Change> changes;
    for(int i=0;i+1<count;i++) {
        auto code=r.input.substr(r.stops[i],r.stops[i+1]-r.stops[i]);
        auto next=r.input.substr(r.stops[i+1],r.stops[i+2]-r.stops[i+1]);
        if(next.empty())continue;
        char tone=next.back();
        if(code=="1j6"&&tone=='4')changes.push_back({i,'4',"不"});
        if((code=="u6"&&tone=='4')||(code=="u4"&&(tone==' '||tone=='6'||tone=='3')))changes.push_back({i,' ',"一"});
    }
    if(changes.empty())return result;
    if(r.sandhi_cache_input!=r.input||r.sandhi_cache_preview!=r.original||r.sandhi_cache_stops!=r.stops) {
        r.sandhi_cache.clear();r.sandhi_cache_input=r.input;r.sandhi_cache_preview=r.original;r.sandhi_cache_stops=r.stops;
    }
    auto cached=r.sandhi_cache.find(target);if(cached!=r.sandhi_cache.end())return cached->second;
    if(!r.probe){auto* api=rime_get_api();r.probe=api->create_session();api->select_schema(r.probe,"bopomofo_express");}
    auto* probe=context(r.probe);if(!probe)return result;
    std::set<std::tuple<int,int,std::string>> seen;
    for(int a=std::max(0,target-3);a<=target;a++)for(int b=target+1;b<=std::min(count,a+4);b++) {
        std::vector<Change> local;for(auto change:changes)if(change.syllable>=a&&change.syllable+1<b)local.push_back(change);
        // At most three eligible positions in a four-syllable lexical window.
        for(unsigned mask=1;mask<(1u<<local.size());mask++) {
            auto input=r.input.substr(0,r.stops[b]);
            for(unsigned i=0;i<local.size();i++)if(mask&(1u<<i))input[r.stops[local[i].syllable+1]-1]=local[i].tone;
            probe->Clear();
            rime::Composition prefix;prefix.Reset(input);pin_text(prefix,0,r.stops[a],cp_slice(r.original,0,a));
            prefix.Forward();probe->set_composition(std::move(prefix));probe->set_input(input);
            if(probe->composition().empty())continue;
            for(int i=0;i<200;i++) {
                auto candidate=probe->composition().back().GetCandidateAt(i);if(!candidate)break;
                auto phrase=native_phrase(candidate);auto sentence=native_sentence(candidate);
                if(!phrase||candidate->start()!=r.stops[a]||candidate->end()!=r.stops[b]||cp_count(candidate->text())!=b-a||(sentence&&sentence->components().size()!=1))continue;
                auto spans=phrase->spans();size_t at=spans.start();int slot=a;bool same=true;
                while(at<spans.end()&&slot<b){at=spans.NextStop(at);if(at!=r.stops[++slot]){same=false;break;}}
                if(!same||slot!=b)continue;
                bool matches=true;for(unsigned j=0;j<local.size();j++)if(mask&(1u<<j))if(cp_slice(candidate->text(),local[j].syllable-a,local[j].syllable-a+1)!=local[j].glyph)matches=false;
                if(!matches||!seen.emplace(a,b,candidate->text()).second)continue;
                RegroupOption option{a,b,candidate->text(),candidate,phrase->weight()};option.homophone=true;result.push_back(std::move(option));
            }
        }
    }
    r.sandhi_cache[target]=result;return result;
}

static bool focus_character(RegroupState& r,RimeSessionId id,int target,bool character_only=false) {
    r.boundary=-1;r.focus_start=r.focus_end=-1;r.options.clear();
    if(!capture(r,id)||target<0||static_cast<size_t>(target+1)>=r.stops.size())return false;
    auto* ctx=context(id);int a=0,b=cp_count(r.original),position=0;
    bool explicit_word=false;
    std::vector<RegroupOption> sandhi;
    if(character_only){a=target;b=target+1;}
    else {
    bool found=false,context_word=false;
    for(auto& seg:ctx->composition()){
        auto candidate=seg.GetSelectedCandidate();if(!candidate)continue;
        for(const auto& word:words(candidate)){
            int end=position+cp_count(word.text);
            if(target>=position&&target<end){a=position;b=end;found=true;context_word=!native_phrase(candidate);break;}
            position=end;
        }
        if(found)break;
    }
    // Explicit teaching and installed vocabulary have recorded origins. Ordinary
    // commits keep their conversion weights but do not acquire a word boundary.
    int explicit_length=0;
    for(const auto& file:{"installed_vocab.tsv","taught_vocab.tsv"}) {
        std::ifstream source(r.user_path+"/"+file);std::string line;
        while(std::getline(source,line)) {
            auto tab=line.find('\t');if(tab==std::string::npos)continue;
            std::string word=line.substr(0,tab);int length=cp_count(word);
            if(length<=explicit_length)continue;
            for(int from=std::max(0,target-length+1);from<=target;from++) {
                int to=from+length;
                if(to<=cp_count(r.original)&&cp_slice(r.original,from,to)==word) {
                    a=from;b=to;explicit_length=length;explicit_word=true;break;
                }
            }
        }
    }
    // All unmarked learned spans are resolved against lexical dictionary
    // windows, including user_phrase and single-component Sentence wrappers.
    // Rime's automatic user dictionary does not retain the teaching origin.
    if(!explicit_word&&(b-a>1||context_word)) {
        int span_start=context_word?std::max(0,target-3):a,span_end=context_word?std::min(cp_count(r.original),target+4):b;
        if(regroup(r,rime_get_api(),id,b))if(auto* probe=context(r.probe)) {
            double best=-1e300,fallback=-1e300;int fallback_a=target,fallback_b=target+1;a=target;b=target+1;
            for(int from=std::max(span_start,target-3);from<=target;from++)
                for(int to=target+1;to<=std::min(span_end,from+4);to++) {
                    if(to-from<=1)continue;
                    const auto probe_input=r.input.substr(0,r.stops[to]);probe->Clear();
                    rime::Composition prefix;prefix.Reset(probe_input);
                    pin_text(prefix,0,r.stops[from],cp_slice(r.original,0,from));
                    prefix.Forward();probe->set_composition(std::move(prefix));probe->set_input(probe_input);
                    if(probe->composition().empty())continue;
                    for(int i=0;i<200;i++) {
                        auto candidate=probe->composition().back().GetCandidateAt(i);if(!candidate)break;
                        auto phrase=native_phrase(candidate);auto sentence=native_sentence(candidate);
                        auto type=genuine_candidate(candidate)->type();
                        if(!phrase||(sentence&&sentence->components().size()!=1)||type=="user_table"||type=="user_phrase"
                            ||candidate->start()!=r.stops[from]||candidate->end()!=r.stops[to]
                            ||cp_count(candidate->text())!=to-from)continue;
                        RegroupOption word{from,to,candidate->text(),candidate,phrase->weight()};
                        double score=sentence_score(r,word);
                        if(candidate->text()==cp_slice(r.original,from,to)){if(score>best){best=score;a=from;b=to;}break;}
                        else if(from==span_start&&to==span_end&&score>fallback){fallback=score;fallback_a=from;fallback_b=to;}
                    }
                }
            if(best==-1e300&&fallback>-1e300){a=fallback_a;b=fallback_b;}
        }
    }
    sandhi=sandhi_words(r,target);
    // Recover a lexical span when the surface tone split it into characters.
    // A matching existing preview is preferred; otherwise use native lookup order.
    if(!explicit_word&&!sandhi.empty()&&b-a==1) {
        auto chosen=std::find_if(sandhi.begin(),sandhi.end(),[&](const auto& x){return x.label==cp_slice(r.original,x.start,x.end);});
        const auto& word=chosen==sandhi.end()?sandhi.front():*chosen;a=word.start;b=word.end;
    }
    } // Word-span resolution is unnecessary for the separate character row.
    r.glyph_stops.clear();
    // Focus is a view operation. Moving the live Rime caret through pinned
    // spans would silently retranslate the untouched suffix.
    for(size_t i=0;i<r.stops.size();++i)r.glyph_stops.push_back(static_cast<int>(i));
    // Build the same-reading menu in its own session. The live sentence stays
    // intact until a choice explicitly pins just this span.
    if(!regroup(r,rime_get_api(),id,b))return false;
    std::vector<RegroupOption> regrouped,repairs,homophones;
    for(const auto& option:r.options) {
        if(option.repaired_input.empty())regrouped.push_back(option);
        else repairs.push_back(option);
    }
    auto* probe=context(r.probe);if(!probe)return false;
    const auto focus_input=r.input.substr(0,r.stops[b]);probe->Clear();
    rime::Composition prefix;prefix.Reset(focus_input);
    pin_text(prefix,0,r.stops[a],cp_slice(r.original,0,a));
    prefix.Forward();probe->set_composition(std::move(prefix));probe->set_input(focus_input);
    if(!probe->composition().empty())for(int i=0;i<200;++i) {
        auto candidate=probe->composition().back().GetCandidateAt(i);if(!candidate)break;
        auto phrase=native_phrase(candidate);
        if(!phrase||candidate->start()!=r.stops[a]||candidate->end()!=r.stops[b]||cp_count(candidate->text())!=b-a)continue;
        if(genuine_candidate(candidate)->type()!="user_table") {
            auto spans=phrase->spans();size_t at=spans.start();int slot=a;
            bool same=at==r.stops[a];
            while(same&&at<spans.end()){size_t end=spans.NextStop(at);if(end<=at||++slot>b||end!=r.stops[slot]){same=false;break;}at=end;}
            if(!same||slot!=b||at!=r.stops[b])continue;
        }
        RegroupOption option{a,b,candidate->text(),candidate,phrase->weight()};option.homophone=true;
        homophones.push_back(std::move(option));
    }
    if(explicit_word) {
        auto chosen=std::find_if(homophones.begin(),homophones.end(),[&](const auto& option){return option.label==cp_slice(r.original,a,b);});
        if(chosen!=homophones.end())std::rotate(homophones.begin(),chosen,chosen+1);
    }
    // Literal words retain their native order before any alternate reading.
    std::set<std::string> labels;for(const auto& x:homophones)labels.insert(x.label);
    for(const auto& x:sandhi)if(x.start==a&&x.end==b&&labels.insert(x.label).second)homophones.push_back(x);
    r.options=std::move(homophones);


    // Append exact-reading single-character choices using the same isolated
    // translator, with candidate offsets anchored to the tapped syllable.
    if(b-a>1) {
        const auto single_input=r.input.substr(0,r.stops[target+1]);probe->Clear();
        rime::Composition single;single.Reset(single_input);
        pin_text(single,0,r.stops[target],cp_slice(r.original,0,target));
        single.Forward();probe->set_composition(std::move(single));probe->set_input(single_input);
        if(!probe->composition().empty())for(int i=0;i<200;++i) {
            auto candidate=probe->composition().back().GetCandidateAt(i);if(!candidate)break;
            auto phrase=native_phrase(candidate);
            if(!phrase||candidate->start()!=r.stops[target]||candidate->end()!=r.stops[target+1]||cp_count(candidate->text())!=1)continue;
            RegroupOption option{target,target+1,candidate->text(),candidate,phrase->weight()};
            option.homophone=true;option.character=true;r.options.push_back(std::move(option));
        }
    }
    // Character focus: whole words, exact-reading characters, bounded repairs.
    std::stable_sort(repairs.begin(),repairs.end(),[](const auto& x,const auto& y){return x.weight>y.weight;});
    int slips=0;
    for(const auto& option:repairs)if(option.start<=target&&option.end>target&&slips++<20)r.options.push_back(option);
    std::set<std::string> seen;
    r.options.erase(std::remove_if(r.options.begin(),r.options.end(),[&](const RegroupOption& option){return !seen.insert(option.label).second;}),r.options.end());
    if(character_only){
        // A character-row choice replaces exactly one character. Word and
        // multi-syllable repair choices come from the separate boundary menu.
        r.options.erase(std::remove_if(r.options.begin(),r.options.end(),[&](const RegroupOption& option){return option.start!=target||option.end!=target+1;}),r.options.end());
        for(auto& option:r.options)option.character=true;
    }
    r.boundary=-1;
    r.focus_start=a;r.focus_end=b;return true;
}

static bool focus_key(RegroupState& r,RimeSessionId id,int at) {
    if(!capture(r,id)||at<=0||static_cast<size_t>(at)>r.input.size())return false;
    auto stops=r.stops;
    auto it=std::lower_bound(stops.begin(),stops.end(),static_cast<size_t>(at));
    int target=std::max(0,static_cast<int>(it-stops.begin())-1);
    if(!focus_character(r,id,target))return false;
    // Use the full scored repair pool, before the character-focus slip cap.
    std::vector<RegroupOption> near,rest=r.options;
    for(const auto& item:r.cache)for(auto option:item.second) {
        if(option.changed_key==at-1&&!option.repaired_input.empty()&&
           default_neighbour(r.input[at-1],option.repaired_input[at-1])) {
            option.caret_neighbour=true;near.push_back(option);
        }
    }
    // Missing-key choices are confined to the incomplete syllable at the caret.
    size_t from=stops[target],to=stops[target+1];std::string code=r.input.substr(from,to-from);
    if(code.size()<4&&!normal_toned(r,code))for(size_t p=0;p<=code.size();++p)for(char key:std::string("1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347")){
        auto corrected=code;corrected.insert(p,1,key);if(!normal_toned(r,corrected))continue;
        std::string input=r.input;input.replace(from,to-from,corrected);auto* probe=context(r.probe);if(!probe)continue;
        probe->Clear();probe->set_input(input.substr(0,to+1));rime::Composition prefix;prefix.Reset(probe->input());
        pin_text(prefix,0,from,cp_slice(r.original,0,target));prefix.Forward();probe->set_composition(std::move(prefix));probe->set_caret_pos(to+1);
        if(probe->composition().empty())continue;
        for(int i=0;i<5;++i){auto c=probe->composition().back().GetCandidateAt(i);if(!c)break;auto phrase=native_phrase(c);
            if(!phrase||c->start()!=from||c->end()!=to+1||cp_count(c->text())!=1)continue;
            RegroupOption option{target,target+1,c->text(),c,phrase->weight()};option.repaired_input=input;option.repaired_code=corrected;
            option.changed_key=from+p;option.caret_neighbour=true;option.neighbour=true;option.weight=sentence_score(r,option);near.push_back(std::move(option));
        }
    }
    std::stable_sort(near.begin(),near.end(),[](const auto& a,const auto& b){return a.weight>b.weight;});
    r.options.clear();std::set<std::string> seen;
    for(const auto& option:near)if(r.options.size()<8&&seen.insert(option.label).second)r.options.push_back(option);
    for(const auto& option:rest)if(option.homophone&&seen.insert(option.label).second)r.options.push_back(option);
    for(const auto& item:r.cache)for(const auto& option:item.second)if(!option.literal&&option.start==r.focus_start&&option.end==r.focus_end&&option.repaired_input.empty()&&!option.homophone&&native_phrase(option.candidate)&&(!native_sentence(option.candidate)||native_sentence(option.candidate)->components().size()==1)&&seen.insert(option.label).second)r.options.push_back(option);
    int slips=0;for(const auto& option:rest)if(!option.repaired_input.empty()&&slips<20&&seen.insert(option.label).second){r.options.push_back(option);++slips;}
    return true;
}

// After a symbol edit, query the entire lexical reading rather than the split
// character menus. Candidate offsets stay anchored to the unchanged sentence.
static bool edited_word_menu(RegroupState& r,RimeSessionId id,int at) {
    if(!focus_key(r,id,at))return false;
    int a=r.focus_start,b=r.focus_end;
    auto* probe=context(r.probe);if(!probe)return false;
    probe->Clear();probe->set_input(r.input.substr(0,r.stops[b]));
    rime::Composition prefix;prefix.Reset(probe->input());
    pin_text(prefix,0,r.stops[a],cp_slice(r.original,0,a));
    prefix.Forward();probe->set_composition(std::move(prefix));probe->set_caret_pos(r.stops[b]);
    std::vector<RegroupOption> lexical;
    if(!probe->composition().empty())for(int i=0;i<200;i++) {
        auto candidate=probe->composition().back().GetCandidateAt(i);if(!candidate)break;
        auto phrase=native_phrase(candidate);if(!phrase||candidate->start()!=r.stops[a]||candidate->end()>r.stops[b])continue;
        auto end=std::lower_bound(r.stops.begin()+a+1,r.stops.begin()+b+1,candidate->end());
        if(end==r.stops.begin()+b+1||*end!=candidate->end())continue;
        int stop=static_cast<int>(end-r.stops.begin());
        if(cp_count(candidate->text())>stop-a)continue;
        auto sentence=native_sentence(candidate);if(sentence&&sentence->components().size()>1)continue;
        RegroupOption option{a,stop,candidate->text(),candidate,phrase->weight()};
        option.homophone=true;option.character=stop-a==1&&b-a>1;lexical.push_back(std::move(option));
    }
    // Word candidates from surface-tone lookup follow as-typed words and
    // precede the character fallbacks without replacing any literal choices.
    std::set<std::string> labels;for(const auto& x:lexical)labels.insert(x.label);
    for(const auto& x:r.options)if(x.homophone&&!x.character&&x.start==a&&x.end==b&&labels.insert(x.label).second)lexical.push_back(x);
    if(!lexical.empty())r.options=std::move(lexical);
    return true;
}
