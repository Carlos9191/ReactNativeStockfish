// Taken from https://github.com/jusax23/flutter_stockfish_plugin

#ifndef _STREAM_FIX_H_
#define _STREAM_FIX_H_
#include <atomic>
#include <condition_variable>
#include <iostream>
#include <mutex>
#include <queue>
#include <sstream>
#include <string>
#include <utility>

template <typename T>
inline std::string stringify(const T& input) {
    std::ostringstream output;
    output << input;
    return output.str();
}

class FakeStream {
   public:
    template <typename T>
    FakeStream& operator<<(const T& val) {
        if (closed) return *this;
        // Stringify BEFORE taking the queue lock. For SyncCout values
        // (IO_LOCK / IO_UNLOCK) stringify acquires Stockfish's global output
        // mutex; doing that while holding mutex_guard inverts the lock order
        // against a thread that holds the output mutex mid-line and is
        // pushing its next operand, which deadlocks the engine's output.
        std::string item = stringify(val);
        std::lock_guard<std::mutex> lock(mutex_guard);
        if (closed) return *this;
        string_queue.push(std::move(item));
        mutex_signal.notify_one();
        return *this;
    };
    template <typename T>
    FakeStream& operator>>(T& val) {
        if (closed) return *this;
        std::unique_lock<std::mutex> lock(mutex_guard);
        mutex_signal.wait(lock,
                          [this] { return !string_queue.empty() || closed; });
        if (closed) return *this;
        val = string_queue.front();
        string_queue.pop();
        return *this;
    };

    bool try_get_line(std::string& val);

    void close();
    // Re-arm a stream that was closed by a previous engine run so the next
    // stockfish_main() can use it. Discards any leftover entries.
    void reopen();
    bool is_closed();

    std::streambuf* rdbuf();
    std::streambuf* rdbuf(std::streambuf* __sb);

   private:
    std::atomic<bool> closed{false};
    std::queue<std::string> string_queue;
    //std::string line;
    std::mutex mutex_guard;
    std::condition_variable mutex_signal;
};

namespace std {
bool getline(FakeStream& is, std::string& str);
}  // namespace std

extern FakeStream fakeout;
extern FakeStream fakein;
extern FakeStream fakeerr;
extern std::string fakeendl;

#endif