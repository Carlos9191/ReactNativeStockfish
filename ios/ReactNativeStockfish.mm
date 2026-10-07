#import <React/RCTLog.h>
#import <Foundation/Foundation.h>
#include <dlfcn.h>
#import "ReactNativeStockfish.h"

// Process-wide engine ownership.
//
// The native engine (fakein/fakeout queues, the UCI thread) is a singleton, so
// exactly one launch may own it at a time. Every launch gets a fresh id; reader
// timers from an older launch stop emitting as soon as they observe a newer id,
// even if they were parked inside a blocking native read when the launch
// changed. Teardown really sends `quit` and waits (bounded) for the UCI thread
// to exit before a new one starts. This prevents the "two engines + two
// readers" state after a Metro reload or an explicit restart, which made the
// readers race on the bridge's shared copy buffers and drop/duplicate tokens.
static NSUInteger gLaunchId = 0;
static BOOL gRunning = NO;
static NSThread *gStockfishThread = nil;
static dispatch_semaphore_t gThreadExited = nil;
static dispatch_source_t gStdoutTimer = nil;
static dispatch_source_t gStderrTimer = nil;
// The module instance that started the current launch. `invalidate` on a
// stale instance (e.g. the old one being torn down after a reload that has
// already relaunched) must not kill the engine the new instance owns.
static __weak ReactNativeStockfish *gOwner = nil;

static const int64_t kEngineJoinTimeoutNs = 2 * NSEC_PER_SEC;

@implementation ReactNativeStockfish

RCT_EXPORT_MODULE(ReactNativeStockfish);

- (instancetype)init {
    NSLog(@"ReactNativeStockfish module is loading");
    self = [super init];
    return self;
}

+ (BOOL)requiresMainQueueSetup {
    return NO;
}

// Supported events
- (NSArray<NSString *> *)supportedEvents {
    return @[@"stockfish-output", @"stockfish-error"];
}

// Tear down whatever launch currently owns the engine. Safe to call when
// nothing is running. Blocks (bounded) until the old UCI thread has exited so
// the next launch starts from quiescent streams.
+ (void)stopGlobalInstance {
    NSThread *thread = nil;
    dispatch_semaphore_t exited = nil;

    @synchronized([ReactNativeStockfish class]) {
        // Invalidate the launch first so any reader that wakes up exits.
        gLaunchId += 1;
        gRunning = NO;
        gOwner = nil;

        if (gStdoutTimer) {
            dispatch_source_cancel(gStdoutTimer);
            gStdoutTimer = nil;
        }
        if (gStderrTimer) {
            dispatch_source_cancel(gStderrTimer);
            gStderrTimer = nil;
        }

        thread = gStockfishThread;
        exited = gThreadExited;
        gStockfishThread = nil;
        gThreadExited = nil;

        if (thread && !thread.isFinished) {
            // Ask the UCI loop to exit; the engine closes its streams on the way
            // out, which also unblocks any reader parked in a native read.
            reactnativestockfish::stockfish_stdin_write("quit\n");
        } else {
            thread = nil;
        }
    }

    // Wait OUTSIDE the lock: the exiting engine thread signals before it
    // touches shared state, but never block it on a lock we hold.
    if (thread && exited) {
        long timedOut = dispatch_semaphore_wait(
            exited, dispatch_time(DISPATCH_TIME_NOW, kEngineJoinTimeoutNs));
        if (timedOut != 0) {
            RCTLogWarn(@"Old Stockfish thread did not exit within the join timeout.");
        } else {
            RCTLogInfo(@"Stockfish stopped.");
        }
    }
}

// Start Stockfish in a background thread
RCT_EXPORT_METHOD(stockfishLoop) {
    // Any previous launch (this instance or another) is torn down first; this
    // blocks until its UCI thread exits so the fresh engine never shares the
    // stdin queue with a stale one.
    [ReactNativeStockfish stopGlobalInstance];

    NSUInteger launchId;
    dispatch_semaphore_t exited = dispatch_semaphore_create(0);
    @synchronized([ReactNativeStockfish class]) {
        gLaunchId += 1;
        launchId = gLaunchId;
        gRunning = YES;
        gThreadExited = exited;
        gOwner = self;
    }

    // Re-arm the streams now (the previous run closed them on exit) so that
    // commands JS sends right after this call are queued, not dropped.
    reactnativestockfish::stockfish_prepare_launch();

    NSThread *thread = [[NSThread alloc] initWithBlock:^{
        @autoreleasepool {
            RCTLogInfo(@"Stockfish thread started.");
            reactnativestockfish::stockfish_main();
            RCTLogInfo(@"Stockfish thread ended.");
        }
        // Signal BEFORE taking the class lock: stopGlobalInstance may be
        // waiting on this semaphore and must never be blocked by us.
        dispatch_semaphore_signal(exited);
        @synchronized([ReactNativeStockfish class]) {
            // Only clear the running flag if this launch is still the current
            // one; a newer launch may already own it.
            if (gLaunchId == launchId) {
                gRunning = NO;
            }
        }
    }];
    thread.name = @"com.reactnativestockfish.engine";

    @synchronized([ReactNativeStockfish class]) {
        gStockfishThread = thread;
    }
    [thread start];

    [self startTimerForStdoutReadingWithLaunchId:launchId];
    [self startTimerForStderrReadingWithLaunchId:launchId];
}

