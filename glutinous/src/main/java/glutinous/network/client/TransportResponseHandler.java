package glutinous.network.client;

import glutinous.network.protocol.ResponseMessage;
import io.netty.channel.Channel;

import java.util.concurrent.atomic.AtomicLong;

public class TransportResponseHandler {

    private final Channel channel;
    private final AtomicLong timeOfLastRequestNs;

    public TransportResponseHandler(Channel channel) {
        this.channel = channel;
        this.timeOfLastRequestNs = new AtomicLong(0);
    }

    public void handle(ResponseMessage request) {

    }

    public void updateTimeOfLastRequest() {
        timeOfLastRequestNs.set(System.nanoTime());
    }

    public void channelActive() {
    }
}
