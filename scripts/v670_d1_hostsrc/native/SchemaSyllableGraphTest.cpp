#include <rime/dict/prism.h>
#include <rime/algo/syllabifier.h>
#include <chrono>
#include <iostream>
#include <stdexcept>
// Public graph API: identical prism contents under another filename must behave identically.
double penalty(rime::Prism& p, const std::string& code, bool enabled=true) {
  rime::Syllabifier s{"'",true,false};
  s.PreferCompleteSyllables(enabled);
  rime::SyllableGraph g;s.BuildSyllableGraph(code,p,&g);
  double value=0;int marked=0;for(auto& a:g.edges)for(auto& b:a.second)for(auto& c:b.second){value+=c.second.credibility;marked+=c.second.complete_syllable_conflict;}
  if(!enabled&&marked)throw std::runtime_error("G6 disabled graph must not propagate conflicts");
  if(enabled&&code=="ej;"&&!marked)throw std::runtime_error("G4 enabled graph silently lost complete preference");
  return value;
}
int main(int argc,char**argv){
  rime::Prism original{rime::path(argv[1])},renamed{rime::path(argv[2])};
  if(!original.Load()||!renamed.Load())return 2;
  auto a=penalty(original,"ej;"),b=penalty(renamed,"ej;");
  std::cout<<"original="<<a<<" renamed="<<b<<std::endl;
  auto off=penalty(original,"ej;",false);
  if(a>=off)throw std::runtime_error("G3 disabling must change graph effect");
  std::cout<<"disabled="<<off<<" conflicts=0"<<std::endl;
  std::string typical="a94tj 2k7u qj w96ej;2u04c94t/4u4su",longer;
  for(int i=0;i<4;++i)longer+=typical;
  for(const auto& input:{typical,longer})for(int sample=0;sample<5;++sample){
    auto start=std::chrono::steady_clock::now();
    for(int i=0;i<2000;++i)penalty(original,input);
    std::cout<<"TIMING graph keys="<<input.size()<<" sample="<<sample<<" iterations=2000 ms="<<std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count()<<std::endl;
  }
  if(a!=b)throw std::runtime_error("G4 filename silently changes complete syllable graph");
}
