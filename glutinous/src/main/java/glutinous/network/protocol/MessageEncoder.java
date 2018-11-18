package glutinous.network.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@ChannelHandler.Sharable
public class MessageEncoder extends MessageToMessageEncoder<Message> {
    private static final Logger logger = LoggerFactory.getLogger(MessageEncoder.class);

    public static final MessageEncoder INSTANCE = new MessageEncoder();
    @Override
    protected void encode(ChannelHandlerContext ctx, Message in, List<Object> out) throws Exception {
        Object body = null;
        long bodyLength = 0;
        boolean isBodyInFrame = false;

        // If the message has a body, take it out to enable zero-copy transfer for the payload.
        if (in.body() != null) {
            try {
                bodyLength = in.body().size();
                body = in.body().convertToNetty();
                isBodyInFrame = in.isBodyInFrame();
            } catch (Exception e) {
                in.body().release();
                if (in instanceof AbstractResponseMessage) {
                    AbstractResponseMessage resp = (AbstractResponseMessage) in;
                    // Re-encode this message as a failure response.
                    String error = e.getMessage() != null ? e.getMessage() : "null";
                    logger.error(String.format("Error processing %s for client %s",
                            in, ctx.channel().remoteAddress()), e);
                    encode(ctx, resp.createFailureResponse(error), out);
                } else {
                    throw e;
                }
                return;
            }
        }

        Message.Type msgType = in.type();
        // All messages have the frame length, message type, and message itself. The frame length
        // may optionally include the length of the body data, depending on what message is being
        // sent.
        int headerLength = 8 + msgType.encodedLength() + in.encodedLength();
        long frameLength = headerLength + (isBodyInFrame ? bodyLength : 0);
        ByteBuf header = ctx.alloc().heapBuffer(headerLength);
        header.writeLong(frameLength);
        msgType.encode(header);
        in.encode(header);
        assert header.writableBytes() == 0;

        if (body != null) {
            // We transfer ownership of the reference on in.body() to MessageWithHeader.
            // This reference will be freed when MessageWithHeader.deallocate() is called.
            out.add(new MessageWithHeader(in.body(), header, body, bodyLength));
        } else {
            out.add(header);
        }
    }
}
