package org.opensearch.migrations.replay;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;
import org.opensearch.migrations.transform.JsonTransformer;

import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
public class PacketToTransformingHttpHandlerFactory implements PacketConsumerFactory<AggregatedTransformedResponse> {
    private final NioEventLoopGroup eventLoopGroup;
    private final URI serverUri;
    private final JsonTransformer jsonTransformer;
    private final SslContext sslContext;
    private final LoadingCache<String, ChannelFuture> connectionId2ChannelCache;
    private int maxRetriesForNewConnection;

    private final AtomicInteger numConnectionsCreated = new AtomicInteger(0);
    private final AtomicInteger numConnectionsClosed = new AtomicInteger(0);

    public PacketToTransformingHttpHandlerFactory(URI serverUri, JsonTransformer jsonTransformer,
                                                  SslContext sslContext, int numThreads, int maxRetriesForNewConnection) {
        this.jsonTransformer = jsonTransformer;
        this.eventLoopGroup = new NioEventLoopGroup(numThreads);
        this.serverUri = serverUri;
        this.sslContext = sslContext;
        this.maxRetriesForNewConnection = maxRetriesForNewConnection;

        connectionId2ChannelCache = CacheBuilder.newBuilder().build(new CacheLoader<>() {
            @Override
            public ChannelFuture load(final String s) {
                numConnectionsCreated.incrementAndGet();
                log.trace("creating connection future");
                return NettyPacketToHttpConsumer.createClientConnection(eventLoopGroup, sslContext, serverUri, s);
            }
        });
    }

    public int getNumConnectionsCreated() {
        return numConnectionsCreated.get();
    }

    public int getNumConnectionsClosed() {
        return numConnectionsClosed.get();
    }

    @Override
    public IPacketFinalizingConsumer<AggregatedTransformedResponse> create(UniqueRequestKey requestKey) {
        log.trace("creating HttpJsonTransformingConsumer");
        return new HttpJsonTransformingConsumer(jsonTransformer,
                createNettyHandler(requestKey, maxRetriesForNewConnection),
                requestKey.toString());
    }

    public DiagnosticTrackableCompletableFuture<String,Void> stopGroup() {
        var eventLoopFuture =
                new StringTrackableCompletableFuture<Void>(new CompletableFuture<>(),
                        () -> "all channels closed");
        this.eventLoopGroup.submit(()-> {
            try {
                var channelClosedFuturesArray =
                        connectionId2ChannelCache.asMap().values().stream()
                                .map(this::closeChannel)
                                .collect(Collectors.toList());
                StringTrackableCompletableFuture.<Channel>allOf(channelClosedFuturesArray.stream(),
                        () -> "all channels closed")
                        .handle((v,t)-> {
                            if (t == null) {
                                eventLoopFuture.future.complete(v);
                            } else {
                                eventLoopFuture.future.completeExceptionally(t);
                            }
                            return null;
                        },
                        () -> "Waiting for all channels to close: Remaining=" +
                                (channelClosedFuturesArray.stream().filter(c -> !c.isDone()).count()));
            } catch (Exception e) {
                log.atError().setCause(e).setMessage("Caught error while closing cached connections").log();
                log.error("bad", e);
                eventLoopFuture.future.completeExceptionally(e);
            }
        });
        return eventLoopFuture.map(f->f.whenComplete((c,t)->{
            connectionId2ChannelCache.invalidateAll();
            eventLoopGroup.shutdownGracefully();
        }), () -> "Final shutdown for " + this.getClass().getSimpleName());
    }

    public NettyPacketToHttpConsumer createNettyHandler(UniqueRequestKey requestKey, int timesToRetry) {
        log.trace("loading NettyHandler");

        ChannelFuture channelFuture = null;
        try {
            channelFuture = connectionId2ChannelCache.get(requestKey.connectionId);
        } catch (ExecutionException e) {
            if (timesToRetry <= 0) {
                throw new RuntimeException(e);
            }
        }
        if (channelFuture == null || (channelFuture.isDone() && !channelFuture.channel().isActive())) {
            connectionId2ChannelCache.invalidate(requestKey.connectionId);
            if (timesToRetry > 0) {
                return createNettyHandler(requestKey, timesToRetry - 1);
            } else {
                throw new RuntimeException("Channel wasn't active and out of retries.");
            }
        }

        return new NettyPacketToHttpConsumer(channelFuture, requestKey.toString());
    }

    public void closeConnection(String connId) {
        log.atDebug().setMessage(()->"closing connection for " + connId).log();
        var channelsFuture = connectionId2ChannelCache.getIfPresent(connId);
        if (channelsFuture != null) {
            closeChannel(channelsFuture);
            connectionId2ChannelCache.invalidate(connId);
        }
    }

    private DiagnosticTrackableCompletableFuture<String, Channel>
    closeChannel(ChannelFuture channelsFuture) {
        var channelClosedFuture =
                new StringTrackableCompletableFuture<>(new CompletableFuture<Channel>(),
                        ()->"Waiting for closeFuture() on channel");

        numConnectionsClosed.incrementAndGet();
        var cf = channelsFuture.channel().close();
        cf.addListener(closeFuture -> {
            if (closeFuture.isSuccess()) {
                channelClosedFuture.future.complete(channelsFuture.channel());
            } else {
                channelClosedFuture.future.completeExceptionally(closeFuture.cause());
            }
        });
        return channelClosedFuture;
    }
}
