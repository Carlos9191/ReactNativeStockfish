#ifndef REACTNATIVESTOCKFISH_H
#define REACTNATIVESTOCKFISH_H

#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#ifdef _WIN64
#define ssize_t __int64
#else
#define ssize_t long
#endif
#else
#include <unistd.h>
#endif

namespace reactnativestockfish
{
  // Re-arms the stdin/stdout/stderr streams for a fresh engine run. Call from
  // the host BEFORE starting the thread that runs stockfish_main(), once the
  // previous engine thread (if any) has exited: a finished run closes the
  // streams, and commands written before re-arming would be dropped.
  void stockfish_prepare_launch();

  // Runs the main stockfish loop
  int stockfish_main();

  // Send command to stockfish
  ssize_t stockfish_stdin_write(const char *data);

  // Reads stockfish output
  char *stockfish_stdout_read();

  // Reads stockfish error
  char *stockfish_stderr_read();
}

#endif /* REACTNATIVESTOCKFISH_H */
