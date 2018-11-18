package glutinous.network.client;

import com.google.common.base.Preconditions;
import glutinous.network.buffer.NioManagedBuffer;
import glutinous.network.protocol.OneWayMessage;
import io.netty.channel.Channel;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

public class TransportClient implements Closeable{
    private final Channel channel;
    private final TransportResponseHandler handler;
    @Nullable
    private String clientId;
    private volatile boolean timedOut;

    public TransportClient(Channel channel, TransportResponseHandler handler) {
        this.channel = Preconditions.checkNotNull(channel);
        this.handler = Preconditions.checkNotNull(handler);
        this.timedOut = false;
    }

    public void send(ByteBuffer message) {
        channel.writeAndFlush(new OneWayMessage(new NioManagedBuffer(message)));
    }

    public SocketAddress getSocketAddress() {
        return channel.remoteAddress();
    }

    public boolean isActive() {
        return !timedOut && (channel.isOpen() || channel.isActive());
    }

    public Channel getChannel() {
        return channel;
    }

    @Override
    public void close() throws IOException {

    }
}
