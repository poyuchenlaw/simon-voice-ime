#include <rime/algo/syllabifier.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/prism.h>
#include <rime/dict/table.h>
#include <cmath>
#include <iostream>
#include <map>
#include <stdexcept>
#include <type_traits>
using Scores = std::map<std::string, double>;
static_assert(std::is_same<rime::SpellingPropertiesList::value_type,
                           const rime::EdgeProperties*>::value,
              "graph index must contain actual edge pointers, no downcast");
Scores lookup(rime::Dictionary& dictionary, const rime::SyllableGraph& graph) {
  Scores scores;
  auto entries = dictionary.Lookup(graph, 0);
  if (!entries) throw std::runtime_error("fixture has no dictionary candidates");
  for (auto& group : *entries)
    for (auto& iter = group.second; !iter.exhausted(); iter.Next()) {
      auto entry = iter.Peek();
      std::string key = std::to_string(group.first) + ":" + entry->text;
      for (auto id : entry->code) key += ":" + std::to_string(id);
      scores.emplace(key, entry->weight);
    }
  return scores;
}
int main(int argc, char** argv) {
  auto prism = rime::New<rime::Prism>(rime::path(argv[1]));
  auto table = rime::New<rime::Table>(rime::path(argv[2]));
  rime::Dictionary dictionary{"terra_pinyin", {}, {table}, prism};
  if (!dictionary.Load()) return 2;
  for (bool enabled : {false, true}) {
    rime::Syllabifier syllabifier{"'", true, false};
    syllabifier.PreferCompleteSyllables(enabled);
    rime::SyllableGraph graph;
    syllabifier.BuildSyllableGraph("ej;", *prism, &graph); // ㄍㄨㄤ
    int full_edges = 0;
    for (auto& start : graph.edges)
      for (auto& end : start.second)
        for (auto& spelling : end.second) {
          auto& props = spelling.second;
          std::cout << "EDGE enabled=" << enabled << " span=" << start.first << ":" << end.first
                    << " id=" << spelling.first << " type=" << props.type
                    << " is_correction=" << props.is_correction
                    << " conflict=" << props.complete_syllable_conflict
                    << " credibility=" << props.credibility << std::endl;
          if (start.first == 0 && end.first == 3) {
            ++full_edges;
            if (props.complete_syllable_conflict)
              throw std::runtime_error("complete untoned ㄍㄨㄤ penalized itself");
          }
        }
    if (!full_edges) throw std::runtime_error("fixture lacks complete ㄍㄨㄤ");
    auto scores = lookup(dictionary, graph);
    for (auto& candidate : scores)
      if (candidate.first.find("光:") != std::string::npos)
        std::cout << "CANDIDATE enabled=" << enabled << " " << candidate.first
                  << " log_weight=" << candidate.second << std::endl;
  }
  // Fail if dictionary still interprets D1 conflict flags while disabled.
  // The immutable disabled graph supplies the counterfactual: flags alone
  // may not change its dictionary output, including long-entry tail ranking.
  for (const std::string input : {"a94tj 2k7u qj w96ej;2u04c94t/4u4su",
                                  "5j/ cj86aup6eji6"}) {
    rime::Syllabifier syllabifier{"'", true, false};
    syllabifier.PreferCompleteSyllables(false);
    rime::SyllableGraph graph;
    syllabifier.BuildSyllableGraph(input, *prism, &graph);
    auto original = lookup(dictionary, graph);
    for (auto& start : graph.edges)
      for (auto& end : start.second)
        for (auto& spelling : end.second)
          spelling.second.complete_syllable_conflict = true;
    auto injected = lookup(dictionary, graph);
    int changed = 0;
    for (auto& candidate : original) {
      auto found = injected.find(candidate.first);
      if (found == injected.end() || std::abs(candidate.second - found->second) > 1e-9) {
        ++changed;
        std::cout << "DISABLED_CHANGED " << candidate.first << " original=" << candidate.second
                  << " injected=" << (found == injected.end() ? 0 : found->second) << std::endl;
      }
    }
    std::cout << "DISABLED input=" << input << " candidates=" << original.size()
              << " changed=" << changed << std::endl;
    if (changed || original.size() != injected.size())
      throw std::runtime_error("disabled dictionary must ignore D1 conflict flags");
  }
  // Enabling must still charge the actual dictionary tail, not silently
  // disable all D1 behavior to make the negative test pass.
  rime::Syllabifier enabled{"'", true, false};
  enabled.PreferCompleteSyllables(true);
  rime::SyllableGraph enabled_graph;
  enabled.BuildSyllableGraph("5j/ cj86aup6eji6", *prism, &enabled_graph);
  auto unmarked = lookup(dictionary, enabled_graph);
  for (auto& start : enabled_graph.edges)
    for (auto& end : start.second)
      for (auto& spelling : end.second)
        spelling.second.complete_syllable_conflict = true;
  auto marked = lookup(dictionary, enabled_graph);
  bool checked_tail = false;
  for (auto& candidate : unmarked) {
    if (candidate.first.find(":中華民國:") == std::string::npos) continue;
    auto found = marked.find(candidate.first);
    if (found == marked.end() || std::abs(candidate.second - found->second - 100.0) > 1e-9)
      throw std::runtime_error("enabled dictionary tail must retain 100-point conflict cost");
    checked_tail = true;
    std::cout << "ENABLED tail=中華民國 delta=" << candidate.second - found->second << std::endl;
  }
  if (!checked_tail) throw std::runtime_error("missing four-syllable tail fixture");
  std::cout << "PASS complete-edge, actual-edge pointer type, disabled dictionary guard, enabled tail cost" << std::endl;
}
