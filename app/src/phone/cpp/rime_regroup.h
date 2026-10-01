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

struct RegroupOption {
    int start, end;
    std::string label;
    rime::an<rime::Candidate> candidate;
    double weight;
    std::vector<rime::an<rime::Candidate>> pieces;
};
struct RegroupState {
    RimeSessionId probe = 0;
    std::string input, original, preedit, glyph_preedit;
    std::vector<int> glyph_stops;
    std::vector<size_t> stops;
    std::vector<RegroupOption> options;
    int boundary = -1;
    int focus_start=-1,focus_end=-1;
    std::unique_ptr<rime::Grammar> grammar;
    std::string cache_input,cache_preview;
    std::map<int,std::vector<RegroupOption>> cache;

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
// Rime's own PhraseSyllabifier supplies all raw-key boundaries, including untoned input.
static bool capture(RegroupState& r,RimeSessionId id) {
    auto* ctx=context(id);if(!ctx||ctx->input().empty())return false;
    if(r.input!=ctx->input()) {r.input=ctx->input();r.stops.clear();}
    if(ctx->caret_pos()!=r.input.size())ctx->set_caret_pos(r.input.size());
    r.original=ctx->GetCommitText();r.preedit=ctx->GetPreedit().text;
    if(r.stops.empty()) {
        r.stops.push_back(0);
        for(auto& seg:ctx->composition()) {
            auto cand=seg.GetSelectedCandidate();
            auto phrase=cand?rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(cand)):nullptr;
            if(!phrase){r.stops.clear();return false;}
            auto spans=phrase->spans();size_t at=spans.start();
            while(at<spans.end()) {size_t next=spans.NextStop(at);if(next<=at)break;r.stops.push_back(next);at=next;}
        }
    }
    return r.stops.size()==static_cast<size_t>(cp_count(r.original)+1) && r.stops.back()==r.input.size();
}
static std::string grouping(const rime::an<rime::Candidate>& cand) {
    auto sentence=rime::As<rime::Sentence>(rime::Candidate::GetGenuineCandidate(cand));
    if(!sentence)return cand->text();
    std::string out;for(const auto& word:sentence->components()){if(!out.empty())out+="｜";out+=word.text;}
    return out;
}
static std::vector<rime::DictEntry> words(const rime::an<rime::Candidate>& cand) {
    auto genuine=rime::Candidate::GetGenuineCandidate(cand);
    auto sentence=rime::As<rime::Sentence>(genuine);if(sentence)return sentence->components();
    auto phrase=rime::As<rime::Phrase>(genuine);if(phrase)return {phrase->entry()};
    rime::DictEntry word;word.text=cand->text();word.weight=0;return {word};
}
static double sentence_score(RegroupState& r,const RegroupOption& option) {
    std::vector<rime::DictEntry> sentence;
    auto append=[&](const std::string& text){if(!text.empty()){rime::DictEntry word;word.text=text;word.weight=0;sentence.push_back(word);}};
    append(cp_slice(r.original,0,option.start));
    if(option.pieces.empty()){auto entries=words(option.candidate);sentence.insert(sentence.end(),entries.begin(),entries.end());}
    else for(const auto& piece:option.pieces){auto entries=words(piece);sentence.insert(sentence.end(),entries.begin(),entries.end());}
    append(cp_slice(r.original,option.end,cp_count(r.original)));
    double score=0;
    for(size_t i=0;i<sentence.size();++i){
        std::string preceding;if(i>1)preceding+=sentence[i-2].text;if(i>0)preceding+=sentence[i-1].text;
        score+=rime::Grammar::Evaluate(preceding,sentence[i].text,sentence[i].weight,i+1==sentence.size(),r.grammar.get());
    }
    return score;
}

