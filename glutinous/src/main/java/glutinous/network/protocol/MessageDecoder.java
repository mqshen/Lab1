package glutinous.network.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@ChannelHandler.Sharable
public final class MessageDecoder extends MessageToMessageDecoder<ByteBuf> {
    private static final Logger logger = LoggerFactory.getLogger(MessageDecoder.class);
    public static final MessageDecoder INSTANCE = new MessageDecoder();

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Message.Type msgType = Message.Type.decode(in);
        Message decoded = decode(msgType, in);
        assert decoded.type() == msgType;
        logger.trace("Received message {}: {}", msgType, decoded);
        out.add(decoded);
    }

    private Message decode(Message.Type msgType, ByteBuf in) {
        switch (msgType) {
            case OneWayMessage:
                return OneWayMessage.decode(in);
            default:
                throw new IllegalArgumentException("Unexpected message type: " + msgType);
        }
    }
}