// Send a command to Stockfish
RCT_EXPORT_METHOD(sendCommandToStockfish:(NSString *)command) {
    BOOL running;
    @synchronized([ReactNativeStockfish class]) {
        running = gRunning;
    }
    if (!running) {
        RCTLogInfo(@"Cannot send command: Stockfish is not running.");
        return;
    }

    const char *nativeCommand = [command UTF8String];
    reactnativestockfish::stockfish_stdin_write(nativeCommand);
}

- (dispatch_source_t)makeReaderTimerNamed:(const char *)queueName
                                 launchId:(NSUInteger)launchId
                                     read:(char *(*)(void))readFn
                                eventName:(NSString *)eventName {
    dispatch_queue_t queue = dispatch_queue_create(queueName, DISPATCH_QUEUE_SERIAL);
    dispatch_source_t timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, queue);
    dispatch_source_set_timer(timer,
                              dispatch_time(DISPATCH_TIME_NOW, 0), // Start immediately
                              0.001 * NSEC_PER_SEC,                // 1 ms interval
                              0);                                  // Tolerance

    // Weak self: the old handlers captured self strongly while self owned the
    // timer, so module instances (and their reader timers) leaked across
    // reloads and kept draining the engine's stdout alongside the new ones.
    __weak ReactNativeStockfish *weakSelf = self;
    dispatch_source_set_event_handler(timer, ^{
        const char *output = readFn();
        // Re-check after the (blocking) read: the launch may have changed while
        // we were parked, in which case this token belongs to nobody.
        BOOL stale;
        @synchronized([ReactNativeStockfish class]) {
            stale = (gLaunchId != launchId);
        }
        if (stale || !output) {
            return;
        }
        // Convert on this thread: `output` points at thread-local storage.
        NSString *body = @(output);
        dispatch_async(dispatch_get_main_queue(), ^{
            ReactNativeStockfish *strongSelf = weakSelf;
            if (strongSelf) {
                [strongSelf sendEventWithName:eventName body:body];
            }
        });
    });
    return timer;
}

- (void)startTimerForStdoutReadingWithLaunchId:(NSUInteger)launchId {
    RCTLogInfo(@"Stdout timer is starting.");
    dispatch_source_t timer = [self makeReaderTimerNamed:"com.reactnativestockfish.stdout"
                                                launchId:launchId
                                                    read:reactnativestockfish::stockfish_stdout_read
                                               eventName:@"stockfish-output"];
    @synchronized([ReactNativeStockfish class]) {
        gStdoutTimer = timer;
    }
    dispatch_resume(timer);
    RCTLogInfo(@"Stdout timer is started.");
}

- (void)startTimerForStderrReadingWithLaunchId:(NSUInteger)launchId {
    RCTLogInfo(@"Stderr timer is starting.");
    dispatch_source_t timer = [self makeReaderTimerNamed:"com.reactnativestockfish.stderr"
                                                launchId:launchId
                                                    read:reactnativestockfish::stockfish_stderr_read
                                               eventName:@"stockfish-error"];
    @synchronized([ReactNativeStockfish class]) {
        gStderrTimer = timer;
    }
    dispatch_resume(timer);
    RCTLogInfo(@"Stderr timer is started.");
}

// Stop the Stockfish thread and timers
RCT_EXPORT_METHOD(stopStockfish) {
    [ReactNativeStockfish stopGlobalInstance];
}

- (void)invalidate {
    BOOL ownsLaunch;
    @synchronized([ReactNativeStockfish class]) {
        ownsLaunch = (gOwner == self);
    }
    if (ownsLaunch) {
        [ReactNativeStockfish stopGlobalInstance];
    }
    [super invalidate];
}

- (void)dealloc {
    NSLog(@"ReactNativeStockfish module is being removed");
}

@end