// Future slip repair can append candidates at this seam after the same span/alignment checks.
// This version only enumerates exact native readings; it never invents alternate key sequences.
static bool regroup(RegroupState& r,const RimeApi* api,RimeSessionId id,int boundary) {
    bool alreadyFocused=r.boundary>=0;
    r.options.clear();r.boundary=-1;r.focus_start=r.focus_end=-1;
    auto* real=context(id);
    if(!(alreadyFocused&&real&&real->input()==r.input&&!r.stops.empty())&&!capture(r,id))return false;
    int count=static_cast<int>(r.stops.size())-1;
    if(boundary<0||boundary>count)return false;
    if(!r.probe) {r.probe=api->create_session();api->select_schema(r.probe,"bopomofo_express");}
    auto* probe=context(r.probe);if(!probe)return false;
    if(!r.grammar){auto* factory=rime::Grammar::Require("grammar");if(factory)r.grammar.reset(factory->Create(rime::Service::instance().GetSession(id)->schema()->config()));}
    if(!r.grammar)return false; // Ranking without the packaged grammar is not a substitute.

    if(r.cache_input!=r.input||r.cache_preview!=r.original){r.cache.clear();r.cache_input=r.input;r.cache_preview=r.original;}
    auto cached=r.cache.find(boundary);
    if(cached!=r.cache.end())r.options=cached->second;
    else {
    // Query native menus for bounded windows ending/starting/spanning the caret.
    for(int a=std::max(0,boundary-2);a<=std::min(boundary,count-1);++a) {
        for(int b=std::max(a+1,boundary);b<=std::min(count,boundary+2);++b) {
            if(b-a>3)continue;
            probe->Clear();probe->set_input(r.input.substr(0,r.stops[b]));
            rime::Composition prefix;prefix.Reset(probe->input());
            pin_text(prefix,0,r.stops[a],cp_slice(r.original,0,a));
            prefix.Forward();probe->set_composition(std::move(prefix));probe->set_caret_pos(r.stops[b]);
            auto& comp=probe->composition();if(comp.empty())continue;
            auto& seg=comp.back();
            for(size_t i=0;i<12;++i) {
                auto cand=seg.GetCandidateAt(i);if(!cand)break;
                if(cand->start()!=r.stops[a]||cand->end()!=r.stops[b]||cp_count(cand->text())!=b-a)continue;
                auto phrase=rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(cand));
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
                probe->Clear();probe->set_input(r.input.substr(0,r.stops[split]));
                rime::Composition leftPrefix;leftPrefix.Reset(probe->input());
                pin_text(leftPrefix,0,r.stops[a],cp_slice(r.original,0,a));
                leftPrefix.Forward();probe->set_composition(std::move(leftPrefix));probe->set_caret_pos(r.stops[split]);
                if(probe->composition().empty())continue;
                std::vector<rime::an<rime::Candidate>> lefts;
                for(int i=0;i<4;i++){auto cand=probe->composition().back().GetCandidateAt(i);if(!cand)break;
                    if(cand->start()==r.stops[a]&&cand->end()==r.stops[split]&&cp_count(cand->text())==split-a)lefts.push_back(cand);
                    if(lefts.size()==1)break;
                }
                for(const auto& left:lefts){
                    auto lp=rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(left));if(!lp)continue;
                    probe->Clear();probe->set_input(r.input.substr(0,r.stops[b]));rime::Composition selected;selected.Reset(probe->input());
                    pin_text(selected,0,r.stops[a],cp_slice(r.original,0,a));pin(selected,r.stops[a],r.stops[split],left);
                    selected.Forward();probe->set_composition(std::move(selected));probe->set_caret_pos(r.stops[b]);
                    if(probe->composition().empty())continue;
                    for(int i=0;i<4;i++){auto right=probe->composition().back().GetCandidateAt(i);if(!right)break;
                        if(right->start()!=r.stops[split]||right->end()!=r.stops[b]||cp_count(right->text())!=b-split)continue;
                        auto rp=rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(right));if(!rp)continue;
                        std::string label=grouping(left)+"｜"+grouping(right);
                        bool duplicate=false;for(const auto& old:r.options)if(old.start==a&&old.end==b&&old.label==label){duplicate=true;break;}
                        if(!duplicate)r.options.push_back({a,b,label,nullptr,lp->weight()+rp->weight(),{left,right}});
                        break;
                    }
                }
            }
        }
    }
    for(auto& option:r.options)option.weight=sentence_score(r,option);
    // Nearest window first. Within each window Rime's Octagram sentence weight
    // (including preceding text) is the score, not a hand-authored word frequency.
    std::stable_sort(r.options.begin(),r.options.end(),[boundary](const auto& x,const auto& y){
        int dx=std::max(boundary-x.start,x.end-boundary),dy=std::max(boundary-y.start,y.end-boundary);
        if(dx!=dy)return dx<dy;
        if(x.end-x.start!=y.end-y.start)return x.end-x.start>y.end-y.start;
        return x.weight>y.weight;
    });
    r.cache[boundary]=r.options;
    }
    // Boundary edits address the decoded preview directly. Mapping every
    // native caret back through the translator here re-decoded the sentence
    // once per syllable; character-focus still uses its legacy preedit map.
    r.boundary=boundary;if(api->get_caret_pos(id)!=r.stops[boundary])api->set_caret_pos(id,r.stops[boundary]);return true;
}
static bool select_regroup(RegroupState& r,RimeSessionId id,int index) {
    auto* ctx=context(id);
    if(!ctx||index<0||index>=static_cast<int>(r.options.size())||ctx->input()!=r.input)return false;
    const auto& option=r.options[index];
    rime::Composition fixed;fixed.Reset(r.input);
    pin_text(fixed,0,r.stops[option.start],cp_slice(r.original,0,option.start));
    if(option.pieces.empty())pin(fixed,r.stops[option.start],r.stops[option.end],option.candidate);
    else for(const auto& piece:option.pieces)pin(fixed,piece->start(),piece->end(),piece);
    pin_text(fixed,r.stops[option.end],r.input.size(),cp_slice(r.original,option.end,cp_count(r.original)));
    ctx->set_composition(std::move(fixed));ctx->set_caret_pos(r.input.size());
    r.options.clear();r.boundary=-1;return true;
}

static bool focus_character(RegroupState& r,RimeSessionId id,int target) {
    r.boundary=-1;r.focus_start=r.focus_end=-1;r.options.clear();
    if(!capture(r,id)||target<0||static_cast<size_t>(target+1)>=r.stops.size())return false;
    auto* ctx=context(id);int a=0,b=cp_count(r.original),position=0;
    bool found=false;
    for(auto& seg:ctx->composition()){
        auto candidate=seg.GetSelectedCandidate();if(!candidate)continue;
        for(const auto& word:words(candidate)){
            int end=position+cp_count(word.text);
            if(target>=position&&target<end){a=position;b=end;found=true;break;}
            position=end;
        }
        if(found)break;
    }
    r.glyph_stops.clear();
    for(size_t stop:r.stops){ctx->set_caret_pos(stop);auto p=ctx->GetPreedit();r.glyph_stops.push_back(cp_count(p.text.substr(0,p.caret_pos)));}
    rime::Composition prefix;prefix.Reset(r.input);
    pin_text(prefix,0,r.stops[a],cp_slice(r.original,0,a));
    prefix.Forward();ctx->set_composition(std::move(prefix));ctx->set_caret_pos(r.stops[b]);
    r.focus_start=a;r.focus_end=b;return true;
}
