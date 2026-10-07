#include "react-native-stockfish.h"
#include "stockfish/fixes/fixes.h"

#include <iostream>
#include <string>

#define BUFFER_SIZE 1024

int stockfish_core(int, char **);

// Per-thread scratch space. Each stdout/stderr read pops one queue entry and
// copies it here; if two host readers ever overlap (e.g. a stale reader that
// has not yet exited after a relaunch), shared buffers would let one reader
// overwrite the other's token before it was handed to the host.
thread_local std::string data;
thread_local std::string err_data;
thread_local char buffer[BUFFER_SIZE + 1];
thread_local char err_buffer[BUFFER_SIZE + 1];

namespace reactnativestockfish
{
	const char *QUITOK = "quit\n";

	void stockfish_prepare_launch()
	{
		// A previous run closes the streams on exit; re-arm them so a relaunch
		// after `quit` has live stdin/stdout instead of silently dropping I/O.
		// Done by the host before the engine thread starts so that commands
		// queued immediately after launch (uci / isready) are never dropped.
		fakein.reopen();
		fakeout.reopen();
		fakeerr.reopen();
	}

	int stockfish_main()
	{
		int argc = 1;
		char *argv[] = {(char *)""};
		int exitCode = stockfish_core(argc, argv);

		fakeout << QUITOK << "\n";

#if _WIN32
		Sleep(100);
#else
		usleep(100);
#endif

		fakeout.close();
		fakein.close();

		return exitCode;
	}

	ssize_t stockfish_stdin_write(const char *data)
	{
		std::string val(data);
		fakein << val << fakeendl;
		return val.length();
	}

	char *stockfish_stdout_read()
	{
		if (getline(fakeout, data))
		{
			size_t len = data.length();
			size_t i;
			for (i = 0; i < len && i < BUFFER_SIZE; i++)
			{
				buffer[i] = data[i];
			}
			buffer[i] = 0;
			return buffer;
		}
		return nullptr;
	}

	char *stockfish_stderr_read()
	{
		if (getline(fakeerr, err_data))
		{
			size_t len = err_data.length();
			size_t i;
			for (i = 0; i < len && i < BUFFER_SIZE; i++)
			{
				err_buffer[i] = err_data[i];
			}
			err_buffer[i] = 0;
			return err_buffer;
		}
		return nullptr;
	}
}
