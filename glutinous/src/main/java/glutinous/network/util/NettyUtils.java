package glutinous.network.util;

import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.internal.PlatformDependent;

import java.util.concurrent.ThreadFactory;

public class NettyUtils {
    public static ThreadFactory createThreadFactory(String threadPoolPrefix) {
        return new DefaultThreadFactory(threadPoolPrefix, true);
    }

    public static Class<? extends ServerChannel> getServerChannelClass(IOMode mode) {
        switch (mode) {
            case NIO:
                return NioServerSocketChannel.class;
            case EPOLL:
                return EpollServerSocketChannel.class;
            default:
                throw new IllegalArgumentException("Unknown io mode: " + mode);
        }
    }

    public static Class<? extends Channel> getClientChannelClass(IOMode mode) {
        switch (mode) {
            case NIO:
                return NioSocketChannel.class;
            case EPOLL:
                return EpollSocketChannel.class;
            default:
                throw new IllegalArgumentException("Unknown io mode: " + mode);
        }
    }

    public static EventLoopGroup createEventLoop(IOMode mode, int numThreads, String threadPrefix) {
        ThreadFactory threadFactory = createThreadFactory(threadPrefix);

        switch (mode) {
            case NIO:
                return new NioEventLoopGroup(numThreads, threadFactory);
            case EPOLL:
                return new EpollEventLoopGroup(numThreads, threadFactory);
            default:
                throw new IllegalArgumentException("Unknown io mode: " + mode);
        }
    }

    public static PooledByteBufAllocator createPooledByteBufAllocator(
            boolean allowDirectBufs,
            boolean allowCache,
            int numCores) {
        if (numCores == 0) {
            numCores = Runtime.getRuntime().availableProcessors();
        }
        return new PooledByteBufAllocator(
                allowDirectBufs && PlatformDependent.directBufferPreferred(),
                Math.min(PooledByteBufAllocator.defaultNumHeapArena(), numCores),
                Math.min(PooledByteBufAllocator.defaultNumDirectArena(), allowDirectBufs ? numCores : 0),
                PooledByteBufAllocator.defaultPageSize(),
                PooledByteBufAllocator.defaultMaxOrder(),
                allowCache ? PooledByteBufAllocator.defaultTinyCacheSize() : 0,
                allowCache ? PooledByteBufAllocator.defaultSmallCacheSize() : 0,
                allowCache ? PooledByteBufAllocator.defaultNormalCacheSize() : 0,
                allowCache ? PooledByteBufAllocator.defaultUseCacheForAllThreads() : false
        );
    }

    public static TransportFrameDecoder createFrameDecoder() {
        return new TransportFrameDecoder();
    }
}
