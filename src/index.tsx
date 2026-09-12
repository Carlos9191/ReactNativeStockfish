import ReactNativeStockfish, {
  _subscribeToStockfishOutput,
  _subscribeToStockfishError,
} from './NativeReactNativeStockfish';

import { useRef, useCallback, useEffect } from 'react';

type UseStockfishOptions = {
  onOutput?: (output: string) => void;
  onError?: (error: string) => void;
};

/**
 * Hook for using Stockfish
 * @param onOutput - an optional function for reading Stockfish output - callback of (string) => void
 * @param onError - an optional function for reading Stockfish error - callback of (string) => void
 * @returns an array with three functions :
 * --------
 * stockfishLoop
 * Starts Stockfish
 * --------
 * stopStockfish
 * Stops Stockfish
 * --------
 * sendCommandToStockfish
 * Sends a command to stockfish, if stockfish is running
 * @param command {string} the command to send (without the newline at the end)
 * --------
 */
export function useStockfish({ onOutput, onError }: UseStockfishOptions) {
  const isStockfishRunning = useRef(false);
  const onOutputRef = useRef(onOutput);
  const onErrorRef = useRef(onError);

  useEffect(() => {
    onOutputRef.current = onOutput;
  }, [onOutput]);

  useEffect(() => {
    onErrorRef.current = onError;
  }, [onError]);

  const stockfishLoop = useCallback(() => {
    if (!isStockfishRunning.current) {
      isStockfishRunning.current = true;
      ReactNativeStockfish.stockfishLoop();
    }
  }, []);

  const stopStockfish = useCallback(() => {
    if (isStockfishRunning.current) {
      ReactNativeStockfish.stopStockfish();
      isStockfishRunning.current = false;
    }
  }, []);

  const sendCommandToStockfish = useCallback((command: string) => {
    if (isStockfishRunning.current) {
      ReactNativeStockfish.sendCommandToStockfish(command);
    } else {
      console.warn('Stockfish is not running. Cannot send command.');
    }
  }, []);

  useEffect(() => {
    const cancelOutputSubscription = _subscribeToStockfishOutput(
      (output: string) => {
        if (isStockfishRunning.current) {
          onOutputRef.current?.(output);
        }
      }
    );

    const cancelErrorSubscription = _subscribeToStockfishError(
      (error: string) => {
        if (isStockfishRunning.current) {
          onErrorRef.current?.(error);
        }
      }
    );

    return () => {
      // Stop native engine before removing listeners to avoid a no-listener race
      // if native emits final output during shutdown.
      stopStockfish();
      cancelOutputSubscription();
      cancelErrorSubscription();
    };
  }, [stopStockfish]);

  return { stockfishLoop, stopStockfish, sendCommandToStockfish };
}

// Export for direct usage
export { _subscribeToStockfishOutput, _subscribeToStockfishError };
export default ReactNativeStockfish;
