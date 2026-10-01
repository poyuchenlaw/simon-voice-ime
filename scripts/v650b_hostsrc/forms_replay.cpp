#include <rime_api.h>
#include <iostream>
#include <string>
#include <vector>
int main(int argc,char** argv){
  if(argc!=3)return 2;
  auto* api=rime_get_api();RIME_STRUCT(RimeTraits,t);
  t.shared_data_dir=argv[1];t.user_data_dir=argv[2];t.app_name="rime.v650b.forms";t.min_log_level=2;t.log_dir="";
  api->setup(&t);api->initialize(&t);
  auto s=api->create_session();if(!s||!api->select_schema(s,"bopomofo_express"))return 3;
  std::string id,keys;
  while(std::cin>>id>>keys){
    std::cout<<id;
    for(bool enabled:{false,true}){
      api->clear_composition(s);api->set_option(s,"zh_hant_tw",enabled);
      for(char c:keys)api->process_key(s,c,0);
      api->select_candidate(s,0);api->commit_composition(s);
      RIME_STRUCT(RimeCommit,commit);std::string text;
      if(api->get_commit(s,&commit)){text=commit.text?commit.text:"";api->free_commit(&commit);}
      std::cout<<'\t'<<text;
    }
    std::cout<<'\n';
  }
  api->destroy_session(s);api->finalize();
}
