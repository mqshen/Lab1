package glutinous.network.util;

import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.LinkedList;

public class TransportFrameDecoder extends ChannelInboundHandlerAdapter {
    public static final String HANDLER_NAME = "frameDecoder";
    private static final int LENGTH_SIZE = 8;
    private static final int MAX_FRAME_SIZE = Integer.MAX_VALUE;
    private static final int UNKNOWN_FRAME_SIZE = -1;

    private long nextFrameSize = UNKNOWN_FRAME_SIZE;

    private final LinkedList<ByteBuf> buffers = new LinkedList<>();
    private final ByteBuf frameLenBuf = Unpooled.buffer(LENGTH_SIZE, LENGTH_SIZE);
    private long totalSize = 0;
    private volatile Interceptor interceptor;

    public void channelRead(ChannelHandlerContext ctx, Object data) throws Exception {
        ByteBuf in = (ByteBuf) data;
        buffers.add(in);
        totalSize += in.readableBytes();

        while (!buffers.isEmpty()) {
            if (interceptor != null) {
                ByteBuf first = buffers.getFirst();
                int available = first.readableBytes();
                if (feedInterceptor(first)) {
                    assert !first.isReadable() : "Interceptor still active but buffer has data.";
                }

                int read = available - first.readableBytes();
                if (read == available) {
                    buffers.removeFirst().release();
                }
                totalSize -= read;
            } else {
                // Interceptor is not active, so try to decode one frame.
                ByteBuf frame = decodeNext();
                if (frame == null) {
                    break;
                }
                ctx.fireChannelRead(frame);
            }
        }
    }

    private ByteBuf decodeNext() {
        long frameSize = decodeFrameSize();
        if (frameSize == UNKNOWN_FRAME_SIZE || totalSize < frameSize) {
            return null;
        }

        // Reset size for next frame.
        nextFrameSize = UNKNOWN_FRAME_SIZE;

        Preconditions.checkArgument(frameSize < MAX_FRAME_SIZE, "Too large frame: %s", frameSize);
        Preconditions.checkArgument(frameSize > 0, "Frame length should be positive: %s", frameSize);

        // If the first buffer holds the entire frame, return it.
        int remaining = (int) frameSize;
        if (buffers.getFirst().readableBytes() >= remaining) {
            return nextBufferForFrame(remaining);
        }

        // Otherwise, create a composite buffer.
        CompositeByteBuf frame = buffers.getFirst().alloc().compositeBuffer(Integer.MAX_VALUE);
        while (remaining > 0) {
            ByteBuf next = nextBufferForFrame(remaining);
            remaining -= next.readableBytes();
            frame.addComponent(next).writerIndex(frame.writerIndex() + next.readableBytes());
        }
        assert remaining == 0;
        return frame;
    }

    private long decodeFrameSize() {
        if (nextFrameSize != UNKNOWN_FRAME_SIZE || totalSize < LENGTH_SIZE) {
            return nextFrameSize;
        }

        // We know there's enough data. If the first buffer contains all the data, great. Otherwise,
        // hold the bytes for the frame length in a composite buffer until we have enough data to read
        // the frame size. Normally, it should be rare to need more than one buffer to read the frame
        // size.
        ByteBuf first = buffers.getFirst();
        if (first.readableBytes() >= LENGTH_SIZE) {
            nextFrameSize = first.readLong() - LENGTH_SIZE;
            totalSize -= LENGTH_SIZE;
            if (!first.isReadable()) {
                buffers.removeFirst().release();
            }
            return nextFrameSize;
        }

        while (frameLenBuf.readableBytes() < LENGTH_SIZE) {
            ByteBuf next = buffers.getFirst();
            int toRead = Math.min(next.readableBytes(), LENGTH_SIZE - frameLenBuf.readableBytes());
            frameLenBuf.writeBytes(next, toRead);
            if (!next.isReadable()) {
                buffers.removeFirst().release();
            }
        }

        nextFrameSize = frameLenBuf.readLong() - LENGTH_SIZE;
        totalSize -= LENGTH_SIZE;
        frameLenBuf.clear();
        return nextFrameSize;
    }

    private ByteBuf nextBufferForFrame(int bytesToRead) {
        ByteBuf buf = buffers.getFirst();
        ByteBuf frame;

        if (buf.readableBytes() > bytesToRead) {
            frame = buf.retain().readSlice(bytesToRead);
            totalSize -= bytesToRead;
        } else {
            frame = buf;
            buffers.removeFirst();
            totalSize -= frame.readableBytes();
        }

        return frame;
    }

    private boolean feedInterceptor(ByteBuf buf) throws Exception {
        if (interceptor != null && !interceptor.handle(buf)) {
            interceptor = null;
        }
        return interceptor != null;
    }

    public interface Interceptor {

        /**
         * Handles data received from the remote end.
         *
         * @param data Buffer containing data.
         * @return "true" if the interceptor expects more data, "false" to uninstall the interceptor.
         */
        boolean handle(ByteBuf data) throws Exception;

        /** Called if an exception is thrown in the channel pipeline. */
        void exceptionCaught(Throwable cause) throws Exception;

        /** Called if the channel is closed and the interceptor is still installed. */
        void channelInactive() throws Exception;

    }
}
