/*
 * Copyright 2015-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.central;

import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import io.grpc.Attributes;
import io.grpc.ManagedChannel;
import io.grpc.NameResolver;
import io.grpc.ResolvedServerInfo;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.util.RoundRobinLoadBalancerFactory;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.util.RateLimitedLogger;
import org.glowroot.agent.util.ThreadFactories;
import org.glowroot.common.util.OnlyUsedByTests;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class CentralConnection {

    private static final Logger logger = LoggerFactory.getLogger(CentralConnection.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    // back pressure on connection to the central collector
    private static final int PENDING_LIMIT = 100;

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private final ThreadLocal<Boolean> suppressLogCollector = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    private final EventLoopGroup eventLoopGroup;
    private final ExecutorService channelExecutor;
    private final ManagedChannel channel;

    private final ScheduledExecutorService retryExecutor;

    private final AtomicBoolean inConnectionFailure;

    private final Random random = new Random();

    private final RateLimitedLogger backPressureLogger =
            new RateLimitedLogger(CentralConnection.class);

    // count does not include init call
    @GuardedBy("backPressureLogger")
    private int pendingRequestCount;

    private final RateLimitedLogger connectionErrorLogger =
            new RateLimitedLogger(CentralConnection.class);

    private volatile boolean initCallSucceeded;
    private volatile boolean closed;

    CentralConnection(List<SocketAddress> collectorAddresses, AtomicBoolean inConnectionFailure) {
        eventLoopGroup = EventLoopGroups.create("Glowroot-GRPC-Worker-ELG");
        channelExecutor =
                Executors.newSingleThreadExecutor(ThreadFactories.create("Glowroot-GRPC-Executor"));
        channel = NettyChannelBuilder
                .forTarget("dummy")
                .nameResolverFactory(new SimpleNameResolverFactory(collectorAddresses))
                .loadBalancerFactory(RoundRobinLoadBalancerFactory.getInstance())
                .eventLoopGroup(eventLoopGroup)
                .executor(channelExecutor)
                .negotiationType(NegotiationType.PLAINTEXT)
                .build();
        retryExecutor = Executors.newSingleThreadScheduledExecutor(
                ThreadFactories.create("Glowroot-Collector-Retry"));
        this.inConnectionFailure = inConnectionFailure;
    }

    boolean suppressLogCollector() {
        return suppressLogCollector.get();
    }

    ManagedChannel getChannel() {
        return channel;
    }

    <T extends /*@NonNull*/ Object> void callOnce(GrpcCall<T> call) {
        callWithAFewRetries(0, -1, call);
    }

    // important that these calls are idempotent
    <T extends /*@NonNull*/ Object> void callWithAFewRetries(GrpcCall<T> call) {
        callWithAFewRetries(0, call);
    }

    // important that these calls are idempotent
    <T extends /*@NonNull*/ Object> void callWithAFewRetries(int initialDelayMillis,
            GrpcCall<T> call) {
        callWithAFewRetries(initialDelayMillis, 60, call);
    }

    // important that these calls are idempotent
    private <T extends /*@NonNull*/ Object> void callWithAFewRetries(int initialDelayMillis,
            final int maxTotalInSeconds, final GrpcCall<T> call) {
        if (closed) {
            return;
        }
        if (inConnectionFailure.get()) {
            return;
        }
        synchronized (backPressureLogger) {
            if (pendingRequestCount >= PENDING_LIMIT) {
                backPressureLogger.warn("not sending data to the central collector because of an"
                        + " excessive backlog of {} requests in progress", PENDING_LIMIT);
                return;
            }
            pendingRequestCount++;
        }
        // TODO revisit retry/backoff after next grpc version

        // 60 seconds should be enough time to restart central collector instance without losing
        // data (though better to use central collector cluster)
        //
        // this cannot retry over too long a period since it retains memory of rpc message for
        // that duration
        if (initialDelayMillis > 0) {
            retryExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        call.call(new RetryingStreamObserver<T>(call, maxTotalInSeconds,
                                maxTotalInSeconds, false));
                    } catch (Throwable t) {
                        logger.error(t.getMessage(), t);
                    }
                }
            }, initialDelayMillis, MILLISECONDS);
        } else {
            call.call(new RetryingStreamObserver<T>(call, maxTotalInSeconds, maxTotalInSeconds,
                    false));
        }
    }

    // important that these calls are idempotent
    <T extends /*@NonNull*/ Object> void callInit(GrpcCall<T> call) {
        if (closed) {
            return;
        }
        // important here not to check inConnectionFailure, since need this to succeed if/when
        // connection is re-established
        call.call(new RetryingStreamObserver<T>(call, 15, -1, true));
    }

    void suppressLogCollector(Runnable runnable) {
        boolean priorValue = suppressLogCollector.get();
        suppressLogCollector.set(true);
        try {
            runnable.run();
        } finally {
            suppressLogCollector.set(priorValue);
        }
    }

    @OnlyUsedByTests
    void close() {
        closed = true;
        retryExecutor.shutdown();
        channel.shutdown();
    }

    @OnlyUsedByTests
    void awaitClose() throws InterruptedException {
        if (!retryExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
        if (!channel.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate channel");
        }
        channelExecutor.shutdown();
        if (!channelExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
        if (!eventLoopGroup.shutdownGracefully(0, 0, SECONDS).await(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate event loop group");
        }
    }

    static abstract class GrpcCall<T extends /*@NonNull*/ Object> {
        abstract void call(StreamObserver<T> responseObserver);
        void doWithResponse(@SuppressWarnings("unused") T response) {}
    }

    private class RetryingStreamObserver<T extends /*@NonNull*/ Object>
            implements StreamObserver<T> {

        private final GrpcCall<T> grpcCall;
        private final int maxSingleDelayInSeconds;
        private final int maxTotalInSeconds;
        private final boolean init;
        private final Stopwatch stopwatch = Stopwatch.createStarted();

        private volatile boolean initErrorLogged;
        private volatile long nextDelayInSeconds = 4;

        private RetryingStreamObserver(GrpcCall<T> grpcCall, int maxSingleDelayInSeconds,
                int maxTotalInSeconds, boolean init) {
            this.grpcCall = grpcCall;
            this.maxSingleDelayInSeconds = maxSingleDelayInSeconds;
            this.maxTotalInSeconds = maxTotalInSeconds;
            this.init = init;
        }

        @Override
        public void onNext(T value) {
            grpcCall.doWithResponse(value);
        }

        @Override
        public void onError(final Throwable t) {
            if (closed) {
                decrementPendingRequestCount();
                return;
            }
            if (init && !initErrorLogged) {
                startupLogger.warn("unable to establish connection with the central collector"
                        + " (will keep trying): {}", t.getMessage());
                logger.debug(t.getMessage(), t);
                initErrorLogged = true;
            }
            if (inConnectionFailure.get()) {
                decrementPendingRequestCount();
                return;
            }
            suppressLogCollector(new Runnable() {
                @Override
                public void run() {
                    logger.debug(t.getMessage(), t);
                }
            });
            if (!init && stopwatch.elapsed(SECONDS) > maxTotalInSeconds) {
                if (initCallSucceeded) {
                    connectionErrorLogger.warn("error sending data to the central collector: {}",
                            t.getMessage(), t);
                }
                decrementPendingRequestCount();
                return;
            }

            // retry delay doubles on average each time, randomized +/- 50%
            double randomizedDoubling = 0.5 + random.nextDouble();
            long currDelay = (long) (nextDelayInSeconds * randomizedDoubling);
            nextDelayInSeconds = Math.min(nextDelayInSeconds * 2, maxSingleDelayInSeconds);

            // TODO revisit retry/backoff after next grpc version
            retryExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        grpcCall.call(RetryingStreamObserver.this);
                    } catch (final Throwable t) {
                        // intentionally capturing InterruptedException here as well to ensure
                        // reconnect is attempted no matter what
                        suppressLogCollector(new Runnable() {
                            @Override
                            public void run() {
                                logger.error(t.getMessage(), t);
                            }
                        });
                    }
                }
            }, currDelay, SECONDS);
        }

        @Override
        public void onCompleted() {
            if (init) {
                initCallSucceeded = true;
            }
            decrementPendingRequestCount();
        }

        private void decrementPendingRequestCount() {
            if (!init) {
                synchronized (backPressureLogger) {
                    pendingRequestCount--;
                }
            }
        }
    }

    private static class SimpleNameResolverFactory extends NameResolver.Factory {

        private final List<SocketAddress> collectorAddresses;

        private SimpleNameResolverFactory(List<SocketAddress> collectorAddresses) {
            this.collectorAddresses = collectorAddresses;
        }

        @Override
        public NameResolver newNameResolver(URI targetUri, Attributes params) {
            return new SimpleNameResolver(collectorAddresses);
        }

        @Override
        public String getDefaultScheme() {
            return "dummy-scheme";
        }
    }

    private static class SimpleNameResolver extends NameResolver {

        private final List<SocketAddress> collectorAddresses;

        private SimpleNameResolver(List<SocketAddress> collectorAddresses) {
            this.collectorAddresses = collectorAddresses;
        }

        @Override
        public String getServiceAuthority() {
            return "dummy-service-authority";
        }

        @Override
        public void start(Listener listener) {
            List<ResolvedServerInfo> resolvedServerInfos = Lists.newArrayList();
            for (SocketAddress collectorAddress : collectorAddresses) {
                resolvedServerInfos.add(new ResolvedServerInfo(collectorAddress, Attributes.EMPTY));
            }
            Collections.shuffle(resolvedServerInfos);
            listener.onUpdate(Collections.singletonList(resolvedServerInfos), Attributes.EMPTY);
        }

        @Override
        public void shutdown() {}
    }
}
