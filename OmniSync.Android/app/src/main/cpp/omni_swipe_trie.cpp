// In-memory English trie adapter for FUTO Swipe's public ITrie ABI.
// The swipe library's GPLv3 license is included alongside this source.
#include <jni.h>
#include "itrie.h"
#include <array>
#include <algorithm>
#include <cstdint>
#include <cmath>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

namespace {
struct Node {
    std::array<uint32_t, 26> children{};
    uint32_t parent = 0;
    int letter = -1;
    uint16_t depth = 0;
    bool word = false;
    float frequency = 0;
};
struct Lexicon {
    // Keep this first: a handle to ITrie can be returned and destroyed safely.
    ITrie api{};
    std::vector<Node> nodes;
    Lexicon();
    void insert(const std::string &word, float frequency) {
        uint32_t at = 0;
        for (char ch : word) {
            if (ch < 'a' || ch > 'z') return;
            int index = ch - 'a';
            uint32_t next = nodes[at].children[index];
            if (!next) {
                next = static_cast<uint32_t>(nodes.size());
                Node n;
                n.parent = at;
                n.letter = index;
                n.depth = static_cast<uint16_t>(nodes[at].depth + 1);
                nodes[at].children[index] = next;
                nodes.push_back(n);
            }
            at = next;
        }
        nodes[at].word = true;
        // FUTO's get_log_frequency expects the natural logarithm of the raw
        // dictionary frequency, matching swipe-library/src/trie.cpp.
        nodes[at].frequency = std::max(nodes[at].frequency, std::log(std::max(0.001f, frequency)));
    }
};
int num_chars(void *) { return 26; }
TrieId root(void *) { return 0; }
int get_char_idx(void *p, TrieId id) {
    const auto &n = static_cast<Lexicon *>(p)->nodes;
    return id < n.size() ? n[id].letter : -1;
}
uint32_t get_child_count(void *p, TrieId id) {
    const auto &n = static_cast<Lexicon *>(p)->nodes;
    if (id >= n.size()) return 0;
    uint32_t count = 0;
    for (auto child : n[id].children) if (child) ++count;
    return count;
}
TrieId get_child(void *p, TrieId id, uint32_t childIdx) {
    const auto &n = static_cast<Lexicon *>(p)->nodes;
    if (id >= n.size()) return 0;
    for (auto child : n[id].children) {
        if (child && childIdx-- == 0) return child;
    }
    return 0;
}
bool is_word(void *p, TrieId id) {
    const auto &n = static_cast<Lexicon *>(p)->nodes;
    return id < n.size() && n[id].word;
}
float get_log_frequency(void *p, TrieId id) {
    const auto &n = static_cast<Lexicon *>(p)->nodes;
    return id < n.size() ? n[id].frequency : 0.0f;
}
uint16_t get_depth(void *p, TrieId id) {
    const auto &n = static_cast<Lexicon *>(p)->nodes;
    return id < n.size() ? n[id].depth : 0;
}
const char *get_word(void *p, TrieId id) {
    const auto &n = static_cast<Lexicon *>(p)->nodes;
    static thread_local std::string result;
    result.clear();
    while (id && id < n.size()) {
        result.push_back(static_cast<char>('a' + n[id].letter));
        id = n[id].parent;
    }
    std::reverse(result.begin(), result.end());
    return result.c_str();
}
void end_search(void *) {}
const ITrieVTable VTABLE = {
    num_chars, root, get_char_idx, get_child_count, get_child,
    is_word, get_log_frequency, get_depth, get_word, end_search
};
Lexicon::Lexicon() {
    api.userdata = this;
    api.vtable = &VTABLE;
    nodes.emplace_back();
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_omni_sync_ui_screen_SwipeNativeTrie_createNative(
    JNIEnv *env, jobject, jstring filename) {
    if (!filename) return 0;
    const char *path = env->GetStringUTFChars(filename, nullptr);
    if (!path) return 0;
    std::ifstream file(path);
    env->ReleaseStringUTFChars(filename, path);
    if (!file.good()) return 0;
    auto lex = std::make_unique<Lexicon>();
    lex->nodes.reserve(160000);
    std::string line;
    unsigned total = 0;
    while (std::getline(file, line)) {
        const auto tab = line.find('\t');
        if (tab == std::string::npos) continue;
        std::string word = line.substr(0, tab);
        if (word.empty() || word.size() > 24) continue;
        float frequency = 1.0f;
        try { frequency = std::stof(line.substr(tab + 1)); } catch (...) { continue; }
        lex->insert(word, frequency);
        ++total;
    }
    if (total < 5000) return 0;
    // "api" is the first Lexicon member and shares its address.
    return static_cast<jlong>(reinterpret_cast<intptr_t>(lex.release()));
}
extern "C" JNIEXPORT void JNICALL
Java_com_omni_sync_ui_screen_SwipeNativeTrie_destroyNative(
    JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Lexicon *>(static_cast<intptr_t>(handle));
}
