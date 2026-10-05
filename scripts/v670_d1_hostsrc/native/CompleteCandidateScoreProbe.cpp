#include <rime_api.h>
#include <rime/service.h>
#include <rime/context.h>
#include <rime/candidate.h>
#include <iostream>
#include <stdexcept>
int main(int argc, char** argv) {
  RimeTraits traits{}; RIME_STRUCT_INIT(RimeTraits, traits);
  traits.shared_data_dir = argv[1]; traits.user_data_dir = argv[2];
  traits.app_name = "rime.simon.voiceime"; traits.min_log_level = 2; traits.log_dir = "";
  auto api = rime_get_api(); api->setup(&traits); api->initialize(&traits);
  auto id = api->create_session();
  if (!id || !api->select_schema(id, "bopomofo_express")) return 2;
  for (char key : std::string("ej;")) api->process_key(id, key, 0);
  auto session = rime::Service::instance().GetSession(id);
  auto& composition = session->context()->composition();
  if (composition.empty()) throw std::runtime_error("missing composition");
  bool found = false;
  for (int i = 0; i < 40; ++i) {
    auto candidate = composition.back().GetCandidateAt(i);
    if (!candidate) break;
    std::cout << "TRANSLATED index=" << i << " text=" << candidate->text()
              << " type=" << candidate->type() << " quality=" << candidate->quality()
              << " span=" << candidate->start() << ":" << candidate->end() << std::endl;
    if (candidate->text() == "光" && candidate->end() == 3) found = true;
  }
  api->destroy_session(id); api->finalize();
  if (!found) throw std::runtime_error("complete 光 missing from actual translator");
}
